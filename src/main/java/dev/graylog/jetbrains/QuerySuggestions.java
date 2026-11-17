package dev.graylog.jetbrains;

import java.util.*;

/** Caret-aware Lucene suggestions. Never parses or rewrites the rest of the query. */
final class QuerySuggestions {
    record Item(String insert, String label, int from, int to) {
        @Override public String toString() { return label; }
        String apply(String query) { return query.substring(0, from) + insert + query.substring(to); }
    }
    static final List<String> BUILT_INS = List.of("message", "full_message", "source", "timestamp", "level", "line", "file",
            "streams", "application", "stage", "facility", "channel", "ctxt_exception", "ctxt_category", "user_id",
            "gl2_message_id", "gl2_receive_timestamp", "gl2_remote_ip", "gl2_remote_port", "gl2_source_input",
            "gl2_source_node", "gl2_processing_timestamp", "gl2_second_sort_field", "request_id", "trace_id", "correlation_id");
    private QuerySuggestions() { }

    static List<Item> at(String query, int caret, Collection<String> fields, Map<String, List<String>> values) {
        caret = Math.max(0, Math.min(caret, query.length()));
        boolean quoted = false, escaped = false; int rangeDepth = 0;
        for (int i = 0; i < caret; i++) {
            char c = query.charAt(i);
            if (!escaped && c == '"') quoted = !quoted;
            if (!escaped && !quoted && (c == '[' || c == '{')) rangeDepth++;
            if (!escaped && !quoted && (c == ']' || c == '}')) rangeDepth = Math.max(0, rangeDepth - 1);
            if (!escaped && c == '\\') escaped = true; else escaped = false;
        }
        if (quoted || escaped || rangeDepth > 0) return List.of();
        int from = caret, to = caret;
        while (from > 0 && token(query.charAt(from - 1))) from--;
        while (to < query.length() && token(query.charAt(to))) to++;
        String prefix = query.substring(from, caret).toLowerCase(Locale.ROOT);
        int before = from;
        while (before > 0 && Character.isWhitespace(query.charAt(before - 1))) before--;
        List<Item> result = new ArrayList<>();
        if (before > 0 && query.charAt(before - 1) == ':') {
            int end = before - 1, start = end;
            while (start > 0 && token(query.charAt(start - 1))) start--;
            String field = query.substring(start, end);
            if (field.equals("level")) {
                String[] levels = {"Emergency", "Alert", "Critical", "Error", "Warning", "Notice", "Info", "Debug"};
                for (int i = 0; i < levels.length; i++) if (Integer.toString(i).startsWith(prefix)) {
                    result.add(new Item(Integer.toString(i), i + " — " + levels[i], from, to));
                }
            } else for (String value : values.getOrDefault(field, List.of())) {
                if (value.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(new Item(quote(value), value, from, to));
            }
            return List.copyOf(result.stream().limit(50).toList());
        }
        // A value after an existing field/range must not be replaced with another field.
        if (from > 0 && !Character.isWhitespace(query.charAt(from - 1)) && "(+-".indexOf(query.charAt(from - 1)) < 0) return List.of();
        boolean hasColon = to < query.length() && query.charAt(to) == ':';
        for (String field : new TreeSet<>(fields)) if (field.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            result.add(new Item(field + (hasColon ? "" : ":"), field + ":", from, to));
        }
        if (!prefix.isBlank() && from > 0) for (String operator : List.of("AND", "OR", "NOT")) {
            if (operator.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(new Item(operator + " ", operator, from, to));
        }
        return List.copyOf(result.stream().limit(80).toList());
    }
    private static boolean token(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-'; }
    private static String quote(String value) { return value.matches("[A-Za-z0-9_.-]+") ? value : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
