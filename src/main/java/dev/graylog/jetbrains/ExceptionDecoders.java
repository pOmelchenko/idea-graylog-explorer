package dev.graylog.jetbrains;

import java.util.List;

final class ExceptionDecoders {
    record Decoded(List<ExceptionDetails.Part> parts, boolean structured) { Decoded { parts = List.copyOf(parts); } }
    private static final ExceptionDecoder STRUCTURED = new StructuredExceptionDecoder();
    private static final ExceptionDecoder ECS = new EcsExceptionDecoder();
    private static final ExceptionDecoder TEXT = new TextExceptionDecoder();
    private ExceptionDecoders() { }
    static Decoded decode(LogEvent event) {
        var structured = STRUCTURED.decode(event);
        if (structured.isEmpty()) structured = ECS.decode(event);
        return structured.isEmpty() ? new Decoded(TEXT.decode(event), false) : new Decoded(structured, true);
    }
}
