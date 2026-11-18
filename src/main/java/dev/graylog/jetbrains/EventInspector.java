package dev.graylog.jetbrains;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.openapi.actionSystem.*;
import com.intellij.ui.components.JBList;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.ui.tabs.JBTabsFactory;
import com.intellij.ui.tabs.TabInfo;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.CardLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Event details, stack navigation and browser links; no networking or search orchestration. */
final class EventInspector extends JPanel implements Disposable {
    private final Project project;
    private final Runnable onOpen, onAround, onFollow;
    private Connection active;
    private SearchRequest loadedRequest;
    private LogEvent selected;
    private final JTextArea message = textArea(true);
    private final JTextArea rawJson = textArea(false);
    private final JTextArea rawStack = textArea(false);
    private final FieldTableModel fieldsModel = new FieldTableModel();
    private final DefaultListModel<SourceLocation> locationsModel = new DefaultListModel<>();
    private final JBList<SourceLocation> locations = new JBList<>(locationsModel);
    private final JLabel sourceMapping = new JLabel(" ");
    private final JButton follow = new JButton("Follow request");
    private final JButton around = new JButton("Show logs around");
    private final JButton open = new JButton("Open in Editor");
    private final JButton browser = new JButton("Open Graylog");
    private JBTabs inspectorTabs;
    private final CardLayout inspectorLayout = new CardLayout();
    private final JPanel inspectorBody = new JPanel(inspectorLayout);
    private final JLabel eventLevel = new JLabel();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Color ERROR_COLOR = ExplorerUi.ERROR_COLOR;
    private static final Color WARN_COLOR = ExplorerUi.WARN_COLOR;
    EventInspector(Project project, Disposable parent, Runnable onOpen, Runnable onAround, Runnable onFollow) {
        super(new BorderLayout());
        this.project = project; this.onOpen = onOpen; this.onAround = onAround; this.onFollow = onFollow;
        com.intellij.openapi.util.Disposer.register(parent, this);
        rawStack.setName("inspector.rawStack");
        sourceMapping.setName("inspector.sourceMapping");
        open.setName("inspector.open");
        browser.setName("inspector.browser");
        inspectorBody.setName("inspector.inspectorBody");
        locations.setName("inspector.locations");
        add(createInspector(), BorderLayout.CENTER);
    }
    void show(LogEvent event, Connection connection, SearchRequest request) {
        active = connection; loadedRequest = request; showEvent(event);
    }
    JBTabs tabs() { return inspectorTabs; }
    SourceLocation selectedLocation() { return locations.getSelectedValue(); }
    private void openSource() { onOpen.run(); }
    @Override public void dispose() { selected = null; active = null; loadedRequest = null; }

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
        open.addActionListener(e -> openSource()); around.addActionListener(e -> onAround.run());
        follow.addActionListener(e -> onFollow.run());
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

    private void showEvent(LogEvent event) {
        selected = event;
        inspectorLayout.show(inspectorBody, event == null ? "empty" : "details");
        eventLevel.setText(event == null ? "" : event.level());
        eventLevel.setForeground(event != null && event.level().equals("ERROR") ? ERROR_COLOR
                : event != null && event.level().equals("WARN") ? WARN_COLOR : getForeground());
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
    private static JTextArea textArea(boolean wrap) {
        JTextArea area = new JTextArea(); area.setEditable(false); area.setLineWrap(wrap); area.setWrapStyleWord(wrap);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().getSize())); area.setBorder(JBUI.Borders.empty(6, 8)); return area;
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
