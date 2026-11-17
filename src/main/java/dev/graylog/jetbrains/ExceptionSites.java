package dev.graylog.jetbrains;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;

/** Maps each member of an exception chain to its first resolvable source location. */
public final class ExceptionSites {
    public record Location(Path file, int line) { }
    public record Detail(String type, String message) { }
    public record Site(Location location, List<String> types, List<LogEvent> events, long count) {
        public Site(Location location, List<String> types, List<LogEvent> events) { this(location, types, events, events.size()); }
    }
    public static final int SAMPLES_PER_SITE = 20;

    /** Consumes a document stream once; retains counts and bounded examples, not the entire period. */
    public static final class Accumulator {
        private final Connection connection;
        private final Path projectRoot;
        private final int sampleLimit;
        private final Map<Location, Summary> sites = new LinkedHashMap<>();
        private long exceptionCount;

        public Accumulator(Connection connection, Path projectRoot) { this(connection, projectRoot, SAMPLES_PER_SITE); }
        public Accumulator(Connection connection, Path projectRoot, int sampleLimit) {
            if (sampleLimit < 1 || sampleLimit > 500) throw new IllegalArgumentException("Examples per source location must be 1–500.");
            this.connection = connection.copy(); this.projectRoot = projectRoot; this.sampleLimit = sampleLimit;
        }
        public void accept(LogEvent event) {
            String name = exceptionName(event);
            if (name.isBlank()) return;
            exceptionCount++;
            observations(event, connection, projectRoot).forEach((location, details) -> {
                Summary summary = sites.computeIfAbsent(location, ignored -> new Summary());
                summary.count++; details.forEach(detail -> summary.types.add(detail.type()));
                if (summary.examples.size() < sampleLimit) summary.examples.add(event);
            });
        }
        public long exceptionCount() { return exceptionCount; }
        public List<Site> finish() {
            return sites.entrySet().stream().map(entry -> new Site(entry.getKey(), List.copyOf(entry.getValue().types),
                            List.copyOf(entry.getValue().examples), entry.getValue().count))
                    .sorted(Comparator.comparing((Site site) -> site.location.file.toString()).thenComparingInt(site -> site.location.line)).toList();
        }
        private static final class Summary {
            final LinkedHashSet<String> types = new LinkedHashSet<>();
            final List<LogEvent> examples = new ArrayList<>();
            long count;
        }
    }
    private static final Pattern TYPE = Pattern.compile("(?<![\\w\\\\.])([\\\\A-Za-z_$][\\w\\\\.$]*(?:Exception|Error)|Exception|Error)(?![\\w\\\\.])");

    private ExceptionSites() { }

    public static Location location(LogEvent event, Connection connection, Path projectRoot) {
        return locations(event, connection, projectRoot).stream().findFirst().orElse(null);
    }

    public static List<Location> locations(LogEvent event, Connection connection, Path projectRoot) {
        return List.copyOf(observations(event, connection, projectRoot).keySet());
    }

    public static List<Detail> detailsAt(LogEvent event, Connection connection, Path projectRoot, Location location) {
        return List.copyOf(observations(event, connection, projectRoot).getOrDefault(location, new LinkedHashSet<>()));
    }

    private static Map<Location, LinkedHashSet<Detail>> observations(LogEvent event, Connection connection, Path projectRoot) {
        Map<Location, LinkedHashSet<Detail>> result = new LinkedHashMap<>();
        String name = exceptionName(event);
        if (name.isBlank()) return result;
        var chain = ExceptionDetails.chain(event);
        if (chain.isEmpty()) addObservation(result, new Detail(name, event.text("message")), exceptionFrames(event), connection, projectRoot);
        else for (int i = 0; i < chain.size(); i++) {
            ExceptionDetails.Part part = chain.get(i);
            List<SourceLocation> frames = part.frames();
            if (i == 0 && frames.isEmpty()) {
                LinkedHashSet<SourceLocation> fallback = new LinkedHashSet<>();
                addLocation(fallback, event.text("exception_file"), event.text("exception_line"));
                addLocation(fallback, event.text("file"), event.text("line"));
                frames = List.copyOf(fallback);
            }
            addObservation(result, new Detail(part.type(), part.message()), frames, connection, projectRoot);
        }
        return result;
    }
    private static void addObservation(Map<Location, LinkedHashSet<Detail>> result, Detail detail,
                                       List<SourceLocation> frames, Connection connection, Path projectRoot) {
        for (SourceLocation frame : frames) {
            Path file = frame.resolve(connection, projectRoot);
            if (file == null) continue;
            result.computeIfAbsent(new Location(file, frame.line()), ignored -> new LinkedHashSet<>()).add(detail);
            break;
        }
    }

    public static List<Site> build(List<LogEvent> events, Connection connection, Path projectRoot) {
        Map<Location, Builder> sites = new LinkedHashMap<>();
        for (LogEvent event : events) {
            observations(event, connection, projectRoot).forEach((location, details) -> {
                Builder builder = sites.computeIfAbsent(location, ignored -> new Builder());
                details.forEach(detail -> builder.types.add(detail.type())); builder.events.putIfAbsent(event.identity(), event);
            });
        }
        return sites.entrySet().stream().map(entry -> new Site(entry.getKey(), List.copyOf(entry.getValue().types),
                        List.copyOf(entry.getValue().events.values())))
                .sorted(Comparator.comparing((Site site) -> site.location.file.toString()).thenComparingInt(site -> site.location.line))
                .toList();
    }

    public static String exceptionName(LogEvent event) {
        String explicit = event.first("exception_class", "exception_type", "exceptionClass", "error_type", "error.type");
        var chain = ExceptionDetails.chain(event);
        if (explicit.isBlank() && !chain.isEmpty()) explicit = chain.getFirst().type();
        if (explicit.isBlank() && TYPE.matcher(event.text("ctxt_category")).matches()) explicit = event.text("ctxt_category");
        if (!explicit.isBlank()) return explicit;
        String exception = event.text("exception");
        Matcher type = TYPE.matcher(exception + "\n" + event.stackTrace());
        if (type.find() && (!exception.isBlank() || !SourceLocation.parseTrace(event.stackTrace()).isEmpty()
                || !event.first("stack_trace", "stacktrace", "exception_stack_trace").isBlank())) return type.group(1);
        if (!exception.isBlank() || (event.level().equals("ERROR") &&
                !event.first("stack_trace", "stacktrace", "exception_stack_trace").isBlank())) return "Exception";
        return "";
    }

    static List<SourceLocation> exceptionFrames(LogEvent event) {
        LinkedHashSet<SourceLocation> frames = new LinkedHashSet<>();
        var chain = ExceptionDetails.chain(event);
        for (ExceptionDetails.Part part : chain) frames.addAll(part.frames());
        if (!frames.isEmpty()) return List.copyOf(frames);
        addLocation(frames, event.text("exception_file"), event.text("exception_line"));
        // A generic file/line often describes the logger call, so prefer the exception's own trace.
        frames.addAll(SourceLocation.parseTrace(event.stackTrace()));
        if (frames.isEmpty()) frames.addAll(SourceLocation.find(event));
        return List.copyOf(frames);
    }

    private static void addLocation(LinkedHashSet<SourceLocation> frames, String file, String line) {
        try {
            int number = Integer.parseInt(line);
            if (!file.isBlank() && number > 0) frames.add(new SourceLocation(file, number));
        } catch (NumberFormatException ignored) { }
    }
    private static final class Builder {
        final LinkedHashSet<String> types = new LinkedHashSet<>();
        final LinkedHashMap<String, LogEvent> events = new LinkedHashMap<>();
    }
}
