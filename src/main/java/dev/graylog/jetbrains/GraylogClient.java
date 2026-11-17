package dev.graylog.jetbrains;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

public final class GraylogClient implements AutoCloseable {
    private static final DateTimeFormatter API_TIME = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    public record Stream(String id, String title) { @Override public String toString() { return title; } }
    public record Result(List<LogEvent> events, long total) { }
    public record PeriodIndex(List<ExceptionSites.Site> sites, long messages, long exceptions) { }
    public record SourceValue(String name, long count) { }
    public record Sources(List<SourceValue> values, boolean limited) { }
    public static final int SOURCE_LIMIT = 1000;
    static final List<String> EXPORT_FIELDS = List.of("timestamp", "_id", "_index", "level", "level_name", "severity", "source",
            "message", "full_message", "exception", "ctxt_exception", "ctxt_category", "exception_class", "exception_type", "exceptionClass", "error_type", "error.type",
            "exception_file", "exception_line", "stack_trace", "stacktrace", "exception_stack_trace", "file", "file_name", "filename",
            "line", "line_number", "service_name", "service", "application_name", "application", "request_id", "trace_id", "correlation_id");
    private final String apiUrl;
    private final String token;
    private final HttpClient http;
    private final IdeOptions options;
    private final LogFormat format;

    public GraylogClient(String apiUrl, String token) {
        this(apiUrl, token, new IdeOptions(), new LogFormat());
    }

    public GraylogClient(String apiUrl, String token, IdeOptions options, LogFormat format) {
        this.apiUrl = Connection.normalizeApiUrl(apiUrl);
        if (token == null || token.isBlank()) throw new IllegalArgumentException("No API token stored. Edit the connection and enter a token.");
        this.token = token;
        options.validate(); format.validate(); this.options = options.copy(); this.format = format.copy();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(options.connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public List<Stream> streams() throws IOException, InterruptedException {
        JsonObject response = get("/streams", Map.of());
        JsonArray items = response.getAsJsonArray("streams");
        if (items == null) throw new IOException("Graylog returned no streams array. Check the API URL.");
        List<Stream> streams = new ArrayList<>();
        for (JsonElement item : items) {
            JsonObject stream = item.getAsJsonObject();
            streams.add(new Stream(requiredString(stream, "id"), requiredString(stream, "title")));
        }
        streams.sort(Comparator.comparing(Stream::title, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(streams);
    }

    public Result search(SearchRequest request) throws IOException, InterruptedException {
        return parseResult(searchResponse(request), format);
    }

    /** Server-side aggregation across the whole query window, independent of message pagination/export. */
    public Sources sources(SearchRequest scope, String field) throws IOException, InterruptedException {
        SearchRequest.exact(field, "validate");
        JsonObject body = new JsonObject(); body.addProperty("query", scope.query());
        JsonArray streams = new JsonArray(); if (!scope.streamId().isBlank()) streams.add(scope.streamId()); body.add("streams", streams);
        JsonObject range = new JsonObject(); range.addProperty("type", "absolute");
        range.addProperty("from", API_TIME.format(scope.from())); range.addProperty("to", API_TIME.format(scope.to())); body.add("timerange", range);
        JsonArray groups = new JsonArray(); JsonObject group = new JsonObject(); group.addProperty("field", field); group.addProperty("limit", SOURCE_LIMIT); groups.add(group); body.add("group_by", groups);
        JsonArray metrics = new JsonArray(); JsonObject count = new JsonObject(); count.addProperty("function", "count"); count.addProperty("sort", "desc"); metrics.add(count); body.add("metrics", metrics);
        HttpRequest aggregate = builder("/search/aggregate", Map.of(), "application/json").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(aggregate, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404 || response.statusCode() == 405) {
            Map<String, String> params = rangeParams(scope); params.put("field", field); params.put("size", Integer.toString(SOURCE_LIMIT)); params.put("order", field + ":desc");
            return parseLegacySources(get("/search/universal/absolute/terms", params));
        }
        if (response.statusCode() == 403) throw new IOException("HTTP 403: Access denied. Check aggregation and stream permissions.");
        requireSuccess(response.statusCode());
        return parseSources(jsonObject(response.body()), field, scope);
    }

    static Sources parseSources(JsonObject response, String field, SearchRequest scope) throws IOException {
        try {
            JsonObject range = response.getAsJsonObject("metadata").getAsJsonObject("effective_timerange");
            JsonObject window = new JsonObject(); window.add("from", range.get("from")); window.add("to", range.get("to"));
            try { verifyWindow(window, scope); } catch (IOException error) { throw new IOException("Graylog did not aggregate the full selected time range.", error); }
            JsonArray schema = response.getAsJsonArray("schema"); int source = -1, count = -1;
            for (int i = 0; i < schema.size(); i++) {
                JsonObject column = schema.get(i).getAsJsonObject();
                if (optionalString(column, "column_type").equals("grouping") && optionalString(column, "field").equals(field)) source = i;
                if (optionalString(column, "column_type").equals("metric") && optionalString(column, "function").equals("count")) count = i;
            }
            if (source < 0 || count < 0) throw new IOException("Graylog aggregation omitted source/count columns.");
            List<SourceValue> values = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
            JsonArray rows = response.getAsJsonArray("datarows");
            for (JsonElement item : rows) {
                JsonArray row = item.getAsJsonArray();
                if (row.size() != schema.size()) throw new IOException("Invalid source aggregation row.");
                if (row.get(source).isJsonNull()) continue;
                String name = row.get(source).getAsString(); long occurrences = nonnegativeCount(row.get(count));
                if (!name.isBlank()) {
                    if (!seen.add(name)) throw new IOException("Duplicate source aggregation value.");
                    values.add(new SourceValue(name, occurrences));
                }
            }
            if (rows.size() > SOURCE_LIMIT) throw new IOException("Graylog exceeded the source aggregation limit.");
            return new Sources(List.copyOf(values), rows.size() >= SOURCE_LIMIT);
        } catch (RuntimeException e) { throw new IOException("Invalid Graylog source aggregation response.", e); }
    }
    static Sources parseLegacySources(JsonObject response) throws IOException {
        try {
            List<SourceValue> values = new ArrayList<>();
            for (var entry : response.getAsJsonObject("terms").entrySet()) if (!entry.getKey().isBlank()) values.add(new SourceValue(entry.getKey(), nonnegativeCount(entry.getValue())));
            if (values.size() > SOURCE_LIMIT) throw new IOException("Graylog exceeded the source aggregation limit.");
            return new Sources(List.copyOf(values), values.size() >= SOURCE_LIMIT || (response.has("other") && nonnegativeCount(response.get("other")) > 0));
        } catch (RuntimeException e) { throw new IOException("Invalid legacy Graylog source aggregation response.", e); }
    }
    private static long nonnegativeCount(JsonElement element) {
        long value = new java.math.BigDecimal(element.getAsString()).longValueExact();
        if (value < 0) throw new IllegalArgumentException("Negative source count"); return value;
    }

    private JsonObject searchResponse(SearchRequest request) throws IOException, InterruptedException {
        Map<String, String> params = rangeParams(request);
        params.put("limit", Integer.toString(request.limit()));
        params.put("offset", Integer.toString(request.offset()));
        params.put("sort", "timestamp:desc");
        params.put("decorate", "false");
        return get("/search/universal/absolute", params);
    }

    private static Map<String, String> rangeParams(SearchRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", request.query());
        params.put("from", API_TIME.format(request.from()));
        params.put("to", API_TIME.format(request.to()));
        if (!request.streamId().isBlank()) params.put("filter", "streams:" + request.streamId());
        return params;
    }

    /** A scroll export is independent of the table's offset and Elasticsearch's result window. */
    public PeriodIndex indexPeriod(SearchRequest request, Connection connection, Path projectRoot, LongConsumer progress)
            throws IOException, InterruptedException {
        SearchRequest countRequest = new SearchRequest(request.query(), request.streamId(), request.from(), request.to(), 0, 1);
        JsonObject baseline = searchResponse(countRequest);
        verifyWindow(baseline, request);
        long expected = parseResult(baseline).total();
        Set<String> fields = new LinkedHashSet<>(EXPORT_FIELDS);
        fields.add(connection.serviceField);
        fields.addAll(format.exportFields());
        Map<String, String> params = rangeParams(request);
        params.put("limit", "0"); // Graylog legacy scroll: no result limit; NOT an ordinary JSON page.
        params.put("offset", "0"); params.put("batch_size", Integer.toString(options.exportBatchSize)); params.put("fields", String.join(",", fields));
        HttpResponse<InputStream> response = http.send(request("/search/universal/absolute/export", params, "text/csv"),
                HttpResponse.BodyHandlers.ofInputStream());
        ExceptionSites.Accumulator accumulator = new ExceptionSites.Accumulator(connection, projectRoot, options.examplesPerLocation);
        long rows = 0;
        List<LogEvent> structured = new ArrayList<>();
        try (InputStream body = response.body()) {
            requireSuccess(response.statusCode());
            if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("text/csv")) {
                throw new IOException("Graylog did not return a CSV scroll export; the full-period index is unavailable.");
            }
            try (CsvRecords csv = new CsvRecords(new InputStreamReader(body, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
                List<String> header = csv.next();
                if (header != null) {
                    if (!header.isEmpty()) header.set(0, header.getFirst().replaceFirst("^\\uFEFF", ""));
                    if (new LinkedHashSet<>(header).size() != header.size() || !header.containsAll(fields)) {
                        throw new IOException("Unexpected Graylog export columns; the full-period index is unavailable.");
                    }
                    List<String> row;
                    while ((row = csv.next()) != null) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        if (row.size() == 1 && row.getFirst().isEmpty()) continue;
                        if (row.size() != header.size()) throw new IOException("Incomplete CSV record in Graylog export.");
                        JsonObject data = new JsonObject();
                        for (int i = 0; i < header.size(); i++) if (!row.get(i).isEmpty()) {
                            // Legacy ScrollChunkWriter replaces actual newlines with two backslashes + n/r.
                            String value = row.get(i).replace("\\\\n", "\n").replace("\\\\r", "\r");
                            data.addProperty(header.get(i), value);
                        }
                        LogEvent event = new LogEvent(optionalString(data, "_id"), optionalString(data, "_index"), data, format);
                        // CSV uses Map.toString() for objects. Restore these via JSON, rather than guessing nested file/line.
                        if (needsHydration(event)) structured.add(event);
                        else accumulator.accept(event);
                        if (structured.size() >= 50) hydrateExceptions(structured, request, accumulator);
                        rows++;
                        if (rows % 500 == 0) progress.accept(rows);
                    }
                }
            }
        }
        hydrateExceptions(structured, request, accumulator);
        if (rows != expected) throw new IOException("The export contained " + rows + " of " + expected
                + " expected messages. The index was not published; refresh after the search data stabilizes.");
        progress.accept(rows);
        return new PeriodIndex(accumulator.finish(), rows, accumulator.exceptionCount());
    }

    private boolean needsHydration(LogEvent event) {
        String exception = event.text("exception").stripLeading();
        if (exception.startsWith("{") || exception.startsWith("[")) return true;
        for (String field : format.aliases().values()) if (field.contains(".")) {
            JsonElement exact = event.fields().get(field);
            if (exact != null && !exact.isJsonNull() && !(exact.isJsonPrimitive() && exact.getAsString().isEmpty())) continue;
            JsonElement root = event.fields().get(field.substring(0, field.indexOf('.')));
            if (root != null && root.isJsonPrimitive()) {
                String value = root.getAsString().stripLeading();
                if (value.startsWith("{") || value.startsWith("[")) return true;
            }
        }
        return false;
    }

    private void hydrateExceptions(List<LogEvent> pending, SearchRequest scope, ExceptionSites.Accumulator accumulator)
            throws IOException, InterruptedException {
        if (pending.isEmpty()) return;
        Set<String> ids = new LinkedHashSet<>();
        for (LogEvent event : pending) {
            if (event.id().isBlank()) throw new IOException("Graylog export omitted an ID needed to restore a structured exception.");
            ids.add(event.id());
        }
        String lookup = ids.stream().map(id -> SearchRequest.exact("_id", id)).collect(Collectors.joining(" OR "));
        String query = "(" + scope.query() + ") AND (" + lookup + ")";
        Result result = search(new SearchRequest(query, scope.streamId(), scope.from(), scope.to(), 0, 500));
        Map<String, LogEvent> restored = new LinkedHashMap<>();
        for (LogEvent event : result.events()) {
            if (restored.putIfAbsent(event.id(), event) != null) throw new IOException("A structured exception has an ambiguous message ID.");
        }
        if (result.total() != ids.size() || restored.size() != ids.size() || !restored.keySet().equals(ids)) {
            throw new IOException("Structured exception data changed during export; the full-period index was not published.");
        }
        for (LogEvent event : pending) accumulator.accept(restored.get(event.id()));
        pending.clear();
    }

    private static void verifyWindow(JsonObject response, SearchRequest request) throws IOException {
        try {
            Instant from = Instant.parse(response.get("from").getAsString());
            Instant to = Instant.parse(response.get("to").getAsString());
            if (!from.equals(Instant.parse(API_TIME.format(request.from()))) || !to.equals(Instant.parse(API_TIME.format(request.to())))) {
                throw new IOException("Graylog restricted the requested time range; the full-period index was not published.");
            }
        } catch (RuntimeException e) { throw new IOException("Graylog did not report a valid effective time range for the period index."); }
    }

    static Result parseResult(JsonObject response) throws IOException {
        return parseResult(response, new LogFormat());
    }
    static Result parseResult(JsonObject response, LogFormat format) throws IOException {
        JsonArray messages = response.getAsJsonArray("messages");
        if (messages == null) throw new IOException("Unexpected search response: no messages array. Check the Graylog API URL.");
        List<LogEvent> events = new ArrayList<>();
        for (JsonElement item : messages) {
            if (!item.isJsonObject()) throw new IOException("Unexpected message in search response.");
            JsonObject wrapper = item.getAsJsonObject();
            if (!wrapper.has("message") || !wrapper.get("message").isJsonObject()) {
                throw new IOException("Unexpected message fields in search response.");
            }
            JsonObject fields = wrapper.getAsJsonObject("message").deepCopy();
            String index = optionalString(wrapper, "index");
            String id = optionalString(fields, "_id");
            if (id.isEmpty()) id = optionalString(wrapper, "id");
            if (index.isEmpty()) index = optionalString(fields, "_index");
            events.add(new LogEvent(id, index, fields, format));
        }
        long total = response.has("total_results") ? response.get("total_results").getAsLong() : events.size();
        return new Result(List.copyOf(events), total);
    }

    private JsonObject get(String path, Map<String, String> params) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(path, params, "application/json"), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        requireSuccess(response.statusCode());
        return jsonObject(response.body());
    }
    private static JsonObject jsonObject(String body) throws IOException {
        try {
            JsonElement json = JsonParser.parseString(body);
            if (!json.isJsonObject()) throw new IllegalStateException();
            return json.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Graylog returned invalid JSON. Check that the URL points to /api, not an SSO login page.");
        }
    }

    private HttpRequest request(String path, Map<String, String> params, String accept) {
        return builder(path, params, accept).GET().build();
    }
    private HttpRequest.Builder builder(String path, Map<String, String> params, String accept) {
        String query = params.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        String auth = Base64.getEncoder().encodeToString((token + ":token").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(apiUrl + path + (query.isEmpty() ? "" : "?" + query)))
                .timeout(Duration.ofSeconds(options.requestTimeoutSeconds)).header("Accept", accept)
                .header("Authorization", "Basic " + auth).header("X-Requested-By", "graylog-jetbrains");
    }

    private static void requireSuccess(int status) throws IOException {
        if (status < 200 || status >= 300) {
            String hint = switch (status) {
                case 400 -> "Invalid search query or time range. Check the query syntax.";
                case 401 -> "Token is invalid or expired. Edit the connection.";
                case 403 -> "Access denied. Check stream permissions and searches:absolute permission.";
                case 404 -> "Endpoint not found. Check the API URL and availability of the legacy Search API.";
                case 429 -> "Too many requests. Pause Live and try again later.";
                default -> status >= 300 && status < 400 ? "Redirect refused. Enter the final API URL directly." : "Graylog request failed.";
            };
            // Error bodies may contain echoed request data. Never expose credentials in UI or logs.
            throw new IOException("HTTP " + status + ": " + hint);
        }
    }

    private static String requiredString(JsonObject object, String key) throws IOException {
        String value = optionalString(object, key);
        if (value.isEmpty()) throw new IOException("Missing " + key + " in Graylog response.");
        return value;
    }
    private static String optionalString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    @Override public void close() { http.shutdownNow(); }
}
