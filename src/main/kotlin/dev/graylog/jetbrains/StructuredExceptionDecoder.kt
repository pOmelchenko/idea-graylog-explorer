package dev.graylog.jetbrains

import com.google.gson.JsonObject

class StructuredExceptionDecoder : ExceptionDecoder {
    override fun decode(event: LogEvent) = decodeObject(ExceptionDetails.`object`(event.element("exception")), event)
    companion object {
        @JvmStatic fun decodeObject(input: JsonObject?, event: LogEvent): List<ExceptionDetails.Part> {
            val chain = mutableListOf<ExceptionDetails.Part>()
            var node = input
            for (depth in 0 until 64) {
                val current = node ?: break
                var type = ExceptionDetails.value(current, "class", "type", "exception_class")
                if (type.isBlank() && depth == 0) type = event.first("exception_class", "exception_type", "exceptionClass", "error_type", "error.type")
                chain.add(ExceptionDetails.Part(type.ifBlank { "Exception" }, ExceptionDetails.value(current, "message"),
                    ExceptionDetails.value(current, "code"), ExceptionDetails.location(current), ExceptionDetails.trace(current)))
                node = ExceptionDetails.`object`(current.get("previous")) ?: ExceptionDetails.`object`(current.get("cause"))
            }
            return java.util.List.copyOf(chain)
        }
    }
}
