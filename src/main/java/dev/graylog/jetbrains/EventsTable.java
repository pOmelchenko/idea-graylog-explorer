package dev.graylog.jetbrains;

import com.intellij.openapi.actionSystem.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/** Log table owns rendering, sorting and row selection, independent of fetching/paging. */
final class EventsTable extends JBTable {
    private final Model model = new Model();
    private static final Color ERROR_COLOR = ExplorerUi.ERROR_COLOR;
    private static final Color WARN_COLOR = ExplorerUi.WARN_COLOR;
    EventsTable(java.util.function.Supplier<IdeOptions> options, Consumer<LogEvent> onSelect, Consumer<MouseEvent> onContext) {
        setModel(model);
        this.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); this.setAutoCreateRowSorter(true);
        this.setShowGrid(false); this.setStriped(true); this.setRowHeight(JBUI.scale(24)); this.setIntercellSpacing(JBUI.size(0, 0));
        this.getEmptyText().setText("Add a connection, select a stream and run a search.");
        this.getColumnModel().getColumn(0).setPreferredWidth(110);
        this.getColumnModel().getColumn(1).setPreferredWidth(65);
        this.getColumnModel().getColumn(2).setPreferredWidth(120);
        this.getColumnModel().getColumn(3).setPreferredWidth(500);
        this.setDefaultRenderer(Object.class, new ExplorerUi.PlainCellRenderer() {
            private Color stripe;
            @Override protected void paintComponent(java.awt.Graphics graphics) {
                super.paintComponent(graphics);
                if (stripe != null) {
                    java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
                    try {
                        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(stripe);
                        int vertical = JBUI.scale(1);
                        g.fillRoundRect(JBUI.scale(2), vertical, JBUI.scale(4), Math.max(0, getHeight() - 2 * vertical), JBUI.scale(3), JBUI.scale(3));
                    } finally { g.dispose(); }
                }
            }
            @Override public Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                int modelColumn = table.convertColumnIndexToModel(column);
                LogEvent event = model.rows.get(EventsTable.this.convertRowIndexToModel(row));
                Color sourceColor = options.get().sourceStripes ? SourceColors.color(event.service(model.serviceField), options.get().sourceColors) : null;
                stripe = column == 0 ? sourceColor : null;
                setIcon(modelColumn == 2 && sourceColor != null ? ExplorerUi.colorIcon(sourceColor) : null);
                setBorder(JBUI.Borders.empty(0, column == 0 ? 10 : 8, 0, 8));
                if (!selected) {
                    String level = model.rows.get(EventsTable.this.convertRowIndexToModel(row)).level();
                    setForeground(modelColumn == 1 && level.equals("ERROR") ? ERROR_COLOR :
                            modelColumn == 1 && level.equals("WARN") ? WARN_COLOR : table.getForeground());
                }
                setToolTipText(ExplorerUi.plainTooltip(value == null ? "" : value.toString()));
                return this;
            }
        });
        this.addMouseListener(new MouseAdapter() {
            private void popup(MouseEvent e) { if (e.isPopupTrigger()) onContext.accept(e); }
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });
        this.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int index = this.getSelectedRow();
                onSelect.accept(index < 0 ? null : model.rows.get(this.convertRowIndexToModel(index)));
            }
        });
    }
    List<LogEvent> rows() { return model.rows; }
    String serviceField() { return model.serviceField; }
    void show(List<LogEvent> rows, String serviceField, String identity, boolean selectFirst) {
        model.rows = List.copyOf(rows); model.serviceField = serviceField; model.fireTableDataChanged();
        int preserve = -1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).identity().equals(identity)) { preserve = i; break; }
        if (preserve >= 0) { int view = convertRowIndexToView(preserve); setRowSelectionInterval(view, view); }
        else if (!rows.isEmpty() && selectFirst) setRowSelectionInterval(0, 0);
        else clearSelection();
    }
    private static final class Model extends AbstractTableModel {
        List<LogEvent> rows = List.of(); String serviceField = "source";
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 4; }
        @Override public String getColumnName(int column) { return new String[]{"Time (UTC)", "Level", "Service", "Message"}[column]; }
        @Override public Object getValueAt(int row, int column) {
            LogEvent event = rows.get(row);
            return switch (column) { case 0 -> event.displayTime(); case 1 -> event.level(); case 2 -> event.service(serviceField);
                default -> event.text("message").replaceAll("[\\r\\n]+", " "); };
        }
    }
}
