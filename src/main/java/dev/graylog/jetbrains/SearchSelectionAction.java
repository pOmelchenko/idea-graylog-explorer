package dev.graylog.jetbrains;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;

public final class SearchSelectionAction extends DumbAwareAction {
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
    @Override public void update(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        String text = editor == null ? null : editor.getSelectionModel().getSelectedText();
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && text != null && !text.isBlank() && text.length() <= 4096);
    }
    @Override public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;
        String selected = editor.getSelectionModel().getSelectedText();
        if (selected == null || selected.isBlank()) return;
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Graylog");
        if (window == null) return;
        window.activate(() -> {
            for (Content content : window.getContentManager().getContents()) {
                if (content.getComponent() instanceof GraylogPanel panel) {
                    panel.searchText(SearchRequest.quote(selected));
                    break;
                }
            }
        });
    }
}
