package dev.graylog.jetbrains

/** A log-schema adapter; source syntax and language PSI are separate boundaries. */
fun interface ExceptionDecoder { fun decode(event: LogEvent): List<ExceptionDetails.Part> }
