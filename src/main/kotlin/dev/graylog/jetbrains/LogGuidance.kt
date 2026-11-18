package dev.graylog.jetbrains

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.file.Path

/** Event-specific guidance, with synthetic examples rather than copying potentially sensitive log values. */
object LogGuidance {
    @JvmStatic fun render(event: LogEvent, connection: Connection?, root: Path?): String = buildString {
        val options = connection?.navigation ?: LogNavigationOptions()
        val metadata = LogOriginMetadata.read(event, options)
        appendLine("Navigation for this event")
        if (metadata.reported.isEmpty()) appendLine("No valid log source coordinates were found.")
        metadata.reported.forEach { entry ->
            appendLine("${entry.fields}: ${entry.location}")
            appendLine(if (entry.explicit) "  Reported log origin. Find log statement checks the local file and line."
                else "  Source role is unspecified: this may be a throw site or logger wrapper. Choose a candidate to inspect it.")
            if (connection != null && entry.location.resolve(connection, root) == null)
                appendLine("  This path is outside the configured local roots. Open Source path mappings.")
        }
        metadata.issues.forEach { appendLine(it) }
        metadata.symbols.forEach { appendLine("${it.field}: ${it.owner}::${it.method} — PHP method scope, not an exact call site.") }
        if (metadata.reported.none { it.explicit }) {
            appendLine()
            appendLine("For direct navigation, first map your existing source fields under Project settings → Log statement fields; a new log format is not required.")
            appendLine("Only if caller coordinates are absent, capture the application's logger-call path and one-based line in a formatter/processor. The configured pair is ${event.format().logFileField} + ${event.format().logLineField}.")
            if (options.standardFields) appendLine("ECS log.origin.file.name + log.origin.file.line and OpenTelemetry code.file.path + code.line.number are also recognized.")
        }
        appendLine()
        appendLine(if (options.categoryLookup) "Method lookup is enabled. PHP can find logger calls inside a Class::method category using the configured logger classes and method names. All method-based results require choosing a candidate."
            else "Method lookup is disabled in this profile; only reported coordinates are used.")
        appendLine("Generic file/line policy: ${options.genericSourceRole}. Standard source fields: ${if (options.standardFields) "enabled" else "disabled"}. Change behavior per connection in Project settings.")
        appendLine("Search message in project opens the IDE's Find in Files. Text matches are candidates for inspection; they can be message construction or translation rather than logging.")
        appendLine()
        appendLine("Keep log origins separate from exception origins. Record exceptions (including previous causes) in an exception object/stack field; INFO/DEBUG/WARN do not need a stack to navigate by source coordinates.")
        if (event.first("request_id", "trace_id", "correlation_id").isBlank())
            appendLine("No request/trace ID was found. Add one to correlate related events and enable Follow request, or map an existing field in Project settings.")
        appendLine("Use paths from the deployed application and map them to the local checkout. A basename alone does not identify a file. Record a build/revision separately and use the matching checkout; the plugin does not verify deployed revisions.")
        appendLine("For custom PHP loggers, configure their resolved class and method names in Log navigation behavior. For caller-capture wrappers, skip the wrapper's frames; a full stack is unnecessary.")
        appendLine()
        appendLine("Copy example produces synthetic Graylog search-result fields, not a GELF wire payload. GELF custom fields use a leading underscore on the wire; confirm the received names in Fields. Dotted names and nested objects are both supported; configure renamed/flattened fields.")
        appendLine("References: https://www.elastic.co/docs/reference/ecs/ecs-log")
        appendLine("https://opentelemetry.io/docs/specs/semconv/registry/attributes/code/")
        appendLine("https://go2docs.graylog.org/current/getting_in_log_data/gelf_format.html")
    }.trimEnd()

    @JvmStatic fun example(format: LogFormat): String {
        val fields = JsonObject().apply {
            addProperty("message", "Import completed")
            addProperty(format.logFileField, "<deployed-source-path>")
            addProperty(format.logLineField, 42)
            addProperty(format.logFunctionField, "<fully-qualified-function-or-method>")
            addProperty(format.requestIdField, "<request-id>")
            addProperty("service_version", "<deployed-revision>")
        }
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(fields)
    }
}
