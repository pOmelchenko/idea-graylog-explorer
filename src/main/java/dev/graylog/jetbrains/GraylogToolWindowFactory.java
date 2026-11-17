package dev.graylog.jetbrains;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;

public final class GraylogToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        GraylogPanel panel = new GraylogPanel(project, toolWindow);
        Content content = toolWindow.getContentManager().getFactory().createContent(panel, "Explorer", false);
        content.setDisposer(panel);
        content.setPreferredFocusableComponent(panel.searchField());
        toolWindow.getContentManager().addContent(content);
    }
}
