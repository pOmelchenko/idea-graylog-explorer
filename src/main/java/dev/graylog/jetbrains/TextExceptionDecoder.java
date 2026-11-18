package dev.graylog.jetbrains;

import java.util.ArrayList;
import java.util.List;

/** Top-level schema adapter; runtime syntax and ordering live in the shared Kotlin parser. */
final class TextExceptionDecoder implements ExceptionDecoder {
    @Override public List<ExceptionDetails.Part> decode(LogEvent event) {
        String text = event.first("stack_trace", "stacktrace", "exception_stack_trace", "full_message", "exception");
        var parts = new ArrayList<>(TextStackParser.parse(text,
                event.first("exception_class", "exception_type", "exceptionClass", "error_type", "error.type"), event.text("message")));
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
}
