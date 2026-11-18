package dev.graylog.jetbrains;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.nio.file.*;

/** Drag the actual divider and restore its saved layout through the native splitter API. */
final class MessageInspectorSmoke {
    static void run(Project project, GraylogPanel panel, Path report, Runnable done) {
        try {
            EventInspector inspector = (EventInspector) SmokeUi.component(panel, "explorer.inspector");
            OnePixelSplitter split = (OnePixelSplitter) SmokeUi.component(panel, "inspector.messageSplitter");
            JTextArea message = (JTextArea) SmokeUi.component(panel, "inspector.message");
            JComponent viewport = (JComponent) SmokeUi.component(panel, "inspector.messagePane");
            var page = SmokeUi.search(panel).getPage(); var index = SmokeUi.index(panel).getResult();
            Connection connection = page.getSession().getConfig().server().connection();
            LogEvent original = panel.viewState().selected(); SearchRequest request = SmokeUi.loadedRequest(panel);
            float previous = split.getProportion();
            String sql = "SELECT\n    item.id,\n    item.offer_id,\n    item.send_until,\n    item.seller_id,\n    item.settlement,\n    item.warranty\nFROM demo_items item\nJOIN demo_offers offer ON offer.id = item.offer_id\nWHERE item.status = 'ready'\n  AND offer.enabled = 1\nORDER BY item.id\nLIMIT 100;";
            JsonObject fields = new JsonObject(); fields.addProperty("message", sql); fields.addProperty("level", 6);
            inspector.show(new LogEvent("message-resize", "fixture", fields), connection, request);
            later(report, () -> {
                int before = viewport.getHeight();
                Component divider = split.getDivider(); Point start = divider.getLocationOnScreen();
                Point origin = split.getLocationOnScreen();
                int x = start.x + divider.getWidth()/2, y = start.y + divider.getHeight()/2;
                Robot robot = new Robot(); robot.mouseMove(x, y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseMove(x, y + 5); robot.mouseMove(x, origin.y + (int) (split.getHeight() * .68));
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                later(report, () -> {
                    if (viewport.getHeight() < before + JBUI.scale(60)) throw new AssertionError("Dragging did not expand the message viewport: " + before + " -> " + viewport.getHeight());
                    if (split.getSecondComponent().getHeight() < JBUI.scale(90)) throw new AssertionError("Message resize hid the details tabs");
                    if (!sql.equals(message.getText()) || message.isEditable()) throw new AssertionError("Resize changed the read-only log message");
                    message.selectAll(); if (!sql.equals(message.getSelectedText())) throw new AssertionError("Full message cannot be selected for copying"); message.select(0, 0);
                    OnePixelSplitter restored = new OnePixelSplitter(true, split.getSplitterProportionKey(), .28f);
                    try {
                        restored.setAndLoadSplitterProportionKey(split.getSplitterProportionKey());
                        if (Math.abs(restored.getProportion() - split.getProportion()) > .01f) throw new AssertionError("Message height preference was not restored");
                    } finally { restored.dispose(); }
                    if (SmokeUi.search(panel).getPage() != page || SmokeUi.index(panel).getResult() != index) throw new AssertionError("Resizing changed search or exception index");
                    ReleaseScreenshots.capture(inspector, report.resolve("message-expanded.png"));
                    split.setProportion(previous); inspector.show(original, connection, request);
                    Files.writeString(report.resolve("message-resize-result.txt"), "PASS: Actual mouse drag expands a long SQL message; details remain usable, full text is selectable, layout persists through native splitter recreation, search/index remain unchanged.\n");
                    later(report, done::run);
                });
            });
        } catch (Throwable error) { fail(report, error); }
    }
    @FunctionalInterface private interface Step { void run() throws Exception; }
    private static void later(Path report, Step step) { Timer timer = new Timer(350, e -> { try { step.run(); } catch (Throwable error) { fail(report, error); } }); timer.setRepeats(false); timer.start(); }
    private static void fail(Path report, Throwable error) { try { var trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace)); Files.writeString(report.resolve("result.txt"), "FAIL: " + trace); } catch (Exception ignored) {} }
}
