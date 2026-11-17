package dev.graylog.jetbrains;

import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.TitledSeparator;

import javax.swing.*;
import java.awt.*;

final class SettingsForms {
    private final JPanel panel = new JPanel(new GridBagLayout());
    private int row;
    SettingsForms() { }
    void add(String label, JComponent input) {
        GridBagConstraints c = new GridBagConstraints(); c.gridy = row++; c.gridx = 0;
        c.insets = JBUI.insets(4, 0, 4, 12); c.anchor = GridBagConstraints.WEST;
        JLabel title = new JLabel(label.endsWith(":") ? label : label + ":"); title.setLabelFor(input); panel.add(title, c);
        c.gridx = 1; c.weightx = 1;
        c.fill = input instanceof JSpinner || input instanceof JComboBox<?> ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        c.insets = JBUI.insets(4, 0); panel.add(input, c);
    }
    void checkbox(JCheckBox input) { component(input, 2, false); }
    void comment(String text) { component(help(text), 2, true); }
    void group(String title) {
        GridBagConstraints c = new GridBagConstraints(); c.gridy = row++; c.gridx = 0; c.gridwidth = 2;
        c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = JBUI.insets(row > 1 ? 18 : 0, 0, 6, 0);
        panel.add(new TitledSeparator(title), c);
    }
    void component(JComponent input) { component(input, 2, !(input instanceof AbstractButton)); }
    private void component(JComponent input, int width, boolean grow) {
        GridBagConstraints c = new GridBagConstraints(); c.gridy = row++; c.gridx = 0; c.gridwidth = width;
        c.weightx = 1; c.fill = grow ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST; c.insets = JBUI.insets(4, 0); panel.add(input, c);
    }
    JPanel panel() {
        JPanel top = new JPanel(new BorderLayout()); top.add(panel, BorderLayout.NORTH); return top;
    }
    static JLabel help(String text) {
        JLabel label = new com.intellij.ui.components.JBLabel("<html>" + text + "</html>");
        label.setFont(JBFont.medium()); label.setForeground(UIUtil.getContextHelpForeground()); return label;
    }
    static JPanel tab(JComponent content) { JPanel panel = new JPanel(new BorderLayout()); panel.setBorder(JBUI.Borders.emptyTop(12)); panel.add(content); return panel; }
    static JSpinner number(int initial, int min, int max) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, min, max, 1));
        ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(5); return spinner;
    }
    static int number(JSpinner field) { return ((Number) field.getValue()).intValue(); }
    static <T> JComboBox<T> combo() { return combo(null); }
    static <T> JComboBox<T> combo(T[] values) {
        JComboBox<T> combo = new com.intellij.openapi.ui.ComboBox<>() {
            @Override public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                int textWidth = 0;
                if (getFont() != null) for (int i = 0; i < getItemCount(); i++) {
                    textWidth = Math.max(textWidth, getFontMetrics(getFont()).stringWidth(String.valueOf(getItemAt(i))));
                }
                // Leave room for the New UI arrow and padding at the current UI font size.
                return new Dimension(Math.max(size.width, Math.min(JBUI.scale(360), textWidth + JBUI.scale(64))), size.height);
            }
        };
        if (values != null) for (T value : values) combo.addItem(value);
        return combo;
    }
    static JPanel color(JBTextField field) {
        JPanel panel = new JPanel(new BorderLayout());
        com.intellij.ui.ColorPanel picker = new com.intellij.ui.ColorPanel(); picker.setSupportTransparency(false);
        boolean[] syncing = {false};
        field.getDocument().addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override protected void textChanged(javax.swing.event.DocumentEvent event) {
                if (syncing[0]) return;
                try { syncing[0] = true; if (field.getText().matches("#[0-9A-Fa-f]{6}")) picker.setSelectedColor(Color.decode(field.getText())); }
                finally { syncing[0] = false; }
            }
        });
        picker.addActionListener(e -> { if (!syncing[0] && picker.getSelectedColor() != null) field.setText(SourceColors.hex(picker.getSelectedColor())); });
        panel.add(picker, BorderLayout.WEST); return panel;
    }
}
