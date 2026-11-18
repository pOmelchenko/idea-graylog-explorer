package dev.graylog.jetbrains

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.ColorChooserService
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.Instant
import java.util.Arrays
import java.util.concurrent.*
import java.util.function.*
import java.util.function.Consumer
import java.util.function.LongSupplier
import javax.swing.*
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.Timer

class GraylogPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : JPanel(BorderLayout()), Disposable {
    @JvmRecord
    private data class Period(val label: String, val seconds: Int) {
        override fun toString(): String = label
    }

    private val settings: GraylogSettings

    @Volatile
    private var ideOptions: IdeOptions
    private val highlights: ExceptionHighlights
    private val inspector: EventInspector
    private val connections: ConnectionsView
    private val tasks: IdeTaskRunner
    private val catalog: SourceCatalog
    private val index: PeriodIndexController
    private val search: SearchController
    private val streamCatalog: StreamCatalog
    private val coordinator: ExplorerCoordinator
    private var renderedPage: SearchController.Page? = null
    private var renderedDisplay: SearchController.Display? = null
    private val timer: Timer
    private val query: ExtendableTextField =
        ExplorerUi.filterField("Graylog query", Runnable { search() })
    private val completion = QueryCompletion(query)
    private val sourceFilter = SourceFilter()
    private var sourceFilterContext = ""
    private var sourcePopup: SourceFilterPopup? = null
    private val period: JComboBox<Period> =
        JComboBox(PERIODS)
    private val pageSize: JComboBox<Int> = JComboBox(arrayOf<Int>(100, 250, 500))
    private val live = JToggleButton("Live · 5s")
    private val codeMarks = JToggleButton("Code marks", true)
    private val status = JLabel("Add a connection to start searching Graylog.")
    private val indexStatus = JLabel("Exceptions: select a connection and search.")
    private val indexControl = JButton("Refresh marks")
    private val events = EventsTable(
        java.util.function.Supplier { ideOptions },
        Consumer { event: LogEvent? -> this.showEvent(event) },
        Consumer { mouse: MouseEvent -> this.showSourceMenu(mouse) })
    private val previous = JButton("Previous")
    private val next = JButton("Next")
    private val allEvents = JButton("All loaded events")
    private var active: Connection? = null
    private var selectedStream = ""
    private var selectedNodeKey = ""
    private var selected: LogEvent? = null
    private var restoreSelection = ""
    private var rebuilding = false
    private val navigationSplitter: OnePixelSplitter
    private var connectionsVisible = true

    @Volatile
    private var disposed = false

    init {
        settings = GraylogSettings.get(project)
        ideOptions = GraylogIdeSettings.get().getState().options
        tasks = IdeTaskRunner(project, this)
        catalog = SourceCatalog(
            tasks,
            GraylogApi.Factory(::client),
            LongSupplier { System.nanoTime() / 1000000 },
            Runnable { this.publishSources() })
        index = PeriodIndexController(
            tasks,
            GraylogApi.Factory(::client),
            Consumer { state: PeriodIndexController.State -> this.renderIndex(state) })
        search = SearchController(
            tasks,
            GraylogApi.Factory(::client),
            Consumer { state: SearchController.State -> this.renderSearch(state) })
        streamCatalog = StreamCatalog(
            tasks,
            GraylogApi.Factory(::client),
            Runnable { this.publishStreams() })
        coordinator = ExplorerCoordinator(search, catalog, index, Supplier { this.projectRoot() })
        highlights = ExceptionHighlights(
            project,
            this,
            Consumer { site: ExceptionSites.Site -> this.showSite(site) })
        inspector = EventInspector(
            project,
            this,
            Runnable { this.openSource() },
            Runnable { this.showAround() },
            Runnable { this.followRequest() })
        inspector.setName("explorer.inspector")
        connections = ConnectionsView(
            java.util.function.Supplier { ideOptions },
            java.util.function.BooleanSupplier { !searchBusy() },
            Runnable { this.rebuildTree() },
            Consumer { value: ConnectionsView.Node -> this.selectNavigationNode(value) },
            Consumer { entry: SourceTree.Entry -> this.toggleTreeSource(entry) })
        val saved = settings.getState()
        codeMarks.setSelected(settings.highlightsEnabled())
        highlights.configure(ideOptions)
        highlights.setEnabled(codeMarks.isSelected())
        query.setText(saved.lastQuery)
        choosePeriod(if (saved.rangeSeconds == 0) ideOptions.defaultPeriodSeconds else saved.rangeSeconds)
        pageSize.setSelectedItem(if (saved.pageSize == 0) ideOptions.defaultPageSize else saved.pageSize)
        live.setSelected(ideOptions.liveOnOpen)
        live.setText(liveLabel())
        query.getEmptyText().setText("Graylog query, e.g. request_id:abc-123")
        updateContext("No connection")

        val content = OnePixelSplitter(false, 0.66f)
        content.setHonorComponentsMinimumSize(false)
        content.setFirstComponent(createSearchPanel())
        content.setSecondComponent(inspector)
        navigationSplitter = OnePixelSplitter(false, "graylog.explorer.connections.proportion", 0.22f)
        val all: OnePixelSplitter = navigationSplitter
        all.setHonorComponentsMinimumSize(false)
        all.setFirstComponent(connections)
        all.setSecondComponent(content)
        add(createActionRail(), BorderLayout.WEST)
        add(all, BorderLayout.CENTER)
        setConnectionsVisible(
            PropertiesComponent.getInstance(project).getBoolean("graylog.explorer.connections.visible", true)
        )
        indexControl.setEnabled(false)
        add(
            ExplorerUi.bar(
                indexStatus, ExplorerUi.toolbar(
                    "Graylog.PeriodIndex", this,
                    ExplorerUi.button(indexControl, AllIcons.Actions.Refresh, true)
                ), true
            ), BorderLayout.SOUTH
        )
        setMinimumSize(JBUI.size(750, 300))

        query.addActionListener({ e -> search() })
        query.registerKeyboardAction(
            { e -> search() },
            KeyStroke.getKeyStroke("control ENTER"),
            JComponent.WHEN_FOCUSED
        )
        period.addActionListener({ e -> search() })
        pageSize.addActionListener({ e ->
            val submitted = search.state().requested
            if (active != null && submitted != null && submitted.scope.matchesInput(
                    query.getText(),
                    selectedStream,
                    sourceFilter.values()
                )
            ) {
                executeSearch(SearchController.Session(operation(), submitted.scope, 0, pageSize.getSelectedItem() as Int)
                )
            } else search()
        })
        live.addActionListener({ e -> if (live.isSelected()) search() })
        codeMarks.addActionListener({ e ->
            highlights.setEnabled(codeMarks.isSelected())
            settings.setHighlightExceptions(codeMarks.isSelected())
            if (!codeMarks.isSelected()) {
                resetIndex()
                indexStatus.setText("Exception marks disabled.")
            } else if (active != null && loadedRequest() != null) loadedRequest()?.let { startIndex(operation(), it, true) }
        })
        indexControl.addActionListener({ e ->
            if (indexBusy()) {
                index.cancel()
            } else if (active != null && loadedRequest() != null) loadedRequest()?.let { startIndex(operation(), it, true) }
        })
        allEvents.addActionListener({ e -> search.showLoadedPage() })
        previous.addActionListener({ e -> page(-1) })
        next.addActionListener({ e -> page(1) })
        timer = Timer(ideOptions.liveIntervalSeconds * 1000, { e ->
            if (live.isSelected() && toolWindow.isVisible() && isShowing() && !searchBusy() && !indexBusy() && active != null) search()
        })
        timer.start()
        ApplicationManager.getApplication().getMessageBus().connect(this)
            .subscribe<SettingsEvents.Listener>(
                SettingsEvents.TOPIC,
                SettingsEvents.Listener(::settingsChanged))
        live.setName("explorer.live")
        indexStatus.setName("explorer.indexStatus")
        query.setName("explorer.query")
        events.setName("explorer.events")
        connections.setName("explorer.connections")
        codeMarks.setName("explorer.codeMarks")
        allEvents.setName("explorer.allEvents")
        next.setName("explorer.next")
        previous.setName("explorer.previous")
        status.setName("explorer.status")
        navigationSplitter.setName("explorer.navigationSplitter")
        rebuildTree()
        val initial: String = initialConnection()
        if (!initial.isBlank()) later(Runnable { selectNode(initial.toString() + ":") })
    }

    @JvmRecord
    data class ViewState(
        val search: SearchController.State,
        val index: PeriodIndexController.State,
        val sources: SourceCatalog.State,
        val selected: LogEvent?,
        val stream: String,
        val selectedSources: Set<String>,
        val options: IdeOptions,
        val liveDelay: Int,
        val sourcesPopupVisible: Boolean
    )

    fun viewState(): ViewState {
        return ViewState(
            search.state(),
            index.state(),
            catalog.state(),
            selected,
            selectedStream,
            sourceFilter.values(),
            ideOptions.copy(),
            timer.getDelay(),
            sourcePopup?.popup()?.isVisible == true
        )
    }

    fun sourcesPopup(): SourceFilterPopup? = sourcePopup

    fun decorations(): ExceptionHighlights = highlights

    @JvmName("queryCompletion") internal fun queryCompletion(): QueryCompletion = completion

    fun project(): Project? = project

    fun searchField(): JComponent = query

    fun searchText(value: String?) {
        query.setText(value)
        search()
        query.requestFocusInWindow()
    }

    private fun createActionRail(): JComponent {
        val rail = JPanel(BorderLayout())
        val visibility: AnAction =
            object : DumbAwareAction("Show/hide connections", "Hide connections", AllIcons.Actions.ArrowCollapse) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun update(event: AnActionEvent) {
                    event.getPresentation().setText(if (connectionsVisible) "Hide connections" else "Show connections")
                    event.getPresentation()
                        .setDescription(if (connectionsVisible) "Hide connections" else "Show connections")
                    event.getPresentation()
                        .setIcon(if (connectionsVisible) AllIcons.Actions.ArrowCollapse else AllIcons.Actions.ArrowExpand)
                }

                override fun actionPerformed(event: AnActionEvent) {
                    setConnectionsVisible(!connectionsVisible)
                }
            }
        val options = DefaultActionGroup("Graylog settings", true)
        options.getTemplatePresentation().setIcon(AllIcons.General.GearPlain)
        options.add(
            ExplorerUi.command(
                "Graylog Explorer settings", AllIcons.General.Settings,
                Runnable {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, GraylogIdeConfigurable::class.java)
                }, BooleanSupplier { true })
        )
        options.add(
            ExplorerUi.command(
                "Project source mappings and log fields", AllIcons.Nodes.Folder,
                Runnable {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, GraylogProjectConfigurable::class.java)
                }, BooleanSupplier { true })
        )
        val expanded: DefaultActionGroup = object : DefaultActionGroup(
            com.intellij.openapi.actionSystem.Separator.getInstance(),
            ExplorerUi.command(
                "Add connection",
                AllIcons.General.Add,
                Runnable { editConnection(null) },
                BooleanSupplier { true }),
            ExplorerUi.command(
                "Edit selected connection",
                AllIcons.Actions.Edit,
                Runnable { editConnection(active) },
                BooleanSupplier { active != null }),
            ExplorerUi.command(
                "Remove selected connection or saved search",
                AllIcons.General.Remove,
                Runnable { this.removeSelected() },
                BooleanSupplier { connections.selectedNode() != null }),
            ExplorerUi.command("Refresh streams and sources", AllIcons.Actions.Refresh, Runnable {
                loadStreams(active)
                loadSources(active!!.copy(), sourceScope(), true)
            }, BooleanSupplier { active != null }),
            com.intellij.openapi.actionSystem.Separator.getInstance(),
            options,
            ExplorerUi.command(
                "Expand all connections",
                AllIcons.Actions.Expandall,
                Runnable { connections.changeExpansion(true) },
                BooleanSupplier { connectionsVisible }),
            ExplorerUi.command(
                "Collapse all connections",
                AllIcons.Actions.Collapseall,
                Runnable { connections.changeExpansion(false) },
                BooleanSupplier { connectionsVisible })
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.getPresentation().setVisible(connectionsVisible)
            }
        }
        val commands: JComponent? = ExplorerUi.toolbar("Graylog.ActionRail", this, false, visibility, expanded)
        rail.add(commands, BorderLayout.NORTH)
        return rail
    }

    private fun setConnectionsVisible(visible: Boolean) {
        connectionsVisible = visible
        connections.setVisible(visible)
        navigationSplitter.setFirstComponent(if (visible) connections else null)
        PropertiesComponent.getInstance(project).setValue("graylog.explorer.connections.visible", visible, true)
        revalidate()
        repaint()
    }

    private fun createSearchPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        live.setToolTipText("Refresh the search while this tool window is visible.")
        codeMarks.setToolTipText("Index all matching messages in the selected period; click a gutter marker to show related examples.")
        val search = JPanel(BorderLayout(6, 0))
        search.setBorder(JBUI.Borders.empty(3, 6))
        search.add(query, BorderLayout.CENTER)
        search.add(
            ExplorerUi.toolbar(
                "Graylog.Filters",
                this,
                SourceFilterAction(
                    Supplier { sourceFilter.label() },
                    BooleanSupplier { active != null },
                    BiFunction { anchor: JComponent, onDispose: Runnable? ->
                        this.createSourcePopup(anchor, onDispose)
                    }),
                ExplorerUi.choice("Time range", "", period),
                ExplorerUi.choice("Page size", "Rows: ", pageSize),
                ExplorerUi.toggle(live, AllIcons.Actions.Resume, false),
                ExplorerUi.toggle(codeMarks, AllIcons.General.InspectionsEye, false),
                ExplorerUi.command(
                    "Save this search",
                    AllIcons.Actions.MenuSaveall,
                    Runnable { this.saveSearch() },
                    BooleanSupplier { active != null })
            ), BorderLayout.EAST
        )
        panel.add(search, BorderLayout.NORTH)

        panel.add(ExplorerUi.scroll(events), BorderLayout.CENTER)
        previous.setEnabled(false)
        next.setEnabled(false)
        allEvents.setEnabled(false)
        val paging = JPanel(BorderLayout(8, 0))
        paging.add(
            ExplorerUi.toolbar(
                "Graylog.Paging",
                this,
                ExplorerUi.button(previous, AllIcons.Actions.Back, false),
                ExplorerUi.button(next, AllIcons.Actions.Forward, false),
                ExplorerUi.button(allEvents, AllIcons.Actions.Show, true, allEvents::isEnabled)
            ), BorderLayout.WEST
        )
        paging.add(status, BorderLayout.CENTER)
        val utc = JLabel("UTC")
        utc.setForeground(UIUtil.getContextHelpForeground())
        paging.add(utc, BorderLayout.EAST)
        panel.add(ExplorerUi.bar(paging, null, true), BorderLayout.SOUTH)
        return panel
    }

    private fun rebuildTree() {
        connections.update(
            ConnectionsView.Content(
                settings.connections(), settings.getState().searches,
                streamCatalog.values(), active, selectedStream, selectedNodeKey, catalog.state(), sourceFilter.values()
            )
        )
    }

    private fun selectNode(key: String) {
        connections.selectNode(key)
    }

    private fun selectNavigationNode(value: ConnectionsView.Node) {
        val changedConnection = active?.id != value.connection.id
        invalidateSearch()
        val connection = value.connection.copy()
        active = connection
        selectedStream = if (value.streamId == null) "" else value.streamId
        val sourceContext = connection.id.toString() + "\n" + connection.apiUrl + "\n" + connection.serviceField
        if (!sourceContext.equals(sourceFilterContext)) {
            sourceFilter.clear()
            sourceFilterContext = sourceContext
        }
        if (changedConnection) completion.reset(connection.fields)
        query.getEmptyText().setText("Graylog query, e.g. " + connection.fields.requestIdField + ":abc-123")
        selectedNodeKey = value.key()
        updateContext(connection.name.toString() + (if (selectedStream.isEmpty()) " / All messages" else " / " + value.title))
        if (value.saved != null) {
            sourceFilter.clear()
            query.setText(value.saved.query)
            selectedStream = value.saved.streamId
            rebuilding = true
            choosePeriod(value.saved.rangeSeconds)
            rebuilding = false
        }
        clearEvents()
        if (changedConnection || !streamCatalog.contains(connection.id)) loadStreams(connection)
        search()
    }

    private fun editConnection(existing: Connection?) {
        val dialog = ConnectionDialog(project, existing)
        if (!dialog.showAndGet()) return
        val connection = dialog.connection()
        val entered = dialog.token()
        setStatus("Saving connection…", false)
        tasks.submit<ServerConnection>(
            Callable {
                try {
                    return@Callable ConnectionManager.get()
                        .save(ServerConnection.from(connection), entered)
                } finally {
                    Arrays.fill(entered, '\u0000')
                }
            },
            Consumer { saved ->
                settings.remember(saved.id, query.getText(), seconds())
                rebuildTree()
                selectNode(saved.id + ":")
            },
            Consumer { error ->
                setStatus("Could not save connection: " + errorMessage(error), true)
            })
    }

    private fun removeSelected() {
        val node = connections.selectedNode()
        if (node == null) return
        if (node.saved != null) {
            settings.removeSearch(node.saved.id)
            selectedNodeKey = node.connection.id.toString() + ":"
            rebuildTree()
            return
        }
        if (Messages.showYesNoDialog(
                project,
                "Remove the IDE-wide connection “" + node.connection.name + "” from all projects? Project mappings and saved searches will be retained.",
                "Remove Graylog Connection",
                Messages.getQuestionIcon()
            ) != Messages.YES
        ) return
        val id: String = node.connection.id
        setStatus("Removing connection…", false)
        tasks.submit<String>(
            Callable {
                ConnectionManager.get().remove(id)
                id
            },
            Consumer { removed ->
                streamCatalog.remove(removed)
                if (active != null && active!!.id.equals(removed)) {
                    invalidateSearch()
                    live.setSelected(false)
                    active = null
                    selectedNodeKey = ""
                    selectedStream = ""
                    clearEvents()
                    updateContext("No connection")
                }
                rebuildTree()
                setStatus("Connection removed. Select or add a connection.", false)
            },
            Consumer { error ->
                setStatus("Could not remove connection: " + errorMessage(error), true)
            })
    }

    private fun loadStreams(connection: Connection?) {
        streamCatalog.load(OperationConfig.capture(connection, ideOptions))
    }

    private fun publishStreams() {
        rebuildTree()
        if (active == null) return
        val entry = streamCatalog.entry(active!!.id)
        if (entry == null) return
        connections.showStreams(
            if (entry.loading) "Loading streams…" else if (!entry.error.isBlank())
                "Streams unavailable · Refresh to retry"
            else
                counted(entry.streams.size, "stream"),
            if (entry.error.isBlank()) null else entry.error
        )
    }

    private fun client(config: OperationConfig): GraylogApi {
        if (Thread.currentThread().isInterrupted()) throw CancellationException()
        val token: String? = GraylogCredentials.load(config.server.credentialKey())
        if (Thread.currentThread().isInterrupted()) throw CancellationException()
        return GraylogClient(config, token)
    }

    private fun search() {
        if (rebuilding || disposed) return
        if (active == null) {
            live.setSelected(false)
            setStatus("Add or select a connection first.", false)
            return
        }
        val connection = active ?: return
        val now: Instant = Instant.now()
        submit(
            SearchScope(
                query.getText(),
                selectedStream,
                now.minusSeconds(seconds().toLong()),
                now,
                connection.serviceField,
                sourceFilter.values()
            )
        )
    }

    private fun operation(): OperationConfig = OperationConfig.capture(active, ideOptions)

    private fun submit(scope: SearchScope) {
        executeSearch(SearchController.Session(operation(), scope, 0, pageSize.getSelectedItem() as Int)
        )
    }

    private fun searchBusy(): Boolean = search.state().phase == SearchController.Phase.LOADING

    private fun loadedRequest(): SearchRequest? {
        val page = search.state().page
        return if (page == null) null else page.session.request()
    }

    private fun loadedEvents(): List<LogEvent> {
        val page = search.state().page
        return if (page == null) listOf() else page.result.events
    }

    private fun effectiveQuery(): String? = sourceFilter.query(query.getText(), active?.serviceField ?: "source")

    private fun sourceScope(): SearchRequest {
        val submitted = search.state().requested
        if (submitted != null) return submitted.scope.facet(pageSize.getSelectedItem() as Int)
        val now: Instant = Instant.now()
        return SearchRequest(
            query.getText(),
            selectedStream,
            now.minusSeconds(seconds().toLong()),
            now,
            0,
            pageSize.getSelectedItem() as Int
        )
    }

    private fun createSourcePopup(anchor: JComponent, onDispose: Runnable?): JBPopup? {
        sourcePopup?.takeIf { it.alive() }?.popup()?.cancel()
        val scope = sourceScope()
        val connection = active?.copy() ?: return null
        val pane = SourceFilterPopup(
            project,
            connection.serviceField,
            sourceFilter.values(),
            Supplier { ideOptions },
            Consumer { values -> applySources(sourceScope(), values) },
            Runnable { active?.let { loadSources(it.copy(), sourceScope(), true) } },
            Runnable {
                sourcePopup = null
                if (onDispose != null) onDispose.run()
            })
        sourcePopup = pane
        loadSources(connection, scope, false)
        return pane.popup()
    }

    private fun toggleTreeSource(entry: SourceTree.Entry) {
        if (active == null || searchBusy() || rebuilding) return
        val values: MutableSet<String> = LinkedHashSet(sourceFilter.values())
        if (entry.name == null) values.clear() else if (!values.remove(entry.name)) values.add(entry.name)
        applySources(sourceScope(), values)
    }

    private fun applySources(
        scope: SearchRequest,
        values: Set<String>
    ) {
        if (active == null || !scope.sameScope(sourceScope())) return
        sourceFilter.replace(values)
        rebuildTree()
        submit(SearchScope(scope.query, selectedStream, scope.from, scope.to, active!!.serviceField, sourceFilter.values())
        )
    }

    private fun publishSources() {
        rebuildTree()
        if (sourcePopup == null || !sourcePopup!!.alive()) return
        val state = catalog.state()
        if (state.loading) sourcePopup!!.loading()
        else if (!state.error.isBlank()) sourcePopup!!.fallback(state.values!!.values, state.error)
        else if (state.values != null) sourcePopup!!.loaded(state.values)
    }

    private fun loadSources(
        connection: Connection,
        scope: SearchRequest,
        force: Boolean
    ) {
        catalog.load(OperationConfig.capture(connection, ideOptions), scope, loadedEvents(), force)
    }

    private fun executeSearch(session: SearchController.Session) {
        settings.remember(session.config.server.id, session.scope.query, seconds())
        settings.rememberPageSize(session.limit)
        search.load(session)
    }

    private fun renderSearch(state: SearchController.State) {
        if (disposed) return
        coordinator.searchChanged(
            state,
            if (active == null) null else operation(),
            codeMarks.isSelected(),
            ideOptions.autoIndex
        )
        val page = state.page
        val pageChanged = page !== renderedPage
        if (pageChanged) {
            renderedPage = page
            if (page != null) completion.observe(page.result.events, page.session.config.server.serviceField)
        }
        if (state.display !== renderedDisplay) {
            renderedDisplay = state.display
            displayEvents(state.rows())
        }
        allEvents.setEnabled(state.display is SearchController.Display.Examples)
        updatePaging()
        when (state.phase) {
            SearchController.Phase.IDLE -> {}
            SearchController.Phase.LOADING -> {
                val submitted = state.requested
                if (!index.matches(submitted!!.config, submitted.request())) {
                    indexStatus.setText(if (codeMarks.isSelected()) "Exceptions: waiting for search results…" else "Exception marks disabled.")
                }
                allEvents.setEnabled(false)
                setStatus("Searching " + active!!.name + "…", false)
            }

            SearchController.Phase.FAILED -> {
                live.setSelected(false)
                setStatus("Search failed: " + state.error + " Previous results, if any, are unchanged.", true)
            }

            SearchController.Phase.READY -> {
                if (state.display is SearchController.Display.Examples) {
                    val site = (state.display as SearchController.Display.Examples).site
                    setStatus(
                        ("Graylog · " + site.location.file.getFileName() + ":" + site.location.line
                                + " · " + site.count + " event(s) in period · showing " + site.events.size + " example(s) · " + site.types.joinToString(", ")), false
                    )
                } else if (page != null) {
                    val request = page.session.request()
                    val first = (request.offset + 1).toLong()
                    val last = (request.offset + page.result.events.size).toLong()
                    val count =
                        if (page.result.events.isEmpty()) "No events" else (if (first == last) first.toString() else first.toString() + "–" + last) + " of " + counted(
                            page.result.total,
                            "event"
                        )
                    setStatus(
                        active!!.name.toString() + " · " + count + (if (live.isSelected()) " · Live every " + ideOptions.liveIntervalSeconds + "s" else ""),
                        false
                    )
                    status.setToolTipText("UTC: " + request.from + " → " + request.to + " | Query: " + request.query)
                    events.getEmptyText().setText("No events matched. Try a wider period or another query.")
                    if (codeMarks.isSelected() && !ideOptions.autoIndex && index.state().phase == PeriodIndexController.Phase.IDLE) {
                        indexStatus.setText("Exception index is manual · click Refresh marks.")
                        indexControl.setEnabled(true)
                    }
                }
            }
        }
    }

    private fun startIndex(
        config: OperationConfig,
        request: SearchRequest,
        force: Boolean
    ) {
        if (codeMarks.isSelected() && !disposed) index.start(config, request, projectRoot(), force)
    }

    private fun indexBusy(): Boolean = index.state().phase == PeriodIndexController.Phase.LOADING

    private fun projectRoot(): Path? = if (project.getBasePath() == null) null else Path.of(project.getBasePath())

    private fun renderIndex(state: PeriodIndexController.State) {
        when (state.phase) {
            PeriodIndexController.Phase.IDLE -> {
                highlights.clear()
                indexControl.setEnabled(false)
                indexControl.setText("Refresh marks")
                indexStatus.setForeground(getForeground())
            }

            PeriodIndexController.Phase.LOADING -> {
                val context =
                    state.config!!.server.name.toString() + " · " + state.request!!.from + " → " + state.request.to + " UTC · " + state.request.query
                if (state.progress == 0L) {
                    highlights.replace(listOf(), context, state.config.server.connection(), state.root)
                    highlights.select(selected)
                }
                indexStatus.setForeground(getForeground())
                indexStatus.setToolTipText(context)
                indexStatus.setText(if (state.progress == 0L) "Indexing exceptions for the whole period…" else "Indexing exceptions · " + state.progress + " messages processed…")
                indexControl.setText("Cancel scan")
                indexControl.setEnabled(true)
            }

            PeriodIndexController.Phase.READY -> {
                val result = state.result
                val context =
                    state.config!!.server.name.toString() + " · " + state.request!!.from + " → " + state.request.to + " UTC · " + state.request.query
                highlights.replace(result!!.sites, context, state.config.server.connection(), state.root)
                highlights.select(selected)
                indexStatus.setText("Whole period · " + counted(result.exceptions, "exception") + " · " + counted(
                        result.sites.size,
                        "location"
                    ) + " · " + counted(result.messages, "message")
                )
                indexStatus.setForeground(getForeground())
                indexControl.setText("Refresh marks")
            }

            PeriodIndexController.Phase.FAILED -> {
                live.setSelected(false)
                indexStatus.setText("Period index incomplete: " + state.error)
                indexStatus.setForeground(ERROR_COLOR)
                indexStatus.setToolTipText(indexStatus.getText())
                indexControl.setText("Retry marks")
            }

            PeriodIndexController.Phase.CANCELED -> {
                live.setSelected(false)
                indexStatus.setText("Exception scan canceled · period index incomplete.")
                indexControl.setText("Retry marks")
            }
        }
    }

    private fun resetIndex() {
        index.clear()
    }

    private fun page(direction: Int) {
        if (active != null) {
            live.setSelected(false)
            search.page(direction, operation())
        }
    }

    private fun showEvent(event: LogEvent?) {
        selected = event
        highlights.select(event)
        inspector.show(event, active, loadedRequest())
    }

    private fun showSourceMenu(mouse: MouseEvent) {
        val row: Int = events.rowAtPoint(mouse.getPoint())
        if (row < 0) return
        events.setRowSelectionInterval(row, row)
        val source: String = events.rows().get(events.convertRowIndexToModel(row)).service(events.serviceField())
        if (source.isBlank()) return
        val menu: javax.swing.JPopupMenu = JPopupMenu()
        val only: javax.swing.JMenuItem = JMenuItem("Only this source")
        only.addActionListener({ e -> applySources(sourceScope(), setOf(source)) })
        menu.add(only)
        val include: javax.swing.JMenuItem = JMenuItem("Add to source filter")
        include.setEnabled(!sourceFilter.values().isEmpty() && !sourceFilter.values().contains(source))
        include.addActionListener({ e ->
            val values: MutableSet<String> = LinkedHashSet(sourceFilter.values())
            values.add(source)
            applySources(sourceScope(), values)
        })
        menu.add(include)
        val clear: javax.swing.JMenuItem = JMenuItem("All sources")
        clear.setEnabled(!sourceFilter.values().isEmpty())
        clear.addActionListener({ e -> applySources(sourceScope(), mutableSetOf()) })
        menu.add(clear)
        menu.addSeparator()
        val choose: javax.swing.JMenuItem = JMenuItem(
            "Source color: " + source + "…",
            ExplorerUi.colorIcon(SourceColors.color(source, ideOptions.sourceColors)
            )
        )
        choose.addActionListener({ e ->
            val color: Color? = ColorChooserService.getInstance().showDialog(
                project,
                this,
                "Source Color — " + source,
                SourceColors.color(source, ideOptions.sourceColors),
                false
            )
            if (color != null) saveSourceColor(source, SourceColors.hex(color))
        })
        menu.add(choose)
        val reset: javax.swing.JMenuItem = JMenuItem("Use automatic source color")
        reset.setEnabled(ideOptions.sourceColors.containsKey(source))
        reset.addActionListener({ e -> saveSourceColor(source, null) })
        menu.add(reset)
        menu.show(events, mouse.getX(), mouse.getY())
    }

    private fun saveSourceColor(source: String, color: String?) {
        val state = GraylogIdeSettings.get().getState()
        if (color == null) state.options.sourceColors.remove(source) else state.options.sourceColors.put(source, color)
        GraylogIdeSettings.get().replace(state)
    }

    private fun openSource() {
        val location = inspector.selectedLocation()
        if (location == null || active == null) return
        val root: Path? = if (project.getBasePath() == null) null else Path.of(project.getBasePath())
        val mapped: Path? = location.resolve(active, root)
        val file: VirtualFile? =
            if (mapped == null) null else LocalFileSystem.getInstance().refreshAndFindFileByNioFile(mapped)
        if (file == null || file.isDirectory()) {
            setStatus(
                "Source file not found. Settings → Tools → Graylog Explorer → Project settings → Source path mappings. " + location.path,
                true
            )
            return
        }
        active?.let { highlights.focus(it, root, selected, location) }
        OpenFileDescriptor(project, file, location.line - 1, 0).navigate(true)
    }

    private fun displayEvents(rows: List<LogEvent>) {
        val identity = selected?.identity() ?: restoreSelection
        restoreSelection = ""
        events.show(rows, active?.serviceField ?: "source", identity, !live.isSelected())
        if (events.getSelectedRow() < 0) showEvent(null)
    }

    private fun updatePaging() {
        previous.setEnabled(search.state().canPrevious())
        next.setEnabled(search.state().canNext())
    }

    private fun showSite(site: ExceptionSites.Site) {
        if (disposed) return
        live.setSelected(false)
        val page = search.state().page
        toolWindow.activate(Runnable { if (!disposed && search.state().page === page) search.showExamples(site) })
    }

    private fun showAround() {
        val event = selected ?: return
        val connection = active ?: return
        val timestamp = event.timestamp() ?: return
        live.setSelected(false)
        query.setText("*")
        submit(
            SearchScope(
                "*",
                selectedStream,
                timestamp.minusSeconds(300),
                timestamp.plusSeconds(300),
                connection.serviceField,
                sourceFilter.values()
            )
        )
    }

    private fun followRequest() {
        val event = selected ?: return
        val connection = active ?: return
        val key = if (!event.text("request_id").isBlank()) "request_id" else if (!event.text("trace_id")
                .isBlank()
        ) "trace_id" else "correlation_id"
        val value: String = event.text(key)
        if (value.isBlank()) return
        sourceFilter.clear()
        query.setText(SearchRequest.exact(event.fieldName(key), value))
        selectedStream = ""
        selectedNodeKey = connection.id.toString() + ":"
        rebuildTree()
        updateContext(connection.name.toString() + " / All messages")
        search()
    }

    private fun saveSearch() {
        if (active == null) {
            setStatus("Select a connection before saving a search.", false)
            return
        }
        val connection = active ?: return
        val name: String? = Messages.showInputDialog(
            project,
            "Search name",
            "Save Graylog Search",
            Messages.getQuestionIcon()
        )
        if (name == null || name.isBlank()) return
        val saved = GraylogSettings.SavedSearch()
        saved.name = name.trim()
        saved.connectionId = connection.id
        saved.streamId = selectedStream
        saved.query = effectiveQuery()
        saved.rangeSeconds = seconds()
        settings.saveSearch(saved)
        rebuildTree()
        setStatus("Search saved: " + saved.name, false)
    }

    private fun clearEvents() {
        sourcePopup?.takeIf { it.alive() }?.popup()?.cancel()
        search.clear()
        catalog.clear()
        restoreSelection = ""
        resetIndex()
        indexStatus.setText("Exceptions: select a connection and search.")
        allEvents.setEnabled(false)
        showEvent(null)
    }

    private fun invalidateSearch() {
        search.cancel()
    }

    private fun liveLabel(): String = "Live · " + ideOptions.liveIntervalSeconds + "s"

    private fun initialConnection(): String {
        val available: List<Connection> = settings.connections()
        if (available.isEmpty()) return ""
        val state = settings.getState()
        for (id in listOf(state.selectedConnection, state.preferredConnection, ideOptions.defaultConnection)) {
            if (available.stream().anyMatch({ c -> c.id.equals(id) })) return id
        }
        return available.first().id
    }

    private fun settingsChanged(event: SettingsEvents.Event) {
        if (disposed || !event.appliesTo(project)) return
        val change = event.changes
        val previous = ideOptions
        ideOptions = GraylogIdeSettings.get().getState().options
        if (change.has(SettingsChange.Area.LIVE)) {
            timer.setDelay(ideOptions.liveIntervalSeconds * 1000)
            timer.setInitialDelay(timer.getDelay())
            live.setText(liveLabel())
        }
        if (change.has(SettingsChange.Area.SOURCE_STYLE)) {
            events.repaint()
            connections.repaint()
            sourcePopup?.takeIf { it.alive() }?.popup()?.content?.repaint()
        }
        if (change.has(SettingsChange.Area.HIGHLIGHT_STYLE) || change.has(SettingsChange.Area.INDEX_OPTIONS)) highlights.configure(
            ideOptions
        )
        val beforeConnection = active
        val nextActive = settings.connections().firstOrNull { it.id == beforeConnection?.id }
        if (beforeConnection == null || nextActive == null || change.has(SettingsChange.Area.PREFERENCE) && !beforeConnection.id.equals(
                initialConnection()
            )
        ) {
            if (change.affectsRequests()) reloadSettings()
            return
        }
        val impact = SettingsImpact.between(
            OperationConfig.capture(beforeConnection, previous), OperationConfig.capture(nextActive, ideOptions)
        )
        active = nextActive.copy()
        if (change.has(SettingsChange.Area.LOG_FORMAT)) completion.reset(nextActive.fields)
        if (change.has(SettingsChange.Area.CONNECTIONS) || change.has(SettingsChange.Area.LOG_FORMAT) || change.has(
                SettingsChange.Area.MAPPINGS
            )
        ) {
            query.getEmptyText().setText("Graylog query, e.g. " + nextActive.fields.requestIdField + ":abc-123")
            updateContext(nextActive.name.toString() + (if (selectedStream.isEmpty()) " / All messages" else " / " + selectedStream))
            inspector.show(selected, active, loadedRequest())
        }
        if (change.has(SettingsChange.Area.HIGHLIGHT_MODE)) {
            codeMarks.setSelected(settings.highlightsEnabled())
            highlights.setEnabled(codeMarks.isSelected())
            if (!codeMarks.isSelected()) {
                resetIndex()
                indexStatus.setText("Exception marks disabled.")
            }
        }
        if (impact.streams) {
            streamCatalog.remove(nextActive.id)
            loadStreams(nextActive.copy())
        }
        if (change.has(SettingsChange.Area.CONNECTIONS)) rebuildTree()
        val context = nextActive.id.toString() + "\n" + nextActive.apiUrl + "\n" + nextActive.serviceField
        if (impact.page && !sourceFilterContext.equals(context)) {
            sourceFilter.clear()
            sourceFilterContext = context
        }
        coordinator.settingsChanged(
            OperationConfig.capture(beforeConnection, previous),
            operation(),
            codeMarks.isSelected(),
            ideOptions.autoIndex,
            sourceFilter.values(),
            change.has(SettingsChange.Area.HIGHLIGHT_MODE),
            change.has(SettingsChange.Area.INDEX_POLICY)
        )
        if (codeMarks.isSelected() && !ideOptions.autoIndex && loadedRequest() != null && !searchBusy()) {
            indexStatus.setText("Exception index is manual · click Refresh marks.")
            indexControl.setEnabled(true)
        }
        if (change.has(SettingsChange.Area.CONNECTIONS)) renderSearch(search.state())
    }

    private fun reloadSettings() {
        if (disposed || project.isDisposed()) return
        connections.clearFilter()
        val before = active
        val previousStream: String = selectedStream
        val previousSelection = selected?.identity().orEmpty()
        ideOptions = GraylogIdeSettings.get().getState().options
        timer.setDelay(ideOptions.liveIntervalSeconds * 1000)
        timer.setInitialDelay(ideOptions.liveIntervalSeconds * 1000)
        live.setText(liveLabel())
        highlights.configure(ideOptions)
        codeMarks.setSelected(settings.highlightsEnabled())
        highlights.setEnabled(codeMarks.isSelected())
        invalidateSearch()
        clearEvents()
        if (before == null || settings.connections().stream()
                .noneMatch({ c -> c.id.equals(before.id) && c.apiUrl.equals(before.apiUrl) })
        ) streamCatalog.clear()
        streamCatalog.invalidatePending()
        active = null
        selectedStream = ""
        selectedNodeKey = ""
        rebuildTree()
        val initial: String = initialConnection()
        if (!initial.isBlank()) {
            if (before != null && before.id.equals(initial) && !previousStream.isBlank()) selectNode(initial.toString() + ":" + previousStream)
            if (active == null) selectNode(initial.toString() + ":")
            restoreSelection = if (before != null && before.id.equals(initial)) previousSelection else ""
        } else {
            updateContext("No connection")
            setStatus("Add an IDE-wide connection in Graylog Explorer settings.", false)
        }
    }

    private fun seconds(): Int = (period.getSelectedItem() as Period).seconds

    private fun updateContext(value: String) {
        query.setToolTipText("<html>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(value) + "<br>field:value · level:3 AND source:api · level:[0 TO 3]<br>Type a field name or press Ctrl+Space for suggestions. Tab/Enter inserts; Esc closes.<br>Enter or Ctrl+Enter runs the search.</html>")
    }

    private fun choosePeriod(seconds: Int) {
        for (item in PERIODS) if (item.seconds == seconds) {
            period.setSelectedItem(item)
            return
        }
        period.setSelectedItem(PERIODS[1])
    }

    private fun setStatus(text: String, error: Boolean) {
        status.setText(text)
        status.setToolTipText(text)
        status.setForeground(if (error) ERROR_COLOR else getForeground())
    }

    private fun later(runnable: Runnable) {
        if (!disposed) tasks.ui(runnable)
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        completion.dispose()
        sourcePopup?.takeIf { it.alive() }?.popup()?.cancel()
        catalog.close()
        index.close()
        search.close()
        streamCatalog.close()
    }

    companion object {
        private val PERIODS = arrayOf<Period>(
            Period("Last 5 minutes", 300),
            Period("Last 15 minutes", 900),
            Period("Last hour", 3600),
            Period("Last 24 hours", 86400)
        )
        private val ERROR_COLOR: Color = ExplorerUi.ERROR_COLOR

        private fun errorMessage(e: Throwable): String = e.message ?: e.javaClass.simpleName

        private fun counted(value: Number, noun: String): String = value.toString() + " " + noun + (if (value.toLong() == 1L) "" else "s")
    }
}
