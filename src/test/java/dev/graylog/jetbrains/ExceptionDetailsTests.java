package dev.graylog.jetbrains;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class ExceptionDetailsTests {
    private static int assertions;
    static int run() throws Exception {
        JsonObject fields = JsonParser.parseString(Files.readString(Path.of("src/test/resources/yii-exception.json"))).getAsJsonObject();
        String original = fields.toString();
        LogEvent event = new LogEvent("yii", "index", fields);
        var chain = ExceptionDetails.chain(event); eq(2, chain.size());
        eq("GraphQL\\Error\\UserError", chain.getFirst().type());
        eq("723", chain.getFirst().code()); eq(59, chain.getFirst().origin().line());
        eq(91, chain.getLast().origin().line());
        eq("Demo\\Profile\\PhoneInvalidException", chain.getLast().type());
        String trace = event.stackTrace();
        check(trace.startsWith("GraphQL\\Error\\UserError: Invalid phone number. (code 723)\n"), trace);
        check(trace.contains("Caused by: Demo\\Profile"), "Cause header missing");
        check(trace.contains("PhoneService.php:91"), "Cause origin missing");
        check(!trace.contains("{\"") && !trace.contains("\\\""), "Rendered trace contains encoded JSON");
        eq(5, chain.getFirst().trace().size());
        eq(chain.getFirst().trace().get(2), chain.getFirst().trace().get(4));
        check(trace.contains("#4 /application/vendor/webonyx"), "Repeated recursive frames must be retained in the rendered trace");
        check(ExceptionSites.exceptionFrames(event).stream().allMatch(f -> f.path().startsWith("/application/") && !f.path().contains("\"")), "Bogus source paths parsed from JSON namespaces");
        check(SourceLocation.parseTrace(event.text("exception")).stream().allMatch(f -> f.path().startsWith("/application/") && !f.path().contains("\"")), "Raw JSON path extraction crosses string boundaries");
        eq("GraphQL\\Error\\UserError", ExceptionSites.exceptionName(event));
        Connection connection = new Connection(); connection.localRoot = "/work";
        var sites = CoreTests.sites(List.of(event), connection, Path.of("/work"));
        eq(2, sites.size());
        var causeSite = sites.stream().filter(s -> s.location().line() == 91).findFirst().orElseThrow();
        eq(List.of(chain.getLast().type()), causeSite.types()); eq(1L, causeSite.count());
        check(causeSite.location().file().endsWith("PhoneService.php"), "Cause points at logger or wrapper");
        ExceptionSites.Accumulator accumulator = new ExceptionSites.Accumulator(connection, Path.of("/work"), 1);
        accumulator.accept(event); eq(1L, accumulator.exceptionCount()); eq(2, accumulator.finish().size());
        eq(2, ExceptionSites.locations(event, connection, Path.of("/work")).size()); eq(original, fields.toString());

        LogFormat format = new LogFormat(); format.exceptionObjectField = "ctxt_exception";
        eq(trace, new LogEvent("yii", "index", fields, format).stackTrace());
        JsonObject nativeFields = new JsonObject(); JsonObject root = JsonParser.parseString(fields.get("ctxt_exception").getAsString()).getAsJsonObject();
        nativeFields.add("exception", root); eq(trace, new LogEvent("native", "", nativeFields).stackTrace());
        root.addProperty("previous", root.get("previous").toString());
        eq(2, ExceptionDetails.chain(new LogEvent("encoded-previous", "", nativeFields)).size());
        root.add("cause", JsonParser.parseString(root.remove("previous").getAsString()));
        eq(2, ExceptionDetails.chain(new LogEvent("cause", "", nativeFields)).size());
        JsonObject cause = root.getAsJsonObject("cause"); cause.addProperty("file", root.get("file").getAsString());
        accumulator = new ExceptionSites.Accumulator(connection, Path.of("/work"));
        accumulator.accept(new LogEvent("same-location", "", nativeFields));
        eq(1L, accumulator.exceptionCount()); eq(1, accumulator.finish().size());
        eq(1L, accumulator.finish().getFirst().count()); eq(2, accumulator.finish().getFirst().types().size());
        JsonObject custom = JsonParser.parseString("{\"payload\":{\"class\":\"CustomError\",\"file\":\"/application/src/Custom.php:7\",\"trace\":[{\"file\":\"/application/src/Caller.php\",\"line\":9}]}}").getAsJsonObject();
        format.exceptionObjectField = "payload";
        var customChain = ExceptionDetails.chain(new LogEvent("custom", "", custom, format));
        eq(7, customChain.getFirst().origin().line()); eq(9, customChain.getFirst().trace().getFirst().line());
        cause.addProperty("line", 17); eq(17, ExceptionDetails.location(cause).line());
        JsonObject bad = new JsonObject(); bad.addProperty("ctxt_exception", "{class=Error, file=/application/src/Foo.php, line=5}");
        eq(0, ExceptionDetails.chain(new LogEvent("bad", "", bad)).size());
        bad.addProperty("ctxt_exception", "{\"class\":"); eq(0, ExceptionDetails.chain(new LogEvent("bad", "", bad)).size());
        bad.addProperty("ctxt_category", "application.checkout"); bad.remove("ctxt_exception");
        eq("", ExceptionSites.exceptionName(new LogEvent("plain", "", bad)));
        bad.addProperty("ctxt_category", "GraphQL\\Error\\UserError");
        eq("GraphQL\\Error\\UserError", ExceptionSites.exceptionName(new LogEvent("category", "", bad)));
        root.add("previous", root); eq(64, ExceptionDetails.chain(new LogEvent("depth", "", nativeFields)).size()); root.remove("previous");
        transport(fields);
        validationChain(fields);
        return assertions;
    }
    private static void validationChain(JsonObject fixture) {
        JsonObject fields = fixture.deepCopy();
        JsonObject root = JsonParser.parseString(fields.get("ctxt_exception").getAsString()).getAsJsonObject();
        JsonObject application = root.getAsJsonObject("previous");
        application.addProperty("message", "Phone number validation failed.");
        JsonObject domain = new JsonObject();
        domain.addProperty("class", "Demo\\Domain\\PhoneNumberInvalidException");
        domain.addProperty("message", "Invalid phone: +12025550123");
        domain.addProperty("file", "/application/src/Domain/Phone.php:46");
        domain.addProperty("code", 0); application.add("previous", domain);
        fields.addProperty("ctxt_exception", root.toString());
        LogEvent event = new LogEvent("e35f2a01-df92-11f1-bbd6-1621a9abbb2c", "graylog_42", fields);
        eq(3, ExceptionDetails.chain(event).size());
        check(event.stackTrace().contains("PhoneNumberInvalidException: Invalid phone: +12025550123"), "New real log loses the domain validation failure");
        check(event.stackTrace().contains("Phone.php:46"), "New real log loses the original validation location");
        ExceptionSites.Accumulator index = new ExceptionSites.Accumulator(new Connection(), Path.of("/work")); index.accept(event);
        eq(1L, index.exceptionCount()); eq(3, index.finish().size());
        var cause = index.finish().stream().filter(s -> s.location().line() == 46).findFirst().orElseThrow();
        eq(Path.of("/work/src/Domain/Phone.php"), cause.location().file());
        eq(List.of(domain.get("class").getAsString()), cause.types());
    }
    private static void transport(JsonObject fixture) throws Exception {
        JsonObject fields = fixture.deepCopy(); fields.addProperty("_id", "yii-event");
        AtomicInteger restored = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/search/universal/absolute", exchange -> {
            Map<String, String> params = params(exchange);
            if (params.get("query").contains("_id:")) restored.incrementAndGet();
            JsonObject response = new JsonObject(); response.addProperty("from", params.get("from")); response.addProperty("to", params.get("to"));
            response.addProperty("total_results", 1);
            JsonObject wrapper = new JsonObject(); wrapper.addProperty("index", "graylog_fixture"); wrapper.add("message", fields);
            com.google.gson.JsonArray messages = new com.google.gson.JsonArray(); messages.add(wrapper); response.add("messages", messages);
            reply(exchange, "application/json", response.toString());
        });
        server.createContext("/api/search/universal/absolute/export", exchange -> {
            List<String> columns = List.of(params(exchange).get("fields").split(","));
            List<String> values = new java.util.ArrayList<>();
            for (String column : columns) values.add(fields.has(column) ? fields.get(column).getAsString().replace("\n", "\\\\n") : "");
            reply(exchange, "text/csv", csv(columns) + csv(values));
        });
        server.start();
        try (GraylogClient client = new GraylogClient("http://127.0.0.1:" + server.getAddress().getPort(), "fixture-token")) {
            Instant from = Instant.parse("2026-11-13T17:50:00Z");
            var index = client.indexPeriod(new SearchRequest("*", "", from, from.plusSeconds(900), 0, 100), new Connection(), Path.of("/work"), ignored -> {});
            eq(1L, index.messages()); eq(1L, index.exceptions()); eq(2, index.sites().size());
            eq(List.of(59, 91), index.sites().stream().map(s -> s.location().line()).sorted().toList());
            eq(1, restored.get());
            check(index.sites().stream().allMatch(s -> s.count() == 1 && s.events().getFirst().stackTrace().contains("Caused by:")), "Whole-period CSV index loses the exception chain");
        } finally { server.stop(0); }
    }
    private static Map<String, String> params(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] pair = item.split("=", 2); result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    private static String csv(List<String> fields) { return fields.stream().map(s -> "\"" + s.replace("\"", "\"\"") + "\"").collect(java.util.stream.Collectors.joining(",")) + "\r\n"; }
    private static void reply(HttpExchange exchange, String contentType, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", contentType); exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static void eq(Object expected, Object actual) { check(java.util.Objects.equals(expected, actual), "Expected " + expected + ", got " + actual); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
