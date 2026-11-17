package dev.graylog.jetbrains

import com.intellij.icons.AllIcons
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*

/** UI bindings edit a detached draft. Apply alone commits settings and the credential transaction. */
class GraylogIdeConfigurable : SearchableConfigurable, Configurable.NoScroll {
    companion object {
        const val ID = "dev.graylog.jetbrains.settings"
        private val PERIODS = listOf(300, 900, 3600, 86400)
        private val PERIOD_NAMES = listOf("Last 5 minutes", "Last 15 minutes", "Last hour", "Last 24 hours")
    }
    private data class Choice(val id: String, val name: String) { override fun toString() = name }
    private var panel: JPanel? = null
    private var base = GraylogIdeSettings.Data()
    private var draft = base.copy()
    private val forms = mutableListOf<DialogPanel>()
    private val connections = DefaultListModel<ServerConnection>()
    private val pendingTokens = linkedMapOf<String, CharArray>()
    private lateinit var list: JBList<ServerConnection>
    private lateinit var executor: ExecutorService
    private var testTask: Future<*>? = null
    private var testToken: CharArray? = null
    private var disposed = false
    private var testNumber = 0L
    private lateinit var testStatus: JLabel
    private lateinit var connectionDetails: JPanel
    private lateinit var connectionName: JLabel
    private lateinit var apiPreview: JTextField
    private lateinit var webPreview: JTextField
    private lateinit var testConnectionButton: JButton
    private lateinit var liveInterval: JSpinner
    private lateinit var connectTimeout: JSpinner
    private lateinit var requestTimeout: JSpinner
    private lateinit var batchSize: JSpinner
    private lateinit var samples: JSpinner
    private lateinit var period: JComboBox<String>
    private lateinit var pageSize: JComboBox<Int>
    private lateinit var defaultConnection: ComboBox<Choice>
    private lateinit var liveOnOpen: JCheckBox
    private lateinit var autoIndex: JCheckBox
    private lateinit var highlight: JCheckBox
    private lateinit var gutter: JCheckBox
    private lateinit var stripe: JCheckBox
    private lateinit var inlineDetails: JCheckBox
    private lateinit var inlineValues: JCheckBox
    private val backgroundLight = JBTextField()
    private val backgroundDark = JBTextField()
    private val selectedLight = JBTextField()
    private val selectedDark = JBTextField()
    private lateinit var sourceColors: SourceColorSettings

    override fun getId() = ID
    override fun getDisplayName() = "Graylog Explorer"
    override fun createComponent(): JComponent {
        disposed = false; forms.clear()
        executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Graylog settings connection test", 2)
        list = JBList(connections); testStatus = JLabel(" ")
        defaultConnection = SettingsForms.combo()
        val tabs = JBTabbedPane()
        tabs.addTab("Connections", connectionsPanel())
        val behavior = panel {
            group("Search defaults") {
                row("Default connection:") { cell(defaultConnection).align(AlignX.FILL).resizableColumn().bindItem({ choice(draft.options.defaultConnection) }, { draft.options.defaultConnection = it?.id.orEmpty() }) }
                row("Initial search period:") { period = comboBox(PERIOD_NAMES).bindItem({ PERIOD_NAMES[PERIODS.indexOf(draft.options.defaultPeriodSeconds)] }, { draft.options.defaultPeriodSeconds = PERIODS[PERIOD_NAMES.indexOf(it)] }).component }
                row("Initial page size:") { pageSize = comboBox(listOf(100, 250, 500)).bindItem({ draft.options.defaultPageSize }, { draft.options.defaultPageSize = it ?: 100 }).component }
            }
            group("Live updates") {
                row { liveOnOpen = checkBox("Start Live when Explorer opens").bindSelected({ draft.options.liveOnOpen }, { draft.options.liveOnOpen = it }).component }
                row("Refresh interval (seconds):") { liveInterval = spinner(1..3600).bindIntValue({ draft.options.liveIntervalSeconds }, { draft.options.liveIntervalSeconds = it }).component }
            }
            group("Exception index") {
                row { autoIndex = checkBox("Build the whole-period exception index after each search").bindSelected({ draft.options.autoIndex }, { draft.options.autoIndex = it }).component }
                row("Examples per source location:") { samples = spinner(1..500).bindIntValue({ draft.options.examplesPerLocation }, { draft.options.examplesPerLocation = it }).component }
            }
        }
        val colors = panel {
            row { highlight = checkBox("Enable code highlights by default").bindSelected({ draft.options.highlightByDefault }, { draft.options.highlightByDefault = it }).component }
            row { gutter = checkBox("Show Graylog gutter markers").bindSelected({ draft.options.gutterIcons }, { draft.options.gutterIcons = it }).component }
            row { stripe = checkBox("Show marks on the editor error stripe").bindSelected({ draft.options.errorStripe }, { draft.options.errorStripe = it }).component }
            row { inlineDetails = checkBox("Show exception messages next to source lines").bindSelected({ draft.options.inlineDetails }, { draft.options.inlineDetails = it }).component }
            row { inlineValues = checkBox("Show selected log values beside PHP variables when source mapping is unambiguous").bindSelected({ draft.options.inlineValues }, { draft.options.inlineValues = it }).component }
            group("Exception colors") {
                color("Background — light theme", backgroundLight, { draft.options.backgroundLight }, { draft.options.backgroundLight = it })
                color("Background — dark theme", backgroundDark, { draft.options.backgroundDark }, { draft.options.backgroundDark = it })
                color("Selected exception — light theme", selectedLight, { draft.options.selectedLight }, { draft.options.selectedLight = it })
                color("Selected exception — dark theme", selectedDark, { draft.options.selectedDark }, { draft.options.selectedDark = it })
            }
        }
        val network = panel {
            row("Connect timeout (seconds):") { connectTimeout = spinner(1..300).bindIntValue({ draft.options.connectTimeoutSeconds }, { draft.options.connectTimeoutSeconds = it }).component }
            row("Request timeout (seconds):") { requestTimeout = spinner(1..3600).bindIntValue({ draft.options.requestTimeoutSeconds }, { draft.options.requestTimeoutSeconds = it }).component }
            row("Scroll export batch size:") { batchSize = spinner(1..5000).bindIntValue({ draft.options.exportBatchSize }, { draft.options.exportBatchSize = it }).component }
        }
        forms += listOf(behavior, colors, network)
        tabs.addTab("Search and updates", SettingsForms.tab(behavior)); tabs.addTab("Highlighting", SettingsForms.tab(colors))
        sourceColors = SourceColorSettings(); tabs.addTab("Source colors", sourceColors); tabs.addTab("Network", SettingsForms.tab(network))
        return JPanel(BorderLayout(0, 8)).also {
            panel = it; it.add(SettingsForms.help("Connections and defaults apply to all projects. Source mappings are under Project settings."), BorderLayout.NORTH); it.add(tabs)
        }
    }
    private fun Panel.color(label: String, field: JBTextField, get: () -> String, set: (String) -> Unit) {
        row("$label:") { cell(SettingsForms.color(field)).onApply { set(field.text.trim()) }.onReset { field.text = get() }.onIsModified { field.text.trim() != get() } }
    }
    private fun connectionsPanel(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION; list.fixedCellHeight = JBUI.scale(26); list.emptyText.text = "No Graylog connections"
        list.cellRenderer = object : ColoredListCellRenderer<ServerConnection>() {
            override fun customizeCellRenderer(list: JList<out ServerConnection>, value: ServerConnection, index: Int, selected: Boolean, focus: Boolean) { append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES); icon = AllIcons.Nodes.DataTables }
        }
        connectionName = JLabel().apply { font = JBFont.regular().asBold(); putClientProperty("html.disable", true) }
        apiPreview = previewField(); webPreview = previewField()
        testConnectionButton = JButton("Test connection").apply { addActionListener { testConnection() } }
        testStatus.foreground = UIUtil.getContextHelpForeground(); testStatus.isVisible = false
        val details = panel {
            row { cell(connectionName) }
            row("API URL:") { cell(apiPreview).align(AlignX.FILL).resizableColumn() }
            row("Web URL:") { cell(webPreview).align(AlignX.FILL).resizableColumn() }
            row("Access token:") { label("Stored in Password Safe") }
            row { cell(testConnectionButton) }; row { cell(testStatus) }
        }
        connectionDetails = JPanel(CardLayout()).apply {
            border = JBUI.Borders.emptyLeft(12)
            add(JLabel("Select or add a connection", JLabel.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }, "empty"); add(details, "details")
        }
        list.addListSelectionListener { showConnection() }
        val controls = ToolbarDecorator.createDecorator(list).disableUpDownActions()
            .setAddAction { editConnection(null) }.setEditAction { list.selectedValue?.let(::editConnection) }
            .setRemoveAction {
                val index = list.selectedIndex
                if (index >= 0) {
                    val id = connections[index].id; val before = selectedDefault()
                    connections.remove(index); wipe(pendingTokens.remove(id)); refreshChoices(if (id == before) "" else before)
                }
            }
        val split = OnePixelSplitter(false, .32f).apply { setHonorComponentsMinimumSize(false); firstComponent = controls.createPanel(); secondComponent = connectionDetails }
        return JPanel(BorderLayout(12, 0)).apply { border = JBUI.Borders.emptyTop(12); add(split) }
    }
    private fun previewField(): JTextField = JBTextField().apply { isEditable = false; isOpaque = false; border = JBUI.Borders.empty(); font = UIUtil.getLabelFont() }
    private fun showConnection() {
        cancelConnectionTest(); val selected = list.selectedValue
        (connectionDetails.layout as CardLayout).show(connectionDetails, if (selected == null) "empty" else "details")
        connectionName.text = selected?.name.orEmpty(); apiPreview.text = selected?.apiUrl.orEmpty(); webPreview.text = selected?.webUrl.orEmpty()
        apiPreview.caretPosition = 0; webPreview.caretPosition = 0; testConnectionButton.isEnabled = selected != null; testStatus.isVisible = false
    }
    private fun editConnection(existing: ServerConnection?) {
        val dialog = ConnectionDialog(null, existing?.connection()); if (!dialog.showAndGet()) return
        val server = ServerConnection.from(dialog.connection()); val token = dialog.token()
        if (token.isNotEmpty()) { wipe(pendingTokens.put(server.id, token)); server.credentialId = UUID.randomUUID().toString() } else wipe(token)
        var position = (0 until connections.size()).firstOrNull { connections[it].id == server.id } ?: -1
        if (position < 0) { connections.addElement(server); position = connections.size() - 1 } else connections.set(position, server)
        list.selectedIndex = position; showConnection(); refreshChoices(selectedDefault())
    }
    private fun cancelConnectionTest() { testNumber++; testTask?.cancel(true); testTask = null; wipe(testToken); testToken = null }
    private fun testConnection() {
        val selected = list.selectedValue ?: return
        val config = try { selected.validate(); OperationConfig.capture(selected.connection(), options()) } catch (error: IllegalArgumentException) { testStatus.text = error.message; testStatus.isVisible = true; return }
        val token = pendingTokens[selected.id]?.clone(); cancelConnectionTest(); testToken = token; val sequence = testNumber
        testStatus.text = "Testing connection…"; testStatus.isVisible = true
        testTask = executor.submit {
            val message = try {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val secret = token?.let(::String) ?: GraylogCredentials.load(config.server().credentialKey())
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                GraylogClient(config, secret).use { "Connected: ${it.streams().size} accessible stream(s)." }
            } catch (error: Exception) { "Connection failed: ${error.message ?: error.javaClass.simpleName}" } finally { wipe(token) }
            SwingUtilities.invokeLater { if (!disposed && sequence == testNumber) { testStatus.text = message; testStatus.isVisible = true } }
        }
    }
    private fun selectedDefault() = (defaultConnection.selectedItem as? Choice)?.id.orEmpty()
    private fun choice(id: String): Choice? = (0 until defaultConnection.itemCount).map(defaultConnection::getItemAt).find { it.id == id }
    private fun refreshChoices(id: String) {
        defaultConnection.removeAllItems(); defaultConnection.addItem(Choice("", "First available connection"))
        for (i in 0 until connections.size()) defaultConnection.addItem(Choice(connections[i].id, connections[i].name))
        defaultConnection.selectedItem = choice(id) ?: defaultConnection.getItemAt(0)
    }
    private fun options(): IdeOptions { forms.forEach { it.apply() }; return draft.options.copy().also { sourceColors.collect(it); it.validate() } }
    override fun isModified(): Boolean = panel != null && (pendingTokens.isNotEmpty() || forms.any { it.isModified() } || sourceColors.isModified(base.options)
        || !SettingsValues.same(draft, base) || !SettingsValues.same((0 until connections.size()).map { connections[it] }, base.connections))
    @Throws(ConfigurationException::class)
    override fun apply() {
        if (!SettingsValues.same(base, GraylogIdeSettings.get().state)) throw ConfigurationException("Graylog IDE settings changed in another window. Reopen Settings before applying your changes.")
        val value = try { draft.copy().also { it.options = options(); it.connections = (0 until connections.size()).map { index -> connections[index].copy() }; it.validate() } }
            catch (error: IllegalArgumentException) { throw ConfigurationException(error.message.orEmpty()) }
        val credentials = CredentialUpdate(base.connections, value.connections, pendingTokens)
        if (credentials.hasChanges()) {
            val failure = AtomicReference<RuntimeException>()
            ProgressManager.getInstance().runProcessWithProgressSynchronously({ try { credentials.apply(GraylogCredentials.STORE) } catch (error: RuntimeException) { failure.set(error) } }, "Saving Graylog credentials", false, null)
            if (failure.get() != null) throw ConfigurationException("Could not update Password Safe; connection settings were not applied.")
        }
        GraylogIdeSettings.get().replace(value); clearTokens(); base = value.copy(); draft = value.copy(); forms.forEach { it.reset() }
    }
    override fun reset() {
        cancelConnectionTest(); ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach { GraylogSettings.get(it) }
        clearTokens(); base = GraylogIdeSettings.get().state; draft = base.copy()
        connections.clear(); base.connections.forEach { connections.addElement(it.copy()) }; refreshChoices(base.options.defaultConnection)
        if (!connections.isEmpty) list.selectedIndex = 0
        sourceColors.reset(draft.options); forms.forEach { it.reset() }; testStatus.text = " "
    }
    override fun disposeUIResources() { disposed = true; cancelConnectionTest(); if (::executor.isInitialized) executor.shutdownNow(); clearTokens(); forms.clear(); panel = null }
    private fun clearTokens() { pendingTokens.values.forEach(::wipe); pendingTokens.clear() }
    private fun wipe(value: CharArray?) { value?.fill('\u0000') }
}
