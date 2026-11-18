package dev.graylog.jetbrains;

import java.util.List;

/** ECS-style error object. Configured exception fields still take precedence. */
final class EcsExceptionDecoder implements ExceptionDecoder {
    public List<ExceptionDetails.Part> decode(LogEvent event) {
        return StructuredExceptionDecoder.decodeObject(ExceptionDetails.object(event.element("error")), event);
    }
}
