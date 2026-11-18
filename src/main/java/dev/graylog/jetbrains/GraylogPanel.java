package dev.graylog.jetbrains;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.components.fields.ExtendableTextField;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public final class GraylogPanel extends JPanel implements Disposable {
    private record Period(String label, int seconds) { @Override public String toString() { return label; } }
    private static final Period[] PERIODS = { new Period("Last 5 minutes", 300), new Period("Last 15 minutes", 900),
            new Period("Last hour", 3600), new Period("Last 24 hours", 86400) };
    private static final Color ERROR_COLOR = ExplorerUi.ERROR_COLOR;

    private final Project project;
    private final ToolWindow toolWindow;
    private final GraylogSettings settings;
    private volatile IdeOptions ideOptions;
    private final ExceptionHighlights highlights;
    private final EventInspector inspector;
    private final ConnectionsView connections;
    private final IdeTaskRunner tasks;
    private final SourceCatalog catalog;
    private final PeriodIndexController index;
    private final SearchController search;
    private final StreamCatalog streamCatalog;
    private final ExplorerCoordinator coordinator;
    private SearchController.Page renderedPage;
    private SearchController.Display renderedDisplay;
    private final Timer timer;
    private final ExtendableTextField query = ExplorerUi.filterField("Graylog query", () -> search());
    private final QueryCompletion completion = new QueryCompletion(query);
    private final SourceFilter sourceFilter = new SourceFilter();
    private String sourceFilterContext = "";
    private SourceFilterPopup sourcePopup;
    private final JComboBox<Period> period = new JComboBox<>(PERIODS);
    private final JComboBox<Integer> pageSize = new JComboBox<>(new Integer[]{100, 250, 500});
    private final JToggleButton live = new JToggleButton("Live · 5s");
    private final JToggleButton codeMarks = new JToggleButton("Code marks", true);
    private final JLabel status = new JLabel("Add a connection to start searching Graylog.");
    private final JLabel indexStatus = new JLabel("Exceptions: select a connection and search.");
    private final JButton indexControl = new JButton("Refresh marks");
    private final EventsTable events = new EventsTable(() -> ideOptions, this::showEvent, this::showSourceMenu);
    private final JButton previous = new JButton("Previous");
    private final JButton next = new JButton("Next");
    private final JButton allEvents = new JButton("All loaded events");
    private Connection active;
    private String selectedStream = "";
    private String selectedNodeKey = "";
    private LogEvent selected;
    private String restoreSelection = "";
    private boolean rebuilding;
    private OnePixelSplitter navigationSplitter;
    private boolean connectionsVisible = true;
    private volatile boolean disposed;

    public GraylogPanel(Project project, ToolWindow toolWindow) {
        super(new BorderLayout());
        this.project = project; this.toolWindow = toolWindow; settings = GraylogSettings.get(project);
        ideOptions = GraylogIdeSettings.get().getState().options;
        tasks = new IdeTaskRunner(project, this);
        catalog = new SourceCatalog(tasks, this::client, () -> System.nanoTime() / 1_000_000, this::publishSources);
        index = new PeriodIndexController(tasks, this::client, this::renderIndex);
        search = new SearchController(tasks, this::client, this::renderSearch);
        streamCatalog = new StreamCatalog(tasks, this::client, this::publishStreams);
        coordinator = new ExplorerCoordinator(search, catalog, index, this::projectRoot);
        highlights = new ExceptionHighlights(project, this, this::showSite);
        inspector = new EventInspector(project, this, this::openSource, this::showAround, this::followRequest);
        inspector.setName("explorer.inspector");
        connections = new ConnectionsView(() -> ideOptions, () -> !searchBusy(), this::rebuildTree, this::selectNavigationNode, this::toggleTreeSource);
        GraylogSettings.Data saved = settings.getState();
        codeMarks.setSelected(settings.highlightsEnabled()); highlights.configure(ideOptions); highlights.setEnabled(codeMarks.isSelected());
        query.setText(saved.lastQuery); choosePeriod(saved.rangeSeconds == 0 ? ideOptions.defaultPeriodSeconds : saved.rangeSeconds);
        pageSize.setSelectedItem(saved.pageSize == 0 ? ideOptions.defaultPageSize : saved.pageSize);
        live.setSelected(ideOptions.liveOnOpen); live.setText(liveLabel());
        query.getEmptyText().setText("Graylog query, e.g. request_id:abc-123");
        updateContext("No connection");

        OnePixelSplitter content = new OnePixelSplitter(false, 0.66f);
        content.setHonorComponentsMinimumSize(false);
        content.setFirstComponent(createSearchPanel()); content.setSecondComponent(inspector);
        OnePixelSplitter all = navigationSplitter = new OnePixelSplitter(false, "graylog.explorer.connections.proportion", 0.22f);
        all.setHonorComponentsMinimumSize(false);
        all.setFirstComponent(connections); all.setSecondComponent(content);
        add(createActionRail(), BorderLayout.WEST);
        add(all, BorderLayout.CENTER);
        setConnectionsVisible(PropertiesComponent.getInstance(project).getBoolean("graylog.explorer.connections.visible", true));
        indexControl.setEnabled(false);
        add(ExplorerUi.bar(indexStatus, ExplorerUi.toolbar("Graylog.PeriodIndex", this,
                ExplorerUi.button(indexControl, AllIcons.Actions.Refresh, true)), true), BorderLayout.SOUTH);
        setMinimumSize(JBUI.size(750, 300));

        query.addActionListener(e -> search());
        query.registerKeyboardAction(e -> search(), KeyStroke.getKeyStroke("control ENTER"), JComponent.WHEN_FOCUSED);
        period.addActionListener(e -> search());
        pageSize.addActionListener(e -> {
            SearchController.Session submitted = search.state().getRequested();
            if (active != null && submitted != null && submitted.getScope().matchesInput(query.getText(), selectedStream, sourceFilter.values())) {
                executeSearch(new SearchController.Session(operation(), submitted.getScope(), 0, (Integer) pageSize.getSelectedItem()));
            } else search();
        });
        live.addActionListener(e -> { if (live.isSelected()) search(); });
        codeMarks.addActionListener(e -> {
            highlights.setEnabled(codeMarks.isSelected()); settings.setHighlightExceptions(codeMarks.isSelected());
            if (!codeMarks.isSelected()) { resetIndex(); indexStatus.setText("Exception marks disabled."); }
            else if (active != null && loadedRequest() != null) startIndex(operation(), loadedRequest(), true);
        });
        indexControl.addActionListener(e -> {
            if (indexBusy()) {
                index.cancel();
            } else if (active != null && loadedRequest() != null) startIndex(operation(), loadedRequest(), true);
        });
        allEvents.addActionListener(e -> search.showLoadedPage());
        previous.addActionListener(e -> page(-1)); next.addActionListener(e -> page(1));
        timer = new Timer(ideOptions.liveIntervalSeconds * 1000, e -> {
            if (live.isSelected() && toolWindow.isVisible() && isShowing() && !searchBusy() && !indexBusy() && active != null) search();
        });
        timer.start();
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(SettingsEvents.TOPIC, this::settingsChanged);
        live.setName("explorer.live"); indexStatus.setName("explorer.indexStatus");
        query.setName("explorer.query");
        events.setName("explorer.events");
        connections.setName("explorer.connections");
        codeMarks.setName("explorer.codeMarks");
        allEvents.setName("explorer.allEvents");
        next.setName("explorer.next");
        previous.setName("explorer.previous");
        status.setName("explorer.status");
        navigationSplitter.setName("explorer.navigationSplitter");
        rebuildTree();
        String initial = initialConnection(); if (!initial.isBlank()) later(() -> selectNode(initial + ":"));
    }

    public record ViewState(SearchController.State search, PeriodIndexController.State index, SourceCatalog.State sources,
                     LogEvent selected, String stream, java.util.Set<String> selectedSources, IdeOptions options, int liveDelay, boolean sourcesPopupVisible) { }
    public ViewState viewState() { return new ViewState(search.state(), index.state(), catalog.state(), selected, selectedStream, sourceFilter.values(), ideOptions.copy(), timer.getDelay(), sourcePopup != null && sourcePopup.popup().isVisible()); }
    SourceFilterPopup sourcesPopup() { return sourcePopup; }
    ExceptionHighlights decorations() { return highlights; }
    QueryCompletion queryCompletion() { return completion; }
    Project project() { return project; }
    JComponent searchField() { return query; }
    public void searchText(String value) { query.setText(value); search(); query.requestFocusInWindow(); }

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
                        () -> connections.selectedNode() != null),
                ExplorerUi.command("Refresh streams and sources", AllIcons.Actions.Refresh, () -> { loadStreams(active); loadSources(active.copy(), sourceScope(), true); }, () -> active != null),
                com.intellij.openapi.actionSystem.Separator.getInstance(),
                options,
                ExplorerUi.command("Expand all connections", AllIcons.Actions.Expandall, () -> connections.changeExpansion(true), () -> connectionsVisible),
                ExplorerUi.command("Collapse all connections", AllIcons.Actions.Collapseall, () -> connections.changeExpansion(false), () -> connectionsVisible)) {
            @Override public ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public void update(AnActionEvent event) { event.getPresentation().setVisible(connectionsVisible); }
        };
        JComponent commands = ExplorerUi.toolbar("Graylog.ActionRail", this, false, visibility, expanded);
        rail.add(commands, BorderLayout.NORTH);
        return rail;
    }

    private void setConnectionsVisible(boolean visible) {
        connectionsVisible = visible; connections.setVisible(visible);
        navigationSplitter.setFirstComponent(visible ? connections : null);
        PropertiesComponent.getInstance(project).setValue("graylog.explorer.connections.visible", visible, true);
        revalidate(); repaint();
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

    private void rebuildTree() {
        connections.update(new ConnectionsView.Content(settings.connections(), settings.getState().searches,
                streamCatalog.values(), active, selectedStream, selectedNodeKey, catalog.state(), sourceFilter.values()));
    }
    private void selectNode(String key) { connections.selectNode(key); }
    private void selectNavigationNode(ConnectionsView.Node value) {
        boolean changedConnection = active == null || !active.id.equals(value.connection().id);
        invalidateSearch();
        active = value.connection().copy(); selectedStream = value.streamId() == null ? "" : value.streamId();
        String sourceContext = active.id + "\n" + active.apiUrl + "\n" + active.serviceField;
        if (!sourceContext.equals(sourceFilterContext)) { sourceFilter.clear(); sourceFilterContext = sourceContext;  }
        if (changedConnection) completion.reset(active.fields);
        query.getEmptyText().setText("Graylog query, e.g. " + active.fields.requestIdField + ":abc-123");
        selectedNodeKey = value.key();
        updateContext(active.name + (selectedStream.isEmpty() ? " / All messages" : " / " + value.title()));
        if (value.saved() != null) {
            sourceFilter.clear();
            query.setText(value.saved().query); selectedStream = value.saved().streamId;
            rebuilding = true; choosePeriod(value.saved().rangeSeconds); rebuilding = false;
    }
        clearEvents();
        if (changedConnection || !streamCatalog.contains(active.id)) loadStreams(active);
        search();
    }


    private void editConnection(Connection existing) {
        ConnectionDialog dialog = new ConnectionDialog(project, existing);
        if (!dialog.showAndGet()) return;
        Connection connection = dialog.connection();
        char[] entered = dialog.token();
        setStatus("Saving connection…", false);
        tasks.submit(() -> {
            try { return ConnectionManager.get().save(ServerConnection.from(connection), entered); }
            finally { Arrays.fill(entered, '\0'); }
        }, saved -> {
            settings.remember(saved.id, query.getText(), seconds()); rebuildTree(); selectNode(saved.id + ":");
        }, error -> setStatus("Could not save connection: " + errorMessage(error), true));
    }

    private void removeSelected() {
        ConnectionsView.Node node = connections.selectedNode();
        if (node == null) return;
        if (node.saved() != null) {
            settings.removeSearch(node.saved().id); selectedNodeKey = node.connection().id + ":"; rebuildTree();
            return;
        }
        if (Messages.showYesNoDialog(project, "Remove the IDE-wide connection “" + node.connection().name + "” from all projects? Project mappings and saved searches will be retained.",
                "Remove Graylog Connection", Messages.getQuestionIcon()) != Messages.YES) return;
        String id = node.connection().id;
        setStatus("Removing connection…", false);
        tasks.submit(() -> { ConnectionManager.get().remove(id); return id; }, removed -> {
            streamCatalog.remove(removed);
            if (active != null && active.id.equals(removed)) {
                invalidateSearch(); live.setSelected(false); active = null; selectedNodeKey = ""; selectedStream = "";
                clearEvents(); updateContext("No connection");
            }
            rebuildTree(); setStatus("Connection removed. Select or add a connection.", false);
        }, error -> setStatus("Could not remove connection: " + errorMessage(error), true));
    }

    private void loadStreams(Connection connection) { streamCatalog.load(OperationConfig.capture(connection, ideOptions)); }
    private void publishStreams() {
        rebuildTree(); if (active == null) return;
        var entry = streamCatalog.entry(active.id); if (entry == null) return;
        connections.showStreams(entry.getLoading() ? "Loading streams…" : !entry.getError().isBlank()
                ? "Streams unavailable · Refresh to retry" : counted(entry.getStreams().size(), "stream"),
                entry.getError().isBlank() ? null : entry.getError());
    }

    private GraylogApi client(OperationConfig config) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        String token = GraylogCredentials.load(config.server().credentialKey());
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        return new GraylogClient(config, token);
    }

    private void search() {
        if (rebuilding || disposed) return;
        if (active == null) { live.setSelected(false); setStatus("Add or select a connection first.", false); return; }
        Instant now = Instant.now();
        submit(new SearchScope(query.getText(), selectedStream, now.minusSeconds(seconds()), now, active.serviceField, sourceFilter.values()));
    }
    private OperationConfig operation() { return OperationConfig.capture(active, ideOptions); }
    private void submit(SearchScope scope) { executeSearch(new SearchController.Session(operation(), scope, 0, (Integer) pageSize.getSelectedItem())); }
    private boolean searchBusy() { return search.state().getPhase() == SearchController.Phase.LOADING; }
    private SearchRequest loadedRequest() { var page = search.state().getPage(); return page == null ? null : page.getSession().request(); }
    private List<LogEvent> loadedEvents() { var page = search.state().getPage(); return page == null ? List.of() : page.getResult().events(); }
    private String effectiveQuery() { return sourceFilter.query(query.getText(), active == null ? "source" : active.serviceField); }
    private SearchRequest sourceScope() {
        var submitted = search.state().getRequested();
        if (submitted != null) return submitted.getScope().facet((Integer) pageSize.getSelectedItem());
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
        if (active == null || searchBusy() || rebuilding) return;
        java.util.Set<String> values = new java.util.LinkedHashSet<>(sourceFilter.values());
        if (entry.name() == null) values.clear(); else if (!values.remove(entry.name())) values.add(entry.name());
        applySources(sourceScope(), values);
    }
    private void applySources(SearchRequest scope, java.util.Set<String> values) {
        if (active == null || !scope.sameScope(sourceScope())) return;
        sourceFilter.replace(values);
        rebuildTree();
        submit(new SearchScope(scope.query(), selectedStream, scope.from(), scope.to(), active.serviceField, sourceFilter.values()));
    }

    private void publishSources() {
        rebuildTree();
        if (sourcePopup == null || !sourcePopup.alive()) return;
        SourceCatalog.State state = catalog.state();
        if (state.getLoading()) sourcePopup.loading();
        else if (!state.getError().isBlank()) sourcePopup.fallback(state.getValues().values(), state.getError());
        else if (state.getValues() != null) sourcePopup.loaded(state.getValues());
    }
    private void loadSources(Connection connection, SearchRequest scope, boolean force) {
        catalog.load(OperationConfig.capture(connection, ideOptions), scope, loadedEvents(), force);
    }

    private void executeSearch(SearchController.Session session) {
        settings.remember(session.getConfig().server().id(), session.getScope().query(), seconds());
        settings.rememberPageSize(session.getLimit()); search.load(session);
    }
    private void renderSearch(SearchController.State state) {
        if (disposed) return;
        coordinator.searchChanged(state, active == null ? null : operation(), codeMarks.isSelected(), ideOptions.autoIndex);
        SearchController.Page page = state.getPage();
        boolean pageChanged = page != renderedPage;
        if (pageChanged) {
            renderedPage = page;
            if (page != null) completion.observe(page.getResult().events(), page.getSession().getConfig().server().serviceField());
        }
        if (state.getDisplay() != renderedDisplay) {
            renderedDisplay = state.getDisplay();
            displayEvents(state.rows());
        }
        allEvents.setEnabled(state.getDisplay() instanceof SearchController.Display.Examples);
        updatePaging();
        switch (state.getPhase()) {
            case IDLE -> { }
            case LOADING -> {
                var submitted = state.getRequested();
                if (!index.matches(submitted.getConfig(), submitted.request())) {
                    indexStatus.setText(codeMarks.isSelected() ? "Exceptions: waiting for search results…" : "Exception marks disabled.");
                }
                allEvents.setEnabled(false); setStatus("Searching " + active.name + "…", false);
            }
            case FAILED -> { live.setSelected(false); setStatus("Search failed: " + state.getError() + " Previous results, if any, are unchanged.", true); }
            case READY -> {
                if (state.getDisplay() instanceof SearchController.Display.Examples examples) {
                    var site = examples.getSite();
                    setStatus("Graylog · " + site.location().file().getFileName() + ":" + site.location().line()
                            + " · " + site.count() + " event(s) in period · showing " + site.events().size() + " example(s) · " + String.join(", ", site.types()), false);
                } else if (page != null) {
                    var request = page.getSession().request(); long first = request.offset() + 1, last = request.offset() + page.getResult().events().size();
                    String count = page.getResult().events().isEmpty() ? "No events" : (first == last ? Long.toString(first) : first + "–" + last) + " of " + counted(page.getResult().total(), "event");
                    setStatus(active.name + " · " + count + (live.isSelected() ? " · Live every " + ideOptions.liveIntervalSeconds + "s" : ""), false);
                    status.setToolTipText("UTC: " + request.from() + " → " + request.to() + " | Query: " + request.query());
                    events.getEmptyText().setText("No events matched. Try a wider period or another query.");
                    if (codeMarks.isSelected() && !ideOptions.autoIndex && index.state().getPhase() == PeriodIndexController.Phase.IDLE) {
                        indexStatus.setText("Exception index is manual · click Refresh marks."); indexControl.setEnabled(true);
                    }
                }
            }
        }
    }

    private void startIndex(OperationConfig config, SearchRequest request, boolean force) {
        if (codeMarks.isSelected() && !disposed) index.start(config, request, projectRoot(), force);
    }
    private boolean indexBusy() { return index.state().getPhase() == PeriodIndexController.Phase.LOADING; }
    private Path projectRoot() { return project.getBasePath() == null ? null : Path.of(project.getBasePath()); }
    private void renderIndex(PeriodIndexController.State state) {
        switch (state.getPhase()) {
            case IDLE -> {
                highlights.clear(); indexControl.setEnabled(false); indexControl.setText("Refresh marks"); indexStatus.setForeground(getForeground());
            }
            case LOADING -> {
                String context = state.getConfig().server().name() + " · " + state.getRequest().from() + " → " + state.getRequest().to() + " UTC · " + state.getRequest().query();
                if (state.getProgress() == 0) { highlights.replace(List.of(), context, state.getConfig().server().connection(), state.getRoot()); highlights.select(selected); }
                indexStatus.setForeground(getForeground()); indexStatus.setToolTipText(context);
                indexStatus.setText(state.getProgress() == 0 ? "Indexing exceptions for the whole period…" : "Indexing exceptions · " + state.getProgress() + " messages processed…");
                indexControl.setText("Cancel scan"); indexControl.setEnabled(true);
            }
            case READY -> {
                GraylogApi.PeriodIndex result = state.getResult();
                String context = state.getConfig().server().name() + " · " + state.getRequest().from() + " → " + state.getRequest().to() + " UTC · " + state.getRequest().query();
                highlights.replace(result.sites(), context, state.getConfig().server().connection(), state.getRoot()); highlights.select(selected);
                indexStatus.setText("Whole period · " + counted(result.exceptions(), "exception") + " · " + counted(result.sites().size(), "location") + " · " + counted(result.messages(), "message"));
                indexStatus.setForeground(getForeground()); indexControl.setText("Refresh marks");
            }
            case FAILED -> {
                live.setSelected(false); indexStatus.setText("Period index incomplete: " + state.getError());
                indexStatus.setForeground(ERROR_COLOR); indexStatus.setToolTipText(indexStatus.getText()); indexControl.setText("Retry marks");
            }
            case CANCELED -> {
                live.setSelected(false); indexStatus.setText("Exception scan canceled · period index incomplete."); indexControl.setText("Retry marks");
            }
        }
    }
    private void resetIndex() { index.clear(); }

    private void page(int direction) { if (active != null) { live.setSelected(false); search.page(direction, operation()); } }

    private void showEvent(LogEvent event) {
        selected = event; highlights.select(event); inspector.show(event, active, loadedRequest());
    }

    private void showSourceMenu(MouseEvent mouse) {
        int row = events.rowAtPoint(mouse.getPoint()); if (row < 0) return;
        events.setRowSelectionInterval(row, row);
        String source = events.rows().get(events.convertRowIndexToModel(row)).service(events.serviceField());
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
        SourceLocation location = inspector.selectedLocation();
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
        String identity = selected == null ? restoreSelection : selected.identity(); restoreSelection = "";
        events.show(rows, active == null ? "source" : active.serviceField, identity, !live.isSelected());
        if (events.getSelectedRow() < 0) showEvent(null);
    }

    private void updatePaging() {
        previous.setEnabled(search.state().canPrevious()); next.setEnabled(search.state().canNext());
    }
    private void showSite(ExceptionSites.Site site) {
        if (disposed) return;
        live.setSelected(false); SearchController.Page page = search.state().getPage();
        toolWindow.activate(() -> { if (!disposed && search.state().getPage() == page) search.showExamples(site); });
    }

    private void showAround() {
        if (selected == null || selected.timestamp() == null || active == null) return;
        live.setSelected(false); query.setText("*");
        Instant timestamp = selected.timestamp();
        submit(new SearchScope("*", selectedStream, timestamp.minusSeconds(300), timestamp.plusSeconds(300), active.serviceField, sourceFilter.values()));
    }

    private void followRequest() {
        if (selected == null || active == null) return;
        String key = !selected.text("request_id").isBlank() ? "request_id" : !selected.text("trace_id").isBlank() ? "trace_id" : "correlation_id";
        String value = selected.text(key);
        if (value.isBlank()) return;
        sourceFilter.clear();
        query.setText(SearchRequest.exact(selected.fieldName(key), value)); selectedStream = "";
        selectedNodeKey = active.id + ":"; rebuildTree();
        updateContext(active.name + " / All messages"); search();
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
        search.clear(); catalog.clear(); restoreSelection = "";
        resetIndex(); indexStatus.setText("Exceptions: select a connection and search."); allEvents.setEnabled(false);
        showEvent(null);
    }
    private void invalidateSearch() { search.cancel(); }
    private String liveLabel() { return "Live · " + ideOptions.liveIntervalSeconds + "s"; }
    private String initialConnection() {
        List<Connection> available = settings.connections(); if (available.isEmpty()) return "";
        GraylogSettings.Data state = settings.getState();
        for (String id : List.of(state.selectedConnection, state.preferredConnection, ideOptions.defaultConnection)) {
            if (available.stream().anyMatch(c -> c.id.equals(id))) return id;
        }
        return available.getFirst().id;
    }
    private void settingsChanged(SettingsEvents.Event event) {
        if (disposed || !event.appliesTo(project)) return;
        SettingsChange change = event.changes(); IdeOptions previous = ideOptions;
        ideOptions = GraylogIdeSettings.get().getState().options;
        if (change.has(SettingsChange.Area.LIVE)) {
            timer.setDelay(ideOptions.liveIntervalSeconds * 1000); timer.setInitialDelay(timer.getDelay()); live.setText(liveLabel());
        }
        if (change.has(SettingsChange.Area.SOURCE_STYLE)) {
            events.repaint(); connections.repaint();
            if (sourcePopup != null && sourcePopup.alive()) sourcePopup.popup().getContent().repaint();
        }
        if (change.has(SettingsChange.Area.HIGHLIGHT_STYLE) || change.has(SettingsChange.Area.INDEX_OPTIONS)) highlights.configure(ideOptions);
        Connection nextActive = active == null ? null : settings.connections().stream().filter(c -> c.id.equals(active.id)).findFirst().orElse(null);
        if (active == null || nextActive == null || change.has(SettingsChange.Area.PREFERENCE) && !active.id.equals(initialConnection())) {
            if (change.affectsRequests()) reloadSettings(); return;
        }
        Connection beforeConnection = active;
        SettingsImpact impact = SettingsImpact.between(OperationConfig.capture(beforeConnection, previous), OperationConfig.capture(nextActive, ideOptions));
        active = nextActive.copy();
        if (change.has(SettingsChange.Area.LOG_FORMAT)) completion.reset(active.fields);
        if (change.has(SettingsChange.Area.CONNECTIONS) || change.has(SettingsChange.Area.LOG_FORMAT) || change.has(SettingsChange.Area.MAPPINGS)) {
            query.getEmptyText().setText("Graylog query, e.g. " + active.fields.requestIdField + ":abc-123");
            updateContext(active.name + (selectedStream.isEmpty() ? " / All messages" : " / " + selectedStream));
            inspector.show(selected, active, loadedRequest());
        }
        if (change.has(SettingsChange.Area.HIGHLIGHT_MODE)) {
            codeMarks.setSelected(settings.highlightsEnabled()); highlights.setEnabled(codeMarks.isSelected());
            if (!codeMarks.isSelected()) { resetIndex(); indexStatus.setText("Exception marks disabled."); }
        }
        if (impact.streams()) { streamCatalog.remove(active.id); loadStreams(active.copy()); }
        if (change.has(SettingsChange.Area.CONNECTIONS)) rebuildTree();
        String context = active.id + "\n" + active.apiUrl + "\n" + active.serviceField;
        if (impact.page() && !sourceFilterContext.equals(context)) { sourceFilter.clear(); sourceFilterContext = context; }
        coordinator.settingsChanged(OperationConfig.capture(beforeConnection, previous), operation(), codeMarks.isSelected(), ideOptions.autoIndex,
                sourceFilter.values(), change.has(SettingsChange.Area.HIGHLIGHT_MODE), change.has(SettingsChange.Area.INDEX_POLICY));
        if (codeMarks.isSelected() && !ideOptions.autoIndex && loadedRequest() != null && !searchBusy()) {
            indexStatus.setText("Exception index is manual · click Refresh marks."); indexControl.setEnabled(true);
        }
        if (change.has(SettingsChange.Area.CONNECTIONS)) renderSearch(search.state());
    }

    private void reloadSettings() {
        if (disposed || project.isDisposed()) return;
        connections.clearFilter();
        Connection before = active;
        String previousStream = selectedStream;
        String previousSelection = selected == null ? "" : selected.identity();
        ideOptions = GraylogIdeSettings.get().getState().options;
        timer.setDelay(ideOptions.liveIntervalSeconds * 1000); timer.setInitialDelay(ideOptions.liveIntervalSeconds * 1000);
        live.setText(liveLabel()); highlights.configure(ideOptions);
        codeMarks.setSelected(settings.highlightsEnabled()); highlights.setEnabled(codeMarks.isSelected());
        invalidateSearch(); clearEvents();
        if (before == null || settings.connections().stream().noneMatch(c -> c.id.equals(before.id) && c.apiUrl.equals(before.apiUrl))) streamCatalog.clear();
        streamCatalog.invalidatePending();
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
    private void later(Runnable runnable) { if (!disposed) tasks.ui(runnable); }
    private static String errorMessage(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static String counted(long value, String noun) { return value + " " + noun + (value == 1 ? "" : "s"); }
    @Override public void dispose() {
        disposed = true; timer.stop(); completion.dispose(); if (sourcePopup != null && sourcePopup.alive()) sourcePopup.popup().cancel(); catalog.close(); index.close(); search.close(); streamCatalog.close();
    }

}
