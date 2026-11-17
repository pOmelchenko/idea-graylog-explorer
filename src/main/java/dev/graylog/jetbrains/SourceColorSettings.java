package dev.graylog.jetbrains;

import com.intellij.ui.table.JBTable;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

/** IDE-wide color overrides, keyed by the value displayed in the Service column. */
final class SourceColorSettings extends JPanel {
    final JCheckBox enabled = new JCheckBox("Show source colors in log rows");
    private final Model model = new Model();
    private final JBTable table = new JBTable(model);
    SourceColorSettings() {
        super(new BorderLayout(0, 8)); setBorder(JBUI.Borders.emptyTop(12));
        JPanel heading = new JPanel(new BorderLayout(0, 8)); heading.add(enabled, BorderLayout.NORTH);
        add(heading, BorderLayout.NORTH);
        table.setShowGrid(false); table.setStriped(true); table.setRowHeight(JBUI.scale(24)); table.setIntercellSpacing(JBUI.size(0, 0));
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, selected, false, row, column); setBorder(JBUI.Borders.empty(0, 8)); setIcon(null);
                if (column == 1) try { setIcon(ExplorerUi.colorIcon(Color.decode(value.toString()))); } catch (RuntimeException ignored) { }
                return this;
            }
        }); table.putClientProperty("terminateEditOnFocusLost", true);
        ToolbarDecorator controls = ToolbarDecorator.createDecorator(table).disableUpDownActions();
        controls.setAddAction(e -> { stopEditing(); model.rows.add(new String[]{"", "#4B86C6"}); model.fireTableDataChanged(); int row = model.rows.size() - 1; table.setRowSelectionInterval(row, row); table.editCellAt(row, 0); if (table.getEditorComponent() != null) table.getEditorComponent().requestFocusInWindow(); });
        controls.setEditAction(e -> { stopEditing(); int row = table.getSelectedRow(); if (row < 0) return;
            Color initial; try { initial = Color.decode(model.rows.get(row)[1]); } catch (RuntimeException ignored) { initial = Color.GRAY; }
            Color chosen = com.intellij.ui.ColorChooserService.getInstance().showDialog(this, "Graylog Source Color", initial, false);
            if (chosen != null) { model.rows.get(row)[1] = SourceColors.hex(chosen); model.fireTableRowsUpdated(row, row); }
        });
        controls.setRemoveAction(e -> { stopEditing(); int row = table.getSelectedRow(); if (row >= 0) { model.rows.remove(row); model.fireTableDataChanged(); } });
        add(controls.createPanel(), BorderLayout.CENTER);
        add(SettingsForms.help("Source names match the Service column. Other sources use automatic colors.<br>Remove an override to restore its automatic color."), BorderLayout.SOUTH);
    }
    private void stopEditing() { if (table.isEditing()) table.getCellEditor().stopCellEditing(); }
    boolean isEditing() { return table.isEditing(); }
    void reset(IdeOptions options) { enabled.setSelected(options.sourceStripes); model.rows.clear(); options.sourceColors.forEach((key,value) -> model.rows.add(new String[]{key,value})); model.fireTableDataChanged(); }
    void collect(IdeOptions options) {
        stopEditing(); options.sourceStripes = enabled.isSelected(); options.sourceColors.clear();
        for (String[] row : model.rows) {
            if (row[0].isBlank()) throw new IllegalArgumentException("Source names in color overrides must not be blank.");
            if (options.sourceColors.put(row[0].trim(), row[1].trim()) != null) throw new IllegalArgumentException("Duplicate source color: " + row[0]);
        }
    }
    private static final class Model extends AbstractTableModel {
        final List<String[]> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int col) { return col == 0 ? "Source / service" : "Color (#RRGGBB)"; }
        @Override public boolean isCellEditable(int row, int col) { return true; }
        @Override public Object getValueAt(int row, int col) { return rows.get(row)[col]; }
        @Override public void setValueAt(Object value, int row, int col) { rows.get(row)[col] = value.toString(); fireTableCellUpdated(row, col); }
    }
}
