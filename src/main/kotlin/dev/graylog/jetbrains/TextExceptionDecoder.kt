package dev.graylog.jetbrains

/** Top-level schema adapter; runtime syntax and ordering live in the shared Kotlin parser. */
class TextExceptionDecoder : ExceptionDecoder {
    override fun decode(event: LogEvent): List<ExceptionDetails.Part> {
        val text = event.first("stack_trace", "stacktrace", "exception_stack_trace", "full_message", "exception")
        val parts = TextStackParser.parse(text,
            event.first("exception_class", "exception_type", "exceptionClass", "error_type", "error.type"), event.text("message")).toMutableList()
        val file = event.text("exception_file")
        val line = event.text("exception_line").toIntOrNull()
        if (parts.isNotEmpty() && file.isNotBlank() && line != null && line > 0) {
            val origin = SourceLocation(file, line)
            val root = parts.first()
            parts[0] = ExceptionDetails.Part(root.type, root.message, root.code, origin, root.frames().filter { it != origin })
        }
        return java.util.List.copyOf(parts)
    }
}
