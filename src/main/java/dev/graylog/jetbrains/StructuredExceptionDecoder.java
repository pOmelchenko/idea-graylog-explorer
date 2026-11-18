package dev.graylog.jetbrains;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** GELF/Yii-style object or JSON-string exception schema; independent of source language. */
final class StructuredExceptionDecoder implements ExceptionDecoder {
    public List<ExceptionDetails.Part> decode(LogEvent event) {
        return decodeObject(ExceptionDetails.object(event.element("exception")), event);
    }
    static List<ExceptionDetails.Part> decodeObject(JsonObject object, LogEvent event) {
        List<ExceptionDetails.Part> chain = new ArrayList<>();
        // Serialized data cannot have reference cycles; cap unusually deep payloads as well.
        for (int depth = 0; object != null && depth < 64; depth++) {
            String type = ExceptionDetails.value(object, "class", "type", "exception_class");
            if (type.isBlank() && depth == 0) type = event.first("exception_class", "exception_type", "exceptionClass", "error_type", "error.type");
            if (type.isBlank()) type = "Exception";
            String message = ExceptionDetails.value(object, "message"), code = ExceptionDetails.value(object, "code");
            SourceLocation origin = ExceptionDetails.location(object);
            List<SourceLocation> trace = ExceptionDetails.trace(object);
            chain.add(new ExceptionDetails.Part(type, message, code, origin, trace));
            JsonObject previous = ExceptionDetails.object(object.get("previous"));
            object = previous == null ? ExceptionDetails.object(object.get("cause")) : previous;
        }
        return List.copyOf(chain);
    }

}
