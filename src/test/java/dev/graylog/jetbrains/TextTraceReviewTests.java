package dev.graylog.jetbrains;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Regressions for explicit throw metadata and text cause ownership. */
final class TextTraceReviewTests {
    private static int assertions;
    static int run() {
        var fields = fields("#0 /srv/app/Caller.php(20): run()");
        fields.addProperty("exception_file", "/srv/app/Throw.php");
        fields.addProperty("exception_line", 10);
        var event = new LogEvent("explicit", "fixture", fields);
        var root = ExceptionDetails.chain(event).getFirst();
        eq(new SourceLocation("/srv/app/Throw.php", 10), root.origin(), "explicit throw wins over caller");
        eq(List.of(new SourceLocation("/srv/app/Caller.php", 20)), root.trace(), "caller retained");
        eq(fields.get("stack_trace").getAsString(), event.stackTrace(), "raw trace unchanged");
        Connection mapping = new Connection(); mapping.remoteRoot = "/srv/app"; mapping.localRoot = "/local";
        var sites = CoreTests.sites(List.of(event), mapping, Path.of("/local"));
        eq(List.of(new ExceptionSites.Location(Path.of("/local/Throw.php"), 10)), sites.stream().map(ExceptionSites.Site::location).toList(), "editor marks use throw");
        eq(List.of(root.origin(), root.trace().getFirst()), ExceptionSites.exceptionFrames(event), "inspector preserves throw and caller");

        for (String line : List.of("", "0", "-1", "invalid", "999999999999999")) {
            fields.addProperty("exception_line", line);
            eq(new SourceLocation("/srv/app/Caller.php", 20), ExceptionDetails.chain(new LogEvent("bad", "", fields)).getFirst().origin(), "invalid explicit line " + line);
        }
        fields.remove("exception_file"); fields.remove("exception_line");
        fields.addProperty("file", "/srv/app/Logger.php"); fields.addProperty("line", 99);
        eq(new SourceLocation("/srv/app/Caller.php", 20), ExceptionDetails.chain(new LogEvent("logger", "", fields)).getFirst().origin(), "logger metadata does not replace throw");
        fields.addProperty("exception_file", "/srv/app/Caller.php"); fields.addProperty("exception_line", 20);
        eq(List.of(), ExceptionDetails.chain(new LogEvent("same", "", fields)).getFirst().trace(), "no duplicate origin");

        var jvm = fields("java.lang.RuntimeException: outer\n at app.Outer.run(Outer.java:20)\nCaused by: java.io.IOException: inner\n at app.Inner.run(Inner.java:30)");
        jvm.addProperty("exception_file", "app/Throw.java"); jvm.addProperty("exception_line", 10);
        var chain = ExceptionDetails.chain(new LogEvent("jvm", "", jvm));
        eq(new SourceLocation("app/Throw.java", 10), chain.getFirst().origin(), "override outer only");
        eq(new SourceLocation("app/Inner.java", 30), chain.get(1).origin(), "inner origin unchanged");
        var python = fields("Traceback (most recent call last):\n  File \"/srv/app/inner.py\", line 30, in run\nValueError: inner\n\nDuring handling of the above exception, another exception occurred:\n\nTraceback (most recent call last):\n  File \"/srv/app/outer.py\", line 20, in run\nRuntimeError: outer");
        python.addProperty("exception_file", "/srv/app/throw.py"); python.addProperty("exception_line", 10);
        chain = ExceptionDetails.chain(new LogEvent("python", "", python));
        eq("RuntimeError", chain.getFirst().type(), "Python outermost first");
        eq(new SourceLocation("/srv/app/throw.py", 10), chain.getFirst().origin(), "override after Python reversal");
        eq(new SourceLocation("/srv/app/inner.py", 30), chain.get(1).origin(), "Python inner origin unchanged");
        return assertions;
    }
    private static JsonObject fields(String trace) {
        var fields = new JsonObject(); fields.addProperty("stack_trace", trace);
        fields.addProperty("exception_class", "RuntimeException"); fields.addProperty("level", 3);
        return fields;
    }
    private static void eq(Object expected, Object actual, String label) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
}
