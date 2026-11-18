package dev.graylog.jetbrains

/** Specific runtime syntaxes precede the portable file:line parser. */
object StackTraceParsers {
    private val PYTHON = Regex("""^\s*File "([^"]+)", line (\d+)(?:,.*)?$""")
    private val DOTNET = Regex("""^\s*at .+? in (.+?):line (\d+)\s*$""")
    private val JVM = Regex("""^\s*at (?:[^\s(]*/)?([\w.$]+)\.([^\s(]+)\(([^():]+\.(?:java|kt|kts)):(\d+)\)\s*$""")
    private val PATH = Regex("""(?<![A-Za-z0-9.@+-])((?:[A-Za-z]:)?[/\\][^\r\n"'<>]*?\.(?:php|phtml|jsx?|tsx?|mjs|cjs|java|kt|kts|py|go|rb|cs|fs|rs|scala|swift|c|cpp|h)|(?:[\w.@-]+[/\\])*[\w.@-]+\.(?:php|phtml|jsx?|tsx?|mjs|cjs|java|kt|kts|py|go|rb|cs|fs|rs|scala|swift|c|cpp|h))(?::|\()(\d+)(?::\d+)?\)?""")
    private val URI_PREFIX = Regex("""[A-Za-z][A-Za-z0-9+.-]*:[^\s"'<>\[\](){} ,]*$""")
    private val parsers = listOf(StackTraceParser { match(PYTHON, it) }, StackTraceParser { match(DOTNET, it) }, StackTraceParser(::jvm), StackTraceParser(::paths))
    @JvmStatic fun frames(text: String): List<SourceLocation> {
        val result = mutableListOf<SourceLocation>()
        for (line in text.split(Regex("""\R"""))) for (parser in parsers) {
            val frames = parser.frames(line)
            if (frames.isNotEmpty()) { result.addAll(frames); break }
        }
        return java.util.List.copyOf(result)
    }
    private fun match(pattern: Regex, line: String): List<SourceLocation> = pattern.matchEntire(line)?.let { location(it.groupValues[1], it.groupValues[2]) } ?: emptyList()
    private fun jvm(line: String): List<SourceLocation> {
        val match = JVM.matchEntire(line) ?: return emptyList()
        val owner = match.groupValues[1]; val dot = owner.lastIndexOf('.')
        val path = (if (dot < 0) "" else owner.substring(0, dot + 1).replace('.', '/')) + match.groupValues[3]
        return location(path, match.groupValues[4])
    }
    private fun paths(line: String): List<SourceLocation> {
        val result = mutableListOf<SourceLocation>()
        for (match in PATH.findAll(line)) {
            var path = match.groupValues[1].trim()
            // Filter the matched URI token, not URLs elsewhere in the stack line.
            val uri = URI_PREFIX.find(line.substring(0, match.range.first))
            val fileUrl = uri != null
            if (uri != null) {
                if (!uri.value.equals("file:", ignoreCase = true) || !path.startsWith("///")) continue
                path = path.substring(2)
            }
            if (path.contains("://")) continue
            if (fileUrl) {
                path = try { java.net.URLDecoder.decode(path.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8) } catch (_: IllegalArgumentException) { continue }
                if (path.matches(Regex("""^/[A-Za-z]:.*"""))) path = path.substring(1)
            }
            result.addAll(location(path, match.groupValues[2]))
        }
        return java.util.List.copyOf(result)
    }
    private fun location(path: String, number: String): List<SourceLocation> {
        if (path.startsWith("<") || path.indexOf('\u0000') >= 0) return emptyList()
        val line = number.toIntOrNull() ?: return emptyList()
        return if (line > 0) listOf(SourceLocation(path, line)) else emptyList()
    }
}
