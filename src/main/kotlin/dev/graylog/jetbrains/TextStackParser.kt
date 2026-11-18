package dev.graylog.jetbrains

/** One runtime-text normalizer for top-level and schema-wrapped stacks. No LogEvent recursion. */
internal object TextStackParser {
    private val LINES = Regex("""\R""")
    private val INLINE_INNER = Regex("""\s*--->\s*(?=[\\\w.$]+(?::|$))""")
    private const val INNER_END = "--- End of inner exception stack trace ---"
    private const val PYTHON_START = "Traceback (most recent call last):"
    private val RUBY = Regex("""^.*?\.rb:\d+.*?: (.*) \(([\w:]+)\)$""")

    @JvmStatic fun parse(text: String, fallbackType: String, fallbackMessage: String): List<ExceptionDetails.Part> {
        if (text.isBlank()) return emptyList()
        if (text.contains(INNER_END) && text.contains("--->")) {
            val dotnet = dotnet(text, fallbackType)
            if (dotnet.isNotEmpty()) return java.util.List.copyOf(dotnet)
        }
        val lines = text.split(LINES)
        val python = lines.any { it.trim() == PYTHON_START }
        val parts = mutableListOf<ExceptionDetails.Part>()
        var type = ""; var message = ""; var frames = mutableListOf<SourceLocation>()
        fun finish() {
            if (parts.size >= 64) return
            val ordered = if (python) frames.asReversed() else frames
            parts.add(ExceptionDetails.Part(type, message, "", ordered.firstOrNull(), ordered.drop(1)))
        }
        for (raw in lines) {
            val line = raw.trim()
            val header = ExceptionHeaderParser.parse(line, fallbackType) ?: if (python && type.isBlank() && frames.isNotEmpty() && raw == raw.trimStart()) ExceptionHeaderParser.python(line) else null
            val ruby = RUBY.matchEntire(line)
            val panic = line.startsWith("panic:") || line.startsWith("thread '") && line.contains("panicked at")
            if (header?.nested == true && type.isBlank() && frames.isNotEmpty()) {
                if (fallbackType.isNotBlank()) { type = fallbackType; message = fallbackMessage }
                else frames = mutableListOf() // Unattributed parent frames must not become the cause's frames.
            }
            if (header != null || ruby != null || panic) {
                if (type.isNotBlank()) { finish(); frames = mutableListOf() }
                when {
                    panic -> { type = "panic"; message = line }
                    ruby != null -> { type = ruby.groupValues[2]; message = ruby.groupValues[1] }
                    header != null -> { type = header.type; message = header.message }
                }
                frames.addAll(SourceLocation.parseFrames(raw))
            } else if (python && line.startsWith("Traceback") && type.isNotBlank()) {
                finish(); type = ""; message = ""; frames = mutableListOf()
            } else frames.addAll(SourceLocation.parseFrames(raw))
        }
        if (type.isBlank()) type = fallbackType
        if (type.isNotBlank()) { if (message.isBlank()) message = fallbackMessage; finish() }
        if (python) parts.reverse()
        return java.util.List.copyOf(parts)
    }

    /** Explicit schema metadata wins; structured causes supersede duplicate printed causes. */
    @JvmStatic fun normalize(text: String, metadata: List<ExceptionDetails.Part>): List<ExceptionDetails.Part> {
        if (metadata.isEmpty() || text.isBlank()) return metadata
        val root = metadata.first()
        val parsed = parse(text, root.type(), root.message())
        if (parsed.isEmpty()) return metadata
        val first = parsed.first()
        val origin = root.origin() ?: first.origin()
        val frames = first.frames().ifEmpty { root.frames() }
        val trace = if (origin == null) frames else frames.filter { it != origin }
        val result = mutableListOf(ExceptionDetails.Part(root.type(), root.message().ifBlank { first.message() }, root.code(), origin, trace))
        result.addAll((if (metadata.size > 1) metadata else parsed).drop(1).take(63))
        return java.util.List.copyOf(result)
    }

    private fun dotnet(text: String, fallbackType: String): List<ExceptionDetails.Part> {
        val parts = mutableListOf<Draft>()
        val parents = ArrayDeque<Draft>()
        var current: Draft? = null
        for (raw in INLINE_INNER.replace(text, "\n---> ").split(LINES)) {
            val line = raw.trim()
            if (line == INNER_END) {
                if (parents.isNotEmpty()) current = parents.removeLast()
                continue
            }
            val header = ExceptionHeaderParser.parse(line, fallbackType)
            if (header != null) {
                if (parts.size >= 64) break
                if (line.startsWith("--->")) current?.let(parents::addLast) else parents.clear()
                current = Draft(header.type, header.message)
                parts.add(current)
            } else current?.frames?.addAll(SourceLocation.parseFrames(raw))
        }
        return parts.map { ExceptionDetails.Part(it.type, it.message, "", it.frames.firstOrNull(), it.frames.drop(1)) }
    }
    private class Draft(val type: String, val message: String) { val frames = mutableListOf<SourceLocation>() }
}
