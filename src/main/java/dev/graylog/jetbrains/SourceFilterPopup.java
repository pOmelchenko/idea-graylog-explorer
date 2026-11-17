package dev.graylog.jetbrains;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.*;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.table.JBTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.table.DefaultTableCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.fields.ExtendableTextField;
import com.intellij.util.ui.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** A draft multi-selection; cancel never changes the active log filter. */
final class SourceFilterPopup {
    private final JPanel body = new JPanel(new BorderLayout(0, 8));
    private final ExtendableTextField filter = ExplorerUi.filterField("Find a source", null);
    private final SourceTableModel model = new SourceTableModel();
    private final JBTable table = new JBTable(model);
    private final JLabel status = SettingsForms.help("Loading sources for this period…");
    private final JButton refresh = new JButton("Refresh");
    private final Set<String> draft = new LinkedHashSet<>();
    private List<GraylogApi.SourceValue> available = List.of();
    private final JBPopup popup;
    private String summary = "Loading sources for this period…";

    SourceFilterPopup(Project project, String field, Set<String> selected, Supplier<IdeOptions> options,
                      Consumer<Set<String>> apply, Runnable reload, Runnable closed) {
        draft.addAll(selected); body.setBorder(JBUI.Borders.empty(8));
        JPanel heading = new JPanel(new BorderLayout(0, 6)); heading.add(filter, BorderLayout.NORTH);
        heading.add(SettingsForms.help("Select one or more sources · field: " + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(field)), BorderLayout.SOUTH); body.add(heading, BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setRowHeight(JBUI.scale(28));
        table.setShowGrid(false); table.setIntercellSpacing(new Dimension());
        table.getEmptyText().setText("No matching sources"); table.getTableHeader().setReorderingAllowed(false);
        var sorter = new TableRowSorter<SourceTableModel>(model);
        sorter.setSortable(0, false);
        sorter.setComparator(1, Comparator.comparing(GraylogApi.SourceValue::name, String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())));
        sorter.setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.ASCENDING))); table.setRowSorter(sorter);
        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(30));
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(30));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(440));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(100));
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(owner, value, selected, false, row, column);
                var source = (GraylogApi.SourceValue) value;
                setText(source.name()); setIcon(ExplorerUi.colorIcon(SourceColors.color(source.name(), options.get().sourceColors)));
                setToolTipText(ExplorerUi.plainTooltip(source.name())); return this;
            }
        });
        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(owner, value, selected, false, row, column);
                setHorizontalAlignment(SwingConstants.RIGHT);
                setText((long) value < 0 ? "" : String.format(Locale.ROOT, "%,d", value)); return this;
            }
        });
        table.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint()), column = table.columnAtPoint(e.getPoint());
            if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1 && row >= 0 && column > 0) toggle(row);
        } });
        table.registerKeyboardAction(e -> { if (table.getSelectedRow() >= 0) toggle(table.getSelectedRow()); }, KeyStroke.getKeyStroke("SPACE"), JComponent.WHEN_FOCUSED);
        filter.getDocument().addDocumentListener(new DocumentAdapter() { @Override protected void textChanged(DocumentEvent event) { rebuild(); } });
        body.add(ExplorerUi.scroll(table), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 8)); bottom.add(status, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new BorderLayout()); JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)), right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton add = new JButton("Add by name…"), all = new JButton("All sources"), commit = new JButton("Apply"), cancel = new JButton("Cancel");
        left.add(refresh); left.add(add); right.add(all); right.add(cancel); right.add(commit); buttons.add(left); buttons.add(right, BorderLayout.EAST); bottom.add(buttons, BorderLayout.SOUTH); body.add(bottom, BorderLayout.SOUTH);
        body.setPreferredSize(JBUI.size(640, 460));
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(body, filter).setRequestFocus(true).setResizable(true).setMovable(true)
                .setMinSize(JBUI.size(560, 320)).setDimensionServiceKey(project, "GraylogExplorer.Sources", true)
                .setCancelOnClickOutside(true).setTitle("Sources").createPopup();
        popup.addListener(new JBPopupListener() { @Override public void onClosed(LightweightWindowEvent event) { closed.run(); } });
        commit.addActionListener(e -> { apply.accept(Set.copyOf(draft)); popup.cancel(); });
        all.addActionListener(e -> { apply.accept(Set.of()); popup.cancel(); }); cancel.addActionListener(e -> popup.cancel());
        refresh.addActionListener(e -> reload.run());
        add.setToolTipText("Type the exact source name in the search field, then add it");
        add.addActionListener(e -> {
            String name = filter.getText().strip();
            if (name.isEmpty()) { summary = "Type an exact source name above, then click Add by name"; updateStatus(); filter.requestFocusInWindow(); }
            else { draft.add(name); filter.setText(""); rebuild(); }
        });
        rebuild();
    }
    JBPopup popup() { return popup; }
    boolean alive() { return !popup.isDisposed(); }
    void loading() { refresh.setEnabled(false); summary = "Loading sources for this period…"; updateStatus(); }
    void loaded(GraylogApi.Sources sources) {
        available = sources.values(); refresh.setEnabled(true);
        status.setToolTipText(null);
        summary = sources.limited() ? "Top " + available.size() + " sources · use Add by name for others" : available.size() + " source(s) in this period";
        rebuild();
    }
    void fallback(List<GraylogApi.SourceValue> sources, String error) {
        available = sources; refresh.setEnabled(true); summary = "Aggregation unavailable · loaded-page sources only";
        status.setToolTipText(error); rebuild();
    }
    private void toggle(int viewRow) { int row = table.convertRowIndexToModel(viewRow); model.setValueAt(!draft.contains(model.rows.get(row).name()), row, 0); }
    private void rebuild() {
        Map<String, GraylogApi.SourceValue> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        available.forEach(value -> values.put(value.name(), value)); draft.forEach(name -> values.putIfAbsent(name, new GraylogApi.SourceValue(name, -1)));
        String needle = filter.getText().strip().toLowerCase(Locale.ROOT);
        model.rows = values.values().stream().filter(value -> value.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
        model.fireTableDataChanged();
        if (table.getRowCount() > 0) table.setRowSelectionInterval(0, 0); updateStatus();
    }
    private final class SourceTableModel extends AbstractTableModel {
        private List<GraylogApi.SourceValue> rows = List.of();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int column) { return switch (column) { case 1 -> "Source"; case 2 -> "Messages"; default -> ""; }; }
        @Override public Class<?> getColumnClass(int column) { return switch (column) { case 0 -> Boolean.class; case 1 -> GraylogApi.SourceValue.class; default -> Long.class; }; }
        @Override public Object getValueAt(int row, int column) { var source = rows.get(row); return switch (column) { case 0 -> draft.contains(source.name()); case 1 -> source; default -> source.count(); }; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }
        @Override public void setValueAt(Object value, int row, int column) {
            if (column != 0) return;
            if (Boolean.TRUE.equals(value)) draft.add(rows.get(row).name()); else draft.remove(rows.get(row).name());
            fireTableCellUpdated(row, column); updateStatus();
        }
    }
    private void updateStatus() { status.setText("<html>" + summary + "<br>" + (draft.isEmpty() ? "All sources included" : draft.size() + " selected") + "</html>"); }
}
