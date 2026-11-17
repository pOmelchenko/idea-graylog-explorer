package dev.graylog.jetbrains;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.SearchFieldWithExtension;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.SideBorder;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBList;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.fields.ExtendableTextField;
import javax.swing.event.DocumentEvent;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.ui.tabs.JBTabsFactory;
import com.intellij.ui.tabs.TabInfo;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class GraylogPanel extends JPanel implements Disposable {
    private record Period(String label, int seconds) { @Override public String toString() { return label; } }
    private record Node(Connection connection, String title, String streamId, GraylogSettings.SavedSearch saved) {
        String key() { return connection.id + ":" + (saved != null ? "saved:" + saved.id : streamId); }
        @Override public String toString() { return title; }
    }
    private static final Period[] PERIODS = { new Period("Last 5 minutes", 300), new Period("Last 15 minutes", 900),
            new Period("Last hour", 3600), new Period("Last 24 hours", 86400) };
    private static final Color ERROR_COLOR = new JBColor(new Color(175, 41, 45), new Color(255, 113, 119));
    private static final Color WARN_COLOR = new JBColor(new Color(145, 99, 10), new Color(231, 181, 73));
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Project project;
    private final ToolWindow toolWindow;
    private final GraylogSettings settings;
    private volatile IdeOptions ideOptions;
    private List<ServerConnection> knownServers;
    private final ExceptionHighlights highlights;
    private final ExecutorService executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Graylog Explorer", 3);
    private final Timer timer;
    private final Tree tree = new Tree(new DefaultMutableTreeNode("Connections"));
    private final ExtendableTextField query = ExplorerUi.filterField("Graylog query", () -> search(true));
    private final SearchTextField connectionSearch = new SearchTextField(false);
    private final com.intellij.ui.components.JBTextField connectionFilter = connectionSearch.getTextEditor();
    private final QueryCompletion completion = new QueryCompletion(query);
    private final SourceFilter sourceFilter = new SourceFilter();
    private String sourceFilterContext = "";
    private SearchRequest baseSnapshot;
    private SourceFilterPopup sourcePopup;
    private Future<?> sourceTask;
    private long sourceNumber;
    private SearchRequest cachedSourceScope;
    private String cachedSourceContext = "";
    private GraylogClient.Sources cachedSources;
    private long cachedSourcesAt;
    private GraylogClient.Sources visibleSources;
    private SearchRequest visibleSourceScope;
    private String visibleSourceContext = "", sourceError = "", sourceSummary = "Loading sources…";
    private boolean sourcesLoading;
    private final java.util.Set<String> collapsedSourceGroups = new java.util.HashSet<>();
    private final JComboBox<Period> period = new JComboBox<>(PERIODS);
    private final JComboBox<Integer> pageSize = new JComboBox<>(new Integer[]{100, 250, 500});
    private final JToggleButton live = new JToggleButton("Live · 5s");
    private final JToggleButton codeMarks = new JToggleButton("Code marks", true);
    private final JLabel status = new JLabel("Add a connection to start searching Graylog.");
    private final JLabel indexStatus = new JLabel("Exceptions: select a connection and search.");
    private final JButton indexControl = new JButton("Refresh marks");
    private final JLabel streamStatus = new JLabel(" ");
    private final EventTableModel eventsModel = new EventTableModel();
    private final JBTable events = new JBTable(eventsModel);
    private final JTextArea message = textArea(true);
    private final JTextArea rawJson = textArea(false);
    private final JTextArea rawStack = textArea(false);
    private final FieldTableModel fieldsModel = new FieldTableModel();
    private final DefaultListModel<SourceLocation> locationsModel = new DefaultListModel<>();
    private final JBList<SourceLocation> locations = new JBList<>(locationsModel);
    private final JLabel sourceMapping = new JLabel(" ");
    private final JButton previous = new JButton("Previous");
    private final JButton next = new JButton("Next");
    private final JButton follow = new JButton("Follow request");
    private final JButton around = new JButton("Show logs around");
    private final JButton open = new JButton("Open in Editor");
    private final JButton browser = new JButton("Open Graylog");
    private final JButton allEvents = new JButton("All loaded events");
    private JBTabs inspectorTabs;
    private final CardLayout inspectorLayout = new CardLayout();
    private final JPanel inspectorBody = new JPanel(inspectorLayout);
    private final JLabel eventLevel = new JLabel();
    private final Map<String, List<GraylogClient.Stream>> streams = new HashMap<>();
    private final Map<String, Long> streamRequests = new HashMap<>();
    private Connection active;
    private String selectedStream = "";
    private String selectedNodeKey = "";
    private SearchRequest snapshot;
    private SearchRequest loadedRequest;
    private List<LogEvent> loadedEvents = List.of();
    private long loadedTotal;
    private String resultsSummary = "";
    private LogEvent selected;
    private String restoreSelection = "";
    private Future<?> searchTask;
    private Future<?> indexTask;
    private SearchRequest indexRequest;
    private String indexConnection = "";
    private long indexNumber;
    private boolean indexBusy;
    private GraylogClient.PeriodIndex periodIndex;
    private long requestNumber;
    private boolean rebuilding;
    private boolean busy;
    private OnePixelSplitter navigationSplitter;
    private JComponent navigationTree;
    private boolean connectionsVisible = true;
    private volatile boolean disposed;

    public GraylogPanel(Project project, ToolWindow toolWindow) {
        super(new BorderLayout());
        this.project = project; this.toolWindow = toolWindow; settings = GraylogSettings.get(project);
        ideOptions = GraylogIdeSettings.get().getState().options;
        knownServers = GraylogIdeSettings.get().getState().connections;
        highlights = new ExceptionHighlights(project, this, this::showSite);
        GraylogSettings.Data saved = settings.getState();
        codeMarks.setSelected(settings.highlightsEnabled()); highlights.configure(ideOptions); highlights.setEnabled(codeMarks.isSelected());
        query.setText(saved.lastQuery); choosePeriod(saved.rangeSeconds == 0 ? ideOptions.defaultPeriodSeconds : saved.rangeSeconds);
        pageSize.setSelectedItem(saved.pageSize == 0 ? ideOptions.defaultPageSize : saved.pageSize);
        live.setSelected(ideOptions.liveOnOpen); live.setText(liveLabel());
        query.getEmptyText().setText("Graylog query, e.g. request_id:abc-123");
        updateContext("No connection");

        OnePixelSplitter content = new OnePixelSplitter(false, 0.66f);
        content.setHonorComponentsMinimumSize(false);
        content.setFirstComponent(createSearchPanel()); content.setSecondComponent(createInspector());
        OnePixelSplitter all = navigationSplitter = new OnePixelSplitter(false, "graylog.explorer.connections.proportion", 0.22f);
        all.setHonorComponentsMinimumSize(false);
        navigationTree = createTreePanel(); all.setFirstComponent(navigationTree); all.setSecondComponent(content);
        add(createActionRail(), BorderLayout.WEST);
        add(all, BorderLayout.CENTER);
        setConnectionsVisible(PropertiesComponent.getInstance(project).getBoolean("graylog.explorer.connections.visible", true));
        indexControl.setEnabled(false);
        add(ExplorerUi.bar(indexStatus, ExplorerUi.toolbar("Graylog.PeriodIndex", this,
                ExplorerUi.button(indexControl, AllIcons.Actions.Refresh, true)), true), BorderLayout.SOUTH);
        setMinimumSize(JBUI.size(750, 300));

        query.addActionListener(e -> search(true));
        query.registerKeyboardAction(e -> search(true), KeyStroke.getKeyStroke("control ENTER"), JComponent.WHEN_FOCUSED);
        period.addActionListener(e -> { snapshot = null; search(true); });
        pageSize.addActionListener(e -> {
            if (snapshot != null && snapshot.query().equals(effectiveQuery())
                    && snapshot.streamId().equals(selectedStream)) {
                baseSnapshot = new SearchRequest(query.getText(), selectedStream, snapshot.from(), snapshot.to(), 0, (Integer) pageSize.getSelectedItem());
                snapshot = sourceFilter.apply(baseSnapshot, active.serviceField);
                executeSearch(snapshot);
            } else search(true);
        });
        live.addActionListener(e -> { if (live.isSelected()) search(true); });
        codeMarks.addActionListener(e -> {
            highlights.setEnabled(codeMarks.isSelected()); settings.setHighlightExceptions(codeMarks.isSelected());
            if (!codeMarks.isSelected()) { resetIndex(); indexStatus.setText("Exception marks disabled."); }
            else if (active != null && loadedRequest != null) startIndex(active.copy(), loadedRequest, true);
        });
        indexControl.addActionListener(e -> {
            if (indexBusy) {
                stopIndex(); live.setSelected(false);
                indexStatus.setText("Exception scan canceled · period index incomplete."); indexControl.setText("Retry marks");
            } else if (active != null && loadedRequest != null) startIndex(active.copy(), loadedRequest, true);
        });
        allEvents.addActionListener(e -> {
            displayEvents(loadedEvents); allEvents.setEnabled(false); updatePaging(); setStatus(resultsSummary, false);
        });
        previous.addActionListener(e -> page(-1)); next.addActionListener(e -> page(1));
        timer = new Timer(ideOptions.liveIntervalSeconds * 1000, e -> {
            if (live.isSelected() && toolWindow.isVisible() && isShowing() && !busy && !indexBusy && active != null) search(true);
        });
        timer.start();
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(SettingsEvents.TOPIC, changed -> {
            IdeOptions nextOptions = GraylogIdeSettings.get().getState().options;
            IdeOptions before = ideOptions.copy(); before.sourceStripes = nextOptions.sourceStripes; before.sourceColors = nextOptions.sourceColors;
            if (changed == null && !SettingsValues.same(ideOptions, nextOptions) && SettingsValues.same(before, nextOptions)
                    && SettingsValues.same(knownServers, GraylogIdeSettings.get().getState().connections)) { ideOptions = nextOptions; events.repaint(); tree.repaint(); if (sourcePopup != null && sourcePopup.alive()) sourcePopup.popup().getContent().repaint(); }
            else if (changed == null || changed == project) reloadSettings();
        });
        rebuildTree();
        String initial = initialConnection(); if (!initial.isBlank()) later(() -> selectNode(initial + ":"));
    }

    JComponent searchField() { return query; }
    public void searchText(String value) { query.setText(value); search(true); query.requestFocusInWindow(); }

    private JComponent createActionRail() {
        JPanel rail = new JPanel(new BorderLayout());
        AnAction visibility = new DumbAwareAction("Show/hide connections", "Hide connections", AllIcons.Actions.ArrowCollapse) {
            @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void update(AnActionEvent event) {
                event.getPresentation().setText(connectionsVisible ? "Hide connections" : "Show connections");
                event.getPresentation().setDescription(connectionsVisible ? "Hide connections" : "Show connections");
                event.getPresentation().setIcon(connectionsVisible ? AllIcons.Actions.ArrowCollapse : AllIcons.Actions.ArrowExpand);
            }
            @Override public void actionPerformed(AnActionEvent event) { setConnectionsVisible(!connectionsVisible); }
        };
        DefaultTreeExpander expander = new DefaultTreeExpander(tree);
        DefaultActionGroup options = new DefaultActionGroup("Graylog settings", true);
        options.getTemplatePresentation().setIcon(AllIcons.General.GearPlain);
        options.add(ExplorerUi.command("Graylog Explorer settings", AllIcons.General.Settings,
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, GraylogIdeConfigurable.class), () -> true));
        options.add(ExplorerUi.command("Project source mappings and log fields", AllIcons.Nodes.Folder,
                () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, GraylogProjectConfigurable.class), () -> true));
        DefaultActionGroup expanded = new DefaultActionGroup(
                Separator.getInstance(),
                ExplorerUi.command("Add connection", AllIcons.General.Add, () -> editConnection(null), () -> true),
                ExplorerUi.command("Edit selected connection", AllIcons.Actions.Edit, () -> editConnection(active), () -> active != null),
                ExplorerUi.command("Remove selected connection or saved search", AllIcons.General.Remove, this::removeSelected,
                        () -> tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Node),
                ExplorerUi.command("Refresh streams and sources", AllIcons.Actions.Refresh, () -> { loadStreams(active); loadSources(active.copy(), sourceScope(), true); }, () -> active != null),
                com.intellij.openapi.actionSystem.Separator.getInstance(),
                options,
                ExplorerUi.command("Expand all connections", AllIcons.Actions.Expandall, () -> changeTreeExpansion(expander, true), () -> connectionsVisible),
                ExplorerUi.command("Collapse all connections", AllIcons.Actions.Collapseall, () -> changeTreeExpansion(expander, false), () -> connectionsVisible)) {
            @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void update(AnActionEvent event) { event.getPresentation().setVisible(connectionsVisible); }
        };
        JComponent commands = ExplorerUi.toolbar("Graylog.ActionRail", this, false, visibility, expanded);
        rail.add(commands, BorderLayout.NORTH);
        return rail;
    }

    private void setConnectionsVisible(boolean visible) {
        connectionsVisible = visible; navigationTree.setVisible(visible);
        navigationSplitter.setFirstComponent(visible ? navigationTree : null);
        PropertiesComponent.getInstance(project).setValue("graylog.explorer.connections.visible", visible, true);
        revalidate(); repaint();
    }
    private void changeTreeExpansion(DefaultTreeExpander expander, boolean expand) {
        boolean before = rebuilding; rebuilding = true;
        try {
            if (expand) { expander.expandAll(); if (!selectedNodeKey.isBlank()) selectNode(selectedNodeKey); }
            else expander.collapseAll();
        } finally { rebuilding = before; }
    }

    private JComponent createTreePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(IdeBorderFactory.createBorder(SideBorder.LEFT));
        connectionFilter.getEmptyText().setText("Connection, stream or source");
        panel.add(new SearchFieldWithExtension(new com.intellij.ui.components.JBLabel(), connectionSearch), BorderLayout.NORTH);
        connectionFilter.setToolTipText("Filter connection, stream, source and saved-search names. This does not change the log query.");
        connectionFilter.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(DocumentEvent event) { rebuildTree(); }
        });
        tree.setRootVisible(false); tree.setShowsRootHandles(true);
        tree.getEmptyText().setText("No matching connections or streams.");
        tree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new ColoredTreeCellRenderer() {
            @Override public void customizeCellRenderer(javax.swing.JTree t, Object value, boolean selected,
                    boolean expanded, boolean leaf, int row, boolean focus) {
                Object user = ((DefaultMutableTreeNode) value).getUserObject();
                append(user.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (user instanceof Node node) setIcon(node.saved != null ? AllIcons.Actions.Find :
                        node.streamId == null ? AllIcons.Nodes.DataTables : AllIcons.FileTypes.Text);
                else setIcon(AllIcons.Nodes.Folder);
                setOpaque(false); setIconOpaque(false);
            }
        });
        tree.setCellRenderer(SourceTree.renderer(tree.getCellRenderer(), sourceFilter::values, () -> ideOptions, () -> !busy));
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 1) return;
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof SourceTree.Entry entry) toggleTreeSource(entry);
            }
        });
        tree.registerKeyboardAction(e -> {
            var node = tree.getLastSelectedPathComponent();
            if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof SourceTree.Entry entry) toggleTreeSource(entry);
        }, KeyStroke.getKeyStroke("SPACE"), JComponent.WHEN_FOCUSED);
        tree.registerKeyboardAction(e -> {
            var node = tree.getLastSelectedPathComponent();
            if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof SourceTree.Entry entry) toggleTreeSource(entry);
        }, KeyStroke.getKeyStroke("ENTER"), JComponent.WHEN_FOCUSED);
        tree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            public void treeExpanded(javax.swing.event.TreeExpansionEvent e) { remember(e, false); }
            public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) { remember(e, true); }
            private void remember(javax.swing.event.TreeExpansionEvent e, boolean collapsed) {
                if (rebuilding || active == null) return;
                if (((DefaultMutableTreeNode) e.getPath().getLastPathComponent()).getUserObject() instanceof SourceTree.Group) {
                    if (collapsed) collapsedSourceGroups.add(active.id); else collapsedSourceGroups.remove(active.id);
                }
            }
        });
        tree.addTreeSelectionListener(e -> {
            if (rebuilding) return;
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null || !(node.getUserObject() instanceof Node value)) return;
            boolean changedConnection = active == null || !active.id.equals(value.connection.id);
            invalidateSearch();
            active = value.connection.copy(); selectedStream = value.streamId == null ? "" : value.streamId;
            String sourceContext = active.id + "\n" + active.apiUrl + "\n" + active.serviceField;
            if (!sourceContext.equals(sourceFilterContext)) { sourceFilter.clear(); sourceFilterContext = sourceContext; cachedSources = null; }
            if (changedConnection) completion.reset(active.fields);
            query.getEmptyText().setText("Graylog query, e.g. " + active.fields.requestIdField + ":abc-123");
            selectedNodeKey = value.key();
            updateContext(active.name + (selectedStream.isEmpty() ? " / All messages" : " / " + value.title));
            if (value.saved != null) {
                sourceFilter.clear();
                query.setText(value.saved.query); selectedStream = value.saved.streamId;
                rebuilding = true; choosePeriod(value.saved.rangeSeconds); rebuilding = false;
            }
            clearEvents();
            if (changedConnection || !streams.containsKey(active.id)) loadStreams(active);
            search(true);
        });
        tree.setBorder(JBUI.Borders.empty());
        panel.add(ExplorerUi.scroll(tree), BorderLayout.CENTER);
        streamStatus.setBorder(JBUI.Borders.empty(4, 8));
        panel.add(streamStatus, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createSearchPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        live.setToolTipText("Refresh the search while this tool window is visible.");
        codeMarks.setToolTipText("Index all matching messages in the selected period; click a gutter marker to show related examples.");
        JPanel search = new JPanel(new BorderLayout(6, 0)); search.setBorder(JBUI.Borders.empty(3, 6));
        search.add(query, BorderLayout.CENTER);
        search.add(ExplorerUi.toolbar("Graylog.Filters", this,
                new SourceFilterAction(sourceFilter::label, () -> active != null, this::createSourcePopup),
                ExplorerUi.choice("Time range", "", period), ExplorerUi.choice("Page size", "Rows: ", pageSize),
                ExplorerUi.toggle(live, AllIcons.Actions.Resume, false), ExplorerUi.toggle(codeMarks, AllIcons.General.InspectionsEye, false),
                ExplorerUi.command("Save this search", AllIcons.Actions.MenuSaveall, this::saveSearch, () -> active != null)), BorderLayout.EAST);
        panel.add(search, BorderLayout.NORTH);

        events.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); events.setAutoCreateRowSorter(true);
        events.setShowGrid(false); events.setStriped(true); events.setRowHeight(JBUI.scale(24)); events.setIntercellSpacing(JBUI.size(0, 0));
        events.getEmptyText().setText("Add a connection, select a stream and run a search.");
        events.getColumnModel().getColumn(0).setPreferredWidth(110);
        events.getColumnModel().getColumn(1).setPreferredWidth(65);
        events.getColumnModel().getColumn(2).setPreferredWidth(120);
        events.getColumnModel().getColumn(3).setPreferredWidth(500);
        events.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
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
                LogEvent event = eventsModel.rows.get(events.convertRowIndexToModel(row));
                Color sourceColor = ideOptions.sourceStripes ? SourceColors.color(event.service(eventsModel.serviceField), ideOptions.sourceColors) : null;
                stripe = column == 0 ? sourceColor : null;
                setIcon(modelColumn == 2 && sourceColor != null ? ExplorerUi.colorIcon(sourceColor) : null);
                setBorder(JBUI.Borders.empty(0, column == 0 ? 10 : 8, 0, 8));
                if (!selected) {
                    String level = eventsModel.rows.get(events.convertRowIndexToModel(row)).level();
                    setForeground(modelColumn == 1 && level.equals("ERROR") ? ERROR_COLOR :
                            modelColumn == 1 && level.equals("WARN") ? WARN_COLOR : table.getForeground());
                }
                setToolTipText(value == null ? "" : value.toString());
                return this;
            }
        });
        events.addMouseListener(new MouseAdapter() {
            private void popup(MouseEvent e) { if (e.isPopupTrigger()) showSourceMenu(e); }
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });
        events.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int index = events.getSelectedRow();
                showEvent(index < 0 ? null : eventsModel.rows.get(events.convertRowIndexToModel(index)));
            }
        });
        panel.add(ExplorerUi.scroll(events), BorderLayout.CENTER);
        previous.setEnabled(false); next.setEnabled(false);
        allEvents.setEnabled(false);
        JPanel paging = new JPanel(new BorderLayout(8, 0));
        paging.add(ExplorerUi.toolbar("Graylog.Paging", this,
                ExplorerUi.button(previous, AllIcons.Actions.Back, false), ExplorerUi.button(next, AllIcons.Actions.Forward, false),
                ExplorerUi.button(allEvents, AllIcons.Actions.Show, true, allEvents::isEnabled)), BorderLayout.WEST);
        paging.add(status, BorderLayout.CENTER); JLabel utc = new JLabel("UTC"); utc.setForeground(UIUtil.getContextHelpForeground()); paging.add(utc, BorderLayout.EAST);
        panel.add(ExplorerUi.bar(paging, null, true), BorderLayout.SOUTH);
        return panel;
    }

    private JComponent createInspector() {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel details = new JPanel(new BorderLayout());
        JPanel heading = new JPanel(new BorderLayout());
        message.setRows(2); message.setFont(UIUtil.getLabelFont()); message.setBorder(JBUI.Borders.empty(2, 8, 8, 8));
        heading.add(ExplorerUi.scroll(message), BorderLayout.CENTER);
        details.add(heading, BorderLayout.NORTH);
        inspectorTabs = JBTabsFactory.createTabs(project, this);
        inspectorTabs.getComponent().setBorder(JBUI.Borders.empty());
        JBTable fields = new JBTable(fieldsModel); fields.setShowGrid(false); fields.setStriped(true);
        fields.setAutoCreateRowSorter(true); fields.setCellSelectionEnabled(true);
        fields.setRowHeight(JBUI.scale(24)); fields.setIntercellSpacing(JBUI.size(0, 0)); fields.setDefaultRenderer(Object.class, ExplorerUi.cells());
        fields.getColumnModel().getColumn(0).setPreferredWidth(180); fields.getColumnModel().getColumn(1).setPreferredWidth(360);
        fields.getEmptyText().setText("Select an event to inspect its fields.");
        inspectorTabs.addTab(new TabInfo(ExplorerUi.scroll(fields)).setText("Fields"));
        locations.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        locations.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) openSource(); }
        });
        locations.registerKeyboardAction(e -> openSource(), KeyStroke.getKeyStroke("ENTER"), JComponent.WHEN_FOCUSED);
        locations.setBorder(JBUI.Borders.empty(6, 8));
        locations.setCellRenderer(new ColoredListCellRenderer<SourceLocation>() {
            @Override protected void customizeCellRenderer(javax.swing.JList<? extends SourceLocation> list, SourceLocation value, int index, boolean selected, boolean focus) {
                Path local = mappedSource(value);
                String display = local == null ? value.path().replace('\\', '/') : displayLocal(local).replace('\\', '/');
                int slash = display.lastIndexOf('/');
                append(display.substring(slash + 1) + ":" + value.line(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (slash >= 0) append("  " + display.substring(0, slash), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                setIcon(local == null ? AllIcons.General.Warning : AllIcons.FileTypes.Text);
                setToolTipText(mappingDescription(value));
            }
        });
        locations.getEmptyText().setText("This event has no source frames.");
        locations.addListSelectionListener(e -> {
            SourceLocation location = locations.getSelectedValue();
            sourceMapping.setText(location == null ? " " : mappingDescription(location));
            sourceMapping.setToolTipText(location == null ? null : mappingDescription(location));
        });
        JPanel trace = new JPanel(new BorderLayout());
        JLabel localLabel = new JLabel("Local source files"); localLabel.setForeground(UIUtil.getContextHelpForeground());
        trace.add(ExplorerUi.bar(localLabel, ExplorerUi.toolbar("Graylog.SourceMapping", this,
                ExplorerUi.command("Source path mappings", AllIcons.General.Settings,
                        () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, GraylogProjectConfigurable.class), () -> true)), false), BorderLayout.NORTH);
        trace.add(ExplorerUi.scroll(locations), BorderLayout.CENTER);
        sourceMapping.setForeground(UIUtil.getContextHelpForeground()); trace.add(ExplorerUi.bar(sourceMapping, null, true), BorderLayout.SOUTH);
        inspectorTabs.addTab(new TabInfo(trace).setText("Stack trace")); inspectorTabs.addTab(new TabInfo(ExplorerUi.scroll(rawJson)).setText("JSON"));
        inspectorTabs.addTab(new TabInfo(ExplorerUi.scroll(rawStack)).setText("Exception text"));
        details.add(inspectorTabs.getComponent(), BorderLayout.CENTER);
        JLabel empty = new JLabel("Select a log event to view details", JLabel.CENTER); empty.setForeground(UIUtil.getContextHelpForeground());
        inspectorBody.add(empty, "empty"); inspectorBody.add(details, "details"); panel.add(inspectorBody, BorderLayout.CENTER);
        open.addActionListener(e -> openSource()); around.addActionListener(e -> showAround());
        follow.addActionListener(e -> followRequest());
        browser.addActionListener(e -> {
            GraylogLinks.Target target = browserTarget();
            if (target != null) BrowserUtil.browse(target.url());
        });
        JComponent actions = ExplorerUi.toolbar("Graylog.Inspector", this,
                ExplorerUi.button(open, AllIcons.Actions.EditSource, false),
                ExplorerUi.button(around, AllIcons.General.History, false), ExplorerUi.button(follow, AllIcons.Ide.Link, false),
                ExplorerUi.button(browser, AllIcons.General.Web, false));
        panel.add(ExplorerUi.bar(eventLevel, actions, false), BorderLayout.NORTH); showEvent(null);
        return panel;
    }

    private void rebuildTree() {
        rebuilding = true;
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Connections");
        GraylogSettings.Data data = settings.getState();
        String filter = connectionFilter.getText().strip().toLowerCase(Locale.ROOT);
        for (Connection c : settings.connections()) {
            boolean connectionMatches = c.name.toLowerCase(Locale.ROOT).contains(filter);
            DefaultMutableTreeNode connection = new DefaultMutableTreeNode(new Node(c, c.name, null, null));
            DefaultMutableTreeNode group = new DefaultMutableTreeNode("Streams");
            if (connectionMatches || "all messages".contains(filter)) group.add(new DefaultMutableTreeNode(new Node(c, "All messages", "", null)));
            for (GraylogClient.Stream s : streams.getOrDefault(c.id, List.of())) {
                if (connectionMatches || s.title().toLowerCase(Locale.ROOT).contains(filter)) group.add(new DefaultMutableTreeNode(new Node(c, s.title(), s.id(), null)));
            }
            if (connectionMatches || group.getChildCount() > 0) connection.add(group);
            DefaultMutableTreeNode saved = new DefaultMutableTreeNode("Saved searches");
            for (GraylogSettings.SavedSearch s : data.searches) if (s.connectionId.equals(c.id)) {
                if (connectionMatches || s.name.toLowerCase(Locale.ROOT).contains(filter)) saved.add(new DefaultMutableTreeNode(new Node(c, s.name, s.streamId, s)));
            }
            if (connectionMatches || saved.getChildCount() > 0) connection.add(saved);
            if (active != null && active.id.equals(c.id)) {
                String scopeLabel = selectedStream.isBlank() ? "All messages" : streams.getOrDefault(c.id, List.of()).stream()
                        .filter(stream -> stream.id().equals(selectedStream)).map(GraylogClient.Stream::title).findFirst().orElse(selectedStream);
                var sources = SourceTree.group(scopeLabel, sourceSummary,
                        visibleSources == null ? List.of() : visibleSources.values(), sourceFilter.values(), filter, connectionMatches || "sources".contains(filter));
                if (connectionMatches || sources.getChildCount() > 0) connection.add(sources);
            }
            if (connectionMatches || connection.getChildCount() > 0) root.add(connection);
        }
        tree.setModel(new DefaultTreeModel(root));
        for (int i = 0; i < tree.getRowCount(); i++) {
            var node = (DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent();
            if (!(node.getUserObject() instanceof SourceTree.Group) || active == null || !collapsedSourceGroups.contains(active.id)) tree.expandRow(i);
        }
        if (!selectedNodeKey.isBlank()) selectNode(selectedNodeKey);
        rebuilding = false;
    }

    private void selectNode(String key) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) nodes.nextElement();
            if (n.getUserObject() instanceof Node value && value.key().equals(key)) {
                tree.setSelectionPath(new TreePath(n.getPath())); return;
            }
        }
    }

    private void editConnection(Connection existing) {
        ConnectionDialog dialog = new ConnectionDialog(project, existing);
        if (!dialog.showAndGet()) return;
        Connection connection = dialog.connection();
        char[] entered = dialog.token();
        if (entered.length > 0) connection.credentialId = java.util.UUID.randomUUID().toString();
        setStatus("Saving connection…", false);
        background(() -> {
            try { if (entered.length > 0) GraylogCredentials.save(connection.credentialId, new String(entered)); }
            finally { Arrays.fill(entered, '\0'); }
            return connection;
        }, saved -> {
            settings.save(saved);
        }, error -> setStatus("Could not save token: " + errorMessage(error), true));
    }

    private void removeSelected() {
        DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (selectedNode == null || !(selectedNode.getUserObject() instanceof Node node)) return;
        if (node.saved != null) {
            settings.removeSearch(node.saved.id); selectedNodeKey = node.connection.id + ":"; rebuildTree();
            return;
        }
        if (Messages.showYesNoDialog(project, "Remove the IDE-wide connection “" + node.connection.name + "” from all projects? Project mappings and saved searches will be retained.",
                "Remove Graylog Connection", Messages.getQuestionIcon()) != Messages.YES) return;
        String id = node.connection.id;
        String credential = node.connection.credentialId.isBlank() ? id : node.connection.credentialId;
        settings.remove(id); streams.remove(id); invalidateSearch(); live.setSelected(false);
        active = null; selectedNodeKey = ""; selectedStream = ""; clearEvents(); rebuildTree();
        updateContext("No connection"); setStatus("Connection removed. Select or add a connection.", false);
        background(() -> { GraylogCredentials.removeIfUnused(credential); return true; }, ignored -> {},
                error -> setStatus("Connection removed; token could not be removed from Password Safe: " + errorMessage(error), true));
    }

    private void loadStreams(Connection connection) {
        streamStatus.setText("Loading streams…");
        long sequence = streamRequests.merge(connection.id, 1L, Long::sum);
        background(() -> { try (GraylogClient api = client(connection)) { return api.streams(); } }, result -> {
            if (streamRequests.get(connection.id) != sequence) return;
            // A deleted or edited connection must not be resurrected by an older response.
            boolean stillExists = settings.connections().stream().anyMatch(c -> c.id.equals(connection.id) && c.apiUrl.equals(connection.apiUrl));
            if (!stillExists) return;
            streams.put(connection.id, result); rebuildTree();
            if (active != null && active.id.equals(connection.id)) {
                streamStatus.setText(result.size() + (result.size() == 1 ? " stream" : " streams")); streamStatus.setToolTipText(null);
            }
        }, error -> {
            if (streamRequests.get(connection.id) != sequence) return;
            if (active != null && active.id.equals(connection.id)) {
                streamStatus.setText("Streams unavailable · Refresh to retry");
                streamStatus.setToolTipText(errorMessage(error));
            }
        });
    }

    private GraylogClient client(Connection connection) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        String token = GraylogCredentials.load(connection);
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        return new GraylogClient(connection.apiUrl, token, ideOptions, connection.fields);
    }

    private void search(boolean newWindow) {
        if (rebuilding || disposed) return;
        if (active == null) { live.setSelected(false); setStatus("Add or select a connection first.", false); return; }
        if (newWindow || snapshot == null) {
            Instant now = Instant.now();
            baseSnapshot = new SearchRequest(query.getText(), selectedStream, now.minusSeconds(seconds()), now, 0, (Integer) pageSize.getSelectedItem());
            snapshot = sourceFilter.apply(baseSnapshot, active.serviceField);
        }
        executeSearch(snapshot);
    }

    private String effectiveQuery() { return sourceFilter.query(query.getText(), active == null ? "source" : active.serviceField); }
    private SearchRequest sourceScope() {
        if (baseSnapshot != null) return new SearchRequest(baseSnapshot.query(), baseSnapshot.streamId(), baseSnapshot.from(), baseSnapshot.to(), 0, (Integer) pageSize.getSelectedItem());
        Instant now = Instant.now(); return new SearchRequest(query.getText(), selectedStream, now.minusSeconds(seconds()), now, 0, (Integer) pageSize.getSelectedItem());
    }
    private com.intellij.openapi.ui.popup.JBPopup createSourcePopup(JComponent anchor, Runnable onDispose) {
        if (sourcePopup != null && sourcePopup.alive()) sourcePopup.popup().cancel();
        SearchRequest scope = sourceScope(); Connection connection = active.copy();
        SourceFilterPopup pane = new SourceFilterPopup(project, connection.serviceField, sourceFilter.values(), () -> ideOptions,
                values -> applySources(sourceScope(), values), () -> { if (active != null) loadSources(active.copy(), sourceScope(), true); },
                () -> { sourcePopup = null; if (onDispose != null) onDispose.run(); });
        sourcePopup = pane; loadSources(connection, scope, false); return pane.popup();
    }
    private void toggleTreeSource(SourceTree.Entry entry) {
        if (active == null || busy || rebuilding) return;
        java.util.Set<String> values = new java.util.LinkedHashSet<>(sourceFilter.values());
        if (entry.name() == null) values.clear(); else if (!values.remove(entry.name())) values.add(entry.name());
        applySources(sourceScope(), values);
    }
    private void applySources(SearchRequest scope, java.util.Set<String> values) {
        if (active == null || !scope.sameScope(sourceScope())) return;
        sourceFilter.replace(values);
        baseSnapshot = new SearchRequest(scope.query(), selectedStream, scope.from(), scope.to(), 0, (Integer) pageSize.getSelectedItem());
        snapshot = sourceFilter.apply(baseSnapshot, active.serviceField); rebuildTree(); executeSearch(snapshot);
    }
    private String sourceContext(Connection connection) { return connection.id + "\n" + connection.apiUrl + "\n" + connection.credentialId + "\n" + connection.serviceField; }
    private void publishSources() {
        rebuildTree();
        if (sourcePopup == null || !sourcePopup.alive()) return;
        if (sourcesLoading) sourcePopup.loading();
        else if (!sourceError.isBlank()) sourcePopup.fallback(visibleSources == null ? List.of() : visibleSources.values(), sourceError);
        else if (visibleSources != null) sourcePopup.loaded(visibleSources);
    }
    private void loadSources(Connection connection, SearchRequest scope, boolean force) {
        String context = sourceContext(connection);
        if (!force && sourcesLoading && context.equals(visibleSourceContext) && scope.sameScope(visibleSourceScope)) { publishSources(); return; }
        if (!force && cachedSources != null && cachedSourceContext.equals(context) && scope.sameScope(cachedSourceScope)
                && System.currentTimeMillis() - cachedSourcesAt < 30000) {
            visibleSources = cachedSources; visibleSourceScope = scope; visibleSourceContext = context;
            sourcesLoading = false; sourceError = ""; sourceSummary = sourceSummary(cachedSources); publishSources(); return;
        }
        stopSources(); long sequence = sourceNumber;
        visibleSources = null; visibleSourceScope = scope; visibleSourceContext = context;
        sourcesLoading = true; sourceError = ""; sourceSummary = "Loading sources for this period…"; publishSources();
        sourceTask = background(() -> { try (GraylogClient api = client(connection)) { return api.sources(scope, connection.serviceField); } }, result -> {
            if (sequence != sourceNumber || active == null || !sourceContext(active).equals(context)) return;
            sourceTask = null; sourcesLoading = false; visibleSources = result;
            cachedSources = result; cachedSourceScope = scope; cachedSourceContext = context; cachedSourcesAt = System.currentTimeMillis();
            sourceSummary = sourceSummary(result); publishSources();
        }, error -> {
            if (sequence != sourceNumber || active == null || !sourceContext(active).equals(context)) return;
            sourceTask = null; sourcesLoading = false; sourceError = errorMessage(error);
            Map<String, Long> counts = new TreeMap<>();
            for (LogEvent event : loadedEvents) { String source = event.service(connection.serviceField); if (!source.isBlank()) counts.merge(source, 1L, Long::sum); }
            visibleSources = new GraylogClient.Sources(counts.entrySet().stream().map(e -> new GraylogClient.SourceValue(e.getKey(), e.getValue())).toList(), false);
            sourceSummary = "Aggregation unavailable · loaded-page sources only"; publishSources();
        });
    }
    private String sourceSummary(GraylogClient.Sources sources) {
        return sources.limited() ? "Top " + sources.values().size() + " sources · use Sources / Add by name for others" : sources.values().size() + " source(s) in this period";
    }
    private void stopSources() { sourceNumber++; if (sourceTask != null) sourceTask.cancel(true); sourceTask = null; }

    private void executeSearch(SearchRequest request) {
        invalidateSearch();
        final long sequence = requestNumber;
        final Connection connection = active.copy();
        if (!connection.id.equals(indexConnection) || !request.sameScope(indexRequest)) {
            resetIndex(); indexStatus.setText(codeMarks.isSelected() ? "Exceptions: waiting for search results…" : "Exception marks disabled.");
        }
        allEvents.setEnabled(false);
        busy = true; previous.setEnabled(false); next.setEnabled(false);
        setStatus("Searching " + connection.name + "…", false);
        settings.remember(connection.id, query.getText(), seconds());
        settings.rememberPageSize((Integer) pageSize.getSelectedItem());
        searchTask = background(() -> { try (GraylogClient api = client(connection)) { return api.search(request); } }, result -> {
            if (sequence != requestNumber) return;
            busy = false;
            loadedEvents = result.events(); loadedRequest = request; loadedTotal = result.total();
            eventsModel.serviceField = connection.serviceField;
            completion.observe(loadedEvents, connection.serviceField);
            displayEvents(loadedEvents); updatePaging();
            loadSources(connection, sourceScope(), false);
            long first = request.offset() + 1, last = request.offset() + result.events().size();
            String count = result.events().isEmpty() ? "No events" : (first == last ? Long.toString(first) : first + "–" + last) + " of " + counted(result.total(), "event");
            resultsSummary = connection.name + " · " + count;
            setStatus(resultsSummary + (live.isSelected() ? " · Live every " + ideOptions.liveIntervalSeconds + "s" : ""), false);
            status.setToolTipText("UTC: " + request.from() + " → " + request.to() + " | Query: " + request.query());
            events.getEmptyText().setText("No events matched. Try a wider period or another query.");
            if (codeMarks.isSelected()) {
                if (ideOptions.autoIndex) startIndex(connection, request, false);
                else { indexStatus.setText("Exception index is manual · click Refresh marks."); indexControl.setEnabled(true); }
            }
        }, error -> {
            if (sequence != requestNumber) return;
            busy = false; live.setSelected(false);
            setStatus("Search failed: " + errorMessage(error) + " Previous results, if any, are unchanged.", true);
        });
    }

    private void startIndex(Connection connection, SearchRequest request, boolean force) {
        if (!codeMarks.isSelected() || disposed) return;
        if (!force && connection.id.equals(indexConnection) && request.sameScope(indexRequest)) return;
        stopIndex(); periodIndex = null; indexRequest = request; indexConnection = connection.id;
        long sequence = indexNumber;
        Path root = project.getBasePath() == null ? null : Path.of(project.getBasePath());
        String scope = connection.name + " · " + request.from() + " → " + request.to() + " UTC · " + request.query();
        highlights.replace(List.of(), scope, connection, root); highlights.select(selected);
        indexBusy = true; indexStatus.setForeground(getForeground()); indexStatus.setText("Indexing exceptions for the whole period…");
        indexStatus.setToolTipText(scope); indexControl.setText("Cancel scan"); indexControl.setEnabled(true);
        indexTask = background(() -> {
            try (GraylogClient api = client(connection)) {
                return api.indexPeriod(request, connection, root, count -> later(() -> {
                    if (sequence == indexNumber) indexStatus.setText("Indexing exceptions · " + count + " messages processed…");
                }));
            }
        }, result -> {
            if (sequence != indexNumber) return;
            indexBusy = false; periodIndex = result;
            highlights.replace(result.sites(), scope, connection, root); highlights.select(selected);
            indexStatus.setText("Whole period · " + counted(result.exceptions(), "exception") + " · " + counted(result.sites().size(), "location")
                    + " · " + counted(result.messages(), "message"));
            indexStatus.setForeground(getForeground()); indexControl.setText("Refresh marks");
        }, error -> {
            if (sequence != indexNumber) return;
            indexBusy = false; live.setSelected(false); periodIndex = null;
            indexStatus.setText("Period index incomplete: " + errorMessage(error)); indexStatus.setForeground(ERROR_COLOR);
            indexStatus.setToolTipText(indexStatus.getText()); indexControl.setText("Retry marks");
        });
    }

    private void stopIndex() { indexNumber++; indexBusy = false; if (indexTask != null) indexTask.cancel(true); }
    private void resetIndex() {
        stopIndex(); indexRequest = null; indexConnection = ""; periodIndex = null;
        highlights.clear(); indexControl.setEnabled(false); indexControl.setText("Refresh marks"); indexStatus.setForeground(getForeground());
    }

    private void page(int direction) {
        if (snapshot == null || active == null || busy) return;
        live.setSelected(false);
        snapshot = snapshot.page(Math.max(0, snapshot.offset() + direction * snapshot.limit()));
        executeSearch(snapshot);
    }

    private void showEvent(LogEvent event) {
        selected = event;
        inspectorLayout.show(inspectorBody, event == null ? "empty" : "details");
        eventLevel.setText(event == null ? "" : event.level());
        eventLevel.setForeground(event != null && event.level().equals("ERROR") ? ERROR_COLOR
                : event != null && event.level().equals("WARN") ? WARN_COLOR : getForeground());
        highlights.select(event);
        message.setText(event == null ? "Select an event to view details." : event.text("message")); message.setCaretPosition(0);
        fieldsModel.rows = event == null ? List.of() : new ArrayList<>(new TreeMap<>(event.fields().asMap()).entrySet());
        fieldsModel.fireTableDataChanged();
        rawJson.setText(event == null ? "" : JSON.toJson(event.fields())); rawJson.setCaretPosition(0);
        rawStack.setText(event == null ? "" : event.stackTrace()); rawStack.setCaretPosition(0);
        locationsModel.clear();
        if (event != null) {
            List<SourceLocation> frames = ExceptionSites.exceptionName(event).isBlank() ? SourceLocation.find(event) : ExceptionSites.exceptionFrames(event);
            frames.forEach(locationsModel::addElement);
        }
        if (!locationsModel.isEmpty()) locations.setSelectedIndex(0);
        open.setEnabled(!locationsModel.isEmpty()); around.setEnabled(event != null && event.timestamp() != null);
        follow.setEnabled(event != null && !event.first("request_id", "trace_id", "correlation_id").isBlank());
        GraylogLinks.Target target = browserTarget();
        browser.setEnabled(target != null);
        browser.setToolTipText(event == null ? "Select an event to open in Graylog."
                : target == null ? "This event has no message ID or no index/time range to locate it in Graylog."
                : (target.direct() ? "Open the selected event in Graylog: " : "Find the selected event by its ID (index unavailable): ") + target.url());
    }

    private GraylogLinks.Target browserTarget() { return GraylogLinks.event(active, selected, loadedRequest); }

    private Path mappedSource(SourceLocation source) {
        return source == null || active == null ? null : source.resolve(active, project.getBasePath() == null ? null : Path.of(project.getBasePath()));
    }
    private String displayLocal(Path path) {
        Path root = project.getBasePath() == null ? null : Path.of(project.getBasePath()).toAbsolutePath().normalize();
        return root != null && path.startsWith(root) ? root.relativize(path).toString() : path.toString();
    }
    private String mappingDescription(SourceLocation source) {
        Path local = mappedSource(source);
        return source + (local == null ? " → No local mapping. Configure source path mappings." : " → " + local + ":" + source.line());
    }
    private void showSourceMenu(MouseEvent mouse) {
        int row = events.rowAtPoint(mouse.getPoint()); if (row < 0) return;
        events.setRowSelectionInterval(row, row);
        String source = eventsModel.rows.get(events.convertRowIndexToModel(row)).service(eventsModel.serviceField);
        if (source.isBlank()) return;
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem only = new javax.swing.JMenuItem("Only this source");
        only.addActionListener(e -> applySources(sourceScope(), java.util.Set.of(source))); menu.add(only);
        javax.swing.JMenuItem include = new javax.swing.JMenuItem("Add to source filter");
        include.setEnabled(!sourceFilter.values().isEmpty() && !sourceFilter.values().contains(source));
        include.addActionListener(e -> { java.util.Set<String> values = new java.util.LinkedHashSet<>(sourceFilter.values()); values.add(source); applySources(sourceScope(), values); }); menu.add(include);
        javax.swing.JMenuItem clear = new javax.swing.JMenuItem("All sources"); clear.setEnabled(!sourceFilter.values().isEmpty());
        clear.addActionListener(e -> applySources(sourceScope(), java.util.Set.of())); menu.add(clear); menu.addSeparator();
        javax.swing.JMenuItem choose = new javax.swing.JMenuItem("Source color: " + source + "…", ExplorerUi.colorIcon(SourceColors.color(source, ideOptions.sourceColors)));
        choose.addActionListener(e -> {
            Color color = com.intellij.ui.ColorChooserService.getInstance().showDialog(project, this, "Source Color — " + source, SourceColors.color(source, ideOptions.sourceColors), false);
            if (color != null) saveSourceColor(source, SourceColors.hex(color));
        });
        menu.add(choose); javax.swing.JMenuItem reset = new javax.swing.JMenuItem("Use automatic source color");
        reset.setEnabled(ideOptions.sourceColors.containsKey(source)); reset.addActionListener(e -> saveSourceColor(source, null)); menu.add(reset);
        menu.show(events, mouse.getX(), mouse.getY());
    }
    private void saveSourceColor(String source, String color) {
        GraylogIdeSettings.Data state = GraylogIdeSettings.get().getState();
        if (color == null) state.options.sourceColors.remove(source); else state.options.sourceColors.put(source, color);
        GraylogIdeSettings.get().replace(state);
    }

    private void openSource() {
        SourceLocation location = locations.getSelectedValue();
        if (location == null || active == null) return;
        Path root = project.getBasePath() == null ? null : Path.of(project.getBasePath());
        Path mapped = location.resolve(active, root);
        VirtualFile file = mapped == null ? null : LocalFileSystem.getInstance().refreshAndFindFileByNioFile(mapped);
        if (file == null || file.isDirectory()) {
            setStatus("Source file not found. Settings → Tools → Graylog Explorer → Project settings → Source path mappings. " + location.path(), true); return;
        }
        highlights.focus(active, root, selected, location);
        new OpenFileDescriptor(project, file, location.line() - 1, 0).navigate(true);
    }

    private void displayEvents(List<LogEvent> rows) {
        String identity = selected == null ? restoreSelection : selected.identity();
        restoreSelection = "";
        eventsModel.rows = rows; eventsModel.fireTableDataChanged();
        int preserve = -1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).identity().equals(identity)) { preserve = i; break; }
        if (preserve >= 0) {
            int view = events.convertRowIndexToView(preserve); events.setRowSelectionInterval(view, view);
        } else if (!rows.isEmpty() && !live.isSelected()) events.setRowSelectionInterval(0, 0);
        else showEvent(null);
    }

    private void updatePaging() {
        if (loadedRequest == null) { previous.setEnabled(false); next.setEnabled(false); return; }
        previous.setEnabled(loadedRequest.offset() > 0);
        next.setEnabled(loadedRequest.offset() + loadedRequest.limit() < loadedTotal && loadedEvents.size() == loadedRequest.limit()
                && loadedRequest.offset() + 2 * loadedRequest.limit() <= 10000);
    }

    private void showSite(ExceptionSites.Site site) {
        if (disposed) return;
        live.setSelected(false); invalidateSearch();
        toolWindow.activate(() -> {
            if (disposed) return;
            displayEvents(site.events()); allEvents.setEnabled(true);
            previous.setEnabled(false); next.setEnabled(false);
            setStatus("Graylog · " + site.location().file().getFileName() + ":" + site.location().line()
                    + " · " + site.count() + " event(s) in period · showing " + site.events().size() + " example(s) · " + String.join(", ", site.types()), false);
        });
    }

    private void showAround() {
        if (selected == null || selected.timestamp() == null || active == null) return;
        live.setSelected(false); query.setText("*");
        Instant timestamp = selected.timestamp();
        baseSnapshot = new SearchRequest("*", selectedStream, timestamp.minusSeconds(300), timestamp.plusSeconds(300), 0, (Integer) pageSize.getSelectedItem());
        snapshot = sourceFilter.apply(baseSnapshot, active.serviceField);
        executeSearch(snapshot);
    }

    private void followRequest() {
        if (selected == null || active == null) return;
        String key = !selected.text("request_id").isBlank() ? "request_id" : !selected.text("trace_id").isBlank() ? "trace_id" : "correlation_id";
        String value = selected.text(key);
        if (value.isBlank()) return;
        sourceFilter.clear();
        query.setText(SearchRequest.exact(selected.fieldName(key), value)); selectedStream = "";
        selectedNodeKey = active.id + ":"; rebuildTree();
        updateContext(active.name + " / All messages"); search(true);
    }

    private void saveSearch() {
        if (active == null) { setStatus("Select a connection before saving a search.", false); return; }
        String name = Messages.showInputDialog(project, "Search name", "Save Graylog Search", Messages.getQuestionIcon());
        if (name == null || name.isBlank()) return;
        GraylogSettings.SavedSearch saved = new GraylogSettings.SavedSearch();
        saved.name = name.trim(); saved.connectionId = active.id; saved.streamId = selectedStream;
        saved.query = effectiveQuery(); saved.rangeSeconds = seconds();
        settings.saveSearch(saved); rebuildTree(); setStatus("Search saved: " + saved.name, false);
    }

    private void clearEvents() {
        if (sourcePopup != null && sourcePopup.alive()) sourcePopup.popup().cancel();
        baseSnapshot = null;
        stopSources(); visibleSources = null; visibleSourceScope = null; visibleSourceContext = "";
        sourcesLoading = false; sourceError = ""; sourceSummary = "Loading sources…";
        loadedEvents = List.of(); loadedRequest = null; loadedTotal = 0; resultsSummary = "";
        restoreSelection = "";
        resetIndex(); indexStatus.setText("Exceptions: select a connection and search."); allEvents.setEnabled(false);
        eventsModel.rows = List.of(); eventsModel.fireTableDataChanged(); showEvent(null); previous.setEnabled(false); next.setEnabled(false);
    }
    private void invalidateSearch() { requestNumber++; busy = false; if (searchTask != null) searchTask.cancel(true); }
    private String liveLabel() { return "Live · " + ideOptions.liveIntervalSeconds + "s"; }
    private String initialConnection() {
        List<Connection> available = settings.connections(); if (available.isEmpty()) return "";
        GraylogSettings.Data state = settings.getState();
        for (String id : List.of(state.selectedConnection, state.preferredConnection, ideOptions.defaultConnection)) {
            if (available.stream().anyMatch(c -> c.id.equals(id))) return id;
        }
        return available.getFirst().id;
    }
    private void reloadSettings() {
        if (disposed || project.isDisposed()) return;
        if (!connectionFilter.getText().isEmpty()) connectionFilter.setText("");
        Connection before = active;
        String previousStream = selectedStream;
        String previousSelection = selected == null ? "" : selected.identity();
        ideOptions = GraylogIdeSettings.get().getState().options;
        knownServers = GraylogIdeSettings.get().getState().connections;
        timer.setDelay(ideOptions.liveIntervalSeconds * 1000); timer.setInitialDelay(ideOptions.liveIntervalSeconds * 1000);
        live.setText(liveLabel()); highlights.configure(ideOptions);
        codeMarks.setSelected(settings.highlightsEnabled()); highlights.setEnabled(codeMarks.isSelected());
        invalidateSearch(); clearEvents();
        if (before == null || settings.connections().stream().noneMatch(c -> c.id.equals(before.id) && c.apiUrl.equals(before.apiUrl))) streams.clear();
        for (String id : new ArrayList<>(streamRequests.keySet())) streamRequests.merge(id, 1L, Long::sum);
        active = null; selectedStream = ""; selectedNodeKey = ""; rebuildTree();
        String initial = initialConnection();
        if (!initial.isBlank()) {
            if (before != null && before.id.equals(initial) && !previousStream.isBlank()) selectNode(initial + ":" + previousStream);
            if (active == null) selectNode(initial + ":");
            restoreSelection = before != null && before.id.equals(initial) ? previousSelection : "";
        }
        else { updateContext("No connection"); setStatus("Add an IDE-wide connection in Graylog Explorer settings.", false); }
    }
    private int seconds() { return ((Period) period.getSelectedItem()).seconds; }
    private void updateContext(String value) { query.setToolTipText("<html>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(value) + "<br>field:value · level:3 AND source:api · level:[0 TO 3]<br>Type a field name or press Ctrl+Space for suggestions. Tab/Enter inserts; Esc closes.<br>Enter or Ctrl+Enter runs the search.</html>"); }
    private void choosePeriod(int seconds) { for (Period item : PERIODS) if (item.seconds == seconds) { period.setSelectedItem(item); return; } period.setSelectedItem(PERIODS[1]); }
    private void setStatus(String text, boolean error) { status.setText(text); status.setToolTipText(text); status.setForeground(error ? ERROR_COLOR : getForeground()); }
    private void later(Runnable runnable) {
        if (disposed || project.isDisposed()) return;
        ToolWindowManager.getInstance(project).invokeLater(() -> { if (!disposed && !project.isDisposed()) runnable.run(); });
    }
    private <T> Future<?> background(Callable<T> work, Consumer<T> done, Consumer<Throwable> failed) {
        return executor.submit(() -> {
            try { T result = work.call(); later(() -> done.accept(result)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { if (!Thread.currentThread().isInterrupted()) later(() -> failed.accept(e)); }
        });
    }
    private static String errorMessage(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static String counted(long value, String noun) { return value + " " + noun + (value == 1 ? "" : "s"); }
    private static JTextArea textArea(boolean wrap) {
        JTextArea area = new JTextArea(); area.setEditable(false); area.setLineWrap(wrap); area.setWrapStyleWord(wrap);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().getSize())); area.setBorder(JBUI.Borders.empty(6, 8)); return area;
    }

    @Override public void dispose() {
        disposed = true; timer.stop(); completion.dispose(); if (sourcePopup != null && sourcePopup.alive()) sourcePopup.popup().cancel(); stopSources(); invalidateSearch(); stopIndex(); executor.shutdownNow();
    }

    private static final class EventTableModel extends AbstractTableModel {
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
    private static final class FieldTableModel extends AbstractTableModel {
        List<Map.Entry<String, JsonElement>> rows = List.of();
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int column) { return column == 0 ? "Field" : "Value"; }
        @Override public Object getValueAt(int row, int column) {
            Map.Entry<String, JsonElement> entry = rows.get(row);
            return column == 0 ? entry.getKey() : entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : entry.getValue().toString();
        }
    }
}
