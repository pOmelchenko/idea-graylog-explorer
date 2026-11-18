package dev.graylog.jetbrains

import com.google.gson.JsonObject

class EcsExceptionDecoder : ExceptionDecoder {
    override fun decode(event: LogEvent): List<ExceptionDetails.Part> {
        var value = ExceptionDetails.`object`(event.element("error"))
        if (value == null && (event.text("error.type").isNotBlank() || event.text("error.stack_trace").isNotBlank())) value = JsonObject().apply {
            addProperty("type", event.text("error.type")); addProperty("message", event.text("error.message"))
            addProperty("stack_trace", event.text("error.stack_trace"))
        }
        val metadata = StructuredExceptionDecoder.decodeObject(value, event)
        val text = value?.let { ExceptionDetails.value(it, "stack_trace", "stacktrace") }.orEmpty()
        return TextStackParser.normalize(text, metadata, StructuredExceptionDecoder.rootType(value, event))
    }
}
