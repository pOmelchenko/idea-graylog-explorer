package dev.graylog.jetbrains

/** Runtime grammar, independent of Graylog schemas. Structural headers allow custom type names. */
internal object ExceptionHeaderParser {
    data class Header(val type: String, val message: String, val nested: Boolean = false)
    private const val UNCAUGHT = "Exception in thread \""
    private val CAUSES = listOf("Caused by:", "Suppressed:", "--->")

    fun parse(line: String, expectedType: String = ""): Header? {
        if (line.startsWith(UNCAUGHT)) {
            var quote = line.indexOf('"', UNCAUGHT.length)
            while (quote >= 0) {
                if (quote + 1 < line.length && line[quote + 1].isWhitespace())
                    typed(line.substring(quote + 1).trimStart())?.let { return it }
                quote = line.indexOf('"', quote + 1)
            }
            return null
        }
        for (prefix in CAUSES) if (line.startsWith(prefix)) return typed(line.removePrefix(prefix).trimStart())?.copy(nested = true)
        val result = typed(line) ?: return null
        return result.takeIf { it.type == expectedType || it.type.endsWith("Exception") || it.type.endsWith("Error") || it.type.endsWith("Fault") }
    }

    fun python(line: String): Header? = typed(line)

    private fun typed(line: String): Header? {
        val colon = line.indexOf(':')
        val type = (if (colon < 0) line else line.substring(0, colon)).trim()
        if (!type.removePrefix("\\").split('.', '\\').all { part -> part.isNotEmpty() &&
                Character.isJavaIdentifierStart(part.first()) && part.drop(1).all { Character.isJavaIdentifierPart(it) } }) return null
        return Header(type, if (colon < 0) "" else line.substring(colon + 1).trimStart())
    }
}
