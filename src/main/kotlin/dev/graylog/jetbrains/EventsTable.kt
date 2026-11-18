package dev.graylog.jetbrains

import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Consumer
import java.util.function.Supplier
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/** Owns presentation, sorting and selection independently of fetching/paging. */
class EventsTable(options: Supplier<IdeOptions>, onSelect: Consumer<LogEvent?>, onContext: Consumer<MouseEvent>) : JBTable() {
    private val events = Model()
    init {
        model = events
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = true
        setShowGrid(false); isStriped = true; rowHeight = JBUI.scale(24); intercellSpacing = JBUI.size(0, 0)
        emptyText.text = "Add a connection, select a stream and run a search."
        listOf(110, 65, 120, 500).forEachIndexed { index, width -> columnModel.getColumn(index).preferredWidth = width }
        setDefaultRenderer(Any::class.java, object : ExplorerUi.PlainCellRenderer() {
            private var stripe: Color? = null
            private var segment: SourceBands.Segment? = null
            override fun paintComponent(graphics: Graphics) {
                super.paintComponent(graphics)
                val color = stripe ?: return
                val band = segment ?: return
                SourceBands.paint(graphics, color, JBUI.scale(2), JBUI.scale(4), height, JBUI.scale(1), JBUI.scale(3), band)
            }
            override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column)
                val modelColumn = table.convertColumnIndexToModel(column)
                val event = events.rows[convertRowIndexToModel(row)]
                val settings = options.get()
                val sourceColor = if (settings.sourceStripes) SourceColors.color(event.service(events.serviceField), settings.sourceColors) else null
                stripe = sourceColor.takeIf { column == 0 }
                segment = if (stripe == null) null else SourceBands.at(row, table.rowCount) { visible ->
                    events.rows[table.convertRowIndexToModel(visible)].service(events.serviceField)
                }
                icon = if (modelColumn == 2 && sourceColor != null) ExplorerUi.colorIcon(sourceColor) else null
                border = JBUI.Borders.empty(0, if (column == 0) 10 else 8, 0, 8)
                if (!selected) foreground = when {
                    modelColumn == 1 && event.level() == "ERROR" -> ExplorerUi.ERROR_COLOR
                    modelColumn == 1 && event.level() == "WARN" -> ExplorerUi.WARN_COLOR
                    else -> table.foreground
                }
                toolTipText = ExplorerUi.plainTooltip(value?.toString().orEmpty())
                return this
            }
        })
        addMouseListener(object : MouseAdapter() {
            private fun popup(event: MouseEvent) { if (event.isPopupTrigger) onContext.accept(event) }
            override fun mousePressed(event: MouseEvent) = popup(event)
            override fun mouseReleased(event: MouseEvent) = popup(event)
        })
        selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) onSelect.accept(selectedRow.takeIf { it >= 0 }?.let { events.rows[convertRowIndexToModel(it)] })
        }
    }
    fun rows(): List<LogEvent> = events.rows
    fun serviceField(): String = events.serviceField
    fun show(rows: List<LogEvent>, serviceField: String, identity: String?, selectFirst: Boolean) {
        events.rows = java.util.List.copyOf(rows); events.serviceField = serviceField; events.fireTableDataChanged()
        val preserve = rows.indexOfFirst { it.identity() == identity }.takeIf { it >= 0 }?.let(::convertRowIndexToView)
        when {
            preserve != null && preserve >= 0 -> setRowSelectionInterval(preserve, preserve)
            rowCount > 0 && selectFirst -> setRowSelectionInterval(0, 0)
            else -> clearSelection()
        }
    }
    private class Model : AbstractTableModel() {
        var rows: List<LogEvent> = emptyList()
        var serviceField = "source"
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = listOf("Time (UTC)", "Level", "Service", "Message")[column]
        override fun getValueAt(row: Int, column: Int): Any = rows[row].let { event ->
            when (column) {
                0 -> event.displayTime()
                1 -> event.level()
                2 -> event.service(serviceField)
                else -> event.text("message").replace(Regex("[\\r\\n]+"), " ")
            }
        }
    }
}
