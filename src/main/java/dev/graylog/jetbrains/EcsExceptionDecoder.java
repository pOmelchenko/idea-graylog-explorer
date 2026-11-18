package dev.graylog.jetbrains;

import java.util.List;

/** ECS-style error object. Configured exception fields still take precedence. */
final class EcsExceptionDecoder implements ExceptionDecoder {
    public List<ExceptionDetails.Part> decode(LogEvent event) {
        var object = ExceptionDetails.object(event.element("error"));
        if (object == null && !event.text("error.type").isBlank()) {
            object = new com.google.gson.JsonObject();
            object.addProperty("type", event.text("error.type")); object.addProperty("message", event.text("error.message"));
            object.addProperty("stack_trace", event.text("error.stack_trace"));
        }
        return StructuredExceptionDecoder.decodeObject(object, event);
    }
}
