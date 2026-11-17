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
        if (parent instanceof ActionToolbar toolbar) for (AnAction action : toolbar.getActions()) {
            if (name.equals(action.getTemplatePresentation().getText())) return action;
        }
        for (Component component : parent.getComponents()) if (component instanceof Container child) {
            AnAction result = findAction(child, name); if (result != null) return result;
        } return null;
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
