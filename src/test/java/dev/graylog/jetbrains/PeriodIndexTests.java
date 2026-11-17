package dev.graylog.jetbrains;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Large-period transport tests exercise the actual scroll endpoint, not JSON pagination. */
final class PeriodIndexTests {
    private static final int TOTAL = 12037;
    private static int assertions;
    private static final String QUERY = "source:\"api & тест\"";
    private static final Instant FROM = Instant.parse("2026-01-01T12:00:00Z"), TO = FROM.plusSeconds(900);

    static int run() throws Exception {
        csv(); scopeAndCounts(); transport(); return assertions;
    }

    private static void csv() throws Exception {
        try (CsvRecords csv = new CsvRecords(new StringReader("a,b,c\r\n\"comma, quote \"\"x\"\"\",\"line1\nline2\",\r\n"))) {
            eq(List.of("a", "b", "c"), csv.next());
            eq(List.of("comma, quote \"x\"", "line1\nline2", ""), csv.next()); eq(null, csv.next());
        }
        try (CsvRecords csv = new CsvRecords(new StringReader("\"unfinished"))) { failure(csv::next, "unterminated"); }
        try (CsvRecords csv = new CsvRecords(new StringReader("\"closed\"junk,field"))) { failure(csv::next, "Malformed"); }
    }

    private static void scopeAndCounts() {
        SearchRequest request = request();
        check(request.sameScope(request.page(100)), "Paging preserves period scope");
        check(request.sameScope(new SearchRequest(QUERY, "stream-1", FROM, TO, 0, 500)), "Page size does not change scope");
        check(!request.sameScope(new SearchRequest("*", "stream-1", FROM, TO, 0, 100)), "Query changes scope");
        check(!request.sameScope(new SearchRequest(QUERY, "other-stream", FROM, TO, 0, 100)), "Stream changes scope");
        check(!request.sameScope(new SearchRequest(QUERY, "stream-1", FROM.minusSeconds(1), TO, 0, 100)), "Time changes scope");
        Connection connection = connection();
        ExceptionSites.Accumulator accumulator = new ExceptionSites.Accumulator(connection, Path.of("/work"));
        for (int i = 0; i < 25000; i++) accumulator.accept(new LogEvent("id-" + i, "index", event(i)));
        ExceptionSites.Site site = accumulator.finish().stream().filter(s -> s.location().line() == 103).findFirst().orElseThrow();
        eq(13000L, site.count()); eq(ExceptionSites.SAMPLES_PER_SITE, site.events().size());
    }

    private static void transport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        AtomicInteger mode = new AtomicInteger(); AtomicInteger exportCount = new AtomicInteger();
        List<Map<String, String>> exports = new ArrayList<>();
        CountDownLatch streaming = new CountDownLatch(1);
        server.createContext("/api/search/universal/absolute", exchange -> {
            Map<String, String> params = params(exchange);
            JsonObject response = new JsonObject();
            response.addProperty("from", mode.get() == 4 ? "2026-01-01T12:01:00.000Z" : params.get("from"));
            response.addProperty("to", params.get("to"));
            JsonArray messages = new JsonArray();
            if (params.get("query").contains("_id:")) {
                JsonObject wrapper = new JsonObject(); wrapper.addProperty("index", "graylog_1");
                JsonObject fields = event(9000); fields.addProperty("_id", "id-9000");
                JsonObject nested = new JsonObject(); nested.addProperty("class", "OutOfStock");
                nested.addProperty("file", "/application/src/Stock.php"); nested.addProperty("line", 44);
                fields.add("exception", nested);
                JsonObject context = new JsonObject(); context.add("err", nested.deepCopy()); fields.add("ctx", context);
                wrapper.add("message", fields); messages.add(wrapper);
                response.addProperty("total_results", 1);
            } else response.addProperty("total_results", mode.get() == 7 ? 0 : mode.get() == 8 ? 1 : TOTAL);
            response.add("messages", messages); json(exchange, response.toString());
        });
        server.createContext("/api/search/universal/absolute/export", exchange -> {
            exportCount.incrementAndGet(); Map<String, String> params = params(exchange);
            synchronized (exports) { exports.add(params); }
            int current = mode.get();
            if (current == 5) { send(exchange, 403, "application/json", "secret-token"); return; }
            if (current == 9) { send(exchange, 200, "text/html", "secret-token"); return; }
            exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8"); exchange.sendResponseHeaders(200, 0);
            try (Writer writer = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8)) {
                if (current == 7) return; // Empty Graylog scrolls may have no header.
                List<String> fields = List.of(params.get("fields").split(","));
                csv(writer, fields);
                if (current == 6) {
                    writer.flush(); streaming.countDown();
                    try { Thread.sleep(10000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return;
                }
                if (current == 2) { writer.write("\"unfinished"); return; }
                if (current == 3) { writer.write("too,few,columns\n"); return; }
                int count = current == 1 ? TOTAL - 1 : current == 8 ? 1 : TOTAL;
                for (int i = 0; i < count; i++) {
                    JsonObject data = event(i); data.addProperty("_id", "id-" + i);
                    if (i == 9000 || current == 8) {
                        data.addProperty("exception", "{class=OutOfStock, file=/application/src/Stock.php, line=44}");
                        data.addProperty("ctx", "{err={class=OutOfStock, file=/application/src/Stock.php, line=44}}");
                        if (current == 8) data.remove("_id");
                    }
                    List<String> values = new ArrayList<>();
                    for (String field : fields) values.add(data.has(field) ? data.get(field).getAsString().replace("\n", "\\\\n").replace("\r", "\\\\r") : "");
                    csv(writer, values);
                }
            } catch (IOException ignored) { /* Client cancellation closes the connection. */ }
            finally { exchange.close(); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        try (GraylogClient client = new GraylogClient(url, "secret-token")) {
            List<Long> progress = new ArrayList<>();
            GraylogApi.PeriodIndex index = client.indexPeriod(request().page(5000), connection(), Path.of("/work"), progress::add);
            eq((long) TOTAL, index.messages()); eq(39L, index.exceptions()); eq(3, index.sites().size());
            ExceptionSites.Site older = index.sites().stream().filter(s -> s.location().line() == 103).findFirst().orElseThrow();
            eq(37L, older.count()); eq(20, older.events().size());
            eq("comma, \"quoted\"\nnext line and literal \\n", older.events().getFirst().text("message"));
            check(index.sites().stream().anyMatch(s -> s.location().file().equals(Path.of("/work/src/Stock.php")) && s.location().line() == 44
                    && s.types().equals(List.of("OutOfStock"))), "Nested exceptions are restored via JSON, not guessed from CSV map strings");
            eq((long) TOTAL, progress.getLast());
            Map<String, String> export = exports.getFirst();
            eq("0", export.get("limit")); eq("0", export.get("offset")); eq("500", export.get("batch_size"));
            eq(QUERY, export.get("query")); eq("streams:stream-1", export.get("filter"));
            eq("2026-01-01T12:00:00.000Z", export.get("from")); eq("2026-01-01T12:15:00.000Z", export.get("to"));
            IdeOptions custom = new IdeOptions(); custom.exportBatchSize = 37; custom.examplesPerLocation = 3;
            LogFormat format = new LogFormat(); format.exceptionObjectField = "ctx.err";
            try (GraylogClient configured = new GraylogClient(url, "secret-token", custom, format)) {
                GraylogApi.PeriodIndex configuredIndex = configured.indexPeriod(request(), connection(), Path.of("/work"), ignored -> {});
                eq(39L, configuredIndex.exceptions());
                ExceptionSites.Site configuredOlder = configuredIndex.sites().stream().filter(s -> s.location().line() == 103).findFirst().orElseThrow();
                eq(37L, configuredOlder.count()); eq(3, configuredOlder.events().size());
                check(configuredIndex.sites().stream().anyMatch(s -> s.location().line() == 44 && s.events().getFirst().element("exception").isJsonObject()), "Custom nested exception survives CSV hydration");
                Map<String, String> configuredExport = exports.getLast(); eq("37", configuredExport.get("batch_size"));
                check(List.of(configuredExport.get("fields").split(",")).containsAll(List.of("ctx", "ctx.err")), "Configured nested roots included in export");
            }
            for (int scenario : new int[]{1, 2, 3, 4, 5, 8, 9}) {
                mode.set(scenario);
                String message = failure(() -> client.indexPeriod(request(), connection(), Path.of("/work"), ignored -> {}),
                        switch (scenario) { case 1 -> "expected messages"; case 2 -> "unterminated"; case 3 -> "Incomplete CSV record";
                            case 4 -> "restricted"; case 5 -> "HTTP 403"; case 8 -> "omitted an ID"; default -> "did not return a CSV"; });
                check(!message.contains("secret-token"), "Scan errors do not echo credentials or bodies");
            }
            mode.set(7);
            index = client.indexPeriod(request(), connection(), Path.of("/work"), ignored -> {});
            eq(0L, index.messages()); eq(0L, index.exceptions()); eq(0, index.sites().size());
            mode.set(6);
            var caller = Executors.newSingleThreadExecutor(); CountDownLatch canceled = new CountDownLatch(1);
            try {
                var future = caller.submit(() -> {
                    try { client.indexPeriod(request(), connection(), Path.of("/work"), ignored -> {}); }
                    catch (IOException | InterruptedException expected) { canceled.countDown(); }
                });
                check(streaming.await(3, TimeUnit.SECONDS), "Slow stream started"); future.cancel(true);
                check(canceled.await(2, TimeUnit.SECONDS), "Cancellation interrupts a blocking export read");
            } finally { caller.shutdownNow(); }
        } finally { server.stop(0); workers.shutdownNow(); }
    }

    private static SearchRequest request() { return new SearchRequest(QUERY, "stream-1", FROM, TO, 0, 100); }
    private static Connection connection() { Connection c = new Connection(); c.localRoot = "/work"; return c; }
    private static JsonObject event(int i) {
        JsonObject fields = new JsonObject(); fields.addProperty("timestamp", "2026-01-01T12:05:00.000Z");
        fields.addProperty("source", "api"); fields.addProperty("level", 6); fields.addProperty("message", "normal");
        if (i >= 12000 || i == 10500) {
            fields.addProperty("level", 3); fields.addProperty("exception_class", "OlderException");
            fields.addProperty("exception_file", "/application/src/Older.php"); fields.addProperty("exception_line", i == 10500 ? 200 : 103);
            fields.addProperty("message", "comma, \"quoted\"\nnext line and literal \\n");
        }
        return fields;
    }
    private static Map<String, String> params(HttpExchange exchange) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String entry : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] pair = entry.split("=", 2); params.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return params;
    }
    private static void csv(Writer writer, List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) { if (i > 0) writer.write(','); writer.write('"'); writer.write(values.get(i).replace("\"", "\"\"")); writer.write('"'); }
        writer.write("\r\n");
    }
    private static void json(HttpExchange exchange, String value) throws IOException { send(exchange, 200, "application/json", value); }
    private static void send(HttpExchange exchange, int status, String type, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static String failure(Throwing action, String contains) throws Exception {
        try { action.run(); throw new AssertionError("Expected failure containing " + contains); }
        catch (IOException error) { check(error.getMessage().contains(contains), error.getMessage()); return error.getMessage(); }
    }
    private static void eq(Object expected, Object actual) { check(java.util.Objects.equals(expected, actual), "Expected " + expected + ", got " + actual); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
