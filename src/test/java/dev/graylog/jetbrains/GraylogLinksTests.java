package dev.graylog.jetbrains;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class GraylogLinksTests {
    private static int assertions;
    static int run() {
        Connection connection = new Connection(); connection.apiUrl = "https://api.example.org/graylog/api";
        connection.webUrl = "https://logs.example.org/graylog/";
        LogEvent event = new LogEvent("e35f2a01-df92-11f1-bbd6-1621a9abbb2c", "graylog_42", new JsonObject());
        var target = GraylogLinks.event(connection, event, null);
        eq("https://logs.example.org/graylog/messages/graylog_42/e35f2a01-df92-11f1-bbd6-1621a9abbb2c", target.url());
        check(target.direct(), "Known index and ID must open the message directly");
        event = new LogEvent("a/b ?#%+ тест", "index /?#", new JsonObject());
        URI encoded = URI.create(GraylogLinks.event(connection, event, null).url());
        eq("/graylog/messages/index%20%2F%3F%23/a%2Fb%20%3F%23%25%2B%20%D1%82%D0%B5%D1%81%D1%82", encoded.getRawPath());
        eq(null, encoded.getRawQuery()); eq(null, encoded.getFragment());
        eq("/graylog/messages/%2E%2E/%2E", URI.create(GraylogLinks.event(connection, new LogEvent(".", "..", new JsonObject()), null).url()).getRawPath());
        connection.webUrl = "";
        eq("https://api.example.org/graylog/messages/graylog_0/id", GraylogLinks.event(connection, new LogEvent("id", "graylog_0", new JsonObject()), null).url());
        connection.webUrl = "https://logs.example.org/graylog";
        Instant from = Instant.parse("2026-11-13T18:40:00Z");
        SearchRequest scope = new SearchRequest("level:3", "some-stream", from, from.plusSeconds(900), 0, 100);
        event = new LogEvent("abc\"\\ +:тест", "", new JsonObject());
        target = GraylogLinks.event(connection, event, scope);
        check(!target.direct(), "CSV sample without an index must search by its ID");
        eq("/graylog/search", URI.create(target.url()).getPath());
        Map<String, String> params = params(target.url());
        eq(SearchRequest.exact("_id", event.id()), params.get("q")); eq("absolute", params.get("rangetype"));
        eq(from.toString(), params.get("from")); eq(from.plusSeconds(900).toString(), params.get("to"));
        JsonObject fields = new JsonObject(); fields.addProperty("timestamp", "2026-11-13T18:48:59.778Z");
        target = GraylogLinks.event(connection, new LogEvent("id", "", fields), null);
        params = params(target.url()); eq("2026-11-13T18:43:59.778Z", params.get("from")); eq("2026-11-13T18:53:59.778Z", params.get("to"));
        eq(null, GraylogLinks.event(connection, null, scope));
        eq(null, GraylogLinks.event(null, event, scope));
        eq(null, GraylogLinks.event(connection, new LogEvent("", "index", fields), scope));
        eq(null, GraylogLinks.event(connection, event, null));
        return assertions;
    }
    private static Map<String, String> params(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String item : URI.create(url).getRawQuery().split("&")) {
            String[] pair = item.split("=", 2); params.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        } return params;
    }
    private static void eq(Object expected, Object actual) { check(java.util.Objects.equals(expected, actual), "Expected " + expected + ", got " + actual); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
