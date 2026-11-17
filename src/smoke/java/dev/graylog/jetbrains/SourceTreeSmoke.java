package dev.graylog.jetbrains;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Checks real popup chrome, sorted checkbox rows and a shared tree/popup facet scope. */
final class SourceTreeSmoke {
    private static String phase = "initial scope";
    static void run(Project project, GraylogPanel panel, Path report, Runnable done) {
        await(report, () -> ready(panel) && SmokeUi.sources(panel).getValues() != null && !(Boolean) SmokeUi.sources(panel).getLoading(), () -> {
            JTree tree = (JTree) SmokeUi.field(SmokeUi.field(panel, "connections"), "tree");
            if (entry(tree, "archive-worker") == null) throw new AssertionError("Sidebar omitted the source outside the loaded page");
            open(panel);
            await(report, () -> popupReady(panel), () -> popupChecks(project, panel, report, done));
        });
    }
    private static void popupChecks(Project project, GraylogPanel panel, Path report, Runnable done) throws Exception {
        SourceFilterPopup pane = (SourceFilterPopup) SmokeUi.field(panel, "sourcePopup");
        JTable table = (JTable) SmokeUi.field(pane, "table"); JBPopup popup = pane.popup();
        if (!table.getColumnName(1).equals("Source") || !table.getColumnName(2).equals("Messages")) throw new AssertionError("Sortable column headers missing");
        Object before = SmokeUi.loadedRequest(panel), index = SmokeUi.index(panel).getResult();
        clickHeader(table, 2);
        await(report, () -> sorted(table, false), () -> {
            clickHeader(table, 2);
            await(report, () -> sorted(table, true), () -> {
                String first = ((GraylogApi.SourceValue) table.getValueAt(0, 1)).name();
                table.getModel().setValueAt(true, table.convertRowIndexToModel(0), 0);
                if (!((Set<?>) SmokeUi.field(pane, "draft")).contains(first) || !first.equals("demo-api")) throw new AssertionError("Sorted checkbox modified the wrong model row");
                Point location = popup.getLocationOnScreen(); Dimension size = popup.getSize();
                drag(location.x + size.width / 2, location.y + 12, -55, 28);
                await(report, () -> Math.abs(popup.getLocationOnScreen().x - location.x) > 25, () -> {
                    Point moved = popup.getLocationOnScreen(); Dimension original = popup.getSize();
                    drag(moved.x + original.width - 2, moved.y + original.height - 2, -35, 25);
                    await(report, () -> Math.abs(popup.getSize().width - original.width) > 15, () -> {
                        Dimension resized = popup.getSize();
                        if (resized.width < 560 || resized.height < 320) throw new AssertionError("Popup resized below usable minimum");
                        screenshot(report.resolve("sources-window.png"));
                        ReleaseScreenshots.capture(panel, report.resolve("sources-window-panel.png"));
                        ((JTextField) SmokeUi.field(pane, "filter")).setText("archive-worker");
                        if (table.getRowCount() != 1) throw new AssertionError("Sorting broke source search");
                        table.getModel().setValueAt(true, table.convertRowIndexToModel(0), 0);
                        ((JTextField) SmokeUi.field(pane, "filter")).setText("");
                        ((JButton) SmokeUi.field(pane, "refresh")).doClick();
                        await(report, () -> popupReady(panel), () -> {
                            if (!((Set<?>) SmokeUi.field(pane, "draft")).containsAll(Set.of("archive-worker", "demo-api")) || !sorted(table, true)) throw new AssertionError("Refresh lost the draft or sort order");
                            button(pane, "Cancel").doClick();
                            if (SmokeUi.loadedRequest(panel) != before || SmokeUi.index(panel).getResult() != index) throw new AssertionError("Window changes applied an uncommitted source draft");
                            open(panel);
                            await(report, () -> popupReady(panel), () -> {
                                SourceFilterPopup reopened = (SourceFilterPopup) SmokeUi.field(panel, "sourcePopup");
                                if (Math.abs(reopened.popup().getSize().width - resized.width) > 3 || Math.abs(reopened.popup().getSize().height - resized.height) > 3) throw new AssertionError("Sources popup size was not restored");
                                button(reopened, "Cancel").doClick();
                                treeChecks(project, panel, report, done);
                            });
                        });
                    });
                });
            });
        });
    }
    private static void treeChecks(Project project, GraylogPanel panel, Path report, Runnable done) throws Exception {
        JTree tree = (JTree) SmokeUi.field(SmokeUi.field(panel, "connections"), "tree");
        SearchRequest baseline = (SearchRequest) SmokeUi.loadedRequest(panel);
        clickEntry(tree, "archive-worker");
        await(report, () -> ready(panel) && ((JTable) SmokeUi.field(panel, "events")).getRowCount() == 1, () -> {
            if (!((String) SmokeUi.field(panel, "selectedStream")).isEmpty() || !((SearchRequest) SmokeUi.loadedRequest(panel)).from().equals(baseline.from())) throw new AssertionError("Checking source changed the active stream or period");
            clickEntry(tree, "demo-worker");
            await(report, () -> ready(panel) && ((JTable) SmokeUi.field(panel, "events")).getRowCount() == 3, () -> {
                if (!((SourceFilter) SmokeUi.field(panel, "sourceFilter")).values().equals(Set.of("archive-worker", "demo-worker"))) throw new AssertionError("Sidebar does not support multiple sources");
                if (((GraylogApi.PeriodIndex) SmokeUi.index(panel).getResult()).messages() != 3) throw new AssertionError("Tree filter did not scope the exception index");
                screenshot(report.resolve("sources-tree.png")); ReleaseScreenshots.capture(panel, report.resolve("sources-tree-panel.png")); open(panel);
                await(report, () -> popupReady(panel), () -> {
                    SourceFilterPopup popup = (SourceFilterPopup) SmokeUi.field(panel, "sourcePopup");
                    if (!((Set<?>) SmokeUi.field(popup, "draft")).equals(Set.of("archive-worker", "demo-worker"))) throw new AssertionError("Tree selection was not reflected in the popup");
                    button(popup, "Cancel").doClick(); clickEntry(tree, null);
                    await(report, () -> ready(panel) && ((JTable) SmokeUi.field(panel, "events")).getRowCount() == 100, () -> {
                        Object request = SmokeUi.loadedRequest(panel), periodIndex = SmokeUi.index(panel).getResult();
                        JTextField filter = (JTextField) SmokeUi.field(SmokeUi.field(panel, "connections"), "connectionFilter"); filter.setText("archive-worker");
                        if (entry(tree, "archive-worker") == null || entry(tree, "demo-worker") != null || SmokeUi.loadedRequest(panel) != request || SmokeUi.index(panel).getResult() != periodIndex) throw new AssertionError("Sidebar search changed logs or did not filter source names");
                        filter.setText("");
                        TreePath group = group(tree); tree.collapsePath(group);
                        open(panel);
                        await(report, () -> popupReady(panel), () -> {
                            if (tree.isExpanded(group(tree))) throw new AssertionError("Facet refresh expanded a manually collapsed source group");
                            button((SourceFilterPopup) SmokeUi.field(panel, "sourcePopup"), "Cancel").doClick(); tree.expandPath(group(tree));
                            selectStream(tree, "Workers");
                            await(report, () -> ready(panel) && !(Boolean) SmokeUi.sources(panel).getLoading(), () -> {
                                if (entry(tree, "demo-worker") == null || entry(tree, "archive-worker") != null || entry(tree, "demo-api") != null) throw new AssertionError("Sources leaked from another stream");
                                selectStream(tree, "All messages");
                                await(report, () -> ready(panel) && !(Boolean) SmokeUi.sources(panel).getLoading() && entry(tree, "archive-worker") != null, () -> {
                                    Files.writeString(report.resolve("source-tree-result.txt"), "PASS: Sources in the active connection tree share server-wide counts, multi-selection, query/stream/time scope and exception index with the popup. Source name search preserves loaded logs; collapsed groups survive updates. Actual title drag, edge resize, minimum/persisted size, numeric header sorting and sorted checkbox mapping passed.\n"); done.run();
                                });
                            });
                        });
                    });
                });
            });
        });
    }
    private static boolean sorted(JTable table, boolean descending) {
        if (table.getRowCount() < 2) return false;
        for (int i = 1; i < table.getRowCount(); i++) { long a = (long) table.getValueAt(i - 1, 2), b = (long) table.getValueAt(i, 2); if (descending ? a < b : a > b) return false; } return true;
    }
    private static void clickHeader(JTable table, int column) throws Exception {
        phase = "sorting column " + column;
        Timer timer = new Timer(650, e -> { try { Rectangle r = table.getTableHeader().getHeaderRect(column); Point p = table.getTableHeader().getLocationOnScreen(); click(p.x + r.x + r.width / 2, p.y + r.height / 2); } catch (Exception failure) { throw new IllegalStateException(failure); } });
        timer.setRepeats(false); timer.start();
    }
    private static void drag(int x, int y, int dx, int dy) throws Exception { phase = "drag/resize at " + x + "," + y + " delta " + dx + "," + dy; Robot r = new Robot(); r.mouseMove(x, y); r.mousePress(InputEvent.BUTTON1_DOWN_MASK); for (int i = 1; i <= 8; i++) { r.mouseMove(x + dx * i / 8, y + dy * i / 8); r.delay(15); } r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); }
    private static void click(int x, int y) throws Exception { Robot r = new Robot(); r.mouseMove(x, y); r.mousePress(InputEvent.BUTTON1_DOWN_MASK); r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); }
    private static void clickEntry(JTree tree, String name) throws Exception { phase = "checking tree source " + name; TreePath path = entry(tree, name); if (path == null) throw new AssertionError("Missing tree source " + name); tree.scrollPathToVisible(path); Rectangle r = tree.getPathBounds(path); Point p = tree.getLocationOnScreen(); click(p.x + r.x + 8, p.y + r.y + r.height / 2); }
    private static TreePath entry(JTree tree, String name) { var root = (DefaultMutableTreeNode) tree.getModel().getRoot(); var all = root.depthFirstEnumeration(); while (all.hasMoreElements()) { var n = (DefaultMutableTreeNode) all.nextElement(); if (n.getUserObject() instanceof SourceTree.Entry entry && Objects.equals(entry.name(), name)) return new TreePath(n.getPath()); } return null; }
    private static TreePath group(JTree tree) { var all = ((DefaultMutableTreeNode) tree.getModel().getRoot()).depthFirstEnumeration(); while (all.hasMoreElements()) { var n = (DefaultMutableTreeNode) all.nextElement(); if (n.getUserObject() instanceof SourceTree.Group) return new TreePath(n.getPath()); } throw new AssertionError("Source group missing"); }
    private static void selectStream(JTree tree, String name) { var all = ((DefaultMutableTreeNode) tree.getModel().getRoot()).depthFirstEnumeration(); while (all.hasMoreElements()) { var n = (DefaultMutableTreeNode) all.nextElement(); if (n.toString().equals(name) && !(n.getUserObject() instanceof SourceTree.Entry)) { tree.setSelectionPath(new TreePath(n.getPath())); return; } } throw new AssertionError("Missing stream " + name); }
    private static boolean ready(GraylogPanel panel) { return !(SmokeUi.search(panel).getPhase() == SearchController.Phase.LOADING) && !(Boolean) (SmokeUi.index(panel).getPhase() == PeriodIndexController.Phase.LOADING) && SmokeUi.index(panel).getResult() != null; }
    private static boolean popupReady(GraylogPanel panel) { Object pane = SmokeUi.field(panel, "sourcePopup"); return pane != null && ((JButton) SmokeUi.field(pane, "refresh")).isEnabled(); }
    private static void open(GraylogPanel panel) throws Exception { AbstractButton b = sourceButton(panel); Point p = b.getLocationOnScreen(); click(p.x + b.getWidth() / 2, p.y + b.getHeight() / 2); }
    private static AbstractButton sourceButton(Container c) { if (c instanceof AbstractButton b && b.getText() != null && b.getText().startsWith("Source")) return b; for (Component child : c.getComponents()) if (child instanceof Container cc) { var b = sourceButton(cc); if (b != null) return b; } return null; }
    private static JButton button(SourceFilterPopup pane, String text) { return button((Container) SmokeUi.field(pane, "body"), text); }
    private static JButton button(Container parent, String text) { for (Component c : parent.getComponents()) { if (c instanceof JButton b && text.equals(b.getText())) return b; if (c instanceof Container cc) { var b = button(cc, text); if (b != null) return b; } } return null; }

    @FunctionalInterface private interface Step { void run() throws Exception; }
    private static void await(Path report, BooleanSupplier condition, Step step) { long deadline = System.currentTimeMillis() + 20000; Timer timer = new Timer(150, null); timer.addActionListener(e -> { try { if (condition.getAsBoolean()) { timer.stop(); step.run(); } else if (System.currentTimeMillis() > deadline) throw new AssertionError("Source tree/window scenario timed out: " + phase); } catch (Throwable failure) { timer.stop(); fail(report, failure); } }); timer.start(); }
    private static void screenshot(Path file) throws Exception { ImageIO.write(new Robot().createScreenCapture(new Rectangle(0, 0, 1780, 1060)), "png", file.toFile()); }
    private static void fail(Path report, Throwable error) { try { screenshot(report.resolve("source-tree-failure.png")); var trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace)); Files.writeString(report.resolve("result.txt"), "FAIL: " + trace); } catch (Exception ignored) {} }
}
