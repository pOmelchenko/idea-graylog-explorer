package dev.graylog.jetbrains

import com.google.gson.JsonObject

class StructuredExceptionDecoder : ExceptionDecoder {
    override fun decode(event: LogEvent) = decodeObject(ExceptionDetails.`object`(event.element("exception")), event)
    companion object {
        @JvmStatic fun rootType(input: JsonObject?, event: LogEvent): String {
            if (input == null) return ""
            return ExceptionDetails.value(input, "class", "type", "exception_class").ifBlank {
                event.first("exception_class", "exception_type", "exceptionClass", "error_type", "error.type")
            }
        }
        @JvmStatic fun decodeObject(input: JsonObject?, event: LogEvent): List<ExceptionDetails.Part> {
            val chain = mutableListOf<ExceptionDetails.Part>()
            var node = input
            for (depth in 0 until 64) {
                val current = node ?: break
                val type = if (depth == 0) rootType(current, event) else ExceptionDetails.value(current, "class", "type", "exception_class")
                chain.add(ExceptionDetails.Part(type.ifBlank { "Exception" }, ExceptionDetails.value(current, "message"),
                    ExceptionDetails.value(current, "code"), ExceptionDetails.location(current), ExceptionDetails.trace(current)))
                node = ExceptionDetails.`object`(current.get("previous")) ?: ExceptionDetails.`object`(current.get("cause"))
            }
            return java.util.List.copyOf(chain)
        }
    }
}
