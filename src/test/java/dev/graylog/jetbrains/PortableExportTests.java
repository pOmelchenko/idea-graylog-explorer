package dev.graylog.jetbrains;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Actual CSV export/JSON hydration boundary, including fields absent from a visible table page. */
final class PortableExportTests {
    private static int assertions;
    static int run() throws Exception {
        for (var fixture : JsonParser.parseString(Files.readString(Path.of("src/test/resources/stacks/fixtures.json"))).getAsJsonArray()) {
            String name = fixture.getAsJsonObject().get("name").getAsString();
            JsonObject fields = base(); fields.addProperty("stack_trace", Files.readString(Path.of("src/test/resources/stacks/" + name + ".txt")));
            transport(fields, 0, name);
            JsonObject ecs = new JsonObject(); ecs.addProperty("type", fixture.getAsJsonObject().getAsJsonArray("types").get(0).getAsString());
            ecs.addProperty("message", "Schema message"); ecs.addProperty("stack_trace", fields.get("stack_trace").getAsString());
            JsonObject objectFields = base(); objectFields.add("error", ecs); transport(objectFields, 1, name + " ECS object");
            JsonObject dottedFields = base(); ecs.entrySet().forEach(e -> dottedFields.add("error." + e.getKey(), e.getValue())); transport(dottedFields, 0, name + " ECS dotted");
            ecs.remove("type");
            JsonObject untypedObject = base(); untypedObject.add("error", ecs); transport(untypedObject, 1, name + " ECS object without type");
            JsonObject untypedDotted = base(); ecs.entrySet().forEach(e -> untypedDotted.add("error." + e.getKey(), e.getValue())); transport(untypedDotted, 0, name + " ECS dotted without type");
        }
        JsonObject error = new JsonObject(); error.addProperty("type", "TypeError"); error.addProperty("message", "bad value");
        error.addProperty("stack_trace", "at run (/srv/app/main.js:42:2)");
        JsonObject nested = base(); nested.add("error", error); transport(nested, 1, "ECS object");
        JsonObject flat = base(); error.entrySet().forEach(e -> flat.add("error." + e.getKey(), e.getValue())); transport(flat, 0, "ECS dotted fields");
        JsonObject explicit = base(); explicit.addProperty("exception_class", "RuntimeException");
        explicit.addProperty("exception_file", "/srv/app/Throw.php"); explicit.addProperty("exception_line", 10);
        explicit.addProperty("stack_trace", "#0 /srv/app/Caller.php(20): run()"); transport(explicit, 0, "explicit origin");
        JsonObject urlArgument = base(); urlArgument.addProperty("exception_class", "RuntimeException");
        urlArgument.addProperty("stack_trace", "#0 /srv/app/Fetch.php(20): fetch('https://example.org/api')"); transport(urlArgument, 0, "URL argument");
        return assertions;
    }
    private static JsonObject base() { JsonObject fields = new JsonObject(); fields.addProperty("_id", "portable"); fields.addProperty("level", 3); return fields; }
    private static void transport(JsonObject fields, int expectedHydration, String label) throws Exception {
        var expectedEvent = new LogEvent("portable", "fixture", fields);
        Connection connection = new Connection(); connection.remoteRoot = "/srv/app"; connection.localRoot = "/local";
        if (label.startsWith("dotnet")) connection.remoteRoot = "C:/app";
        var expected = CoreTests.sites(List.of(expectedEvent), connection, Path.of("/local"));
        AtomicInteger hydrated = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/search/universal/absolute", exchange -> {
            var params = params(exchange); if (params.get("query").contains("_id:")) hydrated.incrementAndGet();
            JsonObject response = new JsonObject(); response.addProperty("from", params.get("from")); response.addProperty("to", params.get("to")); response.addProperty("total_results", 1);
            JsonObject wrapper = new JsonObject(); wrapper.addProperty("index", "fixture"); wrapper.add("message", fields);
            JsonArray messages = new JsonArray(); messages.add(wrapper); response.add("messages", messages); reply(exchange, "application/json", response.toString());
        });
        server.createContext("/api/search/universal/absolute/export", exchange -> {
            var columns = List.of(params(exchange).get("fields").split(",")); List<String> values = new ArrayList<>();
            for (String column : columns) {
                JsonElement value = fields.get(column);
                String text = value == null ? "" : value.isJsonPrimitive() ? value.getAsString() : "{type=TypeError, stack_trace=legacy Map.toString()}";
                values.add(text.replace("\n", "\\\\n"));
            }
            reply(exchange, "text/csv", csv(columns) + csv(values));
        });
        server.start();
        try (var client = new GraylogClient("http://127.0.0.1:" + server.getAddress().getPort(), "fixture-token")) {
            var request = new SearchRequest("*", "", Instant.EPOCH, Instant.EPOCH.plusSeconds(900), 0, 100);
            var actual = client.indexPeriod(request, connection, Path.of("/local"), ignored -> {});
            eq(1L, actual.messages(), label); eq(1L, actual.exceptions(), label);
            eq(expected.stream().map(ExceptionSites.Site::location).toList(), actual.sites().stream().map(ExceptionSites.Site::location).toList(), label + " locations");
            eq(expected.stream().map(ExceptionSites.Site::types).toList(), actual.sites().stream().map(ExceptionSites.Site::types).toList(), label + " types");
            eq(expectedHydration, hydrated.get(), label + " hydration");
            if (label.startsWith("ECS")) eq(42, actual.sites().getFirst().location().line(), label + " source");
            if (label.equals("explicit origin")) eq(10, actual.sites().getFirst().location().line(), label + " source");
        } finally { server.stop(0); }
    }
    private static Map<String, String> params(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : exchange.getRequestURI().getRawQuery().split("&")) { String[] pair = item.split("=", 2); result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); }
        return result;
    }
    private static String csv(List<String> values) { return values.stream().map(s -> "\"" + s.replace("\"", "\"\"") + "\"").collect(Collectors.joining(",")) + "\r\n"; }
    private static void reply(HttpExchange exchange, String type, String text) throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    }
    private static void eq(Object expected, Object actual, String message) { assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual); }
}
