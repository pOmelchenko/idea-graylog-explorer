package dev.graylog.jetbrains

import com.google.gson.*

/** Immutable decoded exception values; Java accessors retain the parser boundary's existing API. */
object ExceptionDetails {
    private val json = GsonBuilder().setStrictness(Strictness.STRICT).create()
    data class Part(val type: String, val message: String, val code: String, val origin: SourceLocation?, private var traceSnapshot: List<SourceLocation>) {
        init { traceSnapshot = java.util.List.copyOf(traceSnapshot) }
        val trace: List<SourceLocation> get() = traceSnapshot
        fun type() = type
        fun message() = message
        fun code() = code
        fun origin() = origin
        fun trace() = trace
        fun frames(): List<SourceLocation> = java.util.List.copyOf(linkedSetOf<SourceLocation>().apply {
            origin?.let(::add); addAll(trace)
        })
    }
    @JvmStatic fun chain(event: LogEvent): List<Part> = event.exceptions()
    @JvmStatic fun `object`(input: JsonElement?): JsonObject? {
        var element = input
        repeat(2) {
            val current = element ?: return null
            if (current.isJsonNull) return null
            if (current.isJsonObject) return current.asJsonObject
            if (!current.isJsonPrimitive || !current.asJsonPrimitive.isString) return null
            val text = current.asString.trim()
            if (!text.startsWith("{")) return null
            element = try { json.fromJson(text, JsonElement::class.java) } catch (_: RuntimeException) { return null }
        }
        return element?.takeIf { it.isJsonObject }?.asJsonObject
    }
    @JvmStatic fun render(chain: List<Part>): String = buildString {
        chain.forEachIndexed { index, part ->
            if (index > 0) append("\nCaused by: ")
            append(part.type)
            if (part.message.isNotBlank() && part.message != part.type) append(": ").append(part.message)
            if (part.code.isNotBlank() && part.code != "0") append(" (code ").append(part.code).append(')')
            append('\n'); part.origin?.let { append("  at ").append(it).append('\n') }
            part.trace.forEachIndexed { frame, location -> append("  #").append(frame).append(' ').append(location).append('\n') }
        }
    }.trimEnd()
    @JvmStatic fun location(value: JsonObject): SourceLocation? {
        val file = value(value, "file", "filename")
        val combined = SourceLocation.parseTrace(file)
        val line = value(value, "line", "line_number").toIntOrNull()
        if (file.isNotBlank() && line != null && line > 0) return SourceLocation(combined.firstOrNull()?.path() ?: file, line)
        return combined.firstOrNull()
    }
    @JvmStatic fun trace(value: JsonObject): List<SourceLocation> {
        val frames = mutableListOf<SourceLocation>()
        for (key in listOf("trace", "stack_trace", "stacktrace")) {
            val trace = value.get(key) ?: continue
            if (trace.isJsonArray) for (frame in trace.asJsonArray) {
                if (frame.isJsonObject) location(frame.asJsonObject)?.let(frames::add)
                else if (frame.isJsonPrimitive && frame.asJsonPrimitive.isString) frames.addAll(SourceLocation.parseFrames(frame.asString))
            } else if (trace.isJsonPrimitive && trace.asJsonPrimitive.isString) frames.addAll(SourceLocation.parseFrames(trace.asString))
            if (frames.isNotEmpty()) break
        }
        return java.util.List.copyOf(frames)
    }
    @JvmStatic fun value(value: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val candidate = value.get(key) ?: continue
            if (candidate.isJsonPrimitive && candidate.asString.isNotBlank()) return candidate.asString
        }
        return ""
    }
}
