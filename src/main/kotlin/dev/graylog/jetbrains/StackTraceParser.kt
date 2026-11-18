package dev.graylog.jetbrains

/** One text-frame syntax adapter, independent of IDE and variable-value analysis. */
fun interface StackTraceParser { fun frames(line: String): List<SourceLocation> }
