package dev.graylog.jetbrains;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Runtime text stacks normalized without replacing their original text or inventing omitted frames. */
final class TextExceptionDecoder implements ExceptionDecoder {
    private static final String TYPE = "(?:[\\\\\\w.$]+(?:Exception|Error|Fault)|Exception|Error)";
    private static final Pattern HEADER = Pattern.compile("^(?:Caused by:\\s*|Suppressed:\\s*|--->\\s*)?(" + TYPE + ")(?::\\s*(.*))?$");
    private static final Pattern INLINE_INNER = Pattern.compile("\\s*--->\\s*(?=" + TYPE + "(?::|$))");
    private static final String INNER_END = "--- End of inner exception stack trace ---";
    private static final Pattern PYTHON_TYPE = Pattern.compile("^([\\w.]+)(?::\\s*(.*))?$");
    private static final Pattern RUBY = Pattern.compile("^.*?\\.rb:\\d+.*?: (.*) \\(([\\w:]+(?:Error|Exception))\\)$");
    @Override public List<ExceptionDetails.Part> decode(LogEvent event) {
        String text = event.first("stack_trace", "stacktrace", "exception_stack_trace", "full_message", "exception");
        if (text.isBlank()) return List.of();
        if (text.contains(INNER_END) && text.contains("--->")) {
            var dotnet = dotnet(text);
            if (!dotnet.isEmpty()) return withExplicitOrigin(dotnet, event);
        }
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
        return withExplicitOrigin(parts, event);
    }
    /** .NET prints inner frames first, then resumes the parent's frames after each boundary. */
    private static List<ExceptionDetails.Part> dotnet(String text) {
        List<DotnetPart> parts = new ArrayList<>();
        var parents = new ArrayDeque<DotnetPart>();
        DotnetPart current = null;
        for (String raw : INLINE_INNER.matcher(text).replaceAll("\n---> ").split("\\R")) {
            String line = raw.strip();
            if (line.equals(INNER_END)) {
                if (!parents.isEmpty()) current = parents.pop();
                continue;
            }
            var head = HEADER.matcher(line);
            if (head.matches()) {
                if (parts.size() >= 64) break;
                if (line.startsWith("--->") && current != null) parents.push(current);
                else parents.clear();
                current = new DotnetPart(head.group(1), head.group(2) == null ? "" : head.group(2));
                parts.add(current);
            } else if (current != null) current.frames.addAll(SourceLocation.parseFrames(raw));
        }
        List<ExceptionDetails.Part> result = new ArrayList<>();
        for (var part : parts) add(result, part.type, part.message, part.frames, false);
        return result;
    }
    private static final class DotnetPart {
        final String type, message;
        final List<SourceLocation> frames = new ArrayList<>();
        DotnetPart(String type, String message) { this.type = type; this.message = message; }
    }
    private static List<ExceptionDetails.Part> withExplicitOrigin(List<ExceptionDetails.Part> parts, LogEvent event) {
        // Explicit exception metadata describes the root throw; generic file/line may be a logger call.
        if (!parts.isEmpty() && !event.text("exception_file").isBlank()) {
            try {
                int line = Integer.parseInt(event.text("exception_line"));
                if (line > 0) {
                    var origin = new SourceLocation(event.text("exception_file"), line);
                    var root = parts.getFirst();
                    var trace = root.frames().stream().filter(frame -> !frame.equals(origin)).toList();
                    parts.set(0, new ExceptionDetails.Part(root.type(), root.message(), root.code(), origin, trace));
                }
            } catch (NumberFormatException ignored) { }
        }
        return List.copyOf(parts);
    }
    private static void add(List<ExceptionDetails.Part> result, String type, String message, List<SourceLocation> frames, boolean python) {
        if (result.size() >= 64) return;
        var ordered = new ArrayList<>(frames); if (python) Collections.reverse(ordered);
        SourceLocation origin = ordered.isEmpty() ? null : ordered.removeFirst();
        result.add(new ExceptionDetails.Part(type, message, "", origin, ordered));
    }
}
