package dev.graylog.jetbrains;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;

final class SourceFilterTests {
    private static int assertions;
    static int run() throws Exception {
        SourceFilter filter = new SourceFilter(); SearchRequest scope = scope();
        eq(scope, filter.apply(scope, "source"));
        filter.replace(List.of("worker", "api", "api"));
        eq("(level:3 OR level:4) AND (source:\"api\" OR source:\"worker\")", filter.query(scope.query(), "source"));
        SearchRequest selected = filter.apply(scope, "source");
        eq(scope.from(), selected.from()); eq(scope.to(), selected.to()); eq(scope.streamId(), selected.streamId());
        eq(selected.query(), selected.page(100).query());
        filter.replace(List.of("worker\" OR *", "a\\b"));
        eq("(*) AND (service.name:\"a\\\\b\" OR service.name:\"worker\\\" OR *\")", filter.query("*", "service.name"));
        rejects(() -> filter.query("*", "bad:field"));
        filter.clear(); eq("*", filter.query("", "source")); eq("Sources: All", filter.label());
        JsonObject parsed = response(scope, "source");
        GraylogClient.Sources values = GraylogClient.parseSources(parsed, "source", scope);
        eq(2, values.values().size()); eq("archive-worker", values.values().get(1).name()); eq(9000L, values.values().get(0).count()); eq(false, values.limited());
        JsonObject bad = parsed.deepCopy(); bad.getAsJsonArray("datarows").get(0).getAsJsonArray().set(0, new JsonPrimitive(-1)); JsonObject negative = bad; io(() -> GraylogClient.parseSources(negative, "source", scope));
        bad = parsed.deepCopy(); bad.remove("metadata"); JsonObject missing = bad; io(() -> GraylogClient.parseSources(missing, "source", scope));
        bad = parsed.deepCopy(); bad.getAsJsonObject("metadata").getAsJsonObject("effective_timerange").addProperty("to", scope.to().plusSeconds(1).toString()); JsonObject wrongRange = bad; io(() -> GraylogClient.parseSources(wrongRange, "source", scope));
        bad = parsed.deepCopy(); bad.add("datarows", new JsonArray()); eq(0, GraylogClient.parseSources(bad, "source", scope).values().size());
        JsonObject duplicate = parsed.deepCopy(); duplicate.getAsJsonArray("datarows").add(duplicate.getAsJsonArray("datarows").get(0).deepCopy()); io(() -> GraylogClient.parseSources(duplicate, "source", scope));
        JsonObject many = parsed.deepCopy(); JsonArray rows = new JsonArray();
        for (int i = 0; i < GraylogClient.SOURCE_LIMIT; i++) { JsonArray row = new JsonArray(); row.add(1); row.add("source-" + i); rows.add(row); }
        many.add("datarows", rows); eq(true, GraylogClient.parseSources(many, "source", scope).limited());
        GraylogClient.Sources legacy = GraylogClient.parseLegacySources(JsonParser.parseString("{\"terms\":{\"api\":12,\"worker\":4},\"other\":2}").getAsJsonObject());
        eq(2, legacy.values().size()); eq(true, legacy.limited());
        http(scope); return assertions;
    }
    private static void http(SearchRequest scope) throws Exception {
        AtomicReference<JsonObject> payload = new AtomicReference<>(); AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger(), legacyCalls = new AtomicInteger();
        AtomicReference<String> auth = new AtomicReference<>(), requestedBy = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/search/aggregate", exchange -> {
            calls.incrementAndGet(); auth.set(exchange.getRequestHeaders().getFirst("Authorization")); requestedBy.set(exchange.getRequestHeaders().getFirst("X-Requested-By"));
            if (!exchange.getRequestMethod().equals("POST")) throw new AssertionError("Aggregation must POST its read-only query");
            JsonObject input = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject(); payload.set(input);
            byte[] bytes = response(scope, "service.name").toString().getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/api/search/universal/absolute/terms", exchange -> {
            legacyCalls.incrementAndGet(); String query = java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            if (!query.contains("field=service.name") || !query.contains("size=1000") || !query.contains("filter=streams:stream-x") || !query.contains("order=service.name:desc")) throw new AssertionError("Legacy source query lost scope or sort format");
            byte[] bytes = "{\"terms\":{\"legacy-worker\":5},\"other\":0}".getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try (GraylogClient api = new GraylogClient("http://127.0.0.1:" + server.getAddress().getPort() + "/api", "test-source-token")) {
            GraylogClient.Sources result = api.sources(scope, "service.name"); eq(2, result.values().size());
            JsonObject input = payload.get(); eq(scope.query(), input.get("query").getAsString());
            eq("stream-x", input.getAsJsonArray("streams").get(0).getAsString());
            eq("absolute", input.getAsJsonObject("timerange").get("type").getAsString());
            eq(scope.from(), Instant.parse(input.getAsJsonObject("timerange").get("from").getAsString()));
            eq(scope.to(), Instant.parse(input.getAsJsonObject("timerange").get("to").getAsString()));
            eq("service.name", input.getAsJsonArray("group_by").get(0).getAsJsonObject().get("field").getAsString());
            eq(1000, input.getAsJsonArray("group_by").get(0).getAsJsonObject().get("limit").getAsInt());
            eq("count", input.getAsJsonArray("metrics").get(0).getAsJsonObject().get("function").getAsString());
            eq(false, input.has("offset")); eq(false, input.has("size")); eq(1, calls.get()); eq(0, legacyCalls.get());
            eq("Basic " + Base64.getEncoder().encodeToString("test-source-token:token".getBytes(StandardCharsets.UTF_8)), auth.get()); eq("graylog-jetbrains", requestedBy.get());
            status.set(403); io(() -> api.sources(scope, "service.name")); eq(0, legacyCalls.get());
            status.set(404); eq("legacy-worker", api.sources(scope, "service.name").values().getFirst().name()); eq(1, legacyCalls.get());
        } finally { server.stop(0); }
    }
    private static JsonObject response(SearchRequest scope, String field) {
        JsonObject object = JsonParser.parseString("{\"schema\":[{\"column_type\":\"metric\",\"function\":\"count\"},{\"column_type\":\"grouping\",\"field\":\"" + field + "\"}],\"datarows\":[[9000,\"api\"],[7,\"archive-worker\"]],\"metadata\":{\"effective_timerange\":{}}}").getAsJsonObject();
        JsonObject range = object.getAsJsonObject("metadata").getAsJsonObject("effective_timerange"); range.addProperty("from", scope.from().toString()); range.addProperty("to", scope.to().toString()); return object;
    }
    private static SearchRequest scope() { Instant start = Instant.parse("2026-11-16T12:00:00Z"); return new SearchRequest("level:3 OR level:4", "stream-x", start, start.plusSeconds(900), 100, 100); }
    @FunctionalInterface private interface Call { void run() throws Exception; }
    private static void io(Call call) throws Exception { try { call.run(); throw new AssertionError("Expected aggregation error"); } catch (IOException expected) { assertions++; } }
    private static void rejects(Runnable call) { try { call.run(); throw new AssertionError("Expected invalid field rejection"); } catch (IllegalArgumentException expected) { assertions++; } }
    private static void eq(Object expected, Object actual) { assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual); }
}
