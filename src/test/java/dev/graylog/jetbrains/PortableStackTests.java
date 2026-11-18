package dev.graylog.jetbrains;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

final class PortableStackTests {
    private static int assertions;
    static int run() throws Exception {
        Path fixtures = Path.of("src/test/resources/stacks");
        for (var item : JsonParser.parseString(Files.readString(fixtures.resolve("fixtures.json"))).getAsJsonArray()) {
            var fixture = item.getAsJsonObject(); String name = fixture.get("name").getAsString();
            String text = Files.readString(fixtures.resolve(name + ".txt"));
            List<SourceLocation> expected = new ArrayList<>();
            for (var frame : fixture.getAsJsonArray("frames")) { var f = frame.getAsJsonObject(); expected.add(new SourceLocation(f.get("path").getAsString(), f.get("line").getAsInt())); }
            eq(expected, SourceLocation.parseFrames(text), name + " frames");
            JsonObject fields = new JsonObject(); fields.addProperty("stack_trace", text); fields.addProperty("level", 3);
            var event = new LogEvent(name, "fixture", fields); var chain = ExceptionDetails.chain(event);
            eq(text, event.stackTrace(), name + " original text retained");
            eq(fixture.getAsJsonArray("types").size(), chain.size(), name + " causes");
            for (int i = 0; i < chain.size(); i++) {
                eq(fixture.getAsJsonArray("types").get(i).getAsString(), chain.get(i).type(), name + " type");
                eq(fixture.getAsJsonArray("origins").get(i).getAsInt(), chain.get(i).origin().line(), name + " origin");
            }
            Connection connection = new Connection(); connection.remoteRoot = "/srv/app"; connection.localRoot = "/local";
            if (name.startsWith("dotnet")) connection.remoteRoot = "C:/app";
            var sites = CoreTests.sites(List.of(event), connection, Path.of("/local"));
            check(!sites.isEmpty(), name + " produces editor sites");
            check(sites.stream().allMatch(s -> s.location().file().startsWith("/local")), name + " confined to root");
            eq(1L, sites.getFirst().count(), name + " count");
            for (var part : chain) check(sites.stream().anyMatch(s -> s.location().line() == part.origin().line() && s.types().contains(part.type())), name + " editor site for " + part.type());
            for (boolean dotted : List.of(false, true)) for (boolean typed : List.of(true, false)) {
                String shape = " ECS " + (dotted ? "dotted" : "object") + (typed ? " with type" : " without type");
                JsonObject error = new JsonObject(); if (typed) error.addProperty("type", chain.getFirst().type());
                error.addProperty("message", "Schema message"); error.addProperty("stack_trace", text);
                JsonObject wrapped = new JsonObject(); wrapped.addProperty("level", 3);
                if (dotted) error.entrySet().forEach(e -> wrapped.add("error." + e.getKey(), e.getValue())); else wrapped.add("error", error);
                var ecsEvent = new LogEvent(name, "fixture", wrapped); var ecsChain = ExceptionDetails.chain(ecsEvent);
                eq(chain.stream().map(ExceptionDetails.Part::type).toList(), ecsChain.stream().map(ExceptionDetails.Part::type).toList(), name + shape + " types");
                eq(chain.stream().map(ExceptionDetails.Part::frames).toList(), ecsChain.stream().map(ExceptionDetails.Part::frames).toList(), name + shape + " frame order/ownership");
                eq("Schema message", ecsChain.getFirst().message(), name + shape + " metadata retained");
                eq(text, ecsEvent.text("error.stack_trace"), name + shape + " original text retained in document");
                eq(sites.stream().map(ExceptionSites.Site::location).toList(), CoreTests.sites(List.of(ecsEvent), connection, Path.of("/local")).stream().map(ExceptionSites.Site::location).toList(), name + shape + " source parity");
                eq(sites.stream().map(ExceptionSites.Site::types).toList(), CoreTests.sites(List.of(ecsEvent), connection, Path.of("/local")).stream().map(ExceptionSites.Site::types).toList(), name + shape + " editor type parity");
            }
            if (name.equals("dotnet-inner")) {
                eq(List.of(8), chain.getFirst().trace().stream().map(SourceLocation::line).toList(), "outer frames resume after boundary");
                eq(List.of(34), chain.get(1).trace().stream().map(SourceLocation::line).toList(), "inner frames stop at boundary");
            }
            if (name.equals("dotnet-nested")) {
                eq(List.of(8), chain.getFirst().trace().stream().map(SourceLocation::line).toList(), "nested outer frames");
                eq(List.of(), chain.get(1).trace(), "middle has only its own origin");
                eq(List.of(20), chain.get(2).trace().stream().map(SourceLocation::line).toList(), "innermost frames");
                eq(List.of("outer", "middle", "inner"), chain.stream().map(ExceptionDetails.Part::message).toList(), "inline cause messages separated");
            }
        }
        for (String text : List.of("at app.Service.run(Native Method)", "at app.Service.run(Unknown Source)",
                "File \"<stdin>\", line 42", "at /app/main.js:0:2", "at /app/main.js:999999999999999:2",
                "at run (webpack:///src/main.ts:42:2)", "at run (https://example.org/main.js:42:2)", "at node:internal/foo.js:12:3"))
            eq(List.of(), SourceLocation.parseFrames(text), "unresolvable frame: " + text);
        eq(List.of(new SourceLocation("/app/my file.tsx", 12)), SourceLocation.parseFrames("at run (file:///app/my%20file.tsx:12:2)"), "file URL");
        for (String argument : List.of("https://example.org/api", "http://example.org/main.js:99:2", "webpack:///src/main.ts:99:2", "node:internal/foo.js:99:2")) {
            eq(List.of(new SourceLocation("/srv/app/Fetch.php", 20)), SourceLocation.parseFrames("#0 /srv/app/Fetch.php(20): fetch('" + argument + "')"), "URL argument does not hide a filesystem frame: " + argument);
        }
        eq(List.of(new SourceLocation("C:\\app\\Fetch.php", 20)), SourceLocation.parseFrames("#0 C:\\app\\Fetch.php(20): fetch('https://example.org/api')"), "Windows frame with URL argument");
        eq(List.of(new SourceLocation("/srv/app/Caller.php", 30)), SourceLocation.parseFrames("https://example.org/main.js:99:2,/srv/app/Caller.php:30"), "skip only the virtual match on a mixed line");
        eq(List.of(new SourceLocation("/app/my file.tsx", 12), new SourceLocation("/app/literal%20name.php", 30)), SourceLocation.parseFrames("at run (file:///app/my%20file.tsx:12:2), /app/literal%20name.php:30"), "decode only the file URL match");
        eq(List.of(new SourceLocation("C:/app/my file.ts", 12)), SourceLocation.parseFrames("at run (file:///C:/app/my%20file.ts:12:2)"), "Windows file URL");
        eq(List.of(new SourceLocation("app/Service.java", 42)), SourceLocation.parseFrames("at app//app.Service.run(Service.java:42)"), "JVM classloader prefix");
        var iteration = new JsonObject(); iteration.addProperty("stack_trace", "Traceback (most recent call last):\n  File \"/app/main.py\", line 2, in run\n  File \"/app/iterator.py\", line 9, in next\nStopIteration");
        var part = ExceptionDetails.chain(new LogEvent("iteration", "", iteration)).getFirst();
        eq("StopIteration", part.type(), "Python type without Error suffix"); eq(9, part.origin().line(), "Python innermost origin");
        Connection mapping = new Connection(); mapping.localRoot = "/local";
        eq(null, new SourceLocation("../../escape.rs", 1).resolve(mapping, Path.of("/local")), "traversal rejected");
        var ecs = JsonParser.parseString("{\"error\":{\"type\":\"TypeError\",\"message\":\"bad\",\"stack_trace\":\"at run (/srv/app/main.js:42:2)\"}}").getAsJsonObject();
        var ecsEvent = new LogEvent("ecs", "fixture", ecs);
        eq("TypeError", ExceptionSites.exceptionName(ecsEvent), "ECS type");
        eq(new SourceLocation("/srv/app/main.js", 42), ExceptionDetails.chain(ecsEvent).getFirst().frames().getFirst(), "ECS frame");
        for (String type : List.of("", "  ")) {
            var flat = new JsonObject(); flat.addProperty("error.type", type);
            flat.addProperty("error.message", "Frame-only stack"); flat.addProperty("error.stack_trace", "at run (/srv/app/main.js:42:2)");
            var untyped = ExceptionDetails.chain(new LogEvent("untyped", "fixture", flat));
            eq(1, untyped.size(), "ECS frame-only stack needs no type metadata");
            eq("Exception", untyped.getFirst().type(), "frame-only stack retains the generic fallback");
            eq(new SourceLocation("/srv/app/main.js", 42), untyped.getFirst().origin(), "untyped ECS frame origin");
        }
        var messageOnly = new JsonObject(); messageOnly.addProperty("error.message", "Ordinary message");
        eq(List.of(), ExceptionDetails.chain(new LogEvent("message-only", "fixture", messageOnly)), "a dotted message alone is not stack data");
        messageOnly.addProperty("error.stack_trace", " \n ");
        eq(List.of(), ExceptionDetails.chain(new LogEvent("blank-stack", "fixture", messageOnly)), "blank stack without type is ignored");
        immutable(); return assertions;
    }
    private static void immutable() {
        var fields = JsonParser.parseString("{\"source\":\"api\",\"exception\":{\"class\":\"RuntimeException\",\"message\":\"original\"}}").getAsJsonObject();
        LogFormat format = new LogFormat(); var event = new LogEvent("id", "index", fields, format);
        fields.addProperty("source", "changed"); format.serviceField = "changed";
        event.fields().addProperty("source", "changed again"); event.format().serviceField = "changed again";
        event.element("exception").getAsJsonObject().addProperty("message", "changed");
        eq("api", event.text("source"), "document snapshot"); eq("source", event.format().serviceField, "format snapshot");
        eq("original", ExceptionDetails.chain(event).getFirst().message(), "parsed immutable chain");
        check(ExceptionDetails.chain(event) == ExceptionDetails.chain(event), "chain reused without reparsing");
        try { ExceptionDetails.chain(event).clear(); throw new AssertionError("Mutable chain"); } catch (UnsupportedOperationException expected) { assertions++; }
    }
    private static void eq(Object expected, Object actual, String message) { check(Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
