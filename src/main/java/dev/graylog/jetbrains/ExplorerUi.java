package dev.graylog.jetbrains;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.icons.AllIcons;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.fields.ExtendableTextField;
import com.intellij.ui.components.fields.ExtendableTextComponent;
import javax.swing.event.DocumentEvent;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.function.BooleanSupplier;

/** Shared native tool-window surfaces and adapters for the Explorer's command models. */
final class ExplorerUi {
    static final Color ERROR_COLOR = new JBColor(new Color(175, 41, 45), new Color(255, 113, 119));
    static final Color WARN_COLOR = new JBColor(new Color(145, 99, 10), new Color(231, 181, 73));
    private ExplorerUi() { }
    static Icon colorIcon(Color color) {
        return new Icon() {
            @Override public int getIconWidth() { return JBUI.scale(10); }
            @Override public int getIconHeight() { return JBUI.scale(10); }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) { g.setColor(color); g.fillRoundRect(x + 1, y + 1, JBUI.scale(8), JBUI.scale(8), JBUI.scale(2), JBUI.scale(2)); }
        };
    }

    static JBScrollPane scroll(JComponent content) {
        JBScrollPane pane = new JBScrollPane(content);
        pane.setBorder(JBUI.Borders.empty()); pane.setViewportBorder(JBUI.Borders.empty());
        pane.getViewport().setBackground(content.getBackground()); return pane;
    }
    static JPanel bar(JComponent main, JComponent right, boolean topSeparator) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), topSeparator ? 1 : 0, 0, topSeparator ? 0 : 1, 0),
                JBUI.Borders.empty(3, 8)));
        if (main != null) panel.add(main, BorderLayout.CENTER);
        if (right != null) panel.add(right, BorderLayout.EAST);
        return panel;
    }
    static JComponent toolbar(String place, JComponent target, AnAction... actions) {
        return toolbar(place, target, true, actions);
    }
    static JComponent toolbar(String place, JComponent target, boolean horizontal, AnAction... actions) {
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(place, new DefaultActionGroup(actions), horizontal);
        toolbar.setTargetComponent(target);
        JComponent component = toolbar.getComponent();
        if (horizontal) { component.setOpaque(false); component.setBorder(JBUI.Borders.empty()); }
        return component;
    }
    static AnAction command(String title, Icon icon, Runnable command, BooleanSupplier enabled) {
        return new DumbAwareAction(title, title, icon) {
            @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void update(AnActionEvent event) { event.getPresentation().setEnabled(enabled.getAsBoolean()); }
            @Override public void actionPerformed(AnActionEvent event) { if (enabled.getAsBoolean()) command.run(); }
        };
    }
    static AnAction button(AbstractButton model, Icon icon, boolean text) { return button(model, icon, text, () -> true); }
    static AnAction button(AbstractButton model, Icon icon, boolean text, BooleanSupplier visible) {
        AnAction action = new DumbAwareAction(model.getText(), model.getText(), icon) {
            @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void update(AnActionEvent event) {
                event.getPresentation().setText(model.getText());
                event.getPresentation().setDescription(model.getToolTipText() == null ? model.getText() : model.getToolTipText());
                event.getPresentation().setEnabled(model.isEnabled()); event.getPresentation().setVisible(visible.getAsBoolean());
            }
            @Override public void actionPerformed(AnActionEvent event) { if (model.isEnabled()) model.doClick(0); }
        };
        action.getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, text);
        return action;
    }
    static AnAction toggle(JToggleButton model, Icon icon) {
        return toggle(model, icon, true);
    }
    static AnAction toggle(JToggleButton model, Icon icon, boolean text) {
        AnAction action = new DumbAwareToggleAction(model.getText(), model.getToolTipText(), icon) {
            @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public boolean isSelected(AnActionEvent event) { return model.isSelected(); }
            @Override public void setSelected(AnActionEvent event, boolean selected) { if (model.isSelected() != selected) model.doClick(0); }
            @Override public void update(AnActionEvent event) {
                super.update(event); event.getPresentation().setText(model.getText());
                event.getPresentation().setDescription(model.getToolTipText() == null ? model.getText() : model.getText() + " — " + model.getToolTipText());
            }
        };
        action.getTemplatePresentation().putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, text);
        return action;
    }
    static ExtendableTextField filterField(String hint, Runnable search) {
        ExtendableTextField field = new ExtendableTextField(); field.getEmptyText().setText(hint);
        field.addExtension(new ExtendableTextComponent.Extension() {
            @Override public Icon getIcon(boolean hovered) { return AllIcons.Actions.Search; }
            @Override public boolean isIconBeforeText() { return true; }
            @Override public String getTooltip() { return search == null ? hint : "Run search (Enter)"; }
            @Override public Runnable getActionOnClick() { return search == null ? field::requestFocusInWindow : search; }
        });
        field.addExtension(new ExtendableTextComponent.Extension() {
            @Override public Icon getIcon(boolean hovered) { return field.getText().isEmpty() ? null : AllIcons.Actions.Close; }
            @Override public String getTooltip() { return "Clear"; }
            @Override public Runnable getActionOnClick() { return () -> { field.setText(""); field.requestFocusInWindow(); }; }
        });
        field.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(DocumentEvent event) { field.revalidate(); field.repaint(); }
        });
        return field;
    }
    static AnAction choice(String title, String prefix, JComboBox<?> model) { return new ChoiceAction(title, prefix, model); }
    private static final class ChoiceAction extends ComboBoxAction implements DumbAware {
        private final JComboBox<?> model;
        private final String prefix;
        ChoiceAction(String title, String prefix, JComboBox<?> model) {
            this.model = model; this.prefix = prefix;
            getTemplatePresentation().setText(title); getTemplatePresentation().setDescription(title); setSmallVariant(true);
        }
        @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        @Override public void update(AnActionEvent event) {
            event.getPresentation().setText(prefix + model.getSelectedItem()); event.getPresentation().setEnabled(model.isEnabled());
        }
        @Override protected DefaultActionGroup createPopupActionGroup(JComponent button, DataContext context) {
            DefaultActionGroup group = new DefaultActionGroup();
            for (int index = 0; index < model.getItemCount(); index++) {
                int value = index;
                group.add(new DumbAwareToggleAction(model.getItemAt(index).toString()) {
                    @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
                    @Override public boolean isSelected(AnActionEvent event) { return model.getSelectedIndex() == value; }
                    @Override public void setSelected(AnActionEvent event, boolean selected) { if (selected) model.setSelectedIndex(value); }
                });
            }
            return group;
        }
    }
    /** Log data is plain text even when it starts with Swing's HTML marker. */
    static String plainTooltip(String value) {
        if (value == null) return null;
        String text = value.codePointCount(0, value.length()) <= 500 ? value : value.substring(0, value.offsetByCodePoints(0, 500)) + "…";
        return javax.swing.plaf.basic.BasicHTML.isHTMLString(text)
                ? "<html>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text) + "</html>" : text;
    }
    static class PlainCellRenderer extends DefaultTableCellRenderer {
        PlainCellRenderer() { putClientProperty("html.disable", Boolean.TRUE); }
    }
    static DefaultTableCellRenderer cells() {
        return new PlainCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setBorder(JBUI.Borders.empty(0, 8));
                String tooltip = value == null ? null : value.toString();
                setToolTipText(plainTooltip(tooltip));
                return this;
            }
        };
    }
}
