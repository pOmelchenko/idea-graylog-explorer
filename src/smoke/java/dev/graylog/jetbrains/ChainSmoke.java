package dev.graylog.jetbrains;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Rectangle;
import java.awt.Robot;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

final class ChainSmoke {
    static void run(Project project, GraylogPanel panel, Path report, Runnable done) {
        panel.searchText("message:\"Invalid phone number.\"");
        long deadline = System.currentTimeMillis() + 30000;
        Timer wait = new Timer(200, null);
        wait.addActionListener(e -> {
            try {
                if (!(SmokeUi.search(panel).getPhase() == SearchController.Phase.LOADING) && !(Boolean) (SmokeUi.index(panel).getPhase() == PeriodIndexController.Phase.LOADING) && SmokeUi.index(panel).getResult() != null) {
                    wait.stop();
                    String text = ((JTextArea) SmokeUi.component(panel, "inspector.rawStack")).getText();
                    if (!text.contains("Caused by: Demo\\Profile") || !text.contains("(code 723)") || text.contains("{\"")) throw new AssertionError("Yii trace is not readable: " + text);
                    GraylogApi.PeriodIndex index = (GraylogApi.PeriodIndex) SmokeUi.index(panel).getResult();
                    if (index.messages() != 1 || index.exceptions() != 1 || index.sites().size() != 3) throw new AssertionError("Yii event chain inflated or lost index counts");
                    var browser = SmokeUi.command(panel, "Open Graylog");
                    if (!browser.isEnabled() || !browser.getToolTipText().contains("/messages/graylog_demo_0/yii-error")) throw new AssertionError("Selected log permalink missing");
                    JTable events = (JTable) SmokeUi.component(panel, "explorer.events"); events.clearSelection();
                    if (browser.isEnabled()) throw new AssertionError("Open Graylog enabled without a selected event");
                    if (!((JPanel) SmokeUi.component(panel, "inspector.inspectorBody")).getComponent(0).isVisible()) throw new AssertionError("Empty inspector hint not shown");
                    events.setRowSelectionInterval(0, 0);
                    if (!browser.isEnabled()) throw new AssertionError("Open Graylog did not follow the new selection");
                    if (!((JPanel) SmokeUi.component(panel, "inspector.inspectorBody")).getComponent(1).isVisible()) throw new AssertionError("Event inspector not restored after selection");
                    @SuppressWarnings("unchecked") JList<SourceLocation> locations = (JList<SourceLocation>) SmokeUi.component(panel, "inspector.locations");
                    int cause = -1;
                    for (int i = 0; i < locations.getModel().getSize(); i++) {
                        SourceLocation source = locations.getModel().getElementAt(i);
                        if (source.path().endsWith("/Domain/Phone.php") && source.line() == 46) cause = i;
                    }
                    if (cause < 0) throw new AssertionError("Previous exception origin missing from source navigation");
                    locations.setSelectedIndex(cause); SmokeUi.clickToolbar(panel, "Open in Editor");
                    long valueDeadline = System.currentTimeMillis() + 60000;
                    Timer capture = new Timer(200, tick -> {
                        try {
                            if (com.intellij.openapi.project.DumbService.isDumb(project)) {
                                if (System.currentTimeMillis() > valueDeadline) throw new AssertionError("PHP indexing timed out");
                                return;
                            }
                            Editor editor = null;
                            for (var fileEditor : FileEditorManager.getInstance(project).getAllEditors()) if (fileEditor instanceof TextEditor t) {
                                var file = FileDocumentManager.getInstance().getFile(t.getEditor().getDocument());
                                if (file != null && file.getPath().endsWith("/Domain/Phone.php")) editor = t.getEditor();
                            }
                            if (editor == null || editor.getCaretModel().getLogicalPosition().line != 45) throw new AssertionError("Previous exception did not navigate to line 46");
                            boolean marker = false;
                            for (var h : editor.getMarkupModel().getAllHighlighters()) if (h.getGutterIconRenderer() != null
                                    && h.getGutterIconRenderer().getClass().getName().startsWith("dev.graylog.jetbrains.ExceptionHighlights")) {
                                marker = h.getGutterIconRenderer().getTooltipText().contains("PhoneNumberInvalidException")
                                        && h.getGutterIconRenderer().getTooltipText().contains("Invalid phone: +12025550123");
                            }
                            if (!marker) throw new AssertionError("Previous exception is not highlighted with its own type");
                            var hints = editor.getInlayModel().getInlineElementsInRange(editor.getDocument().getLineStartOffset(45),
                                    editor.getDocument().getLineEndOffset(45), ExceptionInlineRenderer.class);
                            if (hints.isEmpty() && System.currentTimeMillis() < valueDeadline) return;
                            ((Timer) tick.getSource()).stop();
                            if (hints.size() != 1 || !hints.getFirst().getRenderer().text().equals(" = \"+12025550123\" · Graylog")) throw new AssertionError("Logged value is not shown beside the exception origin: " + PhpHintDiagnostics.describe(project, editor));
                            if (hints.getFirst().getOffset() != editor.getDocument().getText().indexOf("$raw);", editor.getDocument().getLineStartOffset(45)) + 4) throw new AssertionError("Value is not placed at the variable token");
                            if (!editor.getInlayModel().getAfterLineEndElementsInRange(editor.getDocument().getLineStartOffset(45), editor.getDocument().getLineEndOffset(45), ExceptionInlineRenderer.class).isEmpty()) throw new AssertionError("Line fallback duplicates a resolved variable hint");
                            if (editor.getDocument().getText().contains("+12025550123")) throw new AssertionError("Logged value changed the source document");
                            SmokeUi.captureTabs(project, (com.intellij.ui.tabs.JBTabs) SmokeUi.tabs(panel), report, () -> {
                                try {
                                    Files.writeString(report.resolve("yii-result.txt"), "PASS: One Yii log event produces three exception origins; Phone.php:46 shows the exact logged phone in a temporary inline hint and gutter tooltip. Source text is unchanged. Native Fields, Stack trace and JSON tabs were captured.\n");
                                    VariableHintsSmoke.run(project, panel, report, () -> ExplorerUiSmoke.run(project, panel, report, done));
                                } catch (Throwable failure) { fail(report, failure); }
                            });
                        } catch (Throwable failure) { ((Timer) tick.getSource()).stop(); fail(report, failure); }
                    }); capture.start();
                } else if (System.currentTimeMillis() > deadline) { wait.stop(); throw new AssertionError("Yii search/index timed out"); }
            } catch (Throwable failure) { wait.stop(); fail(report, failure); }
        }); wait.start();
    }

    private static void fail(Path report, Throwable error) {
        try { java.io.StringWriter s = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(s)); Files.writeString(report.resolve("result.txt"), "FAIL: " + s); }
        catch (Exception ignored) { }
    }
}
