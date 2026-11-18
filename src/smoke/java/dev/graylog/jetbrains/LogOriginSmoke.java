package dev.graylog.jetbrains;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.concurrency.AppExecutorUtil;
import javax.swing.*;
import java.nio.file.*;
import java.util.List;

/** Real PHP index/callee resolution, source navigation, profile bindings and event-specific guidance. */
final class LogOriginSmoke {
    static void run(Project project, GraylogPanel panel, Path report, Runnable done) {
        var navigator = new LogOriginNavigator(project);
        Connection connection = new Connection(); connection.localRoot = project.getBasePath();
        connection.apiUrl = System.getProperty("graylog.smoke.api");
        Path source = Path.of(project.getBasePath(), "logging-fixture.php");
        try {
            List<String> lines = Files.readAllLines(source);
            int one = line(lines, "// logger-one"), two = line(lines, "// logger-two");
            var explicit = event("", one); var category = event("DemoLogging\\Service::run", 0);
            ReadAction.nonBlocking(() -> {
                var direct = navigator.locate(explicit, connection);
                check(direct.getDirect() && direct.getTargets().size() == 1 && direct.getTargets().getFirst().getLine() == one, "explicit log origin");
                var candidates = navigator.locate(category, connection);
                check(!candidates.getDirect(), "category results require choice");
                check(candidates.getTargets().stream().map(LogOriginNavigator.Target::getLine).sorted().toList().equals(List.of(one, two)), "only direct resolved logger calls inside the method: " + candidates);
                var mixedFields = category.fields(); mixedFields.addProperty("file", "/application/logging-fixture.php"); mixedFields.addProperty("line", line(lines, "// construction"));
                var mixed = navigator.locate(new LogEvent("mixed", "fixture", mixedFields), connection);
                check(mixed.getTargets().size() == 3 && !mixed.getDirect(), "generic throw/source coordinates do not suppress logger candidates");
                var declared = connection.copy(); declared.navigation.genericSourceRole = "logger";
                check(navigator.locate(new LogEvent("declared", "fixture", mixedFields), declared).getDirect(), "consumer can declare generic fields to mean logger coordinates");
                var fallback = navigator.locate(event("DemoLogging\\Service::constructionOnly", 0), connection);
                check(fallback.getTargets().size() == 1 && fallback.getTargets().getFirst().getDescription().contains("exact log call not identified"), "message construction is a method anchor, not a logger call");
                var psr = navigator.locate(event("DemoLogging\\PsrService::run", 0), connection);
                check(psr.getTargets().size() == 1 && psr.getTargets().getFirst().getLine() == line(lines, "// psr-logger"), "PSR-3 interface call");
                var custom = connection.copy(); custom.navigation.knownLoggers = false;
                custom.navigation.loggerClasses = "DemoLogging\\CustomLogger"; custom.navigation.loggerMethods = "write";
                var found = navigator.locate(event("DemoLogging\\CustomService::run", 0), custom);
                check(found.getTargets().size() == 1 && found.getTargets().getFirst().getLine() == line(lines, "// custom-logger"), "consumer-defined logger class and method");
                custom.navigation.categoryLookup = false;
                check(navigator.locate(category, custom).getTargets().isEmpty(), "category lookup disabled");
                check(navigator.locate(event("", 99999), connection).getTargets().isEmpty(), "out-of-range line rejected");
                return true;
            }).inSmartMode(project).withDocumentsCommitted(project).finishOnUiThread(ModalityState.any(), ignored -> {
                try {
                    Disposer.dispose(navigator);
                    profile(project);
                    Timer settled = new Timer(500, e -> { try {
                    EventInspector inspector = (EventInspector) SmokeUi.component(panel, "explorer.inspector");
                    inspector.show(category, connection, null);
                    SmokeUi.prepare(project);
                    inspector.tabs().select(inspector.tabs().getTabAt(4), false).doWhenDone(() -> {
                    Timer painted = new Timer(600, tick -> { try {
                    JTextArea guidance = (JTextArea) SmokeUi.component(panel, "inspector.loggingGuidance");
                    check(guidance.getText().contains("DemoLogging\\Service::run") && guidance.getText().contains("first map your existing"), "event-specific guidance");
                    check(inspector.tabs().getSelectedInfo() == inspector.tabs().getTabAt(4), "guidance tab remains selected after repaint");
                    ReleaseScreenshots.capture(inspector, report.resolve("logging-guidance.png"));
                    SmokeUi.clickToolbar(panel, "Copy logging fields example");
                    String example = com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor);
                    check(example != null && example.contains("<deployed-source-path>") && !example.contains("bad value"), "copy example uses neutral placeholders, not selected event values");
                    inspector.show(explicit, connection, null);
                    check(!SmokeUi.command(panel, "Open in Editor").isEnabled(), "logger origin is separate from exception/legacy source navigation");
                    SmokeUi.clickToolbar(panel, "Find log statement");
                    await(report, () -> {
                        for (var fileEditor : FileEditorManager.getInstance(project).getAllEditors())
                            if (fileEditor instanceof TextEditor editor && editor.getFile().getPath().equals(source.toString()) && editor.getEditor().getCaretModel().getLogicalPosition().line == one - 1) return true;
                        return false;
                    }, () -> {
                        check(Files.readAllLines(source).equals(lines), "navigation does not modify source");
                        Files.writeString(report.resolve("log-origin-result.txt"), "PASS: PHP category/callee candidates, closure exclusion, PSR-3, custom logger profiles, metadata-only policy, Settings bindings, guidance and actual direct navigation.\n");
                        done.run();
                    });
                    } catch (Throwable error) { fail(report, error); } });
                    painted.setRepeats(false); painted.start();
                    });
                    } catch (Throwable error) { fail(report, error); } });
                    settled.setRepeats(false); settled.start();
                } catch (Throwable error) { fail(report, error); }
            }).submit(AppExecutorUtil.getAppExecutorService()).onError(error -> fail(report, error));
        } catch (Throwable error) { fail(report, error); }
    }
    private static void profile(Project project) throws Exception {
        var saved = GraylogSettings.get(project).getState().copy();
        var settings = new GraylogProjectConfigurable(project);
        try {
            settings.createComponent(); settings.reset();
            ((JTextField) SmokeUi.component(settings, "project.logFile")).setText("ctx.origin.file");
            ((JTextField) SmokeUi.component(settings, "project.logLine")).setText("ctx.origin.line");
            ((JTextField) SmokeUi.component(settings, "project.logClass")).setText("ctx.class");
            ((JTextField) SmokeUi.component(settings, "project.logFunction")).setText("ctx.method");
            ((JTextField) SmokeUi.component(settings, "project.loggerClasses")).setText("DemoLogging\\CustomLogger");
            ((JTextField) SmokeUi.component(settings, "project.loggerMethods")).setText("write");
            ((JCheckBox) SmokeUi.component(settings, "project.categoryLookup")).setSelected(false);
            settings.apply(); settings.reset();
            var effective = GraylogSettings.get(project).getState().defaults;
            check(effective.fields.logFileField.equals("ctx.origin.file") && effective.fields.logLineField.equals("ctx.origin.line"), "source field bindings persist");
            check(effective.fields.logClassField.equals("ctx.class") && effective.fields.logFunctionField.equals("ctx.method"), "split symbol field bindings persist");
            check(!effective.navigation.categoryLookup && effective.navigation.loggerClasses.equals("DemoLogging\\CustomLogger") && effective.navigation.loggerMethods.equals("write"), "behavior profile persists");
            check(!settings.isModified(), "reset is clean");
        } finally { settings.disposeUIResources(); GraylogSettings.get(project).replace(saved); }
    }
    private static LogEvent event(String category, int line) {
        var fields = new JsonObject(); fields.addProperty("message", "Error processing items: bad value"); fields.addProperty("level", 6);
        if (!category.isBlank()) fields.addProperty("ctxt_category", category);
        if (line > 0) { fields.addProperty("log_file", "/application/logging-fixture.php"); fields.addProperty("log_line", line); }
        return new LogEvent("log-origin", "fixture", fields);
    }
    private static int line(List<String> lines, String marker) { for (int i=0; i<lines.size(); i++) if (lines.get(i).contains(marker)) return i+1; throw new AssertionError(marker); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface Check { boolean run() throws Exception; }
    @FunctionalInterface private interface Step { void run() throws Exception; }
    private static void await(Path report, Check condition, Step done) {
        long deadline = System.currentTimeMillis() + 20000;
        Timer timer = new Timer(200, null);
        timer.addActionListener(e -> { try { if (condition.run()) { timer.stop(); done.run(); } else if (System.currentTimeMillis() > deadline) { timer.stop(); throw new AssertionError("Log source navigation timed out"); } } catch (Throwable error) { timer.stop(); fail(report, error); } }); timer.start();
    }
    private static void fail(Path report, Throwable error) { try { var text = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(text)); Files.writeString(report.resolve("result.txt"), "FAIL: " + text); } catch (Exception ignored) {} }
}
