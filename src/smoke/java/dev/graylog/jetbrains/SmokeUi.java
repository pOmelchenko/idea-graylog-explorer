package dev.graylog.jetbrains;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationsManager;
import com.intellij.ui.tabs.JBTabs;

import javax.imageio.ImageIO;
import javax.swing.Timer;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Exercises real toolbar actions and captures only the isolated test IDE. */
@SuppressWarnings("removal")
final class SmokeUi {
    static SearchController.State search(GraylogPanel panel) { return panel.viewState().search(); }
    static SearchRequest loadedRequest(GraylogPanel panel) { var page = search(panel).getPage(); return page == null ? null : page.getSession().request(); }
    static PeriodIndexController.State index(Object panel) { return ((GraylogPanel) panel).viewState().index(); }
    static SourceCatalog.State sources(Object panel) { return ((GraylogPanel) panel).viewState().sources(); }
    static JBTabs tabs(GraylogPanel panel) { return ((EventInspector) component(panel, "explorer.inspector")).tabs(); }
    static Component component(Object owner, String id) {
        Container root = owner instanceof GraylogIdeConfigurable c ? (Container) c.component()
                : owner instanceof GraylogProjectConfigurable c ? (Container) c.component()
                : owner instanceof SourceFilterPopup c ? (Container) c.component() : (Container) owner;
        Component result = named(root, id); if (result == null) throw new AssertionError("Component not found: " + id); return result;
    }
    private static Component named(Container root, String id) {
        if (root == null) return null;
        if (id.equals(root.getName())) return root;
        if (root instanceof EventInspector inspector) for (var tab : inspector.tabs().getTabs()) {
            Component found = named(tab.getComponent(), id); if (found != null) return found;
        }
        for (Component child : root.getComponents()) {
            if (id.equals(child.getName())) return child;
            if (child instanceof Container nested) { Component found = named(nested, id); if (found != null) return found; }
        }
        return null;
    }
    record Command(AnAction action) {
        private AnActionEvent event() { var event = AnActionEvent.createFromAnAction(action, null, "GraylogSmoke", DataContext.EMPTY_CONTEXT); action.update(event); return event; }
        void doClick() { var event = event(); if (!event.getPresentation().isEnabled()) throw new AssertionError("Disabled action: " + event.getPresentation().getText()); action.actionPerformed(event); }
        boolean isEnabled() { return event().getPresentation().isEnabled(); }
        String getToolTipText() { return event().getPresentation().getDescription(); }
    }
    static Command command(Container owner, String title) {
        var action = findAction(owner, title); if (action == null) throw new AssertionError("Action not found: " + title); return new Command(action);
    }
    static javax.swing.JTable addSourceColor(Object settings, String source, String color) {
        Container colors = (Container) component(settings, "settings.sourceColors");
        clickToolbar(colors, "Add");
        javax.swing.JTable table = (javax.swing.JTable) component(colors, "sourceColors.table");
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        int row = table.getRowCount() - 1; table.setValueAt(source, row, 0); table.setValueAt(color, row, 1); return table;
    }
    static boolean hasToolbarAction(Container parent, String name) { return findAction(parent, name) != null; }
    static void clickToolbar(Container parent, String name) {
        AnAction action = findAction(parent, name);
        if (action == null) throw new AssertionError("Toolbar action not found: " + name);
        action.actionPerformed(AnActionEvent.createFromAnAction(action, null, "GraylogSmoke", DataContext.EMPTY_CONTEXT));
    }
    static void chooseDropdown(Container parent, String title, String value) throws Exception {
        AnAction action = findAction(parent, title);
        if (action == null) throw new AssertionError("Dropdown not found: " + title);
        var method = action.getClass().getDeclaredMethod("createPopupActionGroup", javax.swing.JComponent.class, DataContext.class);
        method.setAccessible(true);
        DefaultActionGroup choices = (DefaultActionGroup) method.invoke(action, parent, DataContext.EMPTY_CONTEXT);
        for (AnAction choice : choices.getChildActionsOrStubs()) if (value.equals(choice.getTemplatePresentation().getText())) {
            choice.actionPerformed(AnActionEvent.createFromAnAction(choice, null, "GraylogSmoke", DataContext.EMPTY_CONTEXT)); return;
        }
        throw new AssertionError("Dropdown choice not found: " + title + " / " + value);
    }
    private static AnAction findAction(Container parent, String name) {
        if (parent instanceof com.intellij.openapi.actionSystem.impl.ActionButton button
                && name.equals(button.getAction().getTemplatePresentation().getText())) return button.getAction();
        if (parent instanceof ActionToolbar toolbar) {
            AnAction found = findAction(toolbar.getActionGroup(), name); if (found != null) return found;
        }
        for (Component component : parent.getComponents()) if (component instanceof Container child) {
            AnAction result = findAction(child, name); if (result != null) return result;
        } return null;
    }
    private static AnAction findAction(AnAction action, String title) {
        if (title.equals(action.getTemplatePresentation().getText())) return action;
        if (action instanceof ActionGroup group) for (AnAction child : group.getChildren(null)) {
            var found = findAction(child, title); if (found != null) return found;
        }
        return null;
    }
    static void prepare(Project project) throws Exception {
        for (Notification notification : NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification.class, project)) notification.expire();
        for (Notification notification : NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification.class, null)) notification.expire();
        new Robot().mouseMove(1100, 100);
    }
    static void captureTabs(Project project, JBTabs tabs, Path report, Runnable done) {
        capture(project, tabs, 0, report.resolve("explorer-fields.png"), () ->
                capture(project, tabs, 1, report.resolve("explorer-stack.png"), () ->
                        capture(project, tabs, 2, report.resolve("explorer-json.png"), () -> capture(project, tabs, 3, report.resolve("explorer-exception-text.png"), () -> {
                            try { Files.copy(report.resolve("explorer-stack.png"), report.resolve("yii-stack-trace.png"), StandardCopyOption.REPLACE_EXISTING); done.run(); }
                            catch (Exception error) { failure(report, error); }
                        }))));
    }
    private static void capture(Project project, JBTabs tabs, int index, Path file, Runnable done) {
        try { prepare(project); } catch (Exception error) { failure(file.getParent(), error); return; }
        tabs.select(tabs.getTabAt(index), false).doWhenDone(() -> {
            Timer timer = new Timer(600, event -> {
                try { ImageIO.write(new Robot().createScreenCapture(new Rectangle(0, 0, 1780, 1060)), "png", file.toFile()); done.run(); }
                catch (Exception error) { failure(file.getParent(), error); }
            }); timer.setRepeats(false); timer.start();
        });
    }
    private static void failure(Path report, Throwable error) {
        try { java.io.StringWriter s = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(s)); Files.writeString(report.resolve("result.txt"), "FAIL: " + s); }
        catch (Exception ignored) { }
    }
}
