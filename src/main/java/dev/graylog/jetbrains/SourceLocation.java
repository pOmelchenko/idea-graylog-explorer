package dev.graylog.jetbrains;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SourceLocation(String path, int line) {
    private static final Pattern TRACE = Pattern.compile(
            "((?:[A-Za-z]:)?[/\\\\][^\\r\\n\"'<>]*?\\.(?:php|phtml|js|ts|java|kt|py|go|rb)|(?:[\\w.-]+[/\\\\])+[\\w.-]+\\.(?:php|phtml|js|ts|java|kt|py|go|rb))(?::|\\()(\\d+)\\)?");

    public static List<SourceLocation> find(LogEvent event) {
        LinkedHashSet<SourceLocation> result = new LinkedHashSet<>();
        String file = event.first("file", "file_name", "filename", "exception_file");
        String line = event.first("line", "line_number", "exception_line");
        try {
            if (!file.isBlank() && Integer.parseInt(line) > 0) result.add(new SourceLocation(file, Integer.parseInt(line)));
        } catch (NumberFormatException ignored) { }
        result.addAll(parseTrace(event.stackTrace()));
        return List.copyOf(result);
    }

    public static List<SourceLocation> parseTrace(String trace) {
        return List.copyOf(new LinkedHashSet<>(parseFrames(trace)));
    }

    static List<SourceLocation> parseFrames(String trace) {
        java.util.ArrayList<SourceLocation> result = new java.util.ArrayList<>();
        Matcher matcher = TRACE.matcher(trace);
        while (matcher.find()) {
            try {
                int number = Integer.parseInt(matcher.group(2));
                if (number > 0) result.add(new SourceLocation(matcher.group(1).trim(), number));
            } catch (NumberFormatException ignored) { }
        }
        return List.copyOf(result);
    }

    public Path resolve(Connection connection, Path projectRoot) {
        String source = path.replace('\\', '/');
        if (!connection.mappings.isEmpty()) {
            PathMapping best = null; int longest = -1;
            for (PathMapping mapping : connection.mappings) {
                String prefix = mapping.remoteRoot.replace('\\', '/').replaceAll("/+$", "");
                boolean root = mapping.remoteRoot.equals("/");
                if ((root && source.startsWith("/")) || (!prefix.isBlank() && (source.equals(prefix) || source.startsWith(prefix + "/")))) {
                    if (prefix.length() > longest) { best = mapping; longest = prefix.length(); }
                }
            }
            if (best != null) {
                try {
                    Path local = best.localRoot.isBlank() ? projectRoot : Path.of(best.localRoot);
                    if (local == null) return null;
                    local = local.toAbsolutePath().normalize();
                    String relative = source.substring(longest).replaceFirst("^/", "");
                    Path mapped = local.resolve(relative).normalize();
                    return mapped.startsWith(local) ? mapped : null;
                } catch (InvalidPathException e) { return null; }
            }
        }
        String remote = connection.remoteRoot.replace('\\', '/').replaceAll("/+$", "");
        try {
            Path local = connection.localRoot.isBlank() ? projectRoot : Path.of(connection.localRoot);
            if (local == null) return null;
            local = local.toAbsolutePath().normalize();
            if (!remote.isBlank() && (source.equals(remote) || source.startsWith(remote + "/"))) {
                Path mapped = local.resolve(source.substring(remote.length()).replaceFirst("^/", "")).normalize();
                return mapped.startsWith(local) ? mapped : null;
            }
            Path original = Path.of(source);
            if (!original.isAbsolute()) {
                Path relative = local.resolve(original).normalize();
                return relative.startsWith(local) ? relative : null;
            }
            Path absolute = original.normalize();
            Path project = projectRoot == null ? local : projectRoot.toAbsolutePath().normalize();
            if (absolute.startsWith(project) || absolute.startsWith(local)) return absolute;
            for (PathMapping mapping : connection.mappings) {
                if (!mapping.localRoot.isBlank() && absolute.startsWith(Path.of(mapping.localRoot).toAbsolutePath().normalize())) return absolute;
            }
            return null;
        } catch (InvalidPathException e) { return null; }
    }

    @Override public String toString() { return path + ":" + line; }
}
