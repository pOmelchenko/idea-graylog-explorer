package dev.graylog.jetbrains;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public record LogEvent(String id, String index, JsonObject fields, LogFormat format) {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    public LogEvent(String id, String index, JsonObject fields) { this(id, index, fields, new LogFormat()); }
    public JsonElement element(String key) {
        String configured = format.configuredName(key);
        JsonElement value = lookup(configured);
        if (value == null || value.isJsonNull()) value = lookup(key);
        if (key.equals("exception") && (value == null || value.isJsonNull()
                || (value.isJsonPrimitive() && value.getAsString().isBlank()))) value = lookup("ctxt_exception");
        return value;
    }
    private JsonElement lookup(String key) {
        JsonElement exact = fields.get(key);
        if (exact != null) return exact;
        JsonElement value = fields;
        for (String part : key.split("\\.")) {
            if (value == null || !value.isJsonObject()) return null;
            value = value.getAsJsonObject().get(part);
        }
        return value;
    }
    public String fieldName(String key) {
        String configured = format.configuredName(key);
        JsonElement value = lookup(configured);
        return value == null || value.isJsonNull() ? key : configured;
    }
    public String text(String key) {
        JsonElement value = element(key);
        if (value == null || value.isJsonNull()) return "";
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    public String first(String... keys) {
        for (String key : keys) if (!text(key).isBlank()) return text(key);
        return "";
    }

    public String level() {
        String value = first("level_name", "level", "severity").toUpperCase(Locale.ROOT);
        return switch (value) {
            case "0", "1", "2", "3", "400", "500", "550", "600", "ERROR", "CRITICAL", "ALERT", "EMERGENCY", "FATAL" -> "ERROR";
            case "4", "300", "WARN", "WARNING" -> "WARN";
            case "5", "6", "200", "250", "NOTICE", "INFO", "INFORMATIONAL" -> "INFO";
            case "7", "100", "DEBUG", "TRACE" -> "DEBUG";
            default -> value;
        };
    }

    public String service(String preferredField) {
        return first(preferredField, "service_name", "service", "application_name", "application", "source");
    }

    public Instant timestamp() {
        String raw = text("timestamp").replace(' ', 'T').replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2");
        try { return Instant.parse(raw); }
        catch (RuntimeException e) {
            try { return Instant.parse(raw + "Z"); }
            catch (RuntimeException ignored) { return null; }
        }
    }

    public String displayTime() {
        Instant time = timestamp();
        return time == null ? text("timestamp") : DISPLAY_TIME.format(time);
    }

    public String stackTrace() {
        var chain = ExceptionDetails.chain(this);
        if (!chain.isEmpty()) return ExceptionDetails.render(chain);
        return first("stack_trace", "stacktrace", "exception_stack_trace", "full_message", "exception", "message");
    }

    public String identity() { return index + ":" + (id.isBlank() ? fields.toString() : id); }
}
