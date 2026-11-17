package dev.graylog.jetbrains;

import java.awt.Color;
import java.util.List;
import java.util.Map;

final class ExplorerPresentationTests {
    private static int assertions;
    static int run() {
        var fields = List.of("level", "line", "file", "message", "source", "custom_field");
        var values = Map.of("source", List.of("api", "worker queue"));
        var levels = QuerySuggestions.at("le", 2, fields, values);
        eq("level:", levels.getFirst().apply("le"));
        eq(1, levels.size());
        var middle = QuerySuggestions.at("le:3 AND source:api", 2, fields, values).getFirst();
        eq("level:3 AND source:api", middle.apply("le:3 AND source:api"));
        eq(5, middle.from() + middle.insert().length());
        var tail = QuerySuggestions.at("source:api AND li", 17, fields, values).getFirst();
        eq("source:api AND line:", tail.apply("source:api AND li"));
        eq("3 — Error", QuerySuggestions.at("level: 3", 8, fields, values).getFirst().label());
        eq(8, QuerySuggestions.at("level:", 6, fields, values).size());
        eq("source:api", QuerySuggestions.at("source:a", 8, fields, values).getFirst().apply("source:a"));
        eq("source:\"worker queue\"", QuerySuggestions.at("source:w", 8, fields, values).getFirst().apply("source:w"));
        eq(0, QuerySuggestions.at("message:\"le", 11, fields, values).size());
        eq(0, QuerySuggestions.at("level:[3", 8, fields, values).size());
        eq(0, QuerySuggestions.at("level:[0 TO le", 14, fields, values).size());
        eq(0, QuerySuggestions.at("file:/app/li", 12, fields, values).size());
        eq("(level:", QuerySuggestions.at("(le", 3, fields, values).getFirst().apply("(le"));
        eq(0, QuerySuggestions.at("unknown:value", 13, fields, values).size());
        eq("source:api AND ", QuerySuggestions.at("source:api AN", 13, fields, values).getFirst().apply("source:api AN"));
        eq("custom_field:", QuerySuggestions.at("cust", 4, fields, values).getFirst().insert());
        Color automatic = SourceColors.color("api", Map.of());
        eq(automatic, SourceColors.color("api", Map.of("other", "#FFFFFF")));
        eq(Color.decode("#123456"), SourceColors.color("api", Map.of("api", "#123456")));
        eq(null, SourceColors.color("", Map.of()));
        eq("#123456", SourceColors.hex(Color.decode("#123456")));
        // Color is keyed by source; ERROR/INFO from the same source share one color.
        var a = new LogEvent("a", "", com.google.gson.JsonParser.parseString("{\"source\":\"api\",\"level\":3}").getAsJsonObject());
        var b = new LogEvent("b", "", com.google.gson.JsonParser.parseString("{\"source\":\"api\",\"level\":6}").getAsJsonObject());
        eq(SourceColors.color(a.service("source"), Map.of()), SourceColors.color(b.service("source"), Map.of()));
        IdeOptions options = new IdeOptions(); options.sourceColors.put("api", "#123456"); options.validate();
        eq(options.sourceColors, options.copy().sourceColors);
        options.copy().sourceColors.clear(); eq(1, options.sourceColors.size());
        options.sourceColors.put("api", "red");
        try { options.validate(); throw new AssertionError("Invalid source color accepted"); } catch (IllegalArgumentException expected) { assertions++; }
        return assertions;
    }
    private static void eq(Object expected, Object actual) { assertions++; if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual); }
}
