package dev.graylog.jetbrains;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.google.gson.JsonParser;
import javax.swing.Timer;
import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class VariableHintsSmoke {
    static void run(Project project, GraylogPanel panel, Path report, Runnable done) {
        ReadAction.nonBlocking(() -> {
            try { sourceCases(project); return "PASS"; }
            catch (Throwable failure) { return trace(failure); }
        }).inSmartMode(project).withDocumentsCommitted(project).finishOnUiThread(ModalityState.nonModal(), result -> {
            if (!result.equals("PASS")) { fail(report, result); return; }
            try { lifecycle(project, panel, report, done); } catch (Throwable e) { fail(report, trace(e)); }
        }).submit(AppExecutorUtil.getAppExecutorService());
    }
    private static void sourceCases(Project project) {
        var file = LocalFileSystem.getInstance().findFileByPath(project.getBasePath() + "/variable-cases.php");
        var document = FileDocumentManager.getInstance().getDocument(file);
        var providers = VariableHintProvider.EP.getExtensionList();
        if (providers.size() != 1) throw new AssertionError("Optional PHP provider is not registered: " + providers);
        var provider = providers.getFirst();
        String text = document.getText();
        var detail = new ExceptionSites.Detail("GraylogHintFixture\\TextException", "Bad: hello!");
        int renamedLine = document.getLineNumber(text.indexOf("// case:rename")) + 1;
        var renamed = provider.hints(project, file, document, renamedLine, List.of(detail));
        int offset = text.indexOf("$input);", text.indexOf("function renamed")) + 6;
        if (!renamed.equals(List.of(new VariableHintProvider.Hint(offset, "hello")))) throw new AssertionError("Renamed PHP variable did not map: " + renamed);
        for (String name : List.of("expression", "constant", "ambiguous", "changed", "inherited", "slots", "precision", "shadowed", "named", "different", "noThrow")) {
            String type = switch (name) { case "changed" -> "ChangedException"; case "inherited" -> "InheritedChange";
                case "slots" -> "TwoSlots"; case "precision" -> "Precision"; case "shadowed" -> "ShadowedFunction"; default -> "TextException"; };
            int line = document.getLineNumber(text.indexOf("// case:" + name)) + 1;
            var hints = provider.hints(project, file, document, line,
                    List.of(new ExceptionSites.Detail("GraylogHintFixture\\" + type, "Bad: hello!")));
            if (!hints.isEmpty()) throw new AssertionError("Unproven mapping " + name + ": " + hints);
        }
        if (!provider.hints(project, file, document, renamedLine, List.of(new ExceptionSites.Detail(detail.type(), "Other message"))).isEmpty()) throw new AssertionError("Mismatched format accepted");
        if (!provider.hints(project, file, document, renamedLine, List.of(detail, new ExceptionSites.Detail(detail.type(), "Bad: other!"))).isEmpty()) throw new AssertionError("Conflicting values accepted");
    }
    private static void lifecycle(Project project, GraylogPanel panel, Path report, Runnable done) throws Exception {
        Editor editor = null;
        for (var fileEditor : FileEditorManager.getInstance(project).getAllEditors()) if (fileEditor instanceof TextEditor t) {
            var file = FileDocumentManager.getInstance().getFile(t.getEditor().getDocument());
            if (file != null && file.getPath().endsWith("/Domain/Phone.php")) editor = t.getEditor();
        }
        if (editor == null) throw new AssertionError("Phone editor missing");
        final Editor target = editor;
        ExceptionHighlights highlights = (ExceptionHighlights) SmokeUi.field(panel, "highlights");
        LogEvent original = (LogEvent) SmokeUi.field(panel, "selected");
        var fields = original.fields().deepCopy();
        var chain = JsonParser.parseString(fields.get("ctxt_exception").getAsString()).getAsJsonObject();
        chain.getAsJsonObject("previous").getAsJsonObject("previous").addProperty("message", "Invalid phone: +12025550124");
        fields.addProperty("ctxt_exception", chain.toString());
        LogEvent second = new LogEvent("outside-samples", original.index(), fields);
        var foreign = target.getInlayModel().addInlineElement(0, true, new EditorCustomElementRenderer() {
            @Override public int calcWidthInPixels(Inlay inlay) { return 1; }
        });
        highlights.select(second);
        await(report, () -> hint(target).contains("+12025550124"), () -> {
            highlights.setEnabled(false);
            if (!hint(target).isEmpty() || !foreign.isValid()) throw new AssertionError("Disabling marks broke inline ownership");
            highlights.setEnabled(true);
            IdeOptions options = GraylogIdeSettings.get().getState().options;
            IdeOptions disabled = options.copy(); disabled.inlineValues = false;
            highlights.configure(disabled);
            if (!hint(target).isEmpty() || target.getInlayModel().getAfterLineEndElementsInRange(0, target.getDocument().getTextLength(), ExceptionInlineRenderer.class).isEmpty()) throw new AssertionError("Inline values setting did not retain line fallback");
            highlights.configure(options);
            highlights.select(original);
            await(report, () -> hint(target).contains("+12025550123"), () -> {
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                String source = target.getDocument().getText();
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> target.getDocument().insertString(0, "// temporary edit\n"));
                if (!hint(target).isEmpty()) throw new AssertionError("Value survived a source edit");
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> target.getDocument().setText(source));
                com.intellij.openapi.application.WriteIntentReadAction.run(() -> FileDocumentManager.getInstance().saveDocument(target.getDocument()));
                highlights.select(second); highlights.select(original);
                await(report, () -> hint(target).contains("+12025550123"), () -> {
                    if (!foreign.isValid() || target.getDocument().getText().contains("+12025550123")) throw new AssertionError("Hint modified source or foreign markup");
                    foreign.dispose();
                    FileEditorManager.getInstance(project).openFile(FileDocumentManager.getInstance().getFile(target.getDocument()), true);
                    target.getScrollingModel().disableAnimation();
                    target.getScrollingModel().scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER);
                    SmokeUi.prepare(project);
                    Timer capture = new Timer(600, tick -> {
                        try {
                            ImageIO.write(new Robot().createScreenCapture(new Rectangle(0, 0, 1780, 1060)), "png", report.resolve("variable-values.png").toFile());
                            ReleaseScreenshots.capture(target.getComponent(), report.resolve("php-values-editor.png"));
                            Files.writeString(report.resolve("variable-values-result.txt"), "PASS: PHP PSI maps renamed local variables through a single lossless sprintf argument. Rejected transformed, constant, named, ambiguous, mutated, inherited-transform, multi-slot, precision, shadowed-function, wrong-class and wrong-format cases. Selected-event values override period samples; toggles, setting, edits and ownership passed.\n");
                            done.run();
                        } catch (Throwable error) { fail(report, trace(error)); }
                    }); capture.setRepeats(false); capture.start();
                });
                    } catch (Throwable failure) { fail(report, trace(failure)); }
                }, ModalityState.nonModal());
            });
        });
    }
    private static String hint(Editor editor) {
        return editor.getInlayModel().getInlineElementsInRange(0, editor.getDocument().getTextLength(), ExceptionInlineRenderer.class)
                .stream().map(i -> i.getRenderer().text()).collect(java.util.stream.Collectors.joining());
    }
    @FunctionalInterface private interface Check { boolean get() throws Exception; }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void await(Path report, Check check, Action done) {
        long deadline = System.currentTimeMillis() + 15000;
        Timer timer = new Timer(200, null);
        timer.addActionListener(e -> {
            try { if (check.get()) { timer.stop(); done.run(); }
                else if (System.currentTimeMillis() > deadline) throw new AssertionError("Variable hint update timed out"); }
            catch (Throwable failure) { timer.stop(); fail(report, trace(failure)); }
        }); timer.start();
    }

    private static String trace(Throwable failure) { var s = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(s)); return s.toString(); }
    private static void fail(Path report, String error) { try { Files.writeString(report.resolve("result.txt"), "FAIL: " + error); } catch (Exception ignored) {} }
}
