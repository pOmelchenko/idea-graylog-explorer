package dev.graylog.jetbrains

import com.google.gson.JsonObject

class EcsExceptionDecoder : ExceptionDecoder {
    override fun decode(event: LogEvent): List<ExceptionDetails.Part> {
        var value = ExceptionDetails.`object`(event.element("error"))
        if (value == null && event.text("error.type").isNotBlank()) value = JsonObject().apply {
            addProperty("type", event.text("error.type")); addProperty("message", event.text("error.message"))
            addProperty("stack_trace", event.text("error.stack_trace"))
        }
        return StructuredExceptionDecoder.decodeObject(value, event)
    }
}
