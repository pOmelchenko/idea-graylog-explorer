package dev.graylog.jetbrains;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.util.ui.LocalPathCellEditor;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

public final class GraylogProjectConfigurable implements SearchableConfigurable {
    public static final String ID = "dev.graylog.jetbrains.settings.project";
    private record Choice(String id, String label) { @Override public String toString() { return label; } }
    private final Project project;
    private JPanel panel;
    private GraylogSettings.Data base, draft;
    private JComboBox<Choice> profile, preferred;
    private JComboBox<String> highlight;
    private JCheckBox customize;
    private String editing = "";
    private boolean loading;
    private MappingModel mappingModel;
    private JBTable mappings;
    private JBTextField service, exceptionType, exceptionObject, file, line, stack, requestId, traceId;
    private JPanel profileForm;

    public GraylogProjectConfigurable(Project project) { this.project = project; }
    @Override public String getId() { return ID; }
    @Override public String getDisplayName() { return "Project settings"; }
    @Override public JComponent createComponent() {
        panel = new JPanel(new BorderLayout());
        profile = SettingsForms.combo(); preferred = SettingsForms.combo();
        highlight = SettingsForms.combo(new String[]{"Use IDE default", "Enabled for this project", "Disabled for this project"});
        customize = new JCheckBox("Customize this connection for this project");
        service = new JBTextField(); exceptionType = new JBTextField(); exceptionObject = new JBTextField();
        file = new JBTextField(); line = new JBTextField(); stack = new JBTextField(); requestId = new JBTextField(); traceId = new JBTextField();
        SettingsForms top = new SettingsForms(); top.group("General"); top.add("Preferred connection", preferred); top.add("Code highlights", highlight);
        top.group("Connection profile"); top.add("Connection", profile);
        top.comment("Connections inherit project defaults unless customized."); top.checkbox(customize);
        mappingModel = new MappingModel(); mappings = new JBTable(mappingModel);
        mappings.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        mappings.setShowGrid(false); mappings.setStriped(true); mappings.setRowHeight(JBUI.scale(26));
        mappings.setIntercellSpacing(JBUI.size(0, 0)); mappings.setDefaultRenderer(Object.class, ExplorerUi.cells());
        mappings.getEmptyText().setText("Add a mapping from a remote path to a local directory");
        mappings.getColumnModel().getColumn(1).setCellEditor(new LocalPathCellEditor("Local Source Root", project)
                .fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Local Source Root")));
        ToolbarDecorator paths = ToolbarDecorator.createDecorator(mappings).disableUpDownActions().setAddAction(e -> {
            stopEditing(); mappingModel.rows.add(new PathMapping()); mappingModel.fireTableDataChanged();
            int row = mappingModel.rows.size() - 1; mappings.setRowSelectionInterval(row, row); mappings.editCellAt(row, 0);
            if (mappings.getEditorComponent() != null) mappings.getEditorComponent().requestFocusInWindow();
        }).setRemoveAction(e -> {
            stopEditing(); int row = mappings.getSelectedRow();
            if (row >= 0) { mappingModel.rows.remove(mappings.convertRowIndexToModel(row)); mappingModel.fireTableDataChanged(); }
        });
        paths.setPreferredSize(JBUI.size(420, 140));
        SettingsForms fields = new SettingsForms(); fields.group("Source path mappings"); fields.component(paths.createPanel());
        fields.comment("An empty local root uses the project directory.<br>The longest matching remote prefix takes priority.");
        fields.group("Log fields"); fields.add("Service field", service); fields.add("Exception type field", exceptionType);
        fields.add("Exception object field", exceptionObject); fields.add("Source file field", file); fields.add("Source line field", line);
        fields.add("Stack trace field", stack); fields.add("Request ID field", requestId); fields.add("Trace ID field", traceId);
        fields.comment("Use dotted names for nested fields, for example context.request_id.");
        profileForm = fields.panel();
        profileForm.setBorder(JBUI.Borders.emptyTop(14));
        JPanel content = new JPanel(new BorderLayout()); content.add(top.panel(), BorderLayout.NORTH); content.add(profileForm, BorderLayout.CENTER);
        panel.add(content, BorderLayout.NORTH);
        profile.addActionListener(e -> {
            if (loading || draft == null) return;
            storeProfile(); Choice choice = (Choice) profile.getSelectedItem(); editing = choice == null ? "" : choice.id;
            loading = true; customize.setSelected(editing.isBlank() || draft.profiles.stream().anyMatch(p -> p.connectionId.equals(editing))); loading = false;
            showProfile();
        });
        customize.addActionListener(e -> {
            if (loading || draft == null || editing.isBlank()) return;
            if (!customize.isSelected()) draft.profiles.removeIf(p -> p.connectionId.equals(editing));
            showProfile();
        });
        return panel;
    }

    private void stopEditing() { if (mappings != null && mappings.isEditing()) mappings.getCellEditor().stopCellEditing(); }
    private ProjectProfile readProfile() {
        stopEditing(); ProjectProfile value = new ProjectProfile(); value.connectionId = editing;
        value.mappings = new ArrayList<>(mappingModel.rows.stream().map(PathMapping::copy).toList());
        value.fields.serviceField = service.getText().trim(); value.fields.exceptionTypeField = exceptionType.getText().trim();
        value.fields.exceptionObjectField = exceptionObject.getText().trim(); value.fields.fileField = file.getText().trim();
        value.fields.lineField = line.getText().trim(); value.fields.stackTraceField = stack.getText().trim();
        value.fields.requestIdField = requestId.getText().trim(); value.fields.traceIdField = traceId.getText().trim(); return value;
    }
    private void storeProfile() {
        if (draft == null || loading) return;
        if (editing.isBlank()) draft.defaults = readProfile();
        else if (customize.isSelected()) {
            ProjectProfile value = readProfile(); int position = -1;
            for (int i = 0; i < draft.profiles.size(); i++) if (draft.profiles.get(i).connectionId.equals(editing)) { position = i; break; }
            if (position < 0) draft.profiles.add(value); else draft.profiles.set(position, value);
        }
    }
    private void showProfile() {
        loading = true;
        ProjectProfile existing = editing.isBlank() ? draft.defaults : draft.profiles.stream().filter(p -> p.connectionId.equals(editing)).findFirst().orElse(null);
        boolean custom = existing != null;
        // Checking Customize creates a copy of the current project defaults.
        if (!editing.isBlank() && customize.isSelected() && existing == null) {
            existing = draft.defaults.copy(); existing.connectionId = editing; draft.profiles.add(existing); custom = true;
        }
        if (existing == null) existing = draft.defaults;
        customize.setVisible(!editing.isBlank()); customize.setEnabled(!editing.isBlank()); customize.setSelected(editing.isBlank() || custom);
        mappingModel.rows = new ArrayList<>(existing.mappings.stream().map(PathMapping::copy).toList()); mappingModel.fireTableDataChanged();
        LogFormat f = existing.fields; service.setText(f.serviceField); exceptionType.setText(f.exceptionTypeField);
        exceptionObject.setText(f.exceptionObjectField); file.setText(f.fileField); line.setText(f.lineField);
        stack.setText(f.stackTraceField); requestId.setText(f.requestIdField); traceId.setText(f.traceIdField);
        enable(profileForm, editing.isBlank() || custom); loading = false;
    }
    private static void enable(java.awt.Container panel, boolean value) {
        for (java.awt.Component child : panel.getComponents()) { child.setEnabled(value); if (child instanceof java.awt.Container container) enable(container, value); }
    }
    private GraylogSettings.Data collect() {
        storeProfile(); GraylogSettings.Data value = draft.copy(); value.highlightMode = highlight.getSelectedIndex();
        Choice choice = (Choice) preferred.getSelectedItem(); value.preferredConnection = choice == null ? "" : choice.id;
        if (!value.preferredConnection.equals(base.preferredConnection)) value.selectedConnection = value.preferredConnection;
        return value;
    }
    @Override public boolean isModified() {
        if (panel == null || draft == null) return false;
        // Settings polls this method while the user types; only Apply/profile switches finish table editing.
        return mappings.isEditing() || !SettingsValues.same(collect(), GraylogSettings.get(project).getState());
    }
    @Override public void apply() throws ConfigurationException {
        if (!SettingsValues.same(base, GraylogSettings.get(project).getState())) {
            throw new ConfigurationException("Project settings changed while this page was open. Reopen Settings before applying your changes.");
        }
        GraylogSettings.Data value = collect();
        try { value.validate(); } catch (IllegalArgumentException e) { throw new ConfigurationException(e.getMessage()); }
        GraylogSettings.get(project).replace(value); base = value.copy(); draft = value.copy();
    }
    @Override public void reset() {
        loading = true; base = GraylogSettings.get(project).getState(); draft = base.copy(); editing = "";
        profile.removeAllItems(); preferred.removeAllItems(); profile.addItem(new Choice("", "Project defaults"));
        preferred.addItem(new Choice("", "IDE default / last used"));
        var servers = GraylogIdeSettings.get().getState().connections;
        for (ServerConnection server : servers) {
            Choice choice = new Choice(server.id, server.name); profile.addItem(choice); preferred.addItem(choice);
            if (server.id.equals(base.preferredConnection)) preferred.setSelectedItem(choice);
        }
        for (ProjectProfile p : draft.profiles) if (servers.stream().noneMatch(c -> c.id.equals(p.connectionId))) {
            profile.addItem(new Choice(p.connectionId, "Unavailable connection: " + p.connectionId));
        }
        if (!base.preferredConnection.isBlank() && servers.stream().noneMatch(c -> c.id.equals(base.preferredConnection))) {
            Choice missing = new Choice(base.preferredConnection, "Unavailable connection: " + base.preferredConnection);
            preferred.addItem(missing); preferred.setSelectedItem(missing);
        }
        highlight.setSelectedIndex(base.highlightMode); customize.setSelected(true); loading = false; showProfile();
    }
    @Override public void disposeUIResources() { panel = null; base = null; draft = null; }

    private static final class MappingModel extends AbstractTableModel {
        List<PathMapping> rows = new ArrayList<>();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int column) { return column == 0 ? "Remote source root" : "Local source root"; }
        @Override public boolean isCellEditable(int row, int column) { return true; }
        @Override public Object getValueAt(int row, int column) { return column == 0 ? rows.get(row).remoteRoot : rows.get(row).localRoot; }
        @Override public void setValueAt(Object value, int row, int column) {
            if (column == 0) rows.get(row).remoteRoot = value.toString().trim(); else rows.get(row).localRoot = value.toString().trim(); fireTableCellUpdated(row, column);
        }
    }
}
