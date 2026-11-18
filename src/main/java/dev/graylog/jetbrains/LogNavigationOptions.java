package dev.graylog.jetbrains;

import java.util.Arrays;
import java.util.List;

/** Project/connection policy; this local navigation behavior does not change a Graylog query. */
public final class LogNavigationOptions {
    public boolean standardFields = true;
    public boolean categoryLookup = true;
    public boolean knownLoggers = true;
    public String genericSourceRole = "candidate";
    public String loggerClasses = "";
    public String loggerMethods = "log,debug,trace,info,notice,warning,warn,error,critical,alert,emergency";
    public LogNavigationOptions copy() { return SettingsValues.copy(this, LogNavigationOptions.class); }
    public static List<String> names(String value) { return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(); }
    public void validate() {
        if (!List.of("ignore", "candidate", "logger").contains(genericSourceRole)) throw new IllegalArgumentException("Choose a role for generic source coordinates.");
        if (loggerClasses.length() > 2048 || names(loggerClasses).size() > 32 || names(loggerClasses).stream().anyMatch(s -> !s.matches("[\\\\A-Za-z_][\\\\A-Za-z0-9_]*")))
            throw new IllegalArgumentException("Logger classes must be comma-separated PHP fully qualified class names (at most 32).");
        if (loggerMethods.length() > 1024 || names(loggerMethods).size() > 64 || names(loggerMethods).stream().anyMatch(s -> !s.matches("[A-Za-z_][A-Za-z0-9_]*")))
            throw new IllegalArgumentException("Logger methods must be comma-separated method names (at most 64).");
    }
}
