package dev.graylog.jetbrains

object ExceptionDecoders {
    sealed class Decoded(parts: List<ExceptionDetails.Part>) {
        private val snapshot = java.util.List.copyOf(parts)
        fun parts(): List<ExceptionDetails.Part> = snapshot
        abstract fun structured(): Boolean
        class Structured(parts: List<ExceptionDetails.Part>) : Decoded(parts) { override fun structured() = true }
        class Text(parts: List<ExceptionDetails.Part>) : Decoded(parts) { override fun structured() = false }
    }
    private val structured = StructuredExceptionDecoder()
    private val ecs = EcsExceptionDecoder()
    private val text = TextExceptionDecoder()
    @JvmStatic fun decode(event: LogEvent): Decoded {
        val parts = structured.decode(event).ifEmpty { ecs.decode(event) }
        return if (parts.isEmpty()) Decoded.Text(text.decode(event)) else Decoded.Structured(parts)
    }
}
