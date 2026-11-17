package dev.graylog.jetbrains;

import com.intellij.credentialStore.PasswordSafeSettings;
import com.intellij.credentialStore.ProviderType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.ex.ToolWindowEx;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JTable;
import com.intellij.ui.tabs.JBTabs;
import javax.swing.Timer;
import javax.swing.JToggleButton;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Robot;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/** Injected only into a temporary test JAR; never shipped in the distribution. */
@SuppressWarnings({"deprecation", "removal"})
public final class SmokeActivity implements StartupActivity.DumbAware {
    private com.intellij.openapi.editor.Inlay<?> foreignHint;
    @Override public void runActivity(Project project) {
        if (!Boolean.getBoolean("graylog.smoke")) return;
        Path report = Path.of(System.getProperty("graylog.smoke.report"));
        try {
            if (Boolean.getBoolean("graylog.smoke.legacyInstalled")) {
                var id = com.intellij.openapi.extensions.PluginId.getId("dev.graylog.jetbrains");
                var legacy = com.intellij.ide.plugins.PluginManagerCore.getPlugin(id);
                if (legacy != null && legacy.isEnabled()) throw new AssertionError("Legacy prototype must not be enabled beside the public plugin");
                var current = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("dev.omelchenko.graylogexplorer"));
                if (current == null || !current.isEnabled()) throw new AssertionError("Public plugin was blocked by the disabled prototype");
                Files.writeString(report.resolve("legacy-conflict-result.txt"), "PASS: Prototype installed but disabled; public plugin enabled.\n");
            }
            Files.writeString(report.resolve("startup.txt"), "Startup activity invoked\n");
        }
        catch (Exception e) { throw new RuntimeException(e); }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (ApplicationManager.getApplication().getService(PasswordSafeSettings.class).getProviderType() != ProviderType.MEMORY_ONLY) throw new AssertionError("Smoke configuration must select memory-only Password Safe before startup");
                Connection c = new Connection(); c.id = "smoke-demo"; c.name = "Stage (local fixture)";
                c.apiUrl = System.getProperty("graylog.smoke.api"); c.webUrl = c.apiUrl.replaceFirst("/api$", "");
                c.localRoot = project.getBasePath();
                GraylogCredentials.save(c.id, "demo-token"); GraylogSettings.get(project).save(c);
                ToolWindowManager.getInstance(project).invokeLater(() -> startUi(project, report));
            } catch (Throwable e) { fail(report, e); }
        });
    }

    private void startUi(Project project, Path report) {
        try {
            var laf = com.intellij.ide.ui.LafManager.getInstance();
            laf.setAutodetect(false);
            laf.setCurrentUIThemeLookAndFeel(System.getProperty("graylog.smoke.theme", "dark").equals("light") ? laf.getDefaultLightLaf() : laf.getDefaultDarkLaf());
            laf.updateUI(); Files.writeString(report.resolve("theme.txt"), laf.getCurrentUIThemeLookAndFeel().getName());
            JFrame frame = WindowManager.getInstance().getFrame(project);
            if (frame != null) { frame.setBounds(0, 0, 1780, 1060); frame.toFront(); }
            ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Graylog");
            if (window == null) throw new AssertionError("Graylog tool window not registered");
            window.show(() -> {
                try {
                    ((ToolWindowEx) window).stretchHeight(380);
                    GraylogPanel panel = (GraylogPanel) window.getContentManager().getContents()[0].getComponent();
                    if (Boolean.getBoolean("graylog.smoke.sourcesOnly")) {
                        com.intellij.openapi.project.DumbService.getInstance(project).runWhenSmart(() -> {
                            Timer settled = new Timer(2000, e -> {
                                try { SmokeUi.prepare(project); }
                                catch (Throwable failure) { fail(report, failure); return; }
                                SourcesSmoke.run(project, panel, report, () -> {
                                    try { Disposer.dispose(panel); Files.writeString(report.resolve("result.txt"), "PASS: Source tree and window scenarios completed.\n"); }
                                    catch (Throwable error) { fail(report, error); }
                                });
                            }); settled.setRepeats(false); settled.start();
                        }); return;
                    }
                    if (Boolean.getBoolean("graylog.smoke.valuesOnly")) {
                        ChainSmoke.run(project, panel, report, () -> {
                            try { Disposer.dispose(panel); Files.writeString(report.resolve("result.txt"), "PASS: Variable-value UI scenarios completed.\n"); }
                            catch (Throwable error) { fail(report, error); }
                        }); return;
                    }
                    panel.searchText("request_id:\"abc-123\"");
                    long deadline = System.currentTimeMillis() + 60000;
                    Timer wait = new Timer(500, null);
                    wait.addActionListener(e -> {
                        try {
                            JTable table = (JTable) SmokeUi.field(panel, "events");
                            if (table.getRowCount() == 100 && SmokeUi.hasToolbarAction(panel, "Open in Editor")
                                    && SmokeUi.hasToolbarAction(panel, "Code marks") && SmokeUi.hasToolbarAction(panel, "Page size")) {
                                wait.stop();
                                int error = -1;
                                for (int row = 0; row < table.getRowCount(); row++) if ("ERROR".equals(table.getValueAt(row, 1))) error = row;
                                if (error < 0) throw new AssertionError("ERROR event missing");
                                table.setRowSelectionInterval(error, error);
                                JBTabs tabs = (JBTabs) SmokeUi.field(SmokeUi.field(panel, "inspector"), "inspectorTabs");
                                tabs.select(tabs.getTabAt(1), false);
                                JButton open = (JButton) SmokeUi.field(SmokeUi.field(panel, "inspector"), "open");
                                if (!open.isEnabled()) throw new AssertionError("Source navigation button disabled");
                                SmokeUi.clickToolbar(panel, "Open in Editor");
                                long editorDeadline = System.currentTimeMillis() + 45000;
                                Timer editorWait = new Timer(500, null);
                                editorWait.addActionListener(action -> {
                                    var editor = sourceEditor(project);
                                    if (editor != null && editor.getCaretModel().getLogicalPosition().line == 86 && SmokeUi.index(panel).getResult() != null) {
                                        editorWait.stop();
                                        Timer screenshot = new Timer(1500, tick -> finish(project, panel, report));
                                        screenshot.setRepeats(false); screenshot.start();
                                    } else if (System.currentTimeMillis() > editorDeadline) {
                                        editorWait.stop(); finish(project, panel, report);
                                    }
                                });
                                editorWait.start();
                            } else if (System.currentTimeMillis() > deadline) {
                                wait.stop(); throw new AssertionError("No search results: " + ((javax.swing.JLabel) SmokeUi.field(panel, "status")).getText());
                            }
                        } catch (Throwable failure) { wait.stop(); fail(report, failure); }
                    });
                    wait.start();
                } catch (Throwable e) { fail(report, e); }
            });
        } catch (Throwable e) { fail(report, e); }
    }

    private void finish(Project project, GraylogPanel panel, Path report) {
        try {
            var editor = sourceEditor(project);
            if (editor == null || editor.getCaretModel().getLogicalPosition().line != 86) {
                throw new AssertionError("Source navigation did not open line 87; actual=" +
                        (editor == null ? "editor not loaded" : editor.getCaretModel().getLogicalPosition().line));
            }
            JTable currentTable = (JTable) SmokeUi.field(panel, "events");
            for (int row = 0; row < currentTable.getRowCount(); row++) {
                if (currentTable.getValueAt(row, 3).equals("Inventory request timed out")) currentTable.setRowSelectionInterval(row, row);
            }
            RangeHighlighter marker = markerAt(editor, 86);
            if (marker == null || !marker.getGutterIconRenderer().getTooltipText().contains("1 event(s) in period")) {
                throw new AssertionError("Exception gutter marker or occurrence count missing");
            }
            GraylogApi.PeriodIndex index = (GraylogApi.PeriodIndex) SmokeUi.index(panel).getResult();
            if (index.messages() != 128 || index.exceptions() != 3 || index.sites().size() != 5 || markerAt(editor, 102) == null) {
                throw new AssertionError("Exception outside the first table page was not indexed");
            }
            ExceptionHighlights highlights = (ExceptionHighlights) SmokeUi.field(panel, "highlights");
            if (hintAt(editor, 86) == null || !hintAt(editor, 86).getRenderer().text().contains("Inventory request timed out")) throw new AssertionError("Inline exception message missing");
            IdeOptions currentOptions = GraylogIdeSettings.get().getState().options;
            IdeOptions withoutHints = currentOptions.copy(); withoutHints.inlineDetails = false;
            highlights.configure(withoutHints);
            if (hintAt(editor, 86) != null || markerAt(editor, 86) == null) throw new AssertionError("Inline preference must retain gutter marks");
            highlights.configure(currentOptions); marker = markerAt(editor, 86);
            LogEvent selected = (LogEvent) SmokeUi.field(panel, "selected");
            java.awt.Color selectedColor = marker.getTextAttributes().getBackgroundColor();
            highlights.select(null);
            if (selectedColor.equals(markerAt(editor, 86).getTextAttributes().getBackgroundColor())) {
                throw new AssertionError("Selected exception must have a distinct highlight");
            }
            highlights.select(selected);
            RangeHighlighter foreign = editor.getMarkupModel().addLineHighlighter(0, 100, new TextAttributes());
            foreignHint = editor.getInlayModel().addAfterLineEndElement(0, true, new com.intellij.openapi.editor.EditorCustomElementRenderer() {
                @Override public int calcWidthInPixels(com.intellij.openapi.editor.Inlay inlay) { return 10; }
            });
            JToggleButton toggle = (JToggleButton) SmokeUi.field(panel, "codeMarks");
            SmokeUi.clickToolbar(panel, "Code marks");
            if (graylogMarker(editor) != null || hintAt(editor, 86) != null || !foreign.isValid() || !foreignHint.isValid()) throw new AssertionError("Code marks toggle affected unrelated markup");
            SmokeUi.clickToolbar(panel, "Code marks");
            await(report, () -> SmokeUi.index(panel).getResult() != null && !(Boolean) (SmokeUi.index(panel).getPhase() == PeriodIndexController.Phase.LOADING), () -> {
                if (markerAt(editor, 102) == null) throw new AssertionError("Whole-period marks did not return after enabling");
                Object beforePaging = SmokeUi.index(panel).getResult();
                ((JButton) SmokeUi.field(panel, "next")).doClick();
                await(report, () -> !(Boolean) SmokeUi.field(panel, "busy") && ((SearchRequest) SmokeUi.field(panel, "loadedRequest")).offset() == 100, () -> {
                    if (SmokeUi.index(panel).getResult() != beforePaging || markerAt(editor, 102) == null) {
                        throw new AssertionError("Table paging replaced the period index");
                    }
                    ((JButton) SmokeUi.field(panel, "previous")).doClick();
                    await(report, () -> !(Boolean) SmokeUi.field(panel, "busy") && ((SearchRequest) SmokeUi.field(panel, "loadedRequest")).offset() == 0, () -> {
                        if (SmokeUi.index(panel).getResult() != beforePaging) throw new AssertionError("Returning to the first page restarted the period scan");
                        verifyFilters(project, panel, report, editor, foreign, beforePaging);
                    }, "Returning to first table page");
                }, "Paging while retaining all exception marks");
            }, "Reindexing after enabling code marks");
        } catch (Throwable e) { fail(report, e); }
    }

    private void clickOlderException(Project project, GraylogPanel panel, Path report, Editor editor, RangeHighlighter foreign) throws Exception {
        var action = markerAt(editor, 102).getGutterIconRenderer().getClickAction();
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, "GraylogSmoke", DataContext.EMPTY_CONTEXT));
        await(report, () -> ((JTable) SmokeUi.field(panel, "events")).getRowCount() == 1, () -> {
            JTable table = (JTable) SmokeUi.field(panel, "events");
            if (!table.getValueAt(0, 3).toString().equals("Older reservation failed")) {
                throw new AssertionError("Gutter did not show the exception absent from the first page");
            }
            JButton browser = (JButton) SmokeUi.field(SmokeUi.field(panel, "inspector"), "browser");
            if (!browser.isEnabled() || !browser.getToolTipText().contains("/search?")
                    || !browser.getToolTipText().contains("older-119")) {
                throw new AssertionError("CSV example without an index does not open an ID-specific search");
            }
            ((JButton) SmokeUi.field(panel, "allEvents")).doClick();
            if (table.getRowCount() != 100) throw new AssertionError("Original table page was not restored");
            for (int i = 0; i < table.getRowCount(); i++) if (table.getValueAt(i, 3).equals("Inventory request timed out")) table.setRowSelectionInterval(i, i);
            ((JButton) SmokeUi.field(SmokeUi.field(panel, "inspector"), "open")).doClick();
            var source = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (source != null) FileEditorManager.getInstance(project).openFile(source, true);
            editor.getScrollingModel().disableAnimation(); editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            SmokeUi.prepare(project);
            Timer capture = new Timer(2500, ignored -> {
                try {
                    ImageIO.write(new Robot().createScreenCapture(new Rectangle(0, 0, 1780, 1060)), "png", report.resolve("ide-smoke.png").toFile());
                    ReleaseScreenshots.capture(panel, report.resolve("explorer-panel.png"));
                    SettingsSmoke.run(project, panel, report, () -> ChainSmoke.run(project, panel, report, () -> {
                        try {
                            Disposer.dispose(panel);
                            if (graylogMarker(editor) != null || !foreign.isValid()) throw new AssertionError("Disposal did not clean up only Graylog markup");
                            for (var fileEditor : FileEditorManager.getInstance(project).getAllEditors()) if (fileEditor instanceof TextEditor text
                                    && !text.getEditor().getInlayModel().getAfterLineEndElementsInRange(0, text.getEditor().getDocument().getTextLength(), ExceptionInlineRenderer.class).isEmpty()) {
                                throw new AssertionError("Graylog inline hints survived disposal");
                            }
                            for (var fileEditor : FileEditorManager.getInstance(project).getAllEditors()) if (fileEditor instanceof TextEditor text
                                    && !text.getEditor().getInlayModel().getInlineElementsInRange(0, text.getEditor().getDocument().getTextLength(), ExceptionInlineRenderer.class).isEmpty()) throw new AssertionError("Variable hints survived disposal");
                            if (!foreignHint.isValid()) throw new AssertionError("Unrelated inline hint was disposed");
                            foreignHint.dispose();
                            foreign.dispose();
                            Files.writeString(report.resolve("result.txt"), "PASS: PhpStorm searched a 100-row page of 128 messages and indexed three exception events at five source locations. Paging, gutter navigation, Settings, selected-event browser links, toggles and disposal passed. Inline exception messages show the logged phone value at Phone.php:46, preserve unrelated inlays and never modify source text.\n");
                        } catch (Throwable error) { fail(report, error); }
                    }));
                } catch (Throwable error) { fail(report, error); }
            });
            capture.setRepeats(false); capture.start();
        }, "Opening exception examples outside the table page");
    }

    private void verifyFilters(Project project, GraylogPanel panel, Path report, Editor editor, RangeHighlighter foreign, Object index) throws Exception {
        Object loaded = SmokeUi.field(panel, "loadedRequest");
        javax.swing.JTextField filter = (javax.swing.JTextField) SmokeUi.field(SmokeUi.field(panel, "connections"), "connectionFilter");
        javax.swing.JTree tree = (javax.swing.JTree) SmokeUi.field(SmokeUi.field(panel, "connections"), "tree");
        filter.setText("inventory");
        var root = (javax.swing.tree.DefaultMutableTreeNode) tree.getModel().getRoot();
        java.util.Set<String> names = new java.util.HashSet<>();
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) names.add(nodes.nextElement().toString());
        if (!names.contains("Inventory") || names.contains("API") || names.contains("Workers")) throw new AssertionError("Sidebar search does not filter stream names");
        if (SmokeUi.field(panel, "loadedRequest") != loaded || SmokeUi.index(panel).getResult() != index) throw new AssertionError("Sidebar search changed the log scope or exception index");
        filter.setText("missing-connection-name");
        if (((javax.swing.tree.DefaultMutableTreeNode) tree.getModel().getRoot()).getChildCount() != 0) throw new AssertionError("Empty sidebar filter result is not empty");
        filter.setText("");
        if (tree.getSelectionPath() == null || !tree.getLastSelectedPathComponent().toString().equals("All messages")) throw new AssertionError("Clearing sidebar search lost the active stream");
        SmokeUi.chooseDropdown(panel, "Page size", "250");
        await(report, () -> !(Boolean) SmokeUi.field(panel, "busy") && ((SearchRequest) SmokeUi.field(panel, "loadedRequest")).limit() == 250, () -> {
            if (((JTable) SmokeUi.field(panel, "events")).getRowCount() != 128 || SmokeUi.index(panel).getResult() != index) throw new AssertionError("Native page-size filter changed the period index or lost events");
            SmokeUi.chooseDropdown(panel, "Page size", "100");
            await(report, () -> !(Boolean) SmokeUi.field(panel, "busy") && ((SearchRequest) SmokeUi.field(panel, "loadedRequest")).limit() == 100, () -> {
                if (SmokeUi.index(panel).getResult() != index) throw new AssertionError("Restoring page size rebuilt the index");
                clickOlderException(project, panel, report, editor, foreign);
            }, "Restoring page size using native dropdown");
        }, "Applying page size using native dropdown");
    }

    @FunctionalInterface private interface Condition { boolean test() throws Exception; }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void await(Path report, Condition condition, Action action, String label) {
        long deadline = System.currentTimeMillis() + 20000;
        Timer wait = new Timer(200, null);
        wait.addActionListener(e -> {
            try {
                if (condition.test()) { wait.stop(); action.run(); }
                else if (System.currentTimeMillis() > deadline) { wait.stop(); throw new AssertionError(label + " timed out"); }
            } catch (Throwable error) { wait.stop(); fail(report, error); }
        });
        wait.start();
    }

    private static RangeHighlighter markerAt(Editor editor, int line) {
        for (RangeHighlighter h : editor.getMarkupModel().getAllHighlighters()) {
            if (h.getGutterIconRenderer() != null && h.getGutterIconRenderer().getClass().getName().startsWith("dev.graylog.jetbrains.ExceptionHighlights")
                    && editor.getDocument().getLineNumber(h.getStartOffset()) == line) return h;
        }
        return null;
    }

    private static com.intellij.openapi.editor.Inlay<? extends ExceptionInlineRenderer> hintAt(Editor editor, int line) {
        return editor.getInlayModel().getAfterLineEndElementsInRange(editor.getDocument().getLineStartOffset(line),
                editor.getDocument().getLineEndOffset(line), ExceptionInlineRenderer.class).stream().findFirst().orElse(null);
    }

    private static RangeHighlighter graylogMarker(Editor editor) {
        for (RangeHighlighter h : editor.getMarkupModel().getAllHighlighters()) {
            if (h.getGutterIconRenderer() != null && h.getGutterIconRenderer().getClass().getName().startsWith("dev.graylog.jetbrains.ExceptionHighlights")) return h;
        }
        return null;
    }

    private static Editor sourceEditor(Project project) {
        // First-run What's New can steal the selected tab; verify the actual target editor.
        String expected = project.getBasePath() + "/src/StockClient.php";
        for (var fileEditor : FileEditorManager.getInstance(project).getAllEditors()) {
            if (fileEditor instanceof TextEditor text) {
                var file = FileDocumentManager.getInstance().getFile(text.getEditor().getDocument());
                if (file != null && file.getPath().equals(expected)) return text.getEditor();
            }
        }
        return null;
    }


    private static <T> T find(Container parent, Class<T> type) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
            if (component instanceof Container child) { T result = find(child, type); if (result != null) return result; }
        }
        return null;
    }
    private static void fail(Path report, Throwable error) {
        try {
            java.io.StringWriter trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace));
            Files.writeString(report.resolve("result.txt"), "FAIL: " + trace);
        } catch (Exception ignored) { }
    }
}
