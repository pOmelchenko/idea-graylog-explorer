package dev.graylog.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.*
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.*
import java.awt.*
import java.awt.event.*
import java.util.*
import java.util.concurrent.*
import java.util.function.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/** A draft multi-selection; cancel never changes the active log filter.  */
class SourceFilterPopup(
    project: Project,
    field: String,
    selected: Set<String>,
    options: Supplier<IdeOptions>,
    apply: Consumer<Set<String>>,
    reload: Runnable,
    closed: Runnable
) {
    private val body = JPanel(BorderLayout(0, 8))
    private val filter: ExtendableTextField = ExplorerUi.filterField("Find a source", null)
    private val model: SourceTableModel =
        SourceTableModel()
    private val table = JBTable(model)
    private val status: JLabel = SettingsForms.help("Loading sources for this period…")
    private val refresh = JButton("Refresh")
    private val draft: MutableSet<String> = LinkedHashSet()
    private var available: List<GraylogApi.SourceValue> = listOf()
    private val popup: JBPopup
    private var summary = "Loading sources for this period…"

    init {
        body.setName("sources.body")
        filter.setName("sources.filter")
        table.setName("sources.table")
        refresh.setName("sources.refresh")
        draft.addAll(selected)
        body.setBorder(JBUI.Borders.empty(8))
        val heading = JPanel(BorderLayout(0, 6))
        heading.add(filter, BorderLayout.NORTH)
        heading.add(
            SettingsForms.help(
                "Select one or more sources · field: " + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(field)
            ), BorderLayout.SOUTH
        )
        body.add(heading, BorderLayout.NORTH)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setRowHeight(JBUI.scale(28))
        table.setShowGrid(false)
        table.setIntercellSpacing(Dimension())
        table.getEmptyText().setText("No matching sources")
        table.getTableHeader().setReorderingAllowed(false)
        val sorter: TableRowSorter<SourceTableModel> =
            TableRowSorter<SourceTableModel>(model)
        sorter.setSortable(0, false)
        sorter.setComparator(
            1,
            Comparator.comparing(
                GraylogApi.SourceValue::name,
                String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())
            )
        )
        sorter.setSortKeys(listOf(RowSorter.SortKey(1, SortOrder.ASCENDING)))
        table.setRowSorter(sorter)
        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(30))
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(30))
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(440))
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(100))
        table.getColumnModel().getColumn(1).setCellRenderer(object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                owner: JTable,
                value: Any,
                selected: Boolean,
                focus: Boolean,
                row: Int,
                column: Int
            ): Component {
                super.getTableCellRendererComponent(owner, value, selected, false, row, column)
                val source = value as GraylogApi.SourceValue
                setText(source.name)
                setIcon(ExplorerUi.colorIcon(SourceColors.color(source.name, options.get().sourceColors)
                    )
                )
                setToolTipText(ExplorerUi.plainTooltip(source.name))
                return this
            }
        })
        table.getColumnModel().getColumn(2).setCellRenderer(object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                owner: JTable,
                value: Any,
                selected: Boolean,
                focus: Boolean,
                row: Int,
                column: Int
            ): Component {
                super.getTableCellRendererComponent(owner, value, selected, false, row, column)
                setHorizontalAlignment(SwingConstants.RIGHT)
                setText(if ((value as Long) < 0) "" else String.format(Locale.ROOT, "%,d", value))
                return this
            }
        })
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row: Int = table.rowAtPoint(e.getPoint())
                val column: Int = table.columnAtPoint(e.getPoint())
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1 && row >= 0 && column > 0) toggle(row)
            }
        })
        table.registerKeyboardAction(
            { e -> if (table.getSelectedRow() >= 0) toggle(table.getSelectedRow()) },
            KeyStroke.getKeyStroke("SPACE"),
            JComponent.WHEN_FOCUSED
        )
        filter.getDocument().addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                rebuild()
            }
        })
        body.add(ExplorerUi.scroll(table), BorderLayout.CENTER)
        val bottom = JPanel(BorderLayout(0, 8))
        bottom.add(status, BorderLayout.NORTH)
        val buttons = JPanel(BorderLayout())
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        val add = JButton("Add by name…")
        val all = JButton("All sources")
        val commit = JButton("Apply")
        val cancel = JButton("Cancel")
        left.add(refresh)
        left.add(add)
        right.add(all)
        right.add(cancel)
        right.add(commit)
        buttons.add(left)
        buttons.add(right, BorderLayout.EAST)
        bottom.add(buttons, BorderLayout.SOUTH)
        body.add(bottom, BorderLayout.SOUTH)
        body.setPreferredSize(JBUI.size(640, 460))
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(body, filter).setRequestFocus(true)
            .setResizable(true).setMovable(true)
            .setMinSize(JBUI.size(560, 320)).setDimensionServiceKey(project, "GraylogExplorer.Sources", true)
            .setCancelOnClickOutside(true).setTitle("Sources").createPopup()
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                closed.run()
            }
        })
        commit.addActionListener({ e ->
            apply.accept(java.util.Set.copyOf(draft))
            popup.cancel()
        })
        all.addActionListener({ e ->
            apply.accept(setOf())
            popup.cancel()
        })
        cancel.addActionListener({ e -> popup.cancel() })
        refresh.addActionListener({ e -> reload.run() })
        add.setToolTipText("Type the exact source name in the search field, then add it")
        add.addActionListener({ e ->
            val name: String = filter.getText().trim()
            if (name.isEmpty()) {
                summary = "Type an exact source name above, then click Add by name"
                updateStatus()
                filter.requestFocusInWindow()
            } else {
                draft.add(name)
                filter.setText("")
                rebuild()
            }
        })
        rebuild()
    }

    fun component(): JComponent = body

    fun selection(): Set<String> = java.util.Set.copyOf(draft)

    fun values(): List<GraylogApi.SourceValue> = available

    fun popup(): JBPopup = popup

    fun alive(): Boolean = !popup.isDisposed()

    fun loading() {
        refresh.setEnabled(false)
        summary = "Loading sources for this period…"
        updateStatus()
    }

    fun loaded(sources: GraylogApi.Sources) {
        available = sources.values
        refresh.setEnabled(true)
        status.setToolTipText(null)
        summary =
            if (sources.limited) "Top " + available.size + " sources · use Add by name for others" else available.size.toString() + " source(s) in this period"
        rebuild()
    }

    fun fallback(sources: List<GraylogApi.SourceValue>, error: String) {
        available = sources
        refresh.setEnabled(true)
        summary = "Aggregation unavailable · loaded-page sources only"
        status.setToolTipText(error)
        rebuild()
    }

    private fun toggle(viewRow: Int) {
        val row: Int = table.convertRowIndexToModel(viewRow)
        model.setValueAt(!draft.contains(model.rows.get(row).name()), row, 0)
    }

    private fun rebuild() {
        val values: MutableMap<String, GraylogApi.SourceValue> =
            TreeMap(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
        available.forEach({ value -> values.put(value.name(), value) })
        draft.forEach({ name -> values.putIfAbsent(name, GraylogApi.SourceValue(name, -1)) })
        val needle: String = filter.getText().trim().lowercase(Locale.ROOT)
        model.rows =
            values.values.stream().filter({ value -> value.name().lowercase(Locale.ROOT).contains(needle) })
                .toList()
        model.fireTableDataChanged()
        if (table.getRowCount() > 0) table.setRowSelectionInterval(0, 0)
        updateStatus()
    }

    private inner class SourceTableModel : AbstractTableModel() {
        var rows: List<GraylogApi.SourceValue> = listOf()

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String {
            return when (column) {
                1 -> "Source"
                2 -> "Messages"
                else -> ""
            }
        }

        override fun getColumnClass(column: Int): Class<*> {
            return when (column) {
                0 -> Boolean::class.javaObjectType
                1 -> GraylogApi.SourceValue::class.java
                else -> Long::class.javaObjectType
            }
        }

        override fun getValueAt(row: Int, column: Int): Any? {
            val source = rows.get(row)
            return when (column) {
                0 -> draft.contains(source.name())
                1 -> source
                else -> source.count()
            }
        }

        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0

        override fun setValueAt(value: Any, row: Int, column: Int) {
            if (column != 0) return
            if (value == true) draft.add(rows.get(row).name()) else draft.remove(rows.get(row).name())
            fireTableCellUpdated(row, column)
            updateStatus()
        }
    }

    private fun updateStatus() {
        status.setText("<html>" + summary + "<br>" + (if (draft.isEmpty()) "All sources included" else draft.size.toString() + " selected") + "</html>")
    }
}
