package dev.graylog.jetbrains;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import javax.swing.JLabel;

/** Test-only stand-in for the prototype's duplicate tool-window/action registrations. */
public final class LegacyConflictFixture implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(Project project, ToolWindow window) {
        var content = window.getContentManager().getFactory().createContent(new JLabel("Legacy prototype fixture"), "Legacy", false);
        window.getContentManager().addContent(content);
    }
    public static final class Search extends DumbAwareAction {
        @Override public void actionPerformed(AnActionEvent event) { }
    }
}
