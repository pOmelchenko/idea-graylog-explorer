package dev.graylog.jetbrains;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.icons.AllIcons;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.util.ui.JBUI;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;

/** Compare the rail with the same unmodified platform toolbar used by Git Log. */
final class RailSmoke {
    static void run(Project project, GraylogPanel panel, Path report, Runnable done) {
        try {
            Object loaded = SmokeUi.loadedRequest(panel), index = SmokeUi.index(panel).getResult();
            JTree tree = (JTree) SmokeUi.field(SmokeUi.field(panel, "connections"), "tree");
            JTextField query = (JTextField) SmokeUi.field(panel, "query"); String text = query.getText();
            int expandedRows = tree.getRowCount();
            SmokeUi.clickToolbar(panel, "Collapse all connections");
            if (tree.getRowCount() >= expandedRows) throw new AssertionError("Collapse all did not collapse the navigation tree");
            SmokeUi.clickToolbar(panel, "Expand all connections");
            if (tree.getRowCount() != expandedRows || !tree.getLastSelectedPathComponent().toString().equals("All messages")) throw new AssertionError("Expand all did not restore the active tree selection");
            sameScope(panel, loaded, index, query, text);
            ActionToolbar actual = rail(panel);
            ActionToolbar reference = ActionManager.getInstance().createActionToolbar("Git.Log.Branches", new DefaultActionGroup(new DumbAwareAction("Reference", null, AllIcons.General.Add) {
                @Override public void actionPerformed(AnActionEvent event) { }
            }), false);
            reference.setTargetComponent(panel);
            if (actual == null || !actual.getMinimumButtonSize().equals(reference.getMinimumButtonSize())
                    || !actual.getComponent().getInsets().equals(reference.getComponent().getInsets())) throw new AssertionError("Rail overrides native Git toolbar sizing/insets");
            Component navigation = (Component) SmokeUi.field(panel, "connections");
            Point origin = panel.getLocationOnScreen();
            Rectangle bounds = new Rectangle(origin.x, origin.y - JBUI.scale(32), navigation.getX() + actual.getComponent().getWidth() + navigation.getWidth(), panel.getHeight() + JBUI.scale(32));
            ImageIO.write(new Robot().createScreenCapture(bounds), "png", report.resolve("navigation-expanded.png").toFile());
            SmokeUi.clickToolbar(panel, "Show/hide connections");
            OnePixelSplitter splitter = (OnePixelSplitter) SmokeUi.field(panel, "navigationSplitter");
            if (splitter.getFirstComponent() != null || PropertiesComponent.getInstance(project).getBoolean("graylog.explorer.connections.visible", true)) throw new AssertionError("Sidebar hiding did not update the layout and saved preference");
            later(report, () -> {
                ImageIO.write(new Robot().createScreenCapture(new Rectangle(0, 0, 1780, 1060)), "png", report.resolve("navigation-collapsed.png").toFile());
                SmokeUi.clickToolbar(panel, "Show/hide connections");
                later(report, () -> {
                    if (splitter.getFirstComponent() != navigation || !PropertiesComponent.getInstance(project).getBoolean("graylog.explorer.connections.visible", true)) throw new AssertionError("Sidebar did not reopen");
                    sameScope(panel, loaded, index, query, text);
                    Files.writeString(report.resolve("navigation-result.txt"), "PASS: Rail minimum button size and native insets equal Git.Log.Branches reference toolbar. Collapse/expand restores tree selection. Hiding/showing the sidebar preserves the query, loaded page and full-period exception index.\nActual rail width: " + actual.getComponent().getWidth() + ", button minimum: " + actual.getMinimumButtonSize() + ", insets: " + actual.getComponent().getInsets() + "\n");
                    SourcesSmoke.run(project, panel, report, done);
                });
            });
        } catch (Throwable error) { fail(report, error); }
    }
    private static void sameScope(GraylogPanel panel, Object loaded, Object index, JTextField query, String text) {
        if (SmokeUi.loadedRequest(panel) != loaded || SmokeUi.index(panel).getResult() != index || !query.getText().equals(text)) throw new AssertionError("Navigation action changed the search or exception index");
    }
    private static ActionToolbar rail(Container parent) {
        if (parent instanceof ActionToolbar toolbar && toolbar.getPlace().equals("Graylog.ActionRail")) return toolbar;
        for (Component component : parent.getComponents()) if (component instanceof Container child) { ActionToolbar result = rail(child); if (result != null) return result; }
        return null;
    }

    @FunctionalInterface private interface Step { void run() throws Exception; }
    private static void later(Path report, Step step) { Timer timer = new Timer(500, e -> { try { step.run(); } catch (Throwable error) { fail(report, error); } }); timer.setRepeats(false); timer.start(); }
    private static void fail(Path report, Throwable error) { try { var trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace)); Files.writeString(report.resolve("result.txt"), "FAIL: " + trace); } catch (Exception ignored) { } }
}
