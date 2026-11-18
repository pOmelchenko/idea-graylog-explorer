package dev.graylog.jetbrains

import com.intellij.icons.AllIcons
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.actionSystem.*
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SideBorder
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.SearchFieldWithExtension
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import java.util.concurrent.*
import java.util.function.*
import java.util.function.Consumer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Navigation tree owns filtering, rendering, expansion and selection notifications.  */
class ConnectionsView(
    options: java.util.function.Supplier<IdeOptions>,
    enabled: java.util.function.BooleanSupplier,
    refresh: Runnable,
    onSelect: Consumer<Node>,
    onSource: Consumer<SourceTree.Entry>
) : JPanel(BorderLayout()) {
    @JvmRecord
    data class Node(
        val connection: Connection,
        val title: String,
        val streamId: String?,
        val saved: GraylogSettings.SavedSearch?
    ) {
        fun key(): String = connection.id.toString() + ":" + (if (saved != null) "saved:" + saved.id else streamId)

        override fun toString(): String = title
    }

    private val tree: Tree =
        Tree(DefaultMutableTreeNode("Connections"))
    private val connectionSearch = SearchTextField(false)
    private val connectionFilter: JBTextField = connectionSearch.getTextEditor()
    private val streamStatus = JLabel(" ")
    private val collapsedSourceGroups: MutableSet<String> = HashSet()
    private val options: java.util.function.Supplier<IdeOptions>
    private val enabled: java.util.function.BooleanSupplier
    private val refresh: Runnable
    private val onSelect: Consumer<Node>
    private val onSource: Consumer<SourceTree.Entry>
    private var rebuilding = false
    private var selectedKey = ""
    private var content: Content? = null

    @JvmRecord
    data class Content(
        val connections: List<Connection>,
        val searches: List<GraylogSettings.SavedSearch>,
        val streams: Map<String, List<GraylogApi.Stream>>,
        val active: Connection?,
        val streamId: String,
        val selectedKey: String,
        val sources: SourceCatalog.State,
        val selectedSources: Set<String>
    )

    init {
        this.options = options
        this.enabled = enabled
        this.refresh = refresh
        this.onSelect = onSelect
        this.onSource = onSource
        createTreePanel()
    }

    fun selectedNode(): Node? {
        val selected: Any? = tree.getLastSelectedPathComponent()
        return (selected as? DefaultMutableTreeNode)?.userObject as? Node
    }

    fun clearFilter() {
        if (!connectionFilter.getText().isEmpty()) connectionFilter.setText("")
    }

    fun showStreams(text: String, error: String?) {
        streamStatus.setText(text)
        streamStatus.setToolTipText(error)
    }

    fun changeExpansion(expand: Boolean) {
        val before = rebuilding
        rebuilding = true
        try {
            val expander = DefaultTreeExpander(tree)
            if (expand) {
                expander.expandAll()
                if (!selectedKey.isBlank()) selectNode(selectedKey)
            } else expander.collapseAll()
        } finally {
            rebuilding = before
        }
    }

    private fun createTreePanel(): JComponent {
        val panel: JPanel = this
        panel.setBorder(IdeBorderFactory.createBorder(SideBorder.LEFT))
        connectionFilter.getEmptyText().setText("Connection, stream or source")
        panel.add(SearchFieldWithExtension(JBLabel(), connectionSearch), BorderLayout.NORTH)
        connectionFilter.setToolTipText("Filter connection, stream, source and saved-search names. This does not change the log query.")
        connectionFilter.getDocument().addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                refresh.run()
            }
        })
        tree.setName("connections.tree")
        connectionFilter.setName("connections.filter")
        tree.setRootVisible(false)
        tree.setShowsRootHandles(true)
        tree.getEmptyText().setText("No matching connections or streams.")
        tree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION)
        tree.setCellRenderer(object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                t: javax.swing.JTree, value: Any, selected: Boolean,
                expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean
            ) {
                val user: Any = (value as DefaultMutableTreeNode).getUserObject()
                append(user.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (user is Node) setIcon(if (user.saved != null) AllIcons.Actions.Find else if (user.streamId == null) AllIcons.Nodes.DataTables else AllIcons.FileTypes.Text)
                else setIcon(AllIcons.Nodes.Folder)
                setOpaque(false)
                setIconOpaque(false)
            }
        })
        tree.setCellRenderer(
            SourceTree.renderer(
                tree.getCellRenderer(),
                Supplier { content?.selectedSources.orEmpty() },
                options,
                enabled::getAsBoolean
            )
        )
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 1) return
                val path: TreePath? = tree.getPathForLocation(event.getX(), event.getY())
                val entry = (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? SourceTree.Entry
                if (entry != null) onSource.accept(entry)
            }
        })
        tree.registerKeyboardAction({ e ->
            val node =
                tree.getLastSelectedPathComponent()
            val entry = (node as? DefaultMutableTreeNode)?.userObject as? SourceTree.Entry
            if (entry != null) onSource.accept(entry)
        }, KeyStroke.getKeyStroke("SPACE"), JComponent.WHEN_FOCUSED)
        tree.registerKeyboardAction({ e ->
            val node =
                tree.getLastSelectedPathComponent()
            val entry = (node as? DefaultMutableTreeNode)?.userObject as? SourceTree.Entry
            if (entry != null) onSource.accept(entry)
        }, KeyStroke.getKeyStroke("ENTER"), JComponent.WHEN_FOCUSED)
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(e: javax.swing.event.TreeExpansionEvent) {
                remember(e, false)
            }

            override fun treeCollapsed(e: javax.swing.event.TreeExpansionEvent) {
                remember(e, true)
            }

            fun remember(e: javax.swing.event.TreeExpansionEvent, collapsed: Boolean) {
                if (rebuilding) return
                val active = content?.active ?: return
                if ((e.getPath()
                        .getLastPathComponent() as DefaultMutableTreeNode).getUserObject() is SourceTree.Group
                ) {
                    if (collapsed) collapsedSourceGroups.add(active.id) else collapsedSourceGroups.remove(
                        active.id
                    )
                }
            }
        })
        tree.addTreeSelectionListener({ e ->
            if (rebuilding) return@addTreeSelectionListener
            val node: DefaultMutableTreeNode? = tree.getLastSelectedPathComponent() as DefaultMutableTreeNode?
            val value = node?.userObject as? Node ?: return@addTreeSelectionListener
            selectedKey = value.key()
            onSelect.accept(value)
        })
        tree.setBorder(JBUI.Borders.empty())
        panel.add(ExplorerUi.scroll(tree), BorderLayout.CENTER)
        streamStatus.setBorder(JBUI.Borders.empty(4, 8))
        panel.add(streamStatus, BorderLayout.SOUTH)
        return panel
    }

    fun update(value: Content) {
        content = value
        selectedKey = value.selectedKey
        rebuilding = true
        try {
            val root = DefaultMutableTreeNode("Connections")
            val filter: String = connectionFilter.getText().trim().lowercase(Locale.ROOT)
            for (c in value.connections) {
                val connectionMatches: Boolean = c.name.lowercase(Locale.ROOT).contains(filter)
                val connection: DefaultMutableTreeNode =
                    DefaultMutableTreeNode(Node(c, c.name, null, null))
                val group = DefaultMutableTreeNode("Streams")
                if (connectionMatches || "all messages".contains(filter)) group.add(
                    DefaultMutableTreeNode(Node(c, "All messages", "", null)
                    )
                )
                for (s in value.streams.getOrDefault(c.id, listOf())) {
                    if (connectionMatches || s.title.lowercase(Locale.ROOT).contains(filter)) group.add(
                        DefaultMutableTreeNode(Node(c, s.title, s.id, null))
                    )
                }
                if (connectionMatches || group.getChildCount() > 0) connection.add(group)
                val saved = DefaultMutableTreeNode("Saved searches")
                for (s in value.searches) if (s.connectionId.equals(c.id)) {
                    if (connectionMatches || s.name.lowercase(Locale.ROOT).contains(filter)) saved.add(
                        DefaultMutableTreeNode(Node(c, s.name, s.streamId, s))
                    )
                }
                if (connectionMatches || saved.getChildCount() > 0) connection.add(saved)
                if (value.active != null && value.active.id.equals(c.id)) {
                    val scopeLabel: String =
                        if (value.streamId.isBlank()) "All messages" else value.streams.getOrDefault(
                            c.id,
                            listOf()
                        ).stream()
                            .filter({ stream -> stream.id().equals(value.streamId) })
                            .map(GraylogApi.Stream::title).findFirst().orElse(value.streamId)
                    val sources: DefaultMutableTreeNode = SourceTree.group(
                        scopeLabel,
                        value.sources.summary,
                        if (value.sources.values == null) listOf() else value.sources.values.values,
                        value.selectedSources,
                        filter,
                        connectionMatches || "sources".contains(filter)
                    )
                    if (connectionMatches || sources.getChildCount() > 0) connection.add(sources)
                }
                if (connectionMatches || connection.getChildCount() > 0) root.add(connection)
            }
            tree.setModel(DefaultTreeModel(root))
            var row = 0
            // Expanding a row adds visible children; re-read the count on every iteration.
            while (row < tree.rowCount) {
                val node = tree.getPathForRow(row).lastPathComponent as DefaultMutableTreeNode
                if ((node.getUserObject() !is SourceTree.Group) || value.active == null || !collapsedSourceGroups.contains(
                        value.active.id
                    )
                ) tree.expandRow(row)
                row++
            }
            if (!selectedKey.isBlank()) selectNode(selectedKey)
        } finally {
            rebuilding = false
        }
    }

    fun selectNode(key: String?) {
        val root: DefaultMutableTreeNode = tree.getModel().getRoot() as DefaultMutableTreeNode
        val nodes =
            root.depthFirstEnumeration()
        while (nodes.hasMoreElements()) {
            val n: DefaultMutableTreeNode = nodes.nextElement() as DefaultMutableTreeNode
            if ((n.userObject as? Node)?.key() == key) {
                tree.setSelectionPath(TreePath(n.getPath()))
                return
            }
        }
    }
}
