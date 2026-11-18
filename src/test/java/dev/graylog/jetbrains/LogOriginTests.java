package dev.graylog.jetbrains;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Origin semantics and behavior profiles are independent of PHP PSI and Graylog transport. */
final class LogOriginTests {
    private static int assertions;
    static int run() {
        var event = event("{\"message\":\"Error processing items: bad value\",\"ctxt_category\":\"App\\\\Worker::run\"}");
        var metadata = LogOriginMetadata.read(event);
        eq(List.of(), metadata.reported(), "category does not invent a source location");
        eq("App\\Worker", metadata.symbols().getFirst().owner(), "PHP category owner");
        eq("run", metadata.symbols().getFirst().method(), "PHP category method");
        eq("Acme\\Worker", LogOriginMetadata.read(event("{\"log_class\":\"Acme\\\\Worker\",\"log_function\":\"run\"}")).symbols().getFirst().owner(), "separate class and method fields");
        eq("Error processing items:", metadata.messageQuery(), "prefix is a text-search aid");
        eq(List.of(), LogOriginMetadata.read(event("{\"exception_file\":\"/app/Throw.php\",\"exception_line\":3,\"stack_trace\":\"#0 /app/Caller.php(20): run()\"}")).reported(), "exception metadata and stacks are not logger origins");
        for (String json : List.of("{\"log_file\":\"/app/Worker.php\",\"log_line\":42}",
                "{\"log.origin.file.name\":\"/app/Worker.php\",\"log.origin.file.line\":42}",
                "{\"log\":{\"origin\":{\"file\":{\"name\":\"/app/Worker.php\",\"line\":42}}}}",
                "{\"code.file.path\":\"/app/Worker.php\",\"code.line.number\":42}",
                "{\"code.filepath\":\"/app/Worker.php\",\"code.lineno\":42}")) {
            var origin = LogOriginMetadata.read(event(json)).reported().getFirst();
            eq(new SourceLocation("/app/Worker.php", 42), origin.location(), "origin schema"); eq(true, origin.explicit(), "explicit origin");
        }
        for (String value : List.of("0", "-1", "1.5", "999999999999999", "\"oops\"")) {
            var invalid = LogOriginMetadata.read(event("{\"log_file\":\"/app/a.php\",\"log_line\":" + value + "}"));
            eq(List.of(), invalid.reported(), "invalid line " + value); eq(1, invalid.issues().size(), "actionable invalid-line guidance");
        }
        eq(List.of(), LogOriginMetadata.read(event("{\"log_file\":{},\"log_line\":42}")).reported(), "file must be text");
        eq(List.of(), LogOriginMetadata.read(event("{\"log_file\":\"/app/a.php\",\"code.line.number\":42}")).reported(), "never mix schema pairs");
        var generic = event("{\"file\":\"/app/a.php\",\"line\":42}");
        var options = new LogNavigationOptions();
        eq(false, LogOriginMetadata.read(generic, options).reported().getFirst().explicit(), "generic role unknown by default");
        options.genericSourceRole = "ignore"; eq(List.of(), LogOriginMetadata.read(generic, options).reported(), "ignore generic coordinates");
        options.genericSourceRole = "logger"; eq(true, LogOriginMetadata.read(generic, options).reported().getFirst().explicit(), "explicit project contract for generic coordinates");
        options.standardFields = false;
        eq(List.of(), LogOriginMetadata.read(event("{\"code.file.path\":\"/app/a.php\",\"code.line.number\":42}"), options).reported(), "disable automatic format recognition");

        var custom = new LogFormat(); custom.logFileField = "ctx.caller_path"; custom.logLineField = "ctx.caller_line";
        custom.logCategoryField = "logger_method";
        var fields = JsonParser.parseString("{\"ctx\":{\"caller_path\":\"/app/a.php\",\"caller_line\":42},\"logger_method\":\"Acme\\\\Worker::run\"}").getAsJsonObject();
        var mapped = new LogEvent("custom", "fixture", fields, custom);
        eq(42, LogOriginMetadata.read(mapped).reported().getFirst().location().line(), "custom nested mapping");
        eq("Acme\\Worker", LogOriginMetadata.read(mapped).symbols().getFirst().owner(), "custom category mapping");
        Connection connection = new Connection(); connection.fields = custom;
        var snapshot = OperationConfig.capture(connection, new IdeOptions());
        eq("ctx.caller_path", snapshot.server().format().logFileField, "operation snapshot retains source alias");
        eq("logger_method", snapshot.server().format().logCategoryField, "operation snapshot retains category alias");
        eq(List.of(), LogOriginMetadata.read(new LogEvent("missing-custom", "", JsonParser.parseString("{\"log_file\":\"/app/old.php\",\"log_line\":1}").getAsJsonObject(), custom)).reported(), "an explicitly configured field is not silently replaced by a default name");
        eq(true, custom.exportFields().containsAll(List.of("ctx", "ctx.caller_path", "ctx.caller_line", "logger_method")), "export preserves configured fields");
        var before = new GraylogSettings.Data(); var after = before.copy(); after.defaults.navigation.categoryLookup = false;
        var change = SettingsChange.project(before, after);
        eq(true, change.has(SettingsChange.Area.LOG_NAVIGATION), "local lookup policy publishes UI change");
        eq(false, change.affectsRequests(), "local navigation behavior does not restart searches");
        connection.navigation.loggerClasses = "Acme\\Log"; connection.navigation.loggerMethods = "write";
        connection.navigation.validate(); var copy = connection.copy(); connection.navigation.loggerClasses = "Changed";
        eq("Acme\\Log", copy.navigation.loggerClasses, "detached behavior snapshot");
        copy.navigation.loggerMethods = "write(.*)";
        try { copy.navigation.validate(); throw new AssertionError("Expression accepted as method name"); } catch (IllegalArgumentException expected) { assertions++; }
        String ready = LogGuidance.render(mapped, connection, Path.of("/app"));
        eq(false, ready.contains("first map your existing"), "no format migration advice when coordinates already exist");
        eq(true, LogGuidance.render(event, connection, Path.of("/app")).contains("first map your existing"), "map existing fields before suggesting capture");
        String example = LogGuidance.example(custom);
        eq(true, example.contains("ctx.caller_path"), "example uses the consumer's configured names");
        eq(false, example.contains("bad value"), "example does not copy real log values");
        eq("", LogOriginMetadata.read(event("{\"message\":\"ok\"}")).messageQuery(), "do not suggest unhelpfully broad searches");
        return assertions;
    }
    private static LogEvent event(String json) { return new LogEvent("origin", "fixture", JsonParser.parseString(json).getAsJsonObject()); }
    private static void eq(Object expected, Object actual, String label) { assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual); }
}
