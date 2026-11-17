package dev.graylog.jetbrains;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class LogFormat {
    public String serviceField = "source";
    public String exceptionTypeField = "exception_class";
    public String exceptionObjectField = "exception";
    public String fileField = "file";
    public String lineField = "line";
    public String stackTraceField = "stack_trace";
    public String requestIdField = "request_id";
    public String traceIdField = "trace_id";

    public LogFormat copy() { return SettingsValues.copy(this, LogFormat.class); }
    public String configuredName(String canonical) {
        return switch (canonical) {
            case "source" -> serviceField; case "exception_class" -> exceptionTypeField;
            case "exception" -> exceptionObjectField; case "file" -> fileField; case "line" -> lineField;
            case "stack_trace" -> stackTraceField; case "request_id" -> requestIdField; case "trace_id" -> traceIdField;
            default -> canonical;
        };
    }
    public Map<String, String> aliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("source", serviceField); aliases.put("exception_class", exceptionTypeField);
        aliases.put("exception", exceptionObjectField); aliases.put("file", fileField);
        aliases.put("line", lineField); aliases.put("stack_trace", stackTraceField);
        aliases.put("request_id", requestIdField); aliases.put("trace_id", traceIdField);
        return aliases;
    }
    public Set<String> exportFields() {
        Set<String> fields = new LinkedHashSet<>(aliases().values());
        for (String field : aliases().values()) if (field.contains(".")) fields.add(field.substring(0, field.indexOf('.')));
        return fields;
    }
    public void validate() {
        for (String field : aliases().values()) if (!field.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            throw new IllegalArgumentException("Log field names must contain letters, numbers, underscores or dots: " + field);
        }
    }
}
