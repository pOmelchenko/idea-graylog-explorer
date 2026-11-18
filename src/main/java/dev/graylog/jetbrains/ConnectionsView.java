package dev.graylog.jetbrains;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.SearchFieldWithExtension;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.SideBorder;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.openapi.actionSystem.*;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.ui.DocumentAdapter;
import javax.swing.event.DocumentEvent;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;

/** Navigation tree owns filtering, rendering, expansion and selection notifications. */
final class ConnectionsView extends JPanel {
    record Node(Connection connection, String title, String streamId, GraylogSettings.SavedSearch saved) {
        String key() { return connection.id + ":" + (saved != null ? "saved:" + saved.id : streamId); }
        @Override public String toString() { return title; }
    }
    private final Tree tree = new Tree(new DefaultMutableTreeNode("Connections"));
    private final SearchTextField connectionSearch = new SearchTextField(false);
    private final com.intellij.ui.components.JBTextField connectionFilter = connectionSearch.getTextEditor();
    private final JLabel streamStatus = new JLabel(" ");
    private final java.util.Set<String> collapsedSourceGroups = new java.util.HashSet<>();
    private final java.util.function.Supplier<IdeOptions> options;
    private final java.util.function.BooleanSupplier enabled;
    private final Runnable refresh;
    private final Consumer<Node> onSelect;
    private final Consumer<SourceTree.Entry> onSource;
    private boolean rebuilding;
    private String selectedKey = "";
    private Content content;
    record Content(List<Connection> connections, List<GraylogSettings.SavedSearch> searches,
                   Map<String, List<GraylogApi.Stream>> streams, Connection active, String streamId,
                   String selectedKey, SourceCatalog.State sources, java.util.Set<String> selectedSources) { }
    ConnectionsView(java.util.function.Supplier<IdeOptions> options, java.util.function.BooleanSupplier enabled,
                    Runnable refresh, Consumer<Node> onSelect, Consumer<SourceTree.Entry> onSource) {
        super(new BorderLayout());
        this.options = options; this.enabled = enabled; this.refresh = refresh; this.onSelect = onSelect; this.onSource = onSource;
        createTreePanel();
    }
    Node selectedNode() {
        Object selected = tree.getLastSelectedPathComponent();
        return selected instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Node value ? value : null;
    }
    void clearFilter() { if (!connectionFilter.getText().isEmpty()) connectionFilter.setText(""); }
    void showStreams(String text, String error) { streamStatus.setText(text); streamStatus.setToolTipText(error); }
    void changeExpansion(boolean expand) {
        boolean before = rebuilding; rebuilding = true;
        try {
            DefaultTreeExpander expander = new DefaultTreeExpander(tree);
            if (expand) { expander.expandAll(); if (!selectedKey.isBlank()) selectNode(selectedKey); }
            else expander.collapseAll();
        } finally { rebuilding = before; }
    }

    private JComponent createTreePanel() {
        JPanel panel = this;
        panel.setBorder(IdeBorderFactory.createBorder(SideBorder.LEFT));
        connectionFilter.getEmptyText().setText("Connection, stream or source");
        panel.add(new SearchFieldWithExtension(new com.intellij.ui.components.JBLabel(), connectionSearch), BorderLayout.NORTH);
        connectionFilter.setToolTipText("Filter connection, stream, source and saved-search names. This does not change the log query.");
        connectionFilter.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(DocumentEvent event) { refresh.run(); }
        });
        tree.setName("connections.tree"); connectionFilter.setName("connections.filter");
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
        tree.setCellRenderer(SourceTree.renderer(tree.getCellRenderer(), () -> content == null ? java.util.Set.of() : content.selectedSources(), options, enabled::getAsBoolean));
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 1) return;
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof SourceTree.Entry entry) onSource.accept(entry);
            }
        });
        tree.registerKeyboardAction(e -> {
            var node = tree.getLastSelectedPathComponent();
            if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof SourceTree.Entry entry) onSource.accept(entry);
        }, KeyStroke.getKeyStroke("SPACE"), JComponent.WHEN_FOCUSED);
        tree.registerKeyboardAction(e -> {
            var node = tree.getLastSelectedPathComponent();
            if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof SourceTree.Entry entry) onSource.accept(entry);
        }, KeyStroke.getKeyStroke("ENTER"), JComponent.WHEN_FOCUSED);
        tree.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            public void treeExpanded(javax.swing.event.TreeExpansionEvent e) { remember(e, false); }
            public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) { remember(e, true); }
            private void remember(javax.swing.event.TreeExpansionEvent e, boolean collapsed) {
                if (rebuilding || content == null || content.active() == null) return;
                if (((DefaultMutableTreeNode) e.getPath().getLastPathComponent()).getUserObject() instanceof SourceTree.Group) {
                    if (collapsed) collapsedSourceGroups.add(content.active().id); else collapsedSourceGroups.remove(content.active().id);
                }
            }
        });
        tree.addTreeSelectionListener(e -> {
            if (rebuilding) return;
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null || !(node.getUserObject() instanceof Node value)) return;
            selectedKey = value.key(); onSelect.accept(value);
        });
        tree.setBorder(JBUI.Borders.empty());
        panel.add(ExplorerUi.scroll(tree), BorderLayout.CENTER);
        streamStatus.setBorder(JBUI.Borders.empty(4, 8));
        panel.add(streamStatus, BorderLayout.SOUTH);
        return panel;
    }

    void update(Content value) {
        content = value; selectedKey = value.selectedKey(); rebuilding = true;
        try {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("Connections");
            String filter = connectionFilter.getText().strip().toLowerCase(Locale.ROOT);
            for (Connection c : content.connections()) {
                boolean connectionMatches = c.name.toLowerCase(Locale.ROOT).contains(filter);
                DefaultMutableTreeNode connection = new DefaultMutableTreeNode(new Node(c, c.name, null, null));
                DefaultMutableTreeNode group = new DefaultMutableTreeNode("Streams");
                if (connectionMatches || "all messages".contains(filter)) group.add(new DefaultMutableTreeNode(new Node(c, "All messages", "", null)));
                for (GraylogApi.Stream s : content.streams().getOrDefault(c.id, List.of())) {
                    if (connectionMatches || s.title().toLowerCase(Locale.ROOT).contains(filter)) group.add(new DefaultMutableTreeNode(new Node(c, s.title(), s.id(), null)));
                }
                if (connectionMatches || group.getChildCount() > 0) connection.add(group);
                DefaultMutableTreeNode saved = new DefaultMutableTreeNode("Saved searches");
                for (GraylogSettings.SavedSearch s : content.searches()) if (s.connectionId.equals(c.id)) {
                    if (connectionMatches || s.name.toLowerCase(Locale.ROOT).contains(filter)) saved.add(new DefaultMutableTreeNode(new Node(c, s.name, s.streamId, s)));
                }
                if (connectionMatches || saved.getChildCount() > 0) connection.add(saved);
                if (content.active() != null && content.active().id.equals(c.id)) {
                    String scopeLabel = content.streamId().isBlank() ? "All messages" : content.streams().getOrDefault(c.id, List.of()).stream()
                            .filter(stream -> stream.id().equals(content.streamId())).map(GraylogApi.Stream::title).findFirst().orElse(content.streamId());
                    var sources = SourceTree.group(scopeLabel, content.sources().getSummary(),
                            content.sources().getValues() == null ? List.of() : content.sources().getValues().values(), content.selectedSources(), filter, connectionMatches || "sources".contains(filter));
                    if (connectionMatches || sources.getChildCount() > 0) connection.add(sources);
                }
                if (connectionMatches || connection.getChildCount() > 0) root.add(connection);
            }
            tree.setModel(new DefaultTreeModel(root));
            for (int i = 0; i < tree.getRowCount(); i++) {
                var node = (DefaultMutableTreeNode) tree.getPathForRow(i).getLastPathComponent();
                if (!(node.getUserObject() instanceof SourceTree.Group) || content.active() == null || !collapsedSourceGroups.contains(content.active().id)) tree.expandRow(i);
            }
            if (!selectedKey.isBlank()) selectNode(selectedKey);
        } finally { rebuilding = false; }
    }

    void selectNode(String key) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) nodes.nextElement();
            if (n.getUserObject() instanceof Node value && value.key().equals(key)) {
                tree.setSelectionPath(new TreePath(n.getPath())); return;
            }
        }
    }

}
