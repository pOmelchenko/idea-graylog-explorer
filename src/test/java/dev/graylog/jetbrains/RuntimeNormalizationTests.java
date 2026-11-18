package dev.graylog.jetbrains;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;

final class RuntimeNormalizationTests {
    private static int assertions;
    static int run() {
        for (String thread : List.of("main", "worker pool 1", "", "worker \"quoted\"")) {
            String text = "Exception in thread \"" + thread + "\" app.OuterFailure: outer\n at app.Outer.run(Outer.java:10)\nCaused by: app.InnerFailure: inner\n at app.Inner.run(Inner.java:20)";
            var chain = TextStackParser.parse(text, "", "");
            eq(List.of("app.OuterFailure", "app.InnerFailure"), chain.stream().map(ExceptionDetails.Part::type).toList(), "structural headers support custom names: " + thread);
            eq(List.of(10, 20), chain.stream().map(p -> p.origin().line()).toList(), "frame ownership: " + thread);
        }
        var known = TextStackParser.parse("app.Failure: outer\n at app.Outer.run(Outer.java:10)", "app.Failure", "metadata");
        eq("app.Failure", known.getFirst().type(), "metadata proves a custom bare type");
        eq(List.of(), TextStackParser.parse("INFO: request completed", "", ""), "ordinary text is not an exception header");
        String prefixed = "custom logger prefix: app.Failure: outer\n at app.Outer.run(Outer.java:10)\nCaused by: app.Cause: inner\n at app.Inner.run(Inner.java:20)";
        eq(List.of(20), TextStackParser.parse(prefixed, "", "").stream().map(p -> p.origin().line()).toList(), "unknown parent frames are not assigned to the cause");
        eq(List.of(10, 20), TextStackParser.parse(prefixed, "app.Failure", "metadata outer").stream().map(p -> p.origin().line()).toList(), "metadata retains an otherwise unrecognized parent");
        var mentioned = TextStackParser.parse("java.lang.RuntimeException: mentions Traceback (most recent call last)\n at app.Outer.run(Outer.java:10)\n at app.Caller.run(Caller.java:20)", "", "");
        eq(10, mentioned.getFirst().origin().line(), "a phrase in a message does not select Python ordering");
        eq("VendorFailure", TextStackParser.parse("/srv/app/job.rb:31:in `run': bad (VendorFailure)", "", "").getFirst().type(), "Ruby header context allows custom type names");

        var error = new JsonObject(); error.addProperty("type", "SchemaFault"); error.addProperty("message", "Schema message"); error.addProperty("code", 123);
        error.addProperty("stack_trace", "Traceback (most recent call last):\n  File \"/srv/app/main.py\", line 12, in run\n  File \"/srv/app/user.py\", line 43, in validate\nValueError: printed message");
        var part = ecs(error).getFirst();
        eq("SchemaFault", part.type(), "ECS type wins"); eq("Schema message", part.message(), "ECS message wins"); eq("123", part.code(), "ECS code retained");
        eq(new SourceLocation("/srv/app/user.py", 43), part.origin(), "ECS Python innermost origin");
        error.addProperty("file", "/srv/app/reported.py"); error.addProperty("line", 99);
        part = ecs(error).getFirst();
        eq(new SourceLocation("/srv/app/reported.py", 99), part.origin(), "explicit object origin wins");
        eq(List.of(43, 12), part.trace().stream().map(SourceLocation::line).toList(), "normalized frames retained after explicit origin");
        error.remove("file"); error.remove("line");
        error.addProperty("stack_trace", "Exception in thread \"main\" java.lang.RuntimeException: outer\n at app.Outer.run(Outer.java:10)\nCaused by: java.lang.IllegalArgumentException: printed inner\n at app.Inner.run(Inner.java:20)");
        var previous = new JsonObject(); previous.addProperty("class", "ExplicitCause"); previous.addProperty("file", "/srv/app/explicit.php:70");
        error.add("previous", previous);
        var chain = ecs(error);
        eq(List.of("SchemaFault", "ExplicitCause"), chain.stream().map(ExceptionDetails.Part::type).toList(), "explicit causes supersede printed duplicates");
        eq(List.of(10, 70), chain.stream().map(p -> p.origin().line()).toList(), "explicit cause location retained");
        error.remove("type");
        eq(List.of("java.lang.RuntimeException", "ExplicitCause"), ecs(error).stream().map(ExceptionDetails.Part::type).toList(), "parsed root type coexists with explicit structured cause");
        for (boolean dotted : List.of(false, true)) for (String type : List.of("missing", "", "  ", "Exception", "SchemaFault")) {
            var node = new JsonObject();
            if (!type.equals("missing")) node.addProperty("type", type);
            node.addProperty("stack_trace", "TypeError: printed message\n at run (/srv/app/main.js:42:2)");
            node.addProperty("message", "Schema message");
            var fields = new JsonObject();
            if (dotted) node.entrySet().forEach(e -> fields.add("error." + e.getKey(), e.getValue())); else fields.add("error", node);
            var event = new LogEvent("optional-type", "fixture", fields);
            var root = ExceptionDetails.chain(event).getFirst();
            String expected = type.equals("missing") || type.isBlank() ? "TypeError" : type;
            eq(expected, root.type(), "only explicit type metadata overrides the parsed header: " + type + " dotted=" + dotted);
            eq("Schema message", root.message(), "message metadata remains authoritative without a type");
            eq(true, ExceptionDetails.render(ExceptionDetails.chain(event)).startsWith(expected + ":"), "inspector renders the chosen type");
        }
        for (String alias : List.of("exception_class", "exception_type", "exceptionClass", "error_type", "error.type")) {
            var node = new JsonObject(); node.addProperty("stack_trace", "TypeError: failed\n at run (/srv/app/main.js:42:2)");
            var fields = new JsonObject(); fields.add("error", node); fields.addProperty(alias, "ReportedFault");
            eq("ReportedFault", ExceptionDetails.chain(new LogEvent("type-alias", "fixture", fields)).getFirst().type(), "existing explicit event type alias: " + alias);
        }
        for (String type : List.of("class", "exception_class")) {
            var node = new JsonObject(); node.addProperty(type, "ReportedFault"); node.addProperty("stack_trace", "TypeError: failed\n at run (/srv/app/main.js:42:2)");
            eq("ReportedFault", ecs(node).getFirst().type(), "existing structured type alias: " + type);
        }
        return assertions;
    }
    private static List<ExceptionDetails.Part> ecs(JsonObject error) { var fields = new JsonObject(); fields.add("error", error); return ExceptionDetails.chain(new LogEvent("runtime", "fixture", fields)); }
    private static void eq(Object expected, Object actual, String message) { assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual); }
}
