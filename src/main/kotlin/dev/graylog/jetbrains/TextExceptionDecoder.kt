package dev.graylog.jetbrains

/** Text stays intact in the inspector; normalized values feed source markers. */
class TextExceptionDecoder : ExceptionDecoder {
    override fun decode(event: LogEvent): List<ExceptionDetails.Part> {
        val text = event.first("stack_trace", "stacktrace", "exception_stack_trace", "full_message", "exception")
        if (text.isBlank()) return emptyList()
        val python = text.contains("Traceback (most recent call last)")
        val parts = mutableListOf<ExceptionDetails.Part>()
        var type = ""; var message = ""; var frames = mutableListOf<SourceLocation>()
        fun finish() {
            if (parts.size >= 64) return
            val ordered = if (python) frames.asReversed() else frames
            parts.add(ExceptionDetails.Part(type, message, "", ordered.firstOrNull(), ordered.drop(1)))
        }
        for (raw in text.split(Regex("""\R"""))) {
            val line = raw.trim()
            val header = HEADER.matchEntire(line) ?: if (python && type.isBlank() && frames.isNotEmpty() && raw == raw.trimStart()) PYTHON_TYPE.matchEntire(line) else null
            val ruby = RUBY.matchEntire(line)
            val panic = line.startsWith("panic:") || line.startsWith("thread '") && line.contains("panicked at")
            if (header != null || ruby != null || panic) {
                if (type.isNotBlank()) { finish(); frames = mutableListOf() }
                when {
                    panic -> { type = "panic"; message = line }
                    ruby != null -> { type = ruby.groupValues[2]; message = ruby.groupValues[1] }
                    header != null -> { type = header.groupValues[1]; message = header.groupValues[2] }
                }
                frames.addAll(SourceLocation.parseFrames(raw))
            } else if (python && line.startsWith("Traceback") && type.isNotBlank()) {
                finish(); type = ""; message = ""; frames = mutableListOf()
            } else frames.addAll(SourceLocation.parseFrames(raw))
        }
        if (type.isBlank()) type = event.first("exception_class", "exception_type", "exceptionClass", "error_type", "error.type")
        if (type.isNotBlank()) { if (message.isBlank()) message = event.text("message"); finish() }
        if (python) parts.reverse()
        return java.util.List.copyOf(parts)
    }
    companion object {
        private val HEADER = Regex("""^(?:Caused by:\s*|Suppressed:\s*|--->\s*)?([\\\w.$]+(?:Exception|Error|Fault)|Exception|Error)(?::\s*(.*))?$""")
        private val PYTHON_TYPE = Regex("""^([\w.]+)(?::\s*(.*))?$""")
        private val RUBY = Regex("""^.*?\.rb:\d+.*?: (.*) \(([\w:]+(?:Error|Exception))\)$""")
    }
}
