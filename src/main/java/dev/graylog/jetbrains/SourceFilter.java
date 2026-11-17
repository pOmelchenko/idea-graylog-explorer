package dev.graylog.jetbrains;

import java.util.*;
import java.util.stream.Collectors;

/** Source filters are composed outside the editable query and always reach the server. */
final class SourceFilter {
    private final Set<String> selected = new LinkedHashSet<>();
    Set<String> values() { return Collections.unmodifiableSet(selected); }
    void replace(Collection<String> values) {
        Set<String> replacement = new LinkedHashSet<>();
        values.stream().filter(s -> s != null && !s.isBlank()).forEach(replacement::add);
        selected.clear(); selected.addAll(replacement);
    }
    void clear() { selected.clear(); }
    String label() { return selected.isEmpty() ? "Sources: All" : selected.size() == 1 ? "Source: " + shorten(selected.iterator().next()) : "Sources: " + selected.size(); }
    private static String shorten(String text) { return text.length() > 26 ? text.substring(0, 25) + "…" : text; }
    String query(String base, String field) {
        base = base == null || base.isBlank() ? "*" : base.trim();
        if (selected.isEmpty()) return base;
        String clause = selected.stream().sorted().map(value -> SearchRequest.exact(field, value)).collect(Collectors.joining(" OR "));
        return "(" + base + ") AND (" + clause + ")";
    }
    SearchRequest apply(SearchRequest base, String field) { return new SearchRequest(query(base.query(), field), base.streamId(), base.from(), base.to(), base.offset(), base.limit()); }
}
