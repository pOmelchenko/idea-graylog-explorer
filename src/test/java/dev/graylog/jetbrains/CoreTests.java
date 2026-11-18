package dev.graylog.jetbrains;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free contract and transport tests; all HTTP traffic stays on loopback. */
public final class CoreTests {
    private static int assertions;
    private static final String TOKEN = "test-only-token";

    public static void main(String[] args) throws Exception {
        urls(); queries(); events(); paths(); exceptionSites(); http(); assertions += PeriodIndexTests.run(); assertions += SettingsTests.run();
        assertions += ExceptionDetailsTests.run();
        assertions += GraylogLinksTests.run();
        assertions += ExceptionPresentationTests.run();
        assertions += LoggedStringTemplateTests.run();
        assertions += ExplorerPresentationTests.run();
        assertions += SourceFilterTests.run();
        assertions += ControllerTests.run();
        assertions += SettingsModelTests.run();
        assertions += CredentialUpdateTests.run();
        assertions += PortableStackTests.run();
        assertions += PortableExportTests.run();
        assertions += OperationPolicyTests.run();
        assertions += ConnectionUpdatesTests.run();
        System.out.println("PASS: " + assertions + " assertions (URLs, queries, event parsing, exception locations, path mapping, HTTP contracts and cancellation)");
    }

    static List<ExceptionSites.Site> sites(List<LogEvent> events, Connection connection, Path root) {
        ExceptionSites.Accumulator accumulator = new ExceptionSites.Accumulator(connection, root);
        events.forEach(accumulator::accept); return accumulator.finish();
    }

    private static void urls() {
        eq("https://logs.example.org/api", Connection.normalizeApiUrl(" https://logs.example.org/ "));
        eq("https://logs.example.org/graylog/api", Connection.normalizeApiUrl("https://logs.example.org/graylog/api/"));
        for (String invalid : List.of("file:///etc/passwd", "https://user:password@host/api", "https://host/api?token=x", "https://host/#x", "host/api")) {
            rejects(() -> Connection.normalizeApiUrl(invalid));
        }
    }

    private static void queries() {
        eq("request_id:\"a\\\"b\\\\c\"", SearchRequest.exact("request_id", "a\"b\\c"));
        rejects(() -> SearchRequest.exact("bad:field", "x"));
        Instant start = Instant.parse("2026-01-01T12:00:00Z");
        SearchRequest request = new SearchRequest("  ", "stream-1", start, start.plusSeconds(900), 0, 100);
        eq("*", request.query());
        eq(request.from(), request.page(100).from()); eq(request.to(), request.page(100).to());
        eq(100, request.page(100).offset());
        rejects(() -> request.page(10000));
        rejects(() -> request.page(Integer.MAX_VALUE));
        rejects(() -> new SearchRequest("*", "", start, start, 0, 100));
        rejects(() -> new SearchRequest("*", "", start, start.plusSeconds(10), 0, 501));
    }

    private static void events() throws Exception {
        JsonObject response = json("""
                {"total_results":2,"messages":[
                  {"index":"graylog_1","message":{"_id":"one","timestamp":"2026-01-01T12:42:08.162Z","level":3,
                    "source":"api","message":"timeout","custom":{"retry":2},"nullable":null}},
                  {"index":"graylog_1","message":{"_id":"two","timestamp":"2026-01-01 12:42:08.162","level_name":"WARNING",
                    "source":"worker","message":"retry"}}
                ]}
                """);
        GraylogApi.Result parsed = GraylogClient.parseResult(response);
        eq(2L, parsed.total()); eq(2, parsed.events().size());
        LogEvent first = parsed.events().getFirst();
        eq("ERROR", first.level()); eq("12:42:08.162", first.displayTime()); eq("api", first.service("service"));
        eq("graylog_1:one", first.identity()); eq("", first.text("nullable"));
        eq(2, first.fields().getAsJsonObject("custom").get("retry").getAsInt());
        response.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonObject("message").addProperty("source", "changed");
        eq("api", first.text("source"));
        eq("WARN", parsed.events().get(1).level());
        eq(first.timestamp(), parsed.events().get(1).timestamp());
        for (String[] level : new String[][]{{"6","INFO"},{"7","DEBUG"},{"300","WARN"},{"400","ERROR"},{"100","DEBUG"}}) {
            JsonObject fields = json("{}"); fields.addProperty("level", level[0]);
            eq(level[1], new LogEvent("", "", fields).level());
        }
        JsonObject offset = json("{\"timestamp\":\"2026-01-01T14:42:08.162+0200\"}");
        eq(first.timestamp(), new LogEvent("", "", offset).timestamp());
        eq(0, GraylogClient.parseResult(json("{\"messages\":[],\"total_results\":0}")).events().size());
        ioError(() -> GraylogClient.parseResult(json("{\"unexpected\":true}")), "no messages");
    }

    private static void paths() {
        Connection connection = new Connection(); connection.remoteRoot = "/application"; connection.localRoot = "/work/project/back";
        Path root = Path.of("/work/project");
        eq(Path.of("/work/project/back/src/StockClient.php"), new SourceLocation("/application/src/StockClient.php", 87).resolve(connection, root));
        eq(Path.of("/work/project/back/src/StockClient.php"), new SourceLocation("src/StockClient.php", 87).resolve(connection, root));
        eq(null, new SourceLocation("/application/../../etc/passwd", 1).resolve(connection, root));
        eq(null, new SourceLocation("../../outside.php", 1).resolve(connection, root));
        eq(null, new SourceLocation("/application-other/StockClient.php", 1).resolve(connection, root));
        JsonObject fields = json("{}");
        fields.addProperty("file", "/application/src/StockClient.php"); fields.addProperty("line", 87);
        fields.addProperty("full_message", """
                InventoryUnavailableException
                #0 /application/src/StockClient.php(87): request()
                #1 /application/src/OrderService.php:142
                #2 /application/src/CheckoutController.php(56): checkout()
                """);
        List<SourceLocation> locations = SourceLocation.find(new LogEvent("", "", fields));
        eq(3, locations.size()); eq(87, locations.get(0).line()); eq(142, locations.get(1).line());
        eq("/application/src/CheckoutController.php", locations.get(2).path());
        JsonObject fullTrace = json("{}");
        fullTrace.addProperty("exception", "RuntimeException");
        fullTrace.addProperty("full_message", "#0 /application/src/Worker.php(42): run()");
        eq(new SourceLocation("/application/src/Worker.php", 42), SourceLocation.find(new LogEvent("", "", fullTrace)).getFirst());
        connection.remoteRoot = "C:/app";
        eq(Path.of("/work/project/back/src/Test.php"), new SourceLocation("C:\\app\\src\\Test.php", 4).resolve(connection, root));
        connection.localRoot = "bad\0path";
        eq(null, new SourceLocation("src/Test.php", 4).resolve(connection, root));
    }

    private static void exceptionSites() {
        Connection connection = new Connection(); connection.localRoot = "/work/project";
        Path root = Path.of("/work/project");
        LogEvent first = exceptionEvent("one", "InventoryUnavailableException", "/application/src/StockClient.php", 87);
        LogEvent second = exceptionEvent("two", "InventoryUnavailableException", "/application/src/StockClient.php", 87);
        List<ExceptionSites.Site> grouped = CoreTests.sites(List.of(first, second), connection, root);
        eq(1, grouped.size()); eq(2, grouped.getFirst().events().size());
        eq("InventoryUnavailableException", grouped.getFirst().types().getFirst());
        eq(new ExceptionSites.Location(Path.of("/work/project/src/StockClient.php"), 87), grouped.getFirst().location());
        LogEvent anotherThrow = exceptionEvent("three", "InventoryUnavailableException", "/application/src/StockClient.php", 103);
        eq(2, CoreTests.sites(List.of(first, anotherThrow), connection, root).size());
        LogEvent otherType = exceptionEvent("four", "TypeError", "/application/src/StockClient.php", 87);
        grouped = CoreTests.sites(List.of(first, second, otherType), connection, root);
        eq(1, grouped.size()); eq(2, grouped.getFirst().types().size()); eq(3, grouped.getFirst().events().size());

        JsonObject plain = json("{\"message\":\"Error during request\",\"level\":3,\"file\":\"/application/src/Logger.php\",\"line\":9}");
        eq(0, CoreTests.sites(List.of(new LogEvent("plain", "", plain)), connection, root).size());
        JsonObject traced = json("{\"level\":3,\"file\":\"/application/src/Logger.php\",\"line\":9}");
        traced.addProperty("full_message", "App\\Domain\\OrderException: Failed\n#0 /application/src/Order.php(42): place()");
        LogEvent event = new LogEvent("trace", "", traced);
        eq("App\\Domain\\OrderException", ExceptionSites.exceptionName(event));
        eq(42, CoreTests.sites(List.of(event), connection, root).getFirst().location().line());

        JsonObject nested = json("""
                {"exception":{"class":"OutOfStock","file":"/application/src/Stock.php","line":56,
                    "trace":[{"file":"/application/src/Controller.php","line":12}]}}
                """);
        event = new LogEvent("nested", "", nested);
        eq("OutOfStock", ExceptionSites.exceptionName(event));
        eq(56, CoreTests.sites(List.of(event), connection, root).getFirst().location().line());
        eq(2, ExceptionSites.exceptionFrames(event).size());

        JsonObject outside = json("{}");
        outside.addProperty("full_message", "RuntimeException: Failed\n#0 /external/Library.php:3\n#1 /application/src/Worker.php:19");
        eq(19, CoreTests.sites(List.of(new LogEvent("outside", "", outside)), connection, root).getFirst().location().line());
        LogEvent traversal = exceptionEvent("traversal", "RuntimeException", "/application/../../etc/secret.php", 1);
        eq(0, CoreTests.sites(List.of(traversal), connection, root).size());
        JsonObject unlocated = json("{\"exception_class\":\"RuntimeException\",\"message\":\"No location\"}");
        eq(0, CoreTests.sites(List.of(new LogEvent("none", "", unlocated)), connection, root).size());

        LogEvent anonymousOne = exceptionEvent("", "RuntimeException", "/application/src/A.php", 1);
        JsonObject otherFields = anonymousOne.fields().deepCopy(); otherFields.addProperty("request_id", "different");
        grouped = CoreTests.sites(List.of(anonymousOne, new LogEvent("", "", otherFields)), connection, root);
        eq(2, grouped.getFirst().events().size());
    }

    private static LogEvent exceptionEvent(String id, String type, String file, int line) {
        JsonObject fields = json("{}"); fields.addProperty("exception_class", type);
        fields.addProperty("exception_file", file); fields.addProperty("exception_line", line);
        fields.addProperty("message", "Failed"); return new LogEvent(id, "graylog_1", fields);
    }

    private static void http() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        List<Map<String, String>> requests = new ArrayList<>();
        AtomicInteger mode = new AtomicInteger(200); AtomicInteger redirected = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        server.createContext("/prefix/api/streams", exchange -> {
            try {
                eq("Basic " + Base64.getEncoder().encodeToString((TOKEN + ":token").getBytes(StandardCharsets.UTF_8)),
                        exchange.getRequestHeaders().getFirst("Authorization"));
                eq("graylog-jetbrains", exchange.getRequestHeaders().getFirst("X-Requested-By"));
                reply(exchange, 200, "{\"streams\":[{\"id\":\"b\",\"title\":\"Workers\"},{\"id\":\"a\",\"title\":\"API\"}]}");
            } catch (Throwable failure) { reply(exchange, 500, "test assertion failed"); }
        });
        server.createContext("/prefix/api/search/universal/absolute", exchange -> {
            int current = mode.get();
            if (current == 999) {
                started.countDown();
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (current == 302) exchange.getResponseHeaders().add("Location", "/redirect-target");
            if (current != 200 && current != 999) { reply(exchange, current, TOKEN + " internal sensitive error body"); return; }
            synchronized (requests) { requests.add(parameters(exchange.getRequestURI().getRawQuery())); }
            reply(exchange, 200, "{\"messages\":[],\"total_results\":420}");
        });
        server.createContext("/redirect-target", exchange -> { redirected.incrementAndGet(); reply(exchange, 200, "{}"); });
        server.createContext("/invalid/api/streams", exchange -> reply(exchange, 200, "<html>" + TOKEN + "</html>"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (GraylogClient client = new GraylogClient(base + "/prefix/api", TOKEN)) {
            List<GraylogApi.Stream> streams = client.streams(); eq("API", streams.getFirst().title()); eq("a", streams.getFirst().id());
            Instant start = Instant.parse("2026-01-01T12:00:00Z");
            SearchRequest request = new SearchRequest("message:\"a+b & тест\"", "stream-123", start, start.plusSeconds(900), 0, 100);
            eq(420L, client.search(request).total()); client.search(request.page(100));
            eq(request.query(), requests.getFirst().get("query")); eq("streams:stream-123", requests.getFirst().get("filter"));
            eq("timestamp:desc", requests.getFirst().get("sort"));
            eq("2026-01-01T12:00:00.000Z", requests.getFirst().get("from"));
            eq("100", requests.get(1).get("offset"));
            eq(requests.getFirst().get("from"), requests.get(1).get("from"));
            eq(requests.getFirst().get("to"), requests.get(1).get("to"));
            for (int status : new int[]{401, 403, 404, 429, 500, 302}) {
                mode.set(status); String error = ioError(() -> client.search(request), "HTTP " + status);
                check(!error.contains(TOKEN), "Credentials must not appear in errors");
            }
            eq(0, redirected.get());
            mode.set(999);
            var caller = Executors.newSingleThreadExecutor();
            CountDownLatch interrupted = new CountDownLatch(1);
            try {
                var future = caller.submit(() -> {
                    try { client.search(request); }
                    catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                    catch (IOException ignored) { }
                });
                check(started.await(3, TimeUnit.SECONDS), "Slow request started");
                future.cancel(true);
                check(interrupted.await(3, TimeUnit.SECONDS), "Cancellation interrupts HTTP request");
            } finally { caller.shutdownNow(); }
        } finally { server.stop(0); executor.shutdownNow(); }
        // Invalid JSON is checked on a separate short-lived server because the previous one is stopped.
        HttpServer invalid = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        invalid.createContext("/api/streams", exchange -> reply(exchange, 200, "<html>" + TOKEN + "</html>")); invalid.start();
        try (GraylogClient client = new GraylogClient("http://127.0.0.1:" + invalid.getAddress().getPort(), TOKEN)) {
            String message = ioError(client::streams, "invalid JSON"); check(!message.contains(TOKEN), "JSON errors do not echo response body");
        } finally { invalid.stop(0); }
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static Map<String, String> parameters(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : raw.split("&")) { String[] pair = part.split("=", 2); result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); }
        return result;
    }
    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void rejects(Runnable action) { try { action.run(); throw new AssertionError("Expected validation failure"); } catch (IllegalArgumentException expected) { assertions++; } }
    private static String ioError(Throwing action, String contains) throws Exception {
        try { action.run(); throw new AssertionError("Expected IOException containing " + contains); }
        catch (IOException expected) { check(expected.getMessage().contains(contains), expected.getMessage()); return expected.getMessage(); }
    }
    private static void eq(Object expected, Object actual) { check(java.util.Objects.equals(expected, actual), "Expected " + expected + ", got " + actual); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
