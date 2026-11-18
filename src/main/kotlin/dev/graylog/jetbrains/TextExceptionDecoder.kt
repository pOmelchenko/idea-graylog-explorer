package dev.graylog.jetbrains

/** Text stays intact in the inspector; normalized values feed source markers. */
class TextExceptionDecoder : ExceptionDecoder {
    override fun decode(event: LogEvent): List<ExceptionDetails.Part> {
        val text = event.first("stack_trace", "stacktrace", "exception_stack_trace", "full_message", "exception")
        if (text.isBlank()) return emptyList()
        if (text.contains(INNER_END) && text.contains("--->")) {
            val dotnet = dotnet(text)
            if (dotnet.isNotEmpty()) return withExplicitOrigin(dotnet, event)
        }
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
        return withExplicitOrigin(parts, event)
    }

    /** .NET prints inner frames first, then resumes the parent's frames after each boundary. */
    private fun dotnet(text: String): MutableList<ExceptionDetails.Part> {
        val parts = mutableListOf<DotnetPart>()
        val parents = ArrayDeque<DotnetPart>()
        var current: DotnetPart? = null
        for (raw in INLINE_INNER.replace(text, "\n---> ").split(Regex("""\R"""))) {
            val line = raw.trim()
            if (line == INNER_END) {
                if (parents.isNotEmpty()) current = parents.removeLast()
                continue
            }
            val header = HEADER.matchEntire(line)
            if (header != null) {
                if (parts.size >= 64) break
                if (line.startsWith("--->")) current?.let(parents::addLast) else parents.clear()
                current = DotnetPart(header.groupValues[1], header.groupValues[2])
                parts.add(current)
            } else current?.frames?.addAll(SourceLocation.parseFrames(raw))
        }
        return parts.mapTo(mutableListOf()) {
            ExceptionDetails.Part(it.type, it.message, "", it.frames.firstOrNull(), it.frames.drop(1))
        }
    }

    private class DotnetPart(val type: String, val message: String) {
        val frames = mutableListOf<SourceLocation>()
    }

    private fun withExplicitOrigin(parts: MutableList<ExceptionDetails.Part>, event: LogEvent): List<ExceptionDetails.Part> {
        // Explicit exception metadata describes the root throw; generic file/line may be a logger call.
        val file = event.text("exception_file")
        val line = event.text("exception_line").toIntOrNull()
        if (parts.isNotEmpty() && file.isNotBlank() && line != null && line > 0) {
            val origin = SourceLocation(file, line)
            val root = parts.first()
            parts[0] = ExceptionDetails.Part(root.type, root.message, root.code, origin, root.frames().filter { it != origin })
        }
        return java.util.List.copyOf(parts)
    }

    companion object {
        private const val TYPE = """(?:[\\\w.$]+(?:Exception|Error|Fault)|Exception|Error)"""
        private val HEADER = Regex("""^(?:Caused by:\s*|Suppressed:\s*|--->\s*)?($TYPE)(?::\s*(.*))?$""")
        private val INLINE_INNER = Regex("""\s*--->\s*(?=$TYPE(?::|$))""")
        private const val INNER_END = "--- End of inner exception stack trace ---"
        private val PYTHON_TYPE = Regex("""^([\w.]+)(?::\s*(.*))?$""")
        private val RUBY = Regex("""^.*?\.rb:\d+.*?: (.*) \(([\w:]+(?:Error|Exception))\)$""")
    }
}
