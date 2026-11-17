package dev.graylog.jetbrains;

import com.intellij.openapi.project.Project;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** A source present only past the first page must still be discoverable and filterable. */
final class SourcesSmoke {
    private static String phase = "initial source search";
    static void run(Project project, GraylogPanel panel, Path report, Runnable done) {
        panel.searchText("request_id:\"abc-123\"");
        await(report, () -> ready(panel) && ((JTable) SmokeUi.field(panel, "events")).getRowCount() == 100, () -> {
            SearchRequest baseline = (SearchRequest) SmokeUi.loadedRequest(panel);
            JTable events = (JTable) SmokeUi.field(panel, "events");
            for (int i = 0; i < events.getRowCount(); i++) if (events.getValueAt(i, 2).equals("archive-worker")) throw new AssertionError("Fixture's hidden source reached the first page");
            open(panel);
            await(report, () -> catalogReady(panel), () -> {
                Object popup = SmokeUi.field(panel, "sourcePopup");
                @SuppressWarnings("unchecked") java.util.List<GraylogApi.SourceValue> available = (java.util.List<GraylogApi.SourceValue>) SmokeUi.field(popup, "available");
                if (available.size() != 4 || available.stream().noneMatch(value -> value.name().equals("archive-worker") && value.count() == 1)) throw new AssertionError("Server aggregation omitted the source outside the first page");
                screenshot(report.resolve("sources-filter.png"));
                toggle(popup, "archive-worker"); toggle(popup, "demo-worker");
                button((Container) SmokeUi.field(popup, "body"), "Apply").doClick();
                await(report, () -> ready(panel) && events.getRowCount() == 3, () -> {
                    SearchRequest selected = (SearchRequest) SmokeUi.loadedRequest(panel);
                    if (!selected.query().contains("source:\"archive-worker\" OR source:\"demo-worker\"") || !selected.from().equals(baseline.from()) || !selected.to().equals(baseline.to())) throw new AssertionError("Source OR filter lost its query/time window");
                    for (int i = 0; i < events.getRowCount(); i++) if (!Set.of("archive-worker", "demo-worker").contains(events.getValueAt(i, 2))) throw new AssertionError("Filtered table contains another source");
                    GraylogApi.PeriodIndex index = (GraylogApi.PeriodIndex) SmokeUi.index(panel).getResult();
                    if (index.messages() != 3 || index.exceptions() != 1 || index.sites().size() != 1) throw new AssertionError("Whole-period exception marks did not follow the source filter");
                    screenshot(report.resolve("sources-selected.png"));
                    SmokeUi.chooseDropdown(panel, "Page size", "250");
                    await(report, () -> ready(panel) && ((SearchRequest) SmokeUi.loadedRequest(panel)).limit() == 250, () -> {
                        if (SmokeUi.index(panel).getResult() != index || events.getRowCount() != 3) throw new AssertionError("Page size lost the source filter or restarted the period index");
                        open(panel);
                        await(report, () -> catalogReady(panel), () -> {
                            Object again = SmokeUi.field(panel, "sourcePopup");
                            if (((java.util.List<?>) SmokeUi.field(again, "available")).size() != 4) throw new AssertionError("Sources list was narrowed by its own selection");
                            button((Container) SmokeUi.field(again, "body"), "All sources").doClick();
                            await(report, () -> ready(panel) && events.getRowCount() == 128, () -> {
                                if (!((SearchRequest) SmokeUi.loadedRequest(panel)).query().equals(baseline.query())) throw new AssertionError("Clearing sources did not restore the base query");
                                Object beforeCancel = SmokeUi.loadedRequest(panel), indexBeforeCancel = SmokeUi.index(panel).getResult();
                                open(panel);
                                await(report, () -> catalogReady(panel), () -> {
                                    Object cancelled = SmokeUi.field(panel, "sourcePopup"); toggle(cancelled, "archive-worker");
                                    button((Container) SmokeUi.field(cancelled, "body"), "Cancel").doClick();
                                    if (SmokeUi.loadedRequest(panel) != beforeCancel || SmokeUi.index(panel).getResult() != indexBeforeCancel || !((SourceFilter) SmokeUi.field(panel, "sourceFilter")).values().isEmpty()) throw new AssertionError("Cancel applied a source draft");
                                    SmokeUi.chooseDropdown(panel, "Page size", "100");
                                    await(report, () -> ready(panel) && events.getRowCount() == 100, () -> {
                                        Files.writeString(report.resolve("sources-result.txt"), "PASS: Aggregation discovered archive-worker beyond the first 100 rows. Multi-source OR returned three server-filtered events and one exception origin. Page size retained the filter/index; the facet excluded its own selection. Clear and Cancel preserved base query/time and restored all 128 events.\n"); SourceTreeSmoke.run(project, panel, report, done);
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
    }
    private static boolean ready(GraylogPanel panel) { return !(SmokeUi.search(panel).getPhase() == SearchController.Phase.LOADING) && !(Boolean) (SmokeUi.index(panel).getPhase() == PeriodIndexController.Phase.LOADING) && SmokeUi.index(panel).getResult() != null; }
    private static boolean catalogReady(GraylogPanel panel) { Object pane = SmokeUi.field(panel, "sourcePopup"); return pane != null && ((JButton) SmokeUi.field(pane, "refresh")).isEnabled(); }
    private static void open(GraylogPanel panel) throws Exception {
        phase = "opening native Sources popup"; SmokeUi.prepare((Project) SmokeUi.field(panel, "project"));
        AbstractButton source = sourceButton(panel); if (source == null) throw new AssertionError("Native Sources dropdown missing");
        Point point = source.getLocationOnScreen(); Robot robot = new Robot(); robot.mouseMove(point.x + source.getWidth() / 2, point.y + source.getHeight() / 2); robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
    }
    private static AbstractButton sourceButton(Container parent) {
        if (parent instanceof AbstractButton b && b.getText() != null && b.getText().startsWith("Source")) return b;
        for (Component child : parent.getComponents()) if (child instanceof Container container) { var found = sourceButton(container); if (found != null) return found; } return null;
    }
    private static void toggle(Object popup, String source) throws Exception {
        JTextField filter = (JTextField) SmokeUi.field(popup, "filter"); filter.setText(source);
        JTable list = (JTable) SmokeUi.field(popup, "table");
        if (list.getRowCount() != 1) throw new AssertionError("Source name search did not narrow the popup");
        var method = popup.getClass().getDeclaredMethod("toggle", int.class); method.setAccessible(true); method.invoke(popup, 0); filter.setText("");
    }
    private static JButton button(Container parent, String text) { for (Component child : parent.getComponents()) { if (child instanceof JButton b && text.equals(b.getText())) return b; if (child instanceof Container container) { JButton found = button(container, text); if (found != null) return found; } } return null; }

    @FunctionalInterface private interface Step { void run() throws Exception; }
    private static void await(Path report, BooleanSupplier ready, Step step) { long deadline = System.currentTimeMillis() + 20000; Timer timer = new Timer(150, null); timer.addActionListener(e -> { try { if (ready.getAsBoolean()) { timer.stop(); step.run(); } else if (System.currentTimeMillis() > deadline) { timer.stop(); throw new AssertionError("Source filter test timed out: " + phase); } } catch (Throwable error) { timer.stop(); fail(report, error); } }); timer.start(); }
    private static void screenshot(Path file) throws Exception { ImageIO.write(new Robot().createScreenCapture(new Rectangle(0, 0, 1780, 1060)), "png", file.toFile()); }
    private static void fail(Path report, Throwable error) { try { screenshot(report.resolve("sources-failure.png")); var trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace)); Files.writeString(report.resolve("result.txt"), "FAIL: " + trace); } catch (Exception ignored) { } }
}
