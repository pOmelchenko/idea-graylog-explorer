package dev.graylog.jetbrains;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.ui.popup.JBPopup;
import javax.swing.JComponent;
import java.util.function.*;

final class SourceFilterAction extends ComboBoxAction implements DumbAware {
    private final Supplier<String> label;
    private final BooleanSupplier enabled;
    private final BiFunction<JComponent, Runnable, JBPopup> popup;
    SourceFilterAction(Supplier<String> label, BooleanSupplier enabled, BiFunction<JComponent, Runnable, JBPopup> popup) {
        this.label = label; this.enabled = enabled; this.popup = popup;
        getTemplatePresentation().setText("Sources"); setSmallVariant(true);
    }
    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
    @Override public void update(AnActionEvent event) {
        event.getPresentation().setText(label.get()); event.getPresentation().setEnabled(enabled.getAsBoolean());
        event.getPresentation().setDescription("Filter sources across the selected period, stream and query");
    }
    @Override protected JBPopup createActionPopup(DataContext context, JComponent component, Runnable onDispose) { return popup.apply(component, onDispose); }
}
