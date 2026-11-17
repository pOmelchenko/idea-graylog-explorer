package dev.graylog.jetbrains;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;

import javax.swing.Icon;
import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Owns only this Explorer's highlighters. Optional language adapters resolve selected-event variable hints without executing code. */
final class ExceptionHighlights implements Disposable {
    private static final Color STRIPE = new JBColor(new Color(190, 65, 45), new Color(240, 117, 102));
    private static final Icon ICON = IconLoader.getIcon("/icons/graylog.svg", ExceptionHighlights.class);

    private final Project project;
    private final VariableHints variableHints;
    private final Consumer<ExceptionSites.Site> onClick;
    private final Map<Editor, List<RangeHighlighter>> owned = new HashMap<>();
    private final Map<Editor, List<Inlay<ExceptionInlineRenderer>>> inlays = new HashMap<>();
    private final Map<ExceptionSites.Location, ExceptionSites.Site> sites = new LinkedHashMap<>();
    private String context = "";
    private Connection connection;
    private Path projectRoot;
    private LogEvent selectedEvent;
    private Set<ExceptionSites.Location> selectedLocations = Set.of();
    private ExceptionSites.Site focus;
    private final java.util.concurrent.atomic.AtomicBoolean refreshPending = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean enabled = true;
    private boolean disposed;
    private IdeOptions options = new IdeOptions();

    ExceptionHighlights(Project project, Disposable parent, Consumer<ExceptionSites.Site> onClick) {
        this.project = project; this.onClick = onClick;
        variableHints = new VariableHints(project);
        Disposer.register(this, variableHints);
        Disposer.register(parent, this);
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new com.intellij.openapi.editor.event.DocumentListener() {
            @Override public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent event) {
                variableHints.clear(); scheduleRefresh();
            }
        }, this);
        EditorFactory.getInstance().addEditorFactoryListener(new EditorFactoryListener() {
            @Override public void editorCreated(EditorFactoryEvent event) { scheduleRefresh(); }
            @Override public void editorReleased(EditorFactoryEvent event) { remove(event.getEditor()); variableHints.clear(); scheduleRefresh(); }
        }, this);
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void fileOpened(FileEditorManager source, VirtualFile file) { refresh(); }
        });
    }

    void replace(List<ExceptionSites.Site> found, String searchContext, Connection connection, Path projectRoot) {
        this.connection = connection.copy(); this.projectRoot = projectRoot;
        sites.clear(); found.forEach(site -> sites.put(site.location(), site));
        selectedLocations = selectedEvent == null ? Set.of() : Set.copyOf(ExceptionSites.locations(selectedEvent, connection, projectRoot));
        context = searchContext; focus = null; refresh();
    }

    void select(LogEvent event) {
        if (selectedEvent == event && focus == null) return;
        selectedEvent = event;
        selectedLocations = event == null || connection == null ? Set.of() : Set.copyOf(ExceptionSites.locations(event, connection, projectRoot));
        focus = null; refresh();
    }

    void focus(Connection connection, Path root, LogEvent event, SourceLocation source) {
        Path file = source.resolve(connection, root);
        if (file == null || event == null) return;
        this.connection = connection.copy(); this.projectRoot = root;
        selectedEvent = event;
        ExceptionSites.Location key = new ExceptionSites.Location(file, source.line());
        selectedLocations = Set.of(key);
        focus = sites.getOrDefault(key, new ExceptionSites.Site(key, List.of("Selected stack frame"), List.of(event)));
        refresh();
    }

    void setEnabled(boolean value) { enabled = value; refresh(); }
    void configure(IdeOptions value) { options = value.copy(); refresh(); }
    void clear() { sites.clear(); selectedEvent = null; selectedLocations = Set.of(); focus = null; refresh(); }

    private void scheduleRefresh() {
        if (!disposed && !project.isDisposed() && refreshPending.compareAndSet(false, true)) ApplicationManager.getApplication().invokeLater(() -> {
            refreshPending.set(false);
            if (!disposed && !project.isDisposed()) refresh();
        });
    }

    private void refresh() {
        if (disposed || project.isDisposed()) return;
        if (!ApplicationManager.getApplication().isDispatchThread()) { scheduleRefresh(); return; }
        variableHints.clear();
        for (Editor editor : new ArrayList<>(owned.keySet())) remove(editor);
        if (!enabled) return;
        List<VariableHints.Target> valueTargets = new ArrayList<>();
        Color passive = new JBColor(Color.decode(options.backgroundLight), Color.decode(options.backgroundDark));
        Color selectedColor = new JBColor(Color.decode(options.selectedLight), Color.decode(options.selectedDark));
        Map<ExceptionSites.Location, ExceptionSites.Site> visible = new LinkedHashMap<>(sites);
        if (focus != null) visible.put(focus.location(), focus);
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.isDisposed() || editor.getProject() != project) continue;
            VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (file == null || !file.isInLocalFileSystem()) continue;
            Path path = Path.of(file.getPath()).toAbsolutePath().normalize();
            for (ExceptionSites.Site site : visible.values()) {
                if (!site.location().file().equals(path)) continue;
                int line = site.location().line() - 1;
                if (line < 0 || line >= editor.getDocument().getLineCount()) continue;
                boolean selected = selectedLocations.contains(site.location());
                TextAttributes attributes = new TextAttributes(); attributes.setBackgroundColor(selected ? selectedColor : passive);
                RangeHighlighter marker = editor.getMarkupModel().addLineHighlighter(line, HighlighterLayer.CARET_ROW + 1, attributes);
                if (options.errorStripe) marker.setErrorStripeMarkColor(STRIPE);
                List<String> messages = ExceptionPresentation.messages(site, connection, projectRoot, selectedEvent);
                String tooltip = tooltip(site, messages);
                marker.setErrorStripeTooltip(tooltip);
                if (options.gutterIcons) marker.setGutterIconRenderer(new SiteRenderer(site, tooltip));
                owned.computeIfAbsent(editor, ignored -> new ArrayList<>()).add(marker);
                String inline = ExceptionPresentation.inline(site, messages);
                if (options.inlineDetails && !inline.isBlank()) {
                    var inlay = editor.getInlayModel().addAfterLineEndElement(editor.getDocument().getLineEndOffset(line), true,
                            new ExceptionInlineRenderer(inline, selected));
                    if (inlay != null) inlays.computeIfAbsent(editor, ignored -> new ArrayList<>()).add(inlay);
                    if (selected && options.inlineValues && selectedEvent != null) {
                        // Only exact exception origins qualify; a caller frame is not proof of the thrown value.
                        var details = ExceptionDetails.chain(selectedEvent).stream().filter(part -> part.origin() != null
                                && part.origin().line() == site.location().line()
                                && site.location().file().equals(part.origin().resolve(connection, projectRoot)))
                                .map(part -> new ExceptionSites.Detail(part.type(), part.message())).toList();
                        if (!details.isEmpty()) valueTargets.add(new VariableHints.Target(editor, line + 1, details, inlay));
                    }
                }
            }
        }
        variableHints.refresh(valueTargets);
    }

    private String tooltip(ExceptionSites.Site site, List<String> messages) {
        String count = sites.containsKey(site.location()) ? site.count() + " event(s) in period" : "Selected stack frame";
        return "<html><b>Graylog · " + count + "</b><br>" + escape(String.join(", ", site.types()))
                + (messages.isEmpty() ? "" : "<br><b>Exception details:</b><br>" + messages.stream().map(ExceptionHighlights::escape).collect(java.util.stream.Collectors.joining("<br>")))
                + "<br>" + escape(context) + "<br>" + escape(site.location().file().getFileName() + ":" + site.location().line())
                + "<br>Click to show up to " + options.examplesPerLocation + " related examples</html>";
    }

    private void remove(Editor editor) {
        List<RangeHighlighter> markers = owned.remove(editor);
        if (markers != null) for (RangeHighlighter marker : markers) marker.dispose();
        var hints = inlays.remove(editor);
        if (hints != null) hints.forEach(Inlay::dispose);
    }

    @Override public void dispose() {
        disposed = true;
        for (Editor editor : new ArrayList<>(owned.keySet())) remove(editor);
        sites.clear(); focus = null;
    }

    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    private final class SiteRenderer extends GutterIconRenderer implements DumbAware {
        private final ExceptionSites.Site site;
        private final String tooltip;
        SiteRenderer(ExceptionSites.Site site, String tooltip) { this.site = site; this.tooltip = tooltip; }
        @Override public Icon getIcon() { return ICON; }
        @Override public String getTooltipText() { return tooltip; }
        @Override public String getAccessibleName() { return "Graylog exception logs"; }
        @Override public boolean isNavigateAction() { return true; }
        @Override public AnAction getClickAction() {
            return new DumbAwareAction("Show Related Graylog Logs") {
                @Override public void actionPerformed(AnActionEvent event) {
                    if (!disposed) onClick.accept(sites.getOrDefault(site.location(), site));
                }
            };
        }
        @Override public boolean equals(Object other) { return other instanceof ExceptionHighlights.SiteRenderer r &&
                r.site.location().equals(site.location()) && r.tooltip.equals(tooltip); }
        @Override public int hashCode() { return Objects.hash(site.location(), tooltip); }
    }
}
