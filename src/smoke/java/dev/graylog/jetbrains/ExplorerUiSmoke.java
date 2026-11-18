package dev.graylog.jetbrains;

import com.intellij.openapi.project.Project;
import com.intellij.ui.tabs.JBTabs;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;

/** Exercises the reported focus, completion, mapping and source-color regressions. */
final class ExplorerUiSmoke {
    static void run(Project project, GraylogPanel panel, Path report, Runnable done) {
        try {
            Object request = SmokeUi.loadedRequest(panel), index = SmokeUi.index(panel).getResult();
            GraylogIdeConfigurable settings = new GraylogIdeConfigurable(); settings.createComponent(); settings.reset();
            JTable table = (JTable) SmokeUi.component(panel, "explorer.events"); String source = table.getValueAt(0, 2).toString();
            for (int column : new int[]{2, 3}) {
                String markup = "<html><b>literal log data</b>";
                var cell = (JLabel) table.getCellRenderer(0, column).getTableCellRendererComponent(table, markup, false, false, 0, column);
                if (!markup.equals(cell.getText()) || cell.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) != null)
                    throw new AssertionError("Log cell interpreted message/source data as Swing HTML");
            }
            var fieldCell = ExplorerUi.cells();
            fieldCell.getTableCellRendererComponent(new JTable(1, 1), "<html><b>literal field data</b>", false, false, 0, 0);
            if (fieldCell.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) != null)
                throw new AssertionError("Field cell interpreted log data as Swing HTML");
            SmokeUi.addSourceColor(settings, source, "#C05090"); settings.apply(); settings.disposeUIResources();
            later(report, 250, () -> {
                if (SmokeUi.loadedRequest(panel) != request || SmokeUi.index(panel).getResult() != index) throw new AssertionError("Source color change restarted the search or index");
                if (!GraylogIdeSettings.get().getState().options.sourceColors.get(source).equals("#C05090")) throw new AssertionError("Source color override not saved");
                checkStripe(table, 0, Color.decode("#C05090"));
                table.clearSelection(); checkStripe(table, 0, Color.decode("#C05090")); table.setRowSelectionInterval(0, 0);
                table.getColumnModel().moveColumn(2, 0); checkStripe(table, 0, Color.decode("#C05090"));
                if (((JLabel) table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0)).getIcon() == null) throw new AssertionError("Source marker did not follow the reordered Service column");
                table.getColumnModel().moveColumn(0, 2);
                var tabs = (JBTabs) SmokeUi.tabs(panel); tabs.select(tabs.getTabAt(1), false);
                if (contains(tabs.getTabAt(1).getComponent(), (Component) SmokeUi.component(panel, "inspector.rawStack"))) throw new AssertionError("Stack trace still duplicates the exception text");
                if (!contains(tabs.getTabAt(3).getComponent(), (Component) SmokeUi.component(panel, "inspector.rawStack"))) throw new AssertionError("Separate exception-text tab missing");
                if (!((JLabel) SmokeUi.component(panel, "inspector.sourceMapping")).getText().contains(project.getBasePath())) throw new AssertionError("Local source mapping not visible");
                JTree tree = (JTree) SmokeUi.component(panel, "connections.tree"); click(tree, tree.getRowBounds(tree.getLeadSelectionRow()).x + 40, tree.getRowBounds(tree.getLeadSelectionRow()).y + 10);
                later(report, 600, () -> {
                    if (!tree.hasFocus()) throw new AssertionError("Tree did not receive focus");
                    screenshot(report.resolve("tree-focused.png"));
                    JTextField query = (JTextField) SmokeUi.component(panel, "explorer.query"); click(query, 80, query.getHeight() / 2);
                    later(report, 400, () -> {
                        if (tree.hasFocus() || !query.hasFocus()) throw new AssertionError("Unfocused tree state not reached");
                        screenshot(report.resolve("tree-unfocused.png"));
                        query.setText("le"); query.setCaretPosition(2);
                        later(report, 500, () -> {
                            QueryCompletion completion = (QueryCompletion) panel.queryCompletion();
                            var popup = (com.intellij.openapi.ui.popup.JBPopup) completion.popup();
                            if (popup == null || !popup.isVisible()) throw new AssertionError("Automatic field suggestions not shown");
                            screenshot(report.resolve("query-completion.png"));
                            press(KeyEvent.VK_TAB);
                            later(report, 300, () -> {
                                if (!query.getText().equals("level:")) throw new AssertionError("Tab did not insert field completion: " + query.getText());
                                if (SmokeUi.loadedRequest(panel) != request) throw new AssertionError("Accepting completion ran a search");
                                query.setText("level: "); query.setCaretPosition(query.getText().length());
                                later(report, 400, () -> {
                                    screenshot(report.resolve("query-level-values.png"));
                                    press(KeyEvent.VK_ESCAPE);
                                    later(report, 200, () -> {
                                        if (completion.popup() != null) throw new AssertionError("Escape did not dismiss completion");
                                        query.setText("message:\"Invalid phone number.\"");
                                        Files.writeString(report.resolve("ui-details-result.txt"), "PASS: Focused/unfocused native tree, automatic field suggestions, Tab insertion, level values, Escape, separate exception text, visible local mapping and persistent per-source stripes verified. Source color Apply retains the loaded search and period index. Log cells display HTML markers literally.\n");
                                        RailSmoke.run(project, panel, report, done);
                                    });
                                });
                            });
                        });
                    });
                });
            });
        } catch (Throwable error) { fail(report, error); }
    }
    private static void checkStripe(JTable table, int row, Color expected) {
        Component renderer = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
        renderer.setSize(120, table.getRowHeight()); BufferedImage image = new BufferedImage(120, table.getRowHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); renderer.paint(graphics); graphics.dispose();
        int stripeX = com.intellij.util.ui.JBUI.scale(4);
        if (!new Color(image.getRGB(stripeX, table.getRowHeight() / 2)).equals(expected)) throw new AssertionError("Source stripe lost its color on selection");
        if (new Color(image.getRGB(0, table.getRowHeight() / 2)).equals(expected)
                || new Color(image.getRGB(stripeX, 0)).equals(expected)) throw new AssertionError("Source stripe touches the cell/splitter edge");
    }
    private static boolean contains(Component parent, Component target) {
        if (parent == target) return true;
        if (parent instanceof Container container) for (Component child : container.getComponents()) if (contains(child, target)) return true;
        return false;
    }
    private static void press(int key) throws Exception { Robot robot = new Robot(); robot.keyPress(key); robot.keyRelease(key); }
    private static void click(Component component, int x, int y) throws Exception { Point origin = component.getLocationOnScreen(); Robot robot = new Robot(); robot.mouseMove(origin.x + x, origin.y + y); robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK); }
    private static void screenshot(Path path) throws Exception { ImageIO.write(new Robot().createScreenCapture(new Rectangle(0, 0, 1780, 1060)), "png", path.toFile()); }
    @FunctionalInterface private interface Step { void run() throws Exception; }
    private static void later(Path report, int delay, Step step) { Timer timer = new Timer(delay, e -> { try { step.run(); } catch (Throwable error) { fail(report, error); } }); timer.setRepeats(false); timer.start(); }

    private static void fail(Path report, Throwable error) { try { screenshot(report.resolve("ui-details-failure.png")); var trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace)); Files.writeString(report.resolve("result.txt"), "FAIL: " + trace + "\nFocus owner: " + KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()); } catch (Exception ignored) { } }
}
