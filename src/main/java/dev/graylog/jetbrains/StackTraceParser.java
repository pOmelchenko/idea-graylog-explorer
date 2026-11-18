package dev.graylog.jetbrains;

import java.util.List;

/** Text frame syntax adapter. Source resolution and variable values are separate concerns. */
interface StackTraceParser { List<SourceLocation> frames(String line); }
