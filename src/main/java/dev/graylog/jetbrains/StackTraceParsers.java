package dev.graylog.jetbrains;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Ordered line parsers: specific runtime syntax first, then the portable path:line fallback. */
final class StackTraceParsers {
    private static final Pattern PYTHON = Pattern.compile("^\\s*File \\\"([^\\\"]+)\\\", line (\\d+)(?:,.*)?$");
    private static final Pattern DOTNET = Pattern.compile("^\\s*at .+? in (.+?):line (\\d+)\\s*$");
    private static final Pattern JVM = Pattern.compile("^\\s*at (?:[^\\s(]*/)?([\\w.$]+)\\.([^\\s(]+)\\(([^():]+\\.(?:java|kt|kts)):(\\d+)\\)\\s*$");
    private static final Pattern PATH = Pattern.compile("(?<![A-Za-z0-9.@+-])((?:[A-Za-z]:)?[/\\\\][^\\r\\n\\\"'<>]*?\\.(?:php|phtml|jsx?|tsx?|mjs|cjs|java|kt|kts|py|go|rb|cs|fs|rs|scala|swift|c|cpp|h)|(?:[\\w.@-]+[/\\\\])*[\\w.@-]+\\.(?:php|phtml|jsx?|tsx?|mjs|cjs|java|kt|kts|py|go|rb|cs|fs|rs|scala|swift|c|cpp|h))(?::|\\()(\\d+)(?::\\d+)?\\)?");
    private static final List<StackTraceParser> PARSERS = List.of(
            line -> match(PYTHON, line), line -> match(DOTNET, line), StackTraceParsers::jvm, StackTraceParsers::paths);
    private static final Pattern URI_PREFIX = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:[^\\s\"'<>\\[\\](){} ,]*$");
    private StackTraceParsers() { }
    static List<SourceLocation> frames(String text) {
        List<SourceLocation> result = new ArrayList<>();
        for (String line : text.split("\\R")) for (var parser : PARSERS) {
            var frames = parser.frames(line);
            if (!frames.isEmpty()) { result.addAll(frames); break; }
        }
        return List.copyOf(result);
    }
    private static List<SourceLocation> match(Pattern pattern, String line) {
        var m = pattern.matcher(line); return m.matches() ? location(m.group(1), m.group(2)) : List.of();
    }
    private static List<SourceLocation> jvm(String line) {
        var m = JVM.matcher(line); if (!m.matches()) return List.of();
        String owner = m.group(1); int dot = owner.lastIndexOf('.');
        // A package-qualified filename resolves relative to the configured source root, never by basename guessing.
        String path = (dot < 0 ? "" : owner.substring(0, dot + 1).replace('.', '/')) + m.group(3);
        return location(path, m.group(4));
    }
    private static List<SourceLocation> paths(String line) {
        List<SourceLocation> result = new ArrayList<>(); var m = PATH.matcher(line);
        while (m.find()) {
            String path = m.group(1).trim();
            // Inspect this match's URI token; URLs in arguments must not hide a local frame.
            var uri = URI_PREFIX.matcher(line).region(0, m.start(1));
            boolean fileUrl = uri.find();
            if (fileUrl) {
                if (!uri.group().equalsIgnoreCase("file:") || !path.startsWith("///")) continue;
                path = path.substring(2);
            }
            if (path.contains("://")) continue;
            if (!path.startsWith("/") && !path.startsWith("\\") && !path.matches("[A-Za-z]:.*")) {
                path = path.replaceFirst("^(?:at|from)\\s+", "");
            }
            if (fileUrl) {
                try { path = java.net.URLDecoder.decode(path.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8); }
                catch (IllegalArgumentException invalid) { continue; }
                if (path.matches("^/[A-Za-z]:.*")) path = path.substring(1);
            }
            result.addAll(location(path, m.group(2)));
        }
        return List.copyOf(result);
    }
    private static List<SourceLocation> location(String path, String number) {
        if (path.startsWith("<") || path.indexOf('\0') >= 0) return List.of();
        try { int line = Integer.parseInt(number); return line > 0 ? List.of(new SourceLocation(path, line)) : List.of(); }
        catch (NumberFormatException invalid) { return List.of(); }
    }
}
