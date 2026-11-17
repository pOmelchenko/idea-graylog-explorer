package dev.graylog.jetbrains;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.ui.components.*;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.icons.AllIcons;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.JBFont;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public final class GraylogIdeConfigurable implements SearchableConfigurable, Configurable.NoScroll {
    public static final String ID = "dev.graylog.jetbrains.settings";
    private record Choice(String id, String name) { @Override public String toString() { return name; } }
    private JPanel panel;
    private GraylogIdeSettings.Data base;
    private final DefaultListModel<ServerConnection> connections = new DefaultListModel<>();
    private JBList<ServerConnection> list;
    private final Map<String, char[]> pendingTokens = new LinkedHashMap<>();
    private ExecutorService executor;
    private Future<?> testTask;
    private boolean disposed;
    private long testNumber;
    private JLabel testStatus;
    private JPanel connectionDetails;
    private JLabel connectionName;
    private JTextField apiPreview, webPreview;
    private JButton testConnectionButton;
    private JSpinner liveInterval, connectTimeout, requestTimeout, batchSize, samples;
    private JComboBox<String> period;
    private JComboBox<Integer> pageSize;
    private JComboBox<Choice> defaultConnection;
    private JCheckBox liveOnOpen, autoIndex, highlight, gutter, stripe, inlineDetails, inlineValues;
    private JBTextField backgroundLight, backgroundDark, selectedLight, selectedDark;
    private SourceColorSettings sourceColors;

    @Override public String getId() { return ID; }
    @Override public String getDisplayName() { return "Graylog Explorer"; }
    @Override public JComponent createComponent() {
        disposed = false; executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Graylog settings connection test", 2);
        list = new JBList<>(connections); testStatus = new JLabel(" ");
        liveInterval = SettingsForms.number(5, 1, 3600); connectTimeout = SettingsForms.number(10, 1, 300);
        requestTimeout = SettingsForms.number(30, 1, 3600); batchSize = SettingsForms.number(500, 1, 5000); samples = SettingsForms.number(20, 1, 500);
        period = SettingsForms.combo(new String[]{"Last 5 minutes", "Last 15 minutes", "Last hour", "Last 24 hours"});
        pageSize = SettingsForms.combo(new Integer[]{100, 250, 500}); defaultConnection = SettingsForms.combo();
        liveOnOpen = new JCheckBox("Start Live when Explorer opens"); autoIndex = new JCheckBox("Build the whole-period exception index after each search");
        highlight = new JCheckBox("Enable code highlights by default"); gutter = new JCheckBox("Show Graylog gutter markers");
        stripe = new JCheckBox("Show marks on the editor error stripe");
        inlineDetails = new JCheckBox("Show exception messages next to source lines");
        inlineValues = new JCheckBox("Show selected log values beside PHP variables when source mapping is unambiguous");
        backgroundLight = new JBTextField(); backgroundDark = new JBTextField(); selectedLight = new JBTextField(); selectedDark = new JBTextField();
        panel = new JPanel(new BorderLayout(0, 8));
        panel.add(SettingsForms.help("Connections and defaults apply to all projects. Source mappings are under Project settings."), BorderLayout.NORTH);
        JBTabbedPane tabs = new JBTabbedPane(); tabs.addTab("Connections", connectionsPanel());
        SettingsForms behavior = new SettingsForms(); behavior.group("Search defaults");
        behavior.add("Default connection", defaultConnection); behavior.add("Initial search period", period);
        behavior.add("Initial page size", pageSize); behavior.group("Live updates"); behavior.checkbox(liveOnOpen);
        behavior.add("Refresh interval (seconds)", liveInterval); behavior.group("Exception index"); behavior.checkbox(autoIndex);
        behavior.add("Examples per source location", samples);
        tabs.addTab("Search and updates", SettingsForms.tab(behavior.panel()));
        SettingsForms colors = new SettingsForms(); colors.checkbox(highlight); colors.checkbox(gutter);
        colors.checkbox(stripe); colors.checkbox(inlineDetails); colors.checkbox(inlineValues); colors.group("Exception colors");
        colors.add("Background — light theme", SettingsForms.color(backgroundLight));
        colors.add("Background — dark theme", SettingsForms.color(backgroundDark));
        colors.add("Selected exception — light theme", SettingsForms.color(selectedLight));
        colors.add("Selected exception — dark theme", SettingsForms.color(selectedDark));
        tabs.addTab("Highlighting", SettingsForms.tab(colors.panel()));
        sourceColors = new SourceColorSettings(); tabs.addTab("Source colors", sourceColors);
        SettingsForms network = new SettingsForms(); network.add("Connect timeout (seconds)", connectTimeout);
        network.add("Request timeout (seconds)", requestTimeout); network.add("Scroll export batch size", batchSize);
        tabs.addTab("Network", SettingsForms.tab(network.panel())); panel.add(tabs, BorderLayout.CENTER);
        return panel;
    }

    private JComponent connectionsPanel() {
        JPanel view = new JPanel(new BorderLayout(12, 0)); view.setBorder(JBUI.Borders.emptyTop(12));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(JBUI.scale(26)); list.getEmptyText().setText("No Graylog connections");
        list.setCellRenderer(new ColoredListCellRenderer<ServerConnection>() {
            @Override protected void customizeCellRenderer(JList<? extends ServerConnection> list, ServerConnection value, int index, boolean selected, boolean focus) {
                append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES); setIcon(AllIcons.Nodes.DataTables);
            }
        });
        connectionName = new JLabel(); connectionName.setFont(JBFont.regular().asBold());
        apiPreview = previewField(); webPreview = previewField(); testConnectionButton = new JButton("Test connection");
        testConnectionButton.addActionListener(e -> testConnection()); testStatus.setForeground(UIUtil.getContextHelpForeground()); testStatus.setVisible(false);
        SettingsForms info = new SettingsForms(); info.component(connectionName); info.add("API URL", apiPreview); info.add("Web URL", webPreview);
        info.add("Access token", new JLabel("Stored in Password Safe")); info.component(testConnectionButton); info.component(testStatus);
        connectionDetails = new JPanel(new java.awt.CardLayout());
        JLabel empty = new JLabel("Select or add a connection", JLabel.CENTER); empty.setForeground(UIUtil.getContextHelpForeground());
        connectionDetails.add(empty, "empty"); connectionDetails.add(info.panel(), "details");
        connectionDetails.setBorder(JBUI.Borders.emptyLeft(12));
        list.addListSelectionListener(e -> showConnection());
        ToolbarDecorator controls = ToolbarDecorator.createDecorator(list).disableUpDownActions()
                .setAddAction(e -> editConnection(null)).setEditAction(e -> { if (list.getSelectedValue() != null) editConnection(list.getSelectedValue()); });
        controls.setRemoveAction(e -> {
            int selected = list.getSelectedIndex(); if (selected < 0) return;
            String id = connections.get(selected).id; String before = selectedDefault();
            connections.remove(selected); wipe(pendingTokens.remove(id)); refreshChoices(id.equals(before) ? "" : before);
        });
        OnePixelSplitter split = new OnePixelSplitter(false, .32f); split.setHonorComponentsMinimumSize(false);
        split.setFirstComponent(controls.createPanel()); split.setSecondComponent(connectionDetails); view.add(split); return view;
    }

    private static JTextField previewField() {
        JTextField field = new JBTextField(); field.setEditable(false); field.setOpaque(false); field.setBorder(JBUI.Borders.empty()); field.setFont(UIUtil.getLabelFont()); return field;
    }
    private void showConnection() {
        ServerConnection c = list.getSelectedValue();
        ((java.awt.CardLayout) connectionDetails.getLayout()).show(connectionDetails, c == null ? "empty" : "details");
        connectionName.setText(c == null ? "" : c.name); apiPreview.setText(c == null ? "" : c.apiUrl); webPreview.setText(c == null ? "" : c.webUrl);
        apiPreview.setCaretPosition(0); webPreview.setCaretPosition(0); testConnectionButton.setEnabled(c != null); testStatus.setVisible(false);
    }

    private void editConnection(ServerConnection existing) {
        ConnectionDialog dialog = new ConnectionDialog(null, existing == null ? null : existing.connection());
        if (!dialog.showAndGet()) return;
        ServerConnection server = ServerConnection.from(dialog.connection()); char[] token = dialog.token();
        if (token.length > 0) { wipe(pendingTokens.put(server.id, token)); server.credentialId = UUID.randomUUID().toString(); }
        else wipe(token);
        int position = -1;
        for (int i = 0; i < connections.size(); i++) if (connections.get(i).id.equals(server.id)) position = i;
        if (position < 0) { connections.addElement(server); position = connections.size() - 1; }
        else connections.set(position, server);
        list.setSelectedIndex(position); showConnection(); refreshChoices(selectedDefault());
    }

    private void testConnection() {
        ServerConnection selected = list.getSelectedValue(); if (selected == null) { testStatus.setText("Select a connection first."); return; }
        IdeOptions options;
        try { options = options(); options.validate(); selected.validate(); } catch (IllegalArgumentException e) { testStatus.setText(e.getMessage()); testStatus.setVisible(true); return; }
        ServerConnection server = selected.copy(); char[] staged = pendingTokens.get(server.id);
        char[] token = staged == null ? null : staged.clone();
        if (testTask != null) testTask.cancel(true); long sequence = ++testNumber; testStatus.setText("Testing connection…"); testStatus.setVisible(true);
        testTask = executor.submit(() -> {
            String message;
            try {
                String secret = token == null ? GraylogCredentials.load(server.credentialKey()) : new String(token);
                try (GraylogClient api = new GraylogClient(server.apiUrl, secret, options, new LogFormat())) {
                    message = "Connected: " + api.streams().size() + " accessible stream(s).";
                }
            } catch (Exception e) { message = "Connection failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
            finally { wipe(token); }
            String result = message;
            SwingUtilities.invokeLater(() -> { if (!disposed && sequence == testNumber) { testStatus.setText(result); testStatus.setVisible(true); } });
        });
    }

    private String selectedDefault() { Choice item = (Choice) defaultConnection.getSelectedItem(); return item == null ? "" : item.id; }
    private void refreshChoices(String selected) {
        defaultConnection.removeAllItems(); defaultConnection.addItem(new Choice("", "First available connection"));
        for (int i = 0; i < connections.size(); i++) {
            ServerConnection c = connections.get(i); Choice item = new Choice(c.id, c.name); defaultConnection.addItem(item);
            if (c.id.equals(selected)) defaultConnection.setSelectedItem(item);
        }
    }
    private IdeOptions options() {
        IdeOptions options = new IdeOptions(); options.liveIntervalSeconds = SettingsForms.number(liveInterval);
        options.connectTimeoutSeconds = SettingsForms.number(connectTimeout); options.requestTimeoutSeconds = SettingsForms.number(requestTimeout);
        options.exportBatchSize = SettingsForms.number(batchSize); options.examplesPerLocation = SettingsForms.number(samples);
        options.defaultPeriodSeconds = new int[]{300, 900, 3600, 86400}[period.getSelectedIndex()];
        options.defaultPageSize = (Integer) pageSize.getSelectedItem(); options.defaultConnection = selectedDefault();
        options.liveOnOpen = liveOnOpen.isSelected(); options.autoIndex = autoIndex.isSelected(); options.highlightByDefault = highlight.isSelected();
        options.gutterIcons = gutter.isSelected(); options.errorStripe = stripe.isSelected(); options.inlineDetails = inlineDetails.isSelected(); options.inlineValues = inlineValues.isSelected();
        options.backgroundLight = backgroundLight.getText().trim(); options.backgroundDark = backgroundDark.getText().trim();
        options.selectedLight = selectedLight.getText().trim(); options.selectedDark = selectedDark.getText().trim(); sourceColors.collect(options); return options;
    }
    private GraylogIdeSettings.Data draft() {
        GraylogIdeSettings.Data state = base.copy(); state.options = options(); state.connections.clear();
        for (int i = 0; i < connections.size(); i++) state.connections.add(connections.get(i).copy()); return state;
    }
    @Override public boolean isModified() {
        if (panel == null || base == null) return false;
        if (sourceColors.isEditing()) return true;
        try { return !pendingTokens.isEmpty() || !SettingsValues.same(draft(), GraylogIdeSettings.get().getState()); }
        catch (IllegalArgumentException invalidDraft) { return true; }
    }

    @Override public void apply() throws ConfigurationException {
        if (!SettingsValues.same(base, GraylogIdeSettings.get().getState())) {
            throw new ConfigurationException("Graylog IDE settings changed in another window. Reopen Settings before applying your changes.");
        }
        GraylogIdeSettings.Data state;
        try { state = draft(); } catch (IllegalArgumentException e) { throw new ConfigurationException(e.getMessage()); }
        try { state.validate(); } catch (IllegalArgumentException e) { throw new ConfigurationException(e.getMessage()); }
        Set<String> retained = new HashSet<>(); state.connections.forEach(c -> retained.add(c.credentialKey()));
        Set<String> removed = new HashSet<>(); GraylogIdeSettings.get().getState().connections.forEach(c -> {
            boolean deleted = state.connections.stream().noneMatch(server -> server.id.equals(c.id));
            // Keep legacy ID-based entries on rotation: unopened projects may still need their migration.
            if (!retained.contains(c.credentialKey()) && (deleted || !c.credentialId.isBlank())) removed.add(c.credentialKey());
        });
        if (!pendingTokens.isEmpty() || !removed.isEmpty()) {
            AtomicReference<RuntimeException> failure = new AtomicReference<>();
            ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                Map<String, String> previous = new LinkedHashMap<>();
                try {
                    Set<String> keys = new LinkedHashSet<>();
                    for (ServerConnection server : state.connections) if (pendingTokens.containsKey(server.id)) keys.add(server.credentialKey());
                    keys.addAll(removed);
                    for (String key : keys) previous.put(key, GraylogCredentials.load(key));
                    for (ServerConnection server : state.connections) if (pendingTokens.containsKey(server.id)) {
                        GraylogCredentials.save(server.credentialKey(), new String(pendingTokens.get(server.id)));
                    }
                    for (String key : removed) GraylogCredentials.remove(key);
                } catch (RuntimeException e) {
                    previous.forEach((key, value) -> { try { GraylogCredentials.save(key, value); } catch (RuntimeException ignored) { } });
                    failure.set(e);
                }
            }, "Saving Graylog credentials", false, null);
            if (failure.get() != null) throw new ConfigurationException("Could not update Password Safe; connection settings were not applied.");
        }
        GraylogIdeSettings.get().replace(state); clearTokens(); base = state.copy();
    }
    @Override public void reset() {
        testNumber++; if (testTask != null) testTask.cancel(true);
        for (var project : com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) GraylogSettings.get(project);
        }
        clearTokens(); base = GraylogIdeSettings.get().getState(); connections.clear();
        base.connections.forEach(c -> connections.addElement(c.copy())); refreshChoices(base.options.defaultConnection);
        if (!connections.isEmpty()) list.setSelectedIndex(0);
        IdeOptions o = base.options; liveInterval.setValue(o.liveIntervalSeconds); connectTimeout.setValue(o.connectTimeoutSeconds);
        requestTimeout.setValue(o.requestTimeoutSeconds); batchSize.setValue(o.exportBatchSize); samples.setValue(o.examplesPerLocation);
        for (int i = 0; i < 4; i++) if (new int[]{300, 900, 3600, 86400}[i] == o.defaultPeriodSeconds) period.setSelectedIndex(i);
        pageSize.setSelectedItem(o.defaultPageSize); liveOnOpen.setSelected(o.liveOnOpen); autoIndex.setSelected(o.autoIndex);
        highlight.setSelected(o.highlightByDefault); gutter.setSelected(o.gutterIcons); stripe.setSelected(o.errorStripe);
        inlineDetails.setSelected(o.inlineDetails); inlineValues.setSelected(o.inlineValues);
        sourceColors.reset(o);
        backgroundLight.setText(o.backgroundLight); backgroundDark.setText(o.backgroundDark);
        selectedLight.setText(o.selectedLight); selectedDark.setText(o.selectedDark); testStatus.setText(" ");
    }
    @Override public void disposeUIResources() {
        disposed = true; testNumber++; if (testTask != null) testTask.cancel(true); if (executor != null) executor.shutdownNow();
        clearTokens(); panel = null; base = null;
    }
    private void clearTokens() { pendingTokens.values().forEach(GraylogIdeConfigurable::wipe); pendingTokens.clear(); }
    private static void wipe(char[] token) { if (token != null) Arrays.fill(token, '\0'); }
}
