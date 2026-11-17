package dev.graylog.jetbrains;

import java.time.Instant;

public record SearchRequest(String query, String streamId, Instant from, Instant to, int offset, int limit) {
    public SearchRequest {
        query = query == null || query.isBlank() ? "*" : query.trim();
        streamId = streamId == null ? "" : streamId;
        if (from == null || to == null || !from.isBefore(to)) throw new IllegalArgumentException("Invalid time range.");
        if (offset < 0 || limit < 1 || limit > 500 || offset + limit > 10000) {
            throw new IllegalArgumentException("Search supports up to 10,000 events, in pages of 1–500.");
        }
    }

    public SearchRequest page(int newOffset) { return new SearchRequest(query, streamId, from, to, newOffset, limit); }
    public boolean sameScope(SearchRequest other) {
        return other != null && query.equals(other.query) && streamId.equals(other.streamId) && from.equals(other.from) && to.equals(other.to);
    }

    public static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static String exact(String field, String value) {
        if (!field.matches("[A-Za-z_][A-Za-z0-9_.]*")) throw new IllegalArgumentException("Invalid field name.");
        return field + ":" + quote(value);
    }
}
