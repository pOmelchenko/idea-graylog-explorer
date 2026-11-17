package dev.graylog.jetbrains;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Graylog browser routes; authentication stays in the browser, never in the link. */
final class GraylogLinks {
    record Target(String url, boolean direct) { }
    private GraylogLinks() { }

    static Target event(Connection connection, LogEvent event, SearchRequest scope) {
        if (connection == null || event == null || event.id().isBlank()) return null;
        String base = connection.webUrl.isBlank() ? Connection.normalizeApiUrl(connection.apiUrl).replaceFirst("/api$", "") : connection.webUrl.strip();
        Connection.normalizeApiUrl(base); // Reject query strings, credentials and non-HTTP URLs.
        base = base.replaceAll("/+$", "");
        if (!event.index().isBlank()) return new Target(base + "/messages/" + segment(event.index()) + "/" + segment(event.id()), true);

        Instant from, to;
        if (scope != null) { from = scope.from(); to = scope.to(); }
        else if (event.timestamp() != null) { from = event.timestamp().minusSeconds(300); to = event.timestamp().plusSeconds(300); }
        else return null;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", SearchRequest.exact("_id", event.id())); params.put("rangetype", "absolute");
        params.put("from", from.toString()); params.put("to", to.toString());
        String query = params.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).collect(Collectors.joining("&"));
        return new Target(base + "/search?" + query, false);
    }
    private static String segment(String value) {
        String encoded = encode(value).replace("+", "%20");
        return value.equals(".") || value.equals("..") ? encoded.replace(".", "%2E") : encoded;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
