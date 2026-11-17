package dev.graylog.jetbrains

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.LocalPathCellEditor
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

class GraylogProjectConfigurable(private val project: Project) : SearchableConfigurable {
    companion object {
        const val ID = "dev.graylog.jetbrains.settings.project"
        private val MODES = listOf("Use IDE default", "Enabled for this project", "Disabled for this project")
    }
    private data class Choice(val id: String, val label: String) { override fun toString() = label }
    private var panel: JPanel? = null
    private var base = ProjectSettingsDraft.from(GraylogSettings.Data())
    private var draft = base.snapshot()
    private var profileDraft = ProjectProfile()
    private var editing = ""
    private var loading = false
    private lateinit var topForm: DialogPanel
    private lateinit var profileForm: DialogPanel
    private lateinit var profile: ComboBox<Choice>
    private lateinit var preferred: ComboBox<Choice>
    private lateinit var highlight: JComboBox<String>
    private lateinit var customize: JCheckBox
    private lateinit var mappingModel: MappingModel
    private lateinit var mappings: JBTable
    private lateinit var service: JBTextField
    private lateinit var exceptionType: JBTextField
    private lateinit var exceptionObject: JBTextField
    private lateinit var file: JBTextField
    private lateinit var line: JBTextField
    private lateinit var stack: JBTextField
    private lateinit var requestId: JBTextField
    private lateinit var traceId: JBTextField

    override fun getId() = ID
    override fun getDisplayName() = "Project settings"
    override fun createComponent(): JComponent {
        profile = SettingsForms.combo(); preferred = SettingsForms.combo()
        topForm = panel {
            group("General") {
                row("Preferred connection:") { cell(preferred).align(AlignX.FILL).resizableColumn().bindItem({ choice(preferred, draft.preferredConnection) }, { draft.preferredConnection = it?.id.orEmpty() }) }
                row("Code highlights:") { highlight = comboBox(MODES).align(AlignX.FILL).resizableColumn().bindItem({ MODES[draft.highlightMode] }, { draft.highlightMode = MODES.indexOf(it).coerceAtLeast(0) }).component }
            }
            group("Connection profile") {
                row("Connection:") { cell(profile) }; row { comment("Connections inherit project defaults unless customized.") }
                row { customize = checkBox("Customize this connection for this project").component }
            }
        }
        mappingModel = MappingModel(); mappings = JBTable(mappingModel).apply {
            putClientProperty("terminateEditOnFocusLost", true); setShowGrid(false); isStriped = true; rowHeight = JBUI.scale(26)
            intercellSpacing = JBUI.size(0, 0); setDefaultRenderer(Any::class.java, ExplorerUi.cells())
            emptyText.text = "Add a mapping from a remote path to a local directory"
            columnModel.getColumn(1).cellEditor = LocalPathCellEditor("Local Source Root", project)
                .fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Local Source Root"))
        }
        val paths = ToolbarDecorator.createDecorator(mappings).disableUpDownActions().setAddAction {
            stopEditing(); mappingModel.rows += PathMapping(); mappingModel.fireTableDataChanged()
            val row = mappingModel.rows.lastIndex; mappings.setRowSelectionInterval(row, row); mappings.editCellAt(row, 0); mappings.editorComponent?.requestFocusInWindow()
        }.setRemoveAction {
            stopEditing(); val row = mappings.selectedRow
            if (row >= 0) { mappingModel.rows.removeAt(mappings.convertRowIndexToModel(row)); mappingModel.fireTableDataChanged() }
        }.apply { setPreferredSize(JBUI.size(420, 140)) }
        profileForm = panel {
            group("Source path mappings") {
                row { cell(paths.createPanel()).align(AlignX.FILL).resizableColumn() }
                row { comment("An empty local root uses the project directory.<br>The longest matching remote prefix takes priority.") }
            }
            group("Log fields") {
                row("Service field:") { service = textField().align(AlignX.FILL).resizableColumn().bindText({ profileDraft.fields.serviceField }, { profileDraft.fields.serviceField = it.trim() }).component }
                row("Exception type field:") { exceptionType = textField().align(AlignX.FILL).resizableColumn().bindText({ profileDraft.fields.exceptionTypeField }, { profileDraft.fields.exceptionTypeField = it.trim() }).component }
                row("Exception object field:") { exceptionObject = textField().align(AlignX.FILL).resizableColumn().bindText({ profileDraft.fields.exceptionObjectField }, { profileDraft.fields.exceptionObjectField = it.trim() }).component }
                row("Source file field:") { file = textField().align(AlignX.FILL).resizableColumn().bindText({ profileDraft.fields.fileField }, { profileDraft.fields.fileField = it.trim() }).component }
                row("Source line field:") { line = textField().align(AlignX.FILL).resizableColumn().bindText({ profileDraft.fields.lineField }, { profileDraft.fields.lineField = it.trim() }).component }
                row("Stack trace field:") { stack = textField().align(AlignX.FILL).resizableColumn().bindText({ profileDraft.fields.stackTraceField }, { profileDraft.fields.stackTraceField = it.trim() }).component }
                row("Request ID field:") { requestId = textField().align(AlignX.FILL).resizableColumn().bindText({ profileDraft.fields.requestIdField }, { profileDraft.fields.requestIdField = it.trim() }).component }
                row("Trace ID field:") { traceId = textField().align(AlignX.FILL).resizableColumn().bindText({ profileDraft.fields.traceIdField }, { profileDraft.fields.traceIdField = it.trim() }).component }
                row { comment("Use dotted names for nested fields, for example context.request_id.") }
            }
        }.apply { border = JBUI.Borders.emptyTop(14) }
        profile.addActionListener {
            if (!loading) {
                storeProfile(); editing = (profile.selectedItem as? Choice)?.id.orEmpty()
                loading = true; customize.isSelected = editing.isBlank() || draft.profiles.any { it.connectionId == editing }; loading = false
                showProfile()
            }
        }
        customize.addActionListener {
            if (!loading && editing.isNotBlank()) {
                if (!customize.isSelected) draft.profiles.removeIf { it.connectionId == editing }
                else if (draft.profiles.none { it.connectionId == editing }) draft.profiles += draft.defaults.copy().apply { connectionId = editing }
                showProfile()
            }
        }
        val content = JPanel(BorderLayout()).apply { add(topForm, BorderLayout.NORTH); add(profileForm) }
        return JPanel(BorderLayout()).also { panel = it; it.add(content, BorderLayout.NORTH) }
    }
    private fun choice(combo: JComboBox<Choice>, id: String): Choice? = (0 until combo.itemCount).map(combo::getItemAt).find { it.id == id }
    private fun stopEditing() { if (mappings.isEditing) mappings.cellEditor.stopCellEditing() }
    private fun storeProfile() {
        stopEditing(); profileForm.apply(); profileDraft.mappings = mappingModel.rows.map { it.copy() }.toMutableList()
        val value = profileDraft.copy().apply { connectionId = editing }
        if (editing.isBlank()) draft.defaults = value
        else if (customize.isSelected) {
            val position = draft.profiles.indexOfFirst { it.connectionId == editing }
            if (position < 0) draft.profiles += value else draft.profiles[position] = value
        }
    }
    private fun showProfile() {
        loading = true
        val existing = if (editing.isBlank()) draft.defaults else draft.profiles.find { it.connectionId == editing }
        profileDraft = (existing ?: draft.defaults).copy()
        customize.isVisible = editing.isNotBlank(); customize.isEnabled = editing.isNotBlank(); customize.isSelected = editing.isBlank() || existing != null
        mappingModel.rows = profileDraft.mappings.map { it.copy() }.toMutableList(); mappingModel.fireTableDataChanged()
        profileForm.reset(); profileForm.isEnabled = editing.isBlank() || existing != null
        enableChildren(profileForm, profileForm.isEnabled); loading = false
    }
    private fun enableChildren(parent: java.awt.Container, enabled: Boolean) {
        for (child in parent.components) { child.isEnabled = enabled; if (child is java.awt.Container) enableChildren(child, enabled) }
    }
    override fun isModified(): Boolean = panel != null && (mappings.isEditing || topForm.isModified() || profileForm.isModified()
        || !SettingsValues.same(mappingModel.rows, profileDraft.mappings) || !SettingsValues.same(draft, base))
    @Throws(ConfigurationException::class)
    override fun apply() {
        storeProfile(); topForm.apply()
        try { GraylogSettings.get(project).applyProjectSettings(base, draft) }
        catch (error: IllegalArgumentException) { throw ConfigurationException(error.message.orEmpty()) }
        catch (error: IllegalStateException) { throw ConfigurationException(error.message.orEmpty()) }
        base = draft.snapshot(); topForm.reset(); showProfile()
    }
    override fun reset() {
        loading = true; base = ProjectSettingsDraft.from(GraylogSettings.get(project).state); draft = base.snapshot(); editing = ""
        profile.removeAllItems(); preferred.removeAllItems(); profile.addItem(Choice("", "Project defaults")); preferred.addItem(Choice("", "IDE default / last used"))
        val servers = GraylogIdeSettings.get().state.connections
        servers.forEach { val value = Choice(it.id, it.name); profile.addItem(value); preferred.addItem(value) }
        draft.profiles.filter { p -> servers.none { it.id == p.connectionId } }.forEach { profile.addItem(Choice(it.connectionId, "Unavailable connection: ${it.connectionId}")) }
        if (base.preferredConnection.isNotBlank() && servers.none { it.id == base.preferredConnection }) preferred.addItem(Choice(base.preferredConnection, "Unavailable connection: ${base.preferredConnection}"))
        topForm.reset(); loading = false; showProfile()
    }
    override fun disposeUIResources() { panel = null }
    private class MappingModel : AbstractTableModel() {
        var rows = mutableListOf<PathMapping>()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Remote source root" else "Local source root"
        override fun isCellEditable(row: Int, column: Int) = true
        override fun getValueAt(row: Int, column: Int): Any = if (column == 0) rows[row].remoteRoot else rows[row].localRoot
        override fun setValueAt(value: Any, row: Int, column: Int) {
            if (column == 0) rows[row].remoteRoot = value.toString().trim() else rows[row].localRoot = value.toString().trim()
            fireTableCellUpdated(row, column)
        }
    }
}
