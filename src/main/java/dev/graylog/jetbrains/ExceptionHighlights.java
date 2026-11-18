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
    private record Style(boolean gutter, boolean stripe, boolean details, boolean values,
                         String light, String dark, String selectedLight, String selectedDark, int examples) { }
    private record Spec(boolean selected, String tooltip, String inline, Style style) { }
    private record Decoration(Spec spec, RangeHighlighter marker, Inlay<ExceptionInlineRenderer> fallback) { }
    private record HintKey(LogEvent event, long stamp, long proofRevision, List<Decoration> selected) { }
    record RefreshStats(int editors, int locations, int created, int removed, long nanos) { }
    private final Map<Editor, Map<ExceptionSites.Location, Decoration>> owned = new HashMap<>();
    private final Map<Editor, HintKey> hintKeys = new HashMap<>();
    private final Map<ExceptionSites.Location, ExceptionSites.Site> sites = new LinkedHashMap<>();
    private final Map<Path, List<ExceptionSites.Site>> byFile = new HashMap<>();
    private final Set<Editor> pending = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final java.util.concurrent.atomic.AtomicBoolean refreshPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong proofRevision = new java.util.concurrent.atomic.AtomicLong();
    private String context = "";
    private Connection connection;
    private Path projectRoot;
    private LogEvent selectedEvent;
    private Set<ExceptionSites.Location> selectedLocations = Set.of();
    private ExceptionSites.Site focus;
    private boolean enabled = true;
    private volatile boolean disposed;
    private IdeOptions options = new IdeOptions();
    private RefreshStats stats = new RefreshStats(0, 0, 0, 0, 0);

    ExceptionHighlights(Project project, Disposable parent, Consumer<ExceptionSites.Site> onClick) {
        this.project = project; this.onClick = onClick;
        variableHints = new VariableHints(project); Disposer.register(this, variableHints); Disposer.register(parent, this);
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new com.intellij.openapi.editor.event.DocumentListener() {
            @Override public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent event) {
                if (disposed || project.isDisposed()) return;
                Editor[] editors = EditorFactory.getInstance().getEditors(event.getDocument(), project);
                if (editors.length == 0) {
                    VirtualFile file = FileDocumentManager.getInstance().getFile(event.getDocument());
                    var index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project);
                    if (file == null || !index.isInContent(file) && !index.isInLibrary(file)) return;
                }
                // PHP proof can depend on another source file. Invalidate selected values without rebuilding unrelated markers.
                proofRevision.incrementAndGet(); variableHints.invalidateProofs();
                if (editors.length == 0) scheduleRefresh(null);
                else for (Editor editor : editors) scheduleRefresh(editor);
            }
        }, this);
        EditorFactory.getInstance().addEditorFactoryListener(new EditorFactoryListener() {
            @Override public void editorCreated(EditorFactoryEvent event) { if (event.getEditor().getProject() == project) scheduleRefresh(event.getEditor()); }
            @Override public void editorReleased(EditorFactoryEvent event) { if (event.getEditor().getProject() == project) remove(event.getEditor()); }
        }, this);
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
            @Override public void fileOpened(FileEditorManager source, VirtualFile file) {
                var document = FileDocumentManager.getInstance().getDocument(file);
                if (document != null) for (Editor editor : EditorFactory.getInstance().getEditors(document, project)) refresh(List.of(editor));
            }
        });
    }
    void replace(List<ExceptionSites.Site> found, String searchContext, Connection connection, Path root) {
        this.connection = connection.copy(); projectRoot = root; sites.clear(); byFile.clear();
        for (var site : found) { sites.put(site.location(), site); byFile.computeIfAbsent(site.location().file(), ignored -> new ArrayList<>()).add(site); }
        selectedLocations = selectedEvent == null ? Set.of() : Set.copyOf(ExceptionSites.locations(selectedEvent, connection, root));
        context = searchContext; focus = null; refreshAll();
    }
    void select(LogEvent event) {
        if (selectedEvent == event && focus == null) return;
        Set<Path> affected = affectedFiles(); selectedEvent = event;
        selectedLocations = event == null || connection == null ? Set.of() : Set.copyOf(ExceptionSites.locations(event, connection, projectRoot));
        focus = null; affected.addAll(affectedFiles()); refreshFiles(affected);
    }
    void focus(Connection connection, Path root, LogEvent event, SourceLocation source) {
        Path file = source.resolve(connection, root); if (file == null || event == null) return;
        Set<Path> affected = affectedFiles(); this.connection = connection.copy(); projectRoot = root; selectedEvent = event;
        var key = new ExceptionSites.Location(file, source.line()); selectedLocations = Set.of(key);
        focus = sites.getOrDefault(key, new ExceptionSites.Site(key, List.of("Selected stack frame"), List.of(event)));
        affected.add(file); refreshFiles(affected);
    }
    void setEnabled(boolean value) { if (enabled != value) { enabled = value; refreshAll(); } }
    void configure(IdeOptions value) { Style before = style(); options = value.copy(); if (!before.equals(style())) refreshAll(); }
    void clear() { sites.clear(); byFile.clear(); selectedEvent = null; selectedLocations = Set.of(); focus = null; refreshAll(); }
    String context() { return context; }
    RefreshStats stats() { return stats; }
    List<RangeHighlighter> markers(Editor editor) {
        return owned.getOrDefault(editor, Map.of()).values().stream().map(Decoration::marker).toList();
    }
    private Style style() { return new Style(options.gutterIcons, options.errorStripe, options.inlineDetails, options.inlineValues,
            options.backgroundLight, options.backgroundDark, options.selectedLight, options.selectedDark, options.examplesPerLocation); }
    private Set<Path> affectedFiles() {
        Set<Path> files = new java.util.HashSet<>(); selectedLocations.forEach(location -> files.add(location.file()));
        if (focus != null) files.add(focus.location().file()); return files;
    }
    private Path path(Editor editor) {
        var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file == null || !file.isInLocalFileSystem() ? null : Path.of(file.getPath()).toAbsolutePath().normalize();
    }
    private void refreshFiles(Set<Path> files) {
        refresh(java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors()).filter(e -> e.getProject() == project && files.contains(path(e))).toList());
    }
    private void refreshAll() {
        if (disposed || project.isDisposed()) return;
        refresh(java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors()).filter(e -> e.getProject() == project).toList());
    }
    private void scheduleRefresh(Editor editor) {
        if (disposed || project.isDisposed()) return;
        synchronized (pending) { if (editor != null) pending.add(editor); }
        if (refreshPending.compareAndSet(false, true)) ApplicationManager.getApplication().invokeLater(() -> {
            refreshPending.set(false); Set<Editor> editors = new java.util.HashSet<>();
            synchronized (pending) { editors.addAll(pending); pending.clear(); }
            // A changed dependency file can invalidate values in the selected exception's editor.
            hintKeys.forEach((owner, key) -> { if (!key.selected().isEmpty() && key.proofRevision() != proofRevision.get()) editors.add(owner); });
            refresh(editors);
        });
    }
    private void refresh(java.util.Collection<Editor> editors) {
        if (disposed || project.isDisposed()) return;
        if (!ApplicationManager.getApplication().isDispatchThread()) { editors.forEach(this::scheduleRefresh); return; }
        long started = System.nanoTime(); int visited = 0, locations = 0, created = 0, removed = 0;
        Style style = style();
        for (Editor editor : editors) {
            if (editor.isDisposed() || editor.getProject() != project) { remove(editor); continue; }
            visited++; Path path = path(editor);
            Map<ExceptionSites.Location, Decoration> previous = owned.computeIfAbsent(editor, ignored -> new LinkedHashMap<>());
            Map<ExceptionSites.Location, ExceptionSites.Site> visible = new LinkedHashMap<>();
            if (enabled && path != null) {
                for (var site : byFile.getOrDefault(path, List.of())) visible.put(site.location(), site);
                if (focus != null && focus.location().file().equals(path)) visible.put(focus.location(), focus);
            }
            Map<ExceptionSites.Location, Spec> desired = new LinkedHashMap<>();
            List<VariableHints.Target> targets = new ArrayList<>(); List<Decoration> selectedDecorations = new ArrayList<>();
            HintKey beforeHints = hintKeys.get(editor);
            boolean proofChanged = beforeHints == null || beforeHints.stamp() != editor.getDocument().getModificationStamp()
                    || beforeHints.proofRevision() != proofRevision.get() || beforeHints.event() != selectedEvent;
            for (var site : visible.values()) {
                locations++; int line = site.location().line() - 1;
                if (line < 0 || line >= editor.getDocument().getLineCount()) continue;
                boolean selected = selectedLocations.contains(site.location());
                List<String> messages = ExceptionPresentation.messages(site, connection, projectRoot, selectedEvent);
                Spec spec = new Spec(selected, tooltip(site, messages), ExceptionPresentation.inline(site, messages), style);
                desired.put(site.location(), spec); Decoration current = previous.get(site.location());
                if (current != null && (!current.marker().isValid() || !current.spec().equals(spec)
                        || editor.getDocument().getLineNumber(current.marker().getStartOffset()) != line)) {
                    dispose(current); previous.remove(site.location()); current = null; removed++;
                }
                if (current == null) {
                    TextAttributes attributes = new TextAttributes(); attributes.setBackgroundColor(selected
                            ? new JBColor(Color.decode(style.selectedLight()), Color.decode(style.selectedDark()))
                            : new JBColor(Color.decode(style.light()), Color.decode(style.dark())));
                    RangeHighlighter marker = editor.getMarkupModel().addLineHighlighter(line, HighlighterLayer.CARET_ROW + 1, attributes);
                    if (style.stripe()) marker.setErrorStripeMarkColor(STRIPE);
                    marker.setErrorStripeTooltip(spec.tooltip()); if (style.gutter()) marker.setGutterIconRenderer(new SiteRenderer(site, spec.tooltip()));
                    current = new Decoration(spec, marker, fallback(editor, line, spec)); previous.put(site.location(), current); created++;
                } else if (selected && style.values() && proofChanged && current.fallback() != null && !current.fallback().isValid()) {
                    current = new Decoration(spec, current.marker(), fallback(editor, line, spec)); previous.put(site.location(), current);
                }
                if (selected && style.details() && style.values() && selectedEvent != null && current.fallback() != null) {
                    var details = ExceptionDetails.chain(selectedEvent).stream().filter(part -> part.origin() != null
                            && part.origin().line() == site.location().line() && site.location().file().equals(part.origin().resolve(connection, projectRoot)))
                            .map(part -> new ExceptionSites.Detail(part.type(), part.message())).toList();
                    if (!details.isEmpty()) { targets.add(new VariableHints.Target(editor, line + 1, details, current.fallback())); selectedDecorations.add(current); }
                }
            }
            for (var key : new ArrayList<>(previous.keySet())) if (!desired.containsKey(key)) { dispose(previous.remove(key)); removed++; }
            if (previous.isEmpty()) owned.remove(editor);
            if (targets.isEmpty()) { if (hintKeys.remove(editor) != null) variableHints.clear(editor); }
            else {
                HintKey nextHints = new HintKey(selectedEvent, editor.getDocument().getModificationStamp(), proofRevision.get(), List.copyOf(selectedDecorations));
                if (!nextHints.equals(beforeHints)) { hintKeys.put(editor, nextHints); variableHints.refresh(editor, targets); }
            }
        }
        stats = new RefreshStats(visited, locations, created, removed, System.nanoTime() - started);
    }
    private Inlay<ExceptionInlineRenderer> fallback(Editor editor, int line, Spec spec) {
        return !spec.style().details() || spec.inline().isBlank() ? null : editor.getInlayModel().addAfterLineEndElement(
                editor.getDocument().getLineEndOffset(line), true, new ExceptionInlineRenderer(spec.inline(), spec.selected()));
    }
    private String tooltip(ExceptionSites.Site site, List<String> messages) {
        String count = sites.containsKey(site.location()) ? site.count() + " event(s) in period" : "Selected stack frame";
        return "<html><b>Graylog · " + count + "</b><br>" + escape(String.join(", ", site.types()))
                + (messages.isEmpty() ? "" : "<br><b>Exception details:</b><br>" + messages.stream().map(ExceptionHighlights::escape).collect(java.util.stream.Collectors.joining("<br>")))
                + "<br>" + escape(context) + "<br>" + escape(site.location().file().getFileName() + ":" + site.location().line())
                + "<br>Click to show up to " + options.examplesPerLocation + " related examples</html>";
    }

    private void dispose(Decoration decoration) {
        decoration.marker().dispose(); if (decoration.fallback() != null && decoration.fallback().isValid()) decoration.fallback().dispose();
    }
    private void remove(Editor editor) {
        var decorations = owned.remove(editor); if (decorations != null) decorations.values().forEach(this::dispose);
        hintKeys.remove(editor); variableHints.clear(editor);
    }
    @Override public void dispose() {
        disposed = true; for (Editor editor : new ArrayList<>(owned.keySet())) remove(editor);
        variableHints.clear(); hintKeys.clear(); sites.clear(); byFile.clear(); focus = null;
        synchronized (pending) { pending.clear(); }
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
