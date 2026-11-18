package dev.graylog.jetbrains

/** Source evidence belongs to the log record; exception frames never prove a logger call. */
object LogOriginMetadata {
    @JvmRecord data class Reported(val location: SourceLocation, val fields: String, val explicit: Boolean)
    @JvmRecord data class Symbol(val owner: String, val method: String, val field: String)
    @JvmRecord data class Analysis(val reported: List<Reported>, val symbols: List<Symbol>, val issues: List<String>, val messageQuery: String)
    private val PHP_METHOD = Regex("""^\\?([A-Za-z_][\w]*(?:\\[A-Za-z_][\w]*)*)::([A-Za-z_][\w]*)$""")
    private val PAIRS = listOf("log_file" to "log_line", "log.origin.file.name" to "log.origin.file.line",
        "code.file.path" to "code.line.number", "code.filepath" to "code.lineno", "file" to "line")

    @JvmStatic fun read(event: LogEvent): Analysis = read(event, LogNavigationOptions())
    @JvmStatic fun read(event: LogEvent, options: LogNavigationOptions): Analysis {
        val reported = mutableListOf<Reported>(); val issues = mutableListOf<String>()
        for ((fileKey, lineKey) in PAIRS) {
            if (fileKey == "file" && options.genericSourceRole == "ignore") continue
            if (fileKey != "file" && fileKey != "log_file" && !options.standardFields) continue
            val rawFile = if (mapped(event, fileKey)) event.element(fileKey) else null
            val file = rawFile?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty().trim()
            val line = value(event, lineKey).trim()
            if (file.isBlank() && line.isBlank()) continue
            val fields = "${event.fieldName(fileKey)} + ${event.fieldName(lineKey)}"
            val number = line.toIntOrNull()
            if (file.isBlank() || file.any { it == '\n' || it == '\r' || it == '\u0000' } || number == null || number <= 0) {
                issues += "$fields: provide a source path and a positive, one-based integer line."
                continue
            }
            reported += Reported(SourceLocation(file, number), fields, fileKey != "file" || options.genericSourceRole == "logger")
        }
        val symbolFields = listOf("log_function", "log_category") + if (options.standardFields)
            listOf("log.origin.function", "code.function.name", "code.function", "category", "log.logger", "logger") else emptyList()
        val symbols = symbolFields.mapNotNull { key -> PHP_METHOD.matchEntire(value(event, key).trim())?.let {
                Symbol(it.groupValues[1], it.groupValues[2], event.fieldName(key))
            } }.toMutableList()
        PHP_METHOD.matchEntire(value(event, "log_class").trim() + "::" + value(event, "log_function").trim())?.let {
            symbols += Symbol(it.groupValues[1], it.groupValues[2], event.fieldName("log_class") + " + " + event.fieldName("log_function"))
        }
        // A literal prefix is only a search aid: it may point to message construction or a translation.
        val message = event.text("message").lineSequence().firstOrNull().orEmpty().trim().take(160)
        val prefix = message.indexOf(": ").takeIf { it >= 12 }?.let { message.take(it + 1) } ?: message
        return Analysis(java.util.List.copyOf(reported.distinctBy { it.location }),
            java.util.List.copyOf(symbols.distinctBy { it.owner.lowercase() to it.method.lowercase() }), java.util.List.copyOf(issues), prefix.takeIf { it.length >= 8 }.orEmpty())
    }
    private fun mapped(event: LogEvent, key: String): Boolean = !key.startsWith("log_") || event.fieldName(key) == event.format().configuredName(key)
    private fun value(event: LogEvent, key: String): String = if (mapped(event, key)) event.text(key) else ""
}
