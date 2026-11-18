package dev.graylog.jetbrains;

import com.google.gson.JsonObject;
import javax.swing.*;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;

/** Raster checks exercise the actual table renderer after sort/filter/selection changes. */
final class SourceBandsSmoke {
    static void run(Path report) throws Exception {
        IdeOptions options = new IdeOptions(); options.sourceColors.put("api", "#E49A49"); options.sourceColors.put("worker", "#E49A49");
        Color color = Color.decode("#E49A49");
        EventsTable table = new EventsTable(() -> options, event -> {}, event -> {});
        table.show(List.of(event("a", "api"), event("b", "worker"), event("c", "api")), "source", "", false);
        edge(table, 0, false, false, color); edge(table, 1, false, false, color);
        table.getRowSorter().toggleSortOrder(2);
        edge(table, 0, false, true, color); edge(table, 1, true, false, color);
        table.setRowSelectionInterval(1, 1); edge(table, 1, true, false, color);
        table.getRowSorter().setSortKeys(null);
        ((TableRowSorter<?>) table.getRowSorter()).setRowFilter(new RowFilter<javax.swing.table.TableModel, Integer>() {
            @Override public boolean include(Entry<? extends javax.swing.table.TableModel, ? extends Integer> entry) { return entry.getStringValue(2).equals("api"); }
        });
        if (table.getRowCount() != 2) throw new AssertionError("Filter did not create adjacent visible source rows");
        edge(table, 0, false, true, color); edge(table, 1, true, false, color);
        options.sourceStripes = false;
        if (pixel(table, 0, table.getRowHeight() / 2).equals(color)) throw new AssertionError("Disabled source stripe remains visible");
        captureSegments(report);
        Files.writeString(report.resolve("source-bands-result.txt"), "PASS: Continuous source segments use visible sort/filter order, keep selected rows joined, separate different sources sharing one color, preserve end gaps and respect disabling.\n");
    }
    private static void captureSegments(Path report) throws Exception {
        IdeOptions display = new IdeOptions(); display.sourceColors.put("api", "#E49A49"); display.sourceColors.put("worker", "#529D72"); display.sourceColors.put("scheduler", "#9A6DD7");
        EventsTable table = new EventsTable(() -> display, event -> {}, event -> {});
        String[] sources = {"api", "api", "api", "worker", "worker", "api", "api", "scheduler"};
        var events = new java.util.ArrayList<LogEvent>();
        for (int i = 0; i < sources.length; i++) {
            JsonObject fields = new JsonObject(); fields.addProperty("source", sources[i]); fields.addProperty("level", 6);
            fields.addProperty("timestamp", "2026-01-01T12:00:0" + i + "Z");
            fields.addProperty("message", "Synthetic event " + (i+1)); events.add(new LogEvent("band-" + i, "fixture", fields));
        }
        table.show(events, "source", "", false); table.setRowSelectionInterval(1, 1);
        JPanel surface = new JPanel(new BorderLayout()); surface.add(table.getTableHeader(), BorderLayout.NORTH); surface.add(table, BorderLayout.CENTER);
        surface.setSize(com.intellij.util.ui.JBUI.scale(900), table.getRowHeight()*sources.length + table.getTableHeader().getPreferredSize().height);
        surface.doLayout(); table.doLayout();
        BufferedImage image = new BufferedImage(surface.getWidth(), surface.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); surface.paint(graphics); graphics.dispose();
        javax.imageio.ImageIO.write(image, "png", report.resolve("source-bands.png").toFile());
    }
    private static LogEvent event(String id, String source) { JsonObject fields = new JsonObject(); fields.addProperty("source", source); fields.addProperty("level", 6); return new LogEvent(id, "fixture", fields); }
    private static void edge(JTable table, int row, boolean previous, boolean next, Color color) {
        if (pixel(table, row, 0).equals(color) != previous || pixel(table, row, table.getRowHeight()-1).equals(color) != next)
            throw new AssertionError("Incorrect source segment endpoints at visible row " + row);
        if (!pixel(table, row, table.getRowHeight()/2).equals(color)) throw new AssertionError("Missing source segment body");
    }
    private static Color pixel(JTable table, int row, int y) {
        Component renderer = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
        int width = com.intellij.util.ui.JBUI.scale(140); renderer.setSize(width, table.getRowHeight());
        BufferedImage image = new BufferedImage(width, table.getRowHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); renderer.paint(graphics); graphics.dispose();
        return new Color(image.getRGB(com.intellij.util.ui.JBUI.scale(4), y));
    }
}
