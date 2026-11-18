package dev.graylog.jetbrains;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Runtime text stacks normalized without replacing their original text or inventing omitted frames. */
final class TextExceptionDecoder implements ExceptionDecoder {
    private static final Pattern HEADER = Pattern.compile("^(?:Caused by:\\s*|Suppressed:\\s*|--->\\s*)?([\\\\\\w.$]+(?:Exception|Error|Fault)|Exception|Error)(?::\\s*(.*))?$");
    private static final Pattern PYTHON_TYPE = Pattern.compile("^([\\w.]+)(?::\\s*(.*))?$");
    private static final Pattern RUBY = Pattern.compile("^.*?\\.rb:\\d+.*?: (.*) \\(([\\w:]+(?:Error|Exception))\\)$");
    @Override public List<ExceptionDetails.Part> decode(LogEvent event) {
        String text = event.first("stack_trace", "stacktrace", "exception_stack_trace", "full_message", "exception");
        if (text.isBlank()) return List.of();
        boolean python = text.contains("Traceback (most recent call last)");
        List<ExceptionDetails.Part> parts = new ArrayList<>();
        String type = "", message = ""; List<SourceLocation> frames = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip(); var head = HEADER.matcher(line); var ruby = RUBY.matcher(line);
            if (python && type.isBlank() && !frames.isEmpty() && raw.equals(raw.stripLeading()) && !head.matches()) head = PYTHON_TYPE.matcher(line);
            boolean panic = line.startsWith("panic:") || line.startsWith("thread '") && line.contains("panicked at");
            if (head.matches() || ruby.matches() || panic) {
                String nextType = panic ? "panic" : ruby.matches() ? ruby.group(2) : head.group(1);
                String nextMessage = panic ? line : ruby.matches() ? ruby.group(1) : head.group(2);
                if (!type.isBlank()) { add(parts, type, message, frames, python); frames = new ArrayList<>(); }
                type = nextType; message = nextMessage == null ? "" : nextMessage;
                frames.addAll(SourceLocation.parseFrames(raw));
            } else if (python && line.startsWith("Traceback") && !type.isBlank()) {
                add(parts, type, message, frames, true); type = ""; message = ""; frames = new ArrayList<>();
            } else frames.addAll(SourceLocation.parseFrames(raw));
        }
        if (type.isBlank()) type = event.first("exception_class", "exception_type", "exceptionClass", "error_type", "error.type");
        if (!type.isBlank()) add(parts, type, message.isBlank() ? event.text("message") : message, frames, python);
        if (python) Collections.reverse(parts); // Python prints the outermost exception last, JVM prints it first.
        return List.copyOf(parts);
    }
    private static void add(List<ExceptionDetails.Part> result, String type, String message, List<SourceLocation> frames, boolean python) {
        if (result.size() >= 64) return;
        var ordered = new ArrayList<>(frames); if (python) Collections.reverse(ordered);
        SourceLocation origin = ordered.isEmpty() ? null : ordered.removeFirst();
        result.add(new ExceptionDetails.Part(type, message, "", origin, ordered));
    }
}
