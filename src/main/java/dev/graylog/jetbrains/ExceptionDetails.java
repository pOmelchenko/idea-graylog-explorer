package dev.graylog.jetbrains;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Decodes structured exception payloads without modifying the original log document. */
final class ExceptionDetails {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    record Part(String type, String message, String code, SourceLocation origin, List<SourceLocation> trace) {
        Part { trace = List.copyOf(trace); }
        List<SourceLocation> frames() {
            LinkedHashSet<SourceLocation> frames = new LinkedHashSet<>();
            if (origin != null) frames.add(origin);
            frames.addAll(trace); return List.copyOf(frames);
        }
    }
    private ExceptionDetails() { }

    static List<Part> chain(LogEvent event) { return event.exceptions(); }

    static JsonObject object(JsonElement element) {
        // Both native JSON objects and JSON strings occur in GELF / Yii log targets.
        for (int attempt = 0; attempt < 2 && element != null && !element.isJsonNull(); attempt++) {
            if (element.isJsonObject()) return element.getAsJsonObject();
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return null;
            String text = element.getAsString().strip();
            if (!text.startsWith("{")) return null;
            try { element = JSON.fromJson(text, JsonElement.class); } catch (RuntimeException ignored) { return null; }
        }
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    static String render(List<Part> chain) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            Part part = chain.get(i);
            if (i > 0) text.append("\nCaused by: ");
            text.append(part.type());
            if (!part.message().isBlank() && !part.message().equals(part.type())) text.append(": ").append(part.message());
            if (!part.code().isBlank() && !part.code().equals("0")) text.append(" (code ").append(part.code()).append(')');
            text.append('\n');
            if (part.origin() != null) text.append("  at ").append(part.origin()).append('\n');
            for (int frame = 0; frame < part.trace().size(); frame++) text.append("  #").append(frame).append(' ').append(part.trace().get(frame)).append('\n');
        }
        return text.toString().stripTrailing();
    }

    static SourceLocation location(JsonObject object) {
        String file = value(object, "file", "filename");
        List<SourceLocation> combined = SourceLocation.parseTrace(file);
        String line = value(object, "line", "line_number");
        if (!file.isBlank() && !line.isBlank()) {
            try {
                int number = Integer.parseInt(line);
                if (number > 0) return new SourceLocation(combined.isEmpty() ? file : combined.getFirst().path(), number);
            } catch (NumberFormatException ignored) { }
        }
        return combined.isEmpty() ? null : combined.getFirst();
    }
    static List<SourceLocation> trace(JsonObject object) {
        List<SourceLocation> frames = new ArrayList<>();
        for (String key : List.of("trace", "stack_trace", "stacktrace")) {
            JsonElement trace = object.get(key);
            if (trace == null || trace.isJsonNull()) continue;
            if (trace.isJsonArray()) {
                for (JsonElement frame : trace.getAsJsonArray()) {
                    if (frame.isJsonObject()) { SourceLocation source = location(frame.getAsJsonObject()); if (source != null) frames.add(source); }
                    else if (frame.isJsonPrimitive() && frame.getAsJsonPrimitive().isString()) frames.addAll(SourceLocation.parseFrames(frame.getAsString()));
                }
            } else if (trace.isJsonPrimitive() && trace.getAsJsonPrimitive().isString()) frames.addAll(SourceLocation.parseFrames(trace.getAsString()));
            if (!frames.isEmpty()) break;
        }
        return List.copyOf(frames);
    }
    static String value(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive() && !value.getAsString().isBlank()) return value.getAsString();
        }
        return "";
    }
}
