package dev.graylog.jetbrains

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.table.JBTable
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.ArrayList
import java.util.TreeMap
import java.util.concurrent.*
import java.util.function.*
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/** Event details, stack navigation and browser links; no networking or search orchestration.  */
class EventInspector(
    private val project: Project,
    parent: Disposable,
    onOpen: Runnable,
    onAround: Runnable,
    onFollow: Runnable
) : JPanel(BorderLayout()), Disposable {
    private val onOpen: Runnable
    private val onAround: Runnable
    private val onFollow: Runnable
    private var active: Connection? = null
    private var loadedRequest: SearchRequest? = null
    private var selected: LogEvent? = null
    private val message: JTextArea = textArea(true)
    private val rawJson: JTextArea = textArea(false)
    private val rawStack: JTextArea = textArea(false)
    private val fieldsModel = FieldTableModel()
    private val locationsModel: DefaultListModel<SourceLocation> = DefaultListModel()
    private val locations: JBList<SourceLocation> =
        JBList<SourceLocation>(locationsModel)
    private val sourceMapping = JLabel(" ")
    private val follow = JButton("Follow request")
    private val around = JButton("Show logs around")
    private val open = JButton("Open in Editor")
    private val browser = JButton("Open Graylog")
    private lateinit var inspectorTabs: JBTabs
    private val inspectorLayout = CardLayout()
    private val inspectorBody = JPanel(inspectorLayout)
    private val eventLevel = JLabel()

    init {
        this.onOpen = onOpen
        this.onAround = onAround
        this.onFollow = onFollow
        com.intellij.openapi.util.Disposer.register(parent, this)
        rawStack.setName("inspector.rawStack")
        sourceMapping.setName("inspector.sourceMapping")
        open.setName("inspector.open")
        browser.setName("inspector.browser")
        inspectorBody.setName("inspector.inspectorBody")
        locations.setName("inspector.locations")
        add(createInspector(), BorderLayout.CENTER)
    }

    fun show(
        event: LogEvent?,
        connection: Connection?,
        request: SearchRequest?
    ) {
        active = connection
        loadedRequest = request
        showEvent(event)
    }

    fun tabs(): JBTabs = inspectorTabs

    fun selectedLocation(): SourceLocation? = locations.getSelectedValue()

    private fun openSource() {
        onOpen.run()
    }

    override fun dispose() {
        selected = null
        active = null
        loadedRequest = null
    }

    private fun createInspector(): JComponent {
        val panel = JPanel(BorderLayout())
        val details = JPanel(BorderLayout())
        val heading = JPanel(BorderLayout())
        message.setRows(2)
        message.setFont(UIUtil.getLabelFont())
        message.setBorder(JBUI.Borders.empty(2, 8, 8, 8))
        heading.add(ExplorerUi.scroll(message), BorderLayout.CENTER)
        details.add(heading, BorderLayout.NORTH)
        inspectorTabs = JBTabsFactory.createTabs(project, this)
        inspectorTabs.getComponent().setBorder(JBUI.Borders.empty())
        val fields = JBTable(fieldsModel)
        fields.setShowGrid(false)
        fields.setStriped(true)
        fields.setAutoCreateRowSorter(true)
        fields.setCellSelectionEnabled(true)
        fields.setRowHeight(JBUI.scale(24))
        fields.setIntercellSpacing(JBUI.size(0, 0))
        fields.setDefaultRenderer(Any::class.java, ExplorerUi.cells())
        fields.getColumnModel().getColumn(0).setPreferredWidth(180)
        fields.getColumnModel().getColumn(1).setPreferredWidth(360)
        fields.getEmptyText().setText("Select an event to inspect its fields.")
        inspectorTabs.addTab(TabInfo(ExplorerUi.scroll(fields)).setText("Fields"))
        locations.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        locations.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.getClickCount() == 2) openSource()
            }
        })
        locations.registerKeyboardAction(
            { e -> openSource() },
            KeyStroke.getKeyStroke("ENTER"),
            JComponent.WHEN_FOCUSED
        )
        locations.setBorder(JBUI.Borders.empty(6, 8))
        locations.setCellRenderer(object : ColoredListCellRenderer<SourceLocation>() {
            override fun customizeCellRenderer(
                list: javax.swing.JList<out SourceLocation>,
                value: SourceLocation,
                index: Int,
                selected: Boolean,
                focus: Boolean
            ) {
                val local: Path? = mappedSource(value)
                val display: String =
                    if (local == null) value.path.replace('\\', '/') else displayLocal(local).replace('\\', '/')
                val slash = display.lastIndexOf('/')
                append(display.substring(slash + 1) + ":" + value.line, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (slash >= 0) append("  " + display.substring(0, slash), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                setIcon(if (local == null) AllIcons.General.Warning else AllIcons.FileTypes.Text)
                setToolTipText(mappingDescription(value))
            }
        })
        locations.getEmptyText().setText("This event has no source frames.")
        locations.addListSelectionListener({ e ->
            val location: SourceLocation? = locations.getSelectedValue()
            sourceMapping.setText(if (location == null) " " else mappingDescription(location))
            sourceMapping.setToolTipText(if (location == null) null else mappingDescription(location))
        })
        val trace = JPanel(BorderLayout())
        val localLabel = JLabel("Local source files")
        localLabel.setForeground(UIUtil.getContextHelpForeground())
        trace.add(
            ExplorerUi.bar(
                localLabel, ExplorerUi.toolbar(
                    "Graylog.SourceMapping", this,
                    ExplorerUi.command(
                        "Source path mappings", AllIcons.General.Settings,
                        Runnable {
                            ShowSettingsUtil.getInstance().showSettingsDialog(
                                project,
                                GraylogProjectConfigurable::class.java
                            )
                        }, BooleanSupplier { true })
                ), false
            ), BorderLayout.NORTH
        )
        trace.add(ExplorerUi.scroll(locations), BorderLayout.CENTER)
        sourceMapping.setForeground(UIUtil.getContextHelpForeground())
        trace.add(ExplorerUi.bar(sourceMapping, null, true), BorderLayout.SOUTH)
        inspectorTabs.addTab(TabInfo(trace).setText("Stack trace"))
        inspectorTabs.addTab(TabInfo(ExplorerUi.scroll(rawJson)).setText("JSON"))
        inspectorTabs.addTab(TabInfo(ExplorerUi.scroll(rawStack)).setText("Exception text"))
        details.add(inspectorTabs.getComponent(), BorderLayout.CENTER)
        val empty = JLabel("Select a log event to view details", JLabel.CENTER)
        empty.setForeground(UIUtil.getContextHelpForeground())
        inspectorBody.add(empty, "empty")
        inspectorBody.add(details, "details")
        panel.add(inspectorBody, BorderLayout.CENTER)
        open.addActionListener({ e -> openSource() })
        around.addActionListener({ e -> onAround.run() })
        follow.addActionListener({ e -> onFollow.run() })
        browser.addActionListener({ e ->
            val target = browserTarget()
            if (target != null) BrowserUtil.browse(target.url)
        })
        val actions: JComponent? = ExplorerUi.toolbar(
            "Graylog.Inspector",
            this,
            ExplorerUi.button(open, AllIcons.Actions.EditSource, false),
            ExplorerUi.button(around, AllIcons.General.History, false),
            ExplorerUi.button(follow, AllIcons.Ide.Link, false),
            ExplorerUi.button(browser, AllIcons.General.Web, false)
        )
        panel.add(ExplorerUi.bar(eventLevel, actions, false), BorderLayout.NORTH)
        showEvent(null)
        return panel
    }

    private fun showEvent(event: LogEvent?) {
        selected = event
        inspectorLayout.show(inspectorBody, if (event == null) "empty" else "details")
        eventLevel.setText(if (event == null) "" else event.level())
        eventLevel.setForeground(
            if (event != null && event.level().equals("ERROR"))
                ERROR_COLOR
            else
                if (event != null && event.level()
                        .equals("WARN")
                ) WARN_COLOR else getForeground()
        )
        message.setText(if (event == null) "Select an event to view details." else event.text("message"))
        message.setCaretPosition(0)
        fieldsModel.rows = if (event == null) listOf() else ArrayList(TreeMap(event.fields().asMap()).entries)
        fieldsModel.fireTableDataChanged()
        rawJson.setText(if (event == null) "" else JSON.toJson(event.fields()))
        rawJson.setCaretPosition(0)
        rawStack.setText(if (event == null) "" else event.stackTrace())
        rawStack.setCaretPosition(0)
        locationsModel.clear()
        if (event != null) {
            val frames: List<SourceLocation> =
                if (ExceptionSites.exceptionName(event)
                        .isBlank()
                ) SourceLocation.find(event) else ExceptionSites.exceptionFrames(event)
            frames.forEach(locationsModel::addElement)
        }
        if (!locationsModel.isEmpty()) locations.setSelectedIndex(0)
        open.setEnabled(!locationsModel.isEmpty())
        around.setEnabled(event != null && event.timestamp() != null)
        follow.setEnabled(event != null && !event.first("request_id", "trace_id", "correlation_id").isBlank())
        val target = browserTarget()
        browser.setEnabled(target != null)
        browser.setToolTipText(
            if (event == null)
                "Select an event to open in Graylog."
            else
                if (target == null)
                    "This event has no message ID or no index/time range to locate it in Graylog."
                else
                    (if (target.direct) "Open the selected event in Graylog: " else "Find the selected event by its ID (index unavailable): ") + target.url
        )
    }

    private fun browserTarget(): GraylogLinks.Target? = GraylogLinks.event(active, selected, loadedRequest)

    private fun mappedSource(source: SourceLocation?): Path? {
        return if (source == null || active == null) null else source.resolve(
            active,
            if (project.getBasePath() == null) null else Path.of(project.getBasePath())
        )
    }

    private fun displayLocal(path: Path): String {
        val root: Path? =
            if (project.getBasePath() == null) null else Path.of(project.getBasePath()).toAbsolutePath().normalize()
        return if (root != null && path.startsWith(root)) root.relativize(path).toString() else path.toString()
    }

    private fun mappingDescription(source: SourceLocation): String {
        val local: Path? = mappedSource(source)
        return source.toString() + (if (local == null) " → No local mapping. Configure source path mappings." else " → " + local + ":" + source.line)
    }

    private class FieldTableModel : AbstractTableModel() {
        var rows: List<Map.Entry<String, JsonElement>> = listOf()

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 2

        override fun getColumnName(column: Int): String = if (column == 0) "Field" else "Value"

        override fun getValueAt(row: Int, column: Int): Any? {
            val entry: Map.Entry<String, JsonElement> = rows.get(row)
            return if (column == 0) entry.key else if (entry.value.isJsonPrimitive()) entry.value
                .getAsString() else entry.value.toString()
        }
    }

    companion object {
        private val JSON: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        private val ERROR_COLOR: Color = ExplorerUi.ERROR_COLOR
        private val WARN_COLOR: Color = ExplorerUi.WARN_COLOR
        private fun textArea(wrap: Boolean): JTextArea {
            val area = JTextArea()
            area.setEditable(false)
            area.setLineWrap(wrap)
            area.setWrapStyleWord(wrap)
            area.setFont(Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().getSize()))
            area.setBorder(JBUI.Borders.empty(6, 8))
            return area
        }
    }
}
