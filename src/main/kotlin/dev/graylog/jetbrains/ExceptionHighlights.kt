package dev.graylog.jetbrains

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Objects
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.*
import java.util.function.Consumer
import javax.swing.Icon

/** Owns only this Explorer's highlighters. Optional language adapters resolve selected-event variable hints without executing code.  */
class ExceptionHighlights(
    private val project: Project,
    parent: Disposable,
    onClick: Consumer<ExceptionSites.Site>
) : Disposable {
    private val variableHints: VariableHints
    private val onClick: Consumer<ExceptionSites.Site>

    @JvmRecord
    private data class Style(
        val gutter: Boolean,
        val stripe: Boolean,
        val details: Boolean,
        val values: Boolean,
        val light: String,
        val dark: String,
        val selectedLight: String,
        val selectedDark: String,
        val examples: Int
    )

    @JvmRecord
    private data class Spec(val selected: Boolean, val tooltip: String, val inline: String, val style: Style)

    private data class Decoration(val spec: Spec, val marker: RangeHighlighter, val fallback: Inlay<ExceptionInlineRenderer>?)

    @JvmRecord
    private data class HintKey(
        val event: LogEvent?,
        val stamp: Long,
        val proofRevision: Long,
        val selected: List<Decoration>
    )

    @JvmRecord
    data class RefreshStats(val editors: Int, val locations: Int, val created: Int, val removed: Int, val nanos: Long)

    private val owned: MutableMap<Editor, MutableMap<ExceptionSites.Location, Decoration>> =
        HashMap()
    private val hintKeys: MutableMap<Editor, HintKey> =
        HashMap()
    private val sites: MutableMap<ExceptionSites.Location, ExceptionSites.Site> =
        LinkedHashMap()
    private val byFile: MutableMap<Path, MutableList<ExceptionSites.Site>> = HashMap()
    private val pending: MutableSet<Editor> =
        java.util.Collections.newSetFromMap(IdentityHashMap())
    private val refreshPending = AtomicBoolean()
    private val proofRevision = AtomicLong()
    private var context = ""
    private var connection: Connection? = null
    private var projectRoot: Path? = null
    private var selectedEvent: LogEvent? = null
    private var selectedLocations: Set<ExceptionSites.Location> = setOf()
    private var focus: ExceptionSites.Site? = null
    private var enabled = true

    @Volatile
    private var disposed = false
    private var options = IdeOptions()
    private var stats = RefreshStats(0, 0, 0, 0, 0)

    init {
        this.onClick = onClick
        variableHints = VariableHints(project)
        Disposer.register(this, variableHints)
        Disposer.register(parent, this)
        EditorFactory.getInstance().getEventMulticaster()
            .addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (disposed || project.isDisposed()) return
                    val editors =
                        EditorFactory.getInstance().getEditors(event.getDocument(), project)
                    if (editors.size == 0) {
                        val file: VirtualFile? = FileDocumentManager.getInstance().getFile(event.getDocument())
                        val index: com.intellij.openapi.roots.ProjectFileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
                        if (file == null || !index.isInContent(file) && !index.isInLibrary(file)) return
                    }
                    // PHP proof can depend on another source file. Invalidate selected values without rebuilding unrelated markers.
                    proofRevision.incrementAndGet()
                    variableHints.invalidateProofs()
                    if (editors.size == 0) scheduleRefresh(null)
                    else for (editor in editors) scheduleRefresh(editor)
                }
            }, this)
        EditorFactory.getInstance()
            .addEditorFactoryListener(object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    if (event.getEditor().getProject() === project) scheduleRefresh(event.getEditor())
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    if (event.getEditor().getProject() === project) remove(event.getEditor())
                }
            }, this)
        project.getMessageBus().connect(this).subscribe<FileEditorManagerListener>(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    val document: com.intellij.openapi.editor.Document? =
                        FileDocumentManager.getInstance().getDocument(file)
                    if (document != null) for (editor in EditorFactory.getInstance()
                        .getEditors(document, project)) refresh(listOf(editor))
                }
            })
    }

    fun replace(
        found: List<ExceptionSites.Site>,
        searchContext: String,
        connection: Connection,
        root: Path?
    ) {
        this.connection = connection.copy()
        projectRoot = root
        sites.clear()
        byFile.clear()
        for (site in found) {
            sites.put(site.location(), site)
            byFile.computeIfAbsent(site.location().file(), { ignored -> ArrayList() }).add(site)
        }
        selectedLocations = if (selectedEvent == null) setOf() else java.util.Set.copyOf(
            ExceptionSites.locations(selectedEvent, connection, root)
        )
        context = searchContext
        focus = null
        refreshAll()
    }

    fun select(event: LogEvent?) {
        if (selectedEvent === event && focus == null) return
        val affected: MutableSet<Path> = affectedFiles()
        selectedEvent = event
        selectedLocations = if (event == null || connection == null) setOf() else java.util.Set.copyOf(
            ExceptionSites.locations(event, connection, projectRoot)
        )
        focus = null
        affected.addAll(affectedFiles())
        refreshFiles(affected)
    }

    fun focus(
        connection: Connection,
        root: Path?,
        event: LogEvent?,
        source: SourceLocation
    ) {
        val file: Path? = source.resolve(connection, root)
        if (file == null || event == null) return
        val affected: MutableSet<Path> = affectedFiles()
        this.connection = connection.copy()
        projectRoot = root
        selectedEvent = event
        val key = ExceptionSites.Location(file, source.line)
        selectedLocations = setOf(key)
        focus = sites.getOrDefault(key, ExceptionSites.Site(key, listOf("Selected stack frame"), listOf(event)))
        affected.add(file)
        refreshFiles(affected)
    }

    fun setEnabled(value: Boolean) {
        if (enabled != value) {
            enabled = value
            refreshAll()
        }
    }

    fun configure(value: IdeOptions) {
        val before = style()
        options = value.copy()
        if (!before.equals(style())) refreshAll()
    }

    fun clear() {
        sites.clear()
        byFile.clear()
        selectedEvent = null
        selectedLocations = setOf()
        focus = null
        refreshAll()
    }

    fun context(): String = context

    fun stats(): RefreshStats = stats

    fun markers(editor: Editor?): List<RangeHighlighter> {
        return owned.getOrDefault(editor, emptyMap()).values.stream()
            .map(Decoration::marker).toList()
    }

    private fun style(): Style {
        return Style(
            options.gutterIcons,
            options.errorStripe,
            options.inlineDetails,
            options.inlineValues,
            options.backgroundLight,
            options.backgroundDark,
            options.selectedLight,
            options.selectedDark,
            options.examplesPerLocation
        )
    }

    private fun affectedFiles(): MutableSet<Path> {
        val files: MutableSet<Path> = HashSet()
        selectedLocations.forEach({ location -> files.add(location.file()) })
        if (focus != null) files.add(focus!!.location.file)
        return files
    }

    private fun path(editor: Editor): Path? {
        val file: VirtualFile? = FileDocumentManager.getInstance().getFile(editor.getDocument())
        return if (file == null || !file.isInLocalFileSystem()) null else Path.of(file.getPath()).toAbsolutePath()
            .normalize()
    }

    private fun refreshFiles(files: Set<Path>) {
        refresh(
            java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors())
                .filter({ e -> e.getProject() === project && files.contains(path(e)) }).toList()
        )
    }

    private fun refreshAll() {
        if (disposed || project.isDisposed()) return
        refresh(
            java.util.Arrays.stream(EditorFactory.getInstance().getAllEditors())
                .filter({ e -> e.getProject() === project }).toList()
        )
    }

    private fun scheduleRefresh(editor: Editor?) {
        if (disposed || project.isDisposed()) return
        synchronized(pending) { if (editor != null) pending.add(editor) }
        if (refreshPending.compareAndSet(false, true)) ApplicationManager.getApplication()
            .invokeLater(Runnable {
                refreshPending.set(false)
                val editors: MutableSet<Editor> = HashSet()
                synchronized(pending) {
                    editors.addAll(pending)
                    pending.clear()
                }
                // A changed dependency file can invalidate values in the selected exception's editor.
                hintKeys.forEach({ owner, key ->
                    if (!key.selected.isEmpty() && key.proofRevision != proofRevision.get()) editors.add(owner)
                })
                refresh(editors)
            })
    }

    private fun refresh(editors: Collection<Editor>) {
        if (disposed || project.isDisposed()) return
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            editors.forEach({ editor: Editor? -> this.scheduleRefresh(editor) })
            return
        }
        val started: Long = System.nanoTime()
        var visited = 0
        var locations = 0
        var created = 0
        var removed = 0
        val style = style()
        for (editor in editors) {
            if (editor.isDisposed() || editor.getProject() !== project) {
                remove(editor)
                continue
            }
            visited++
            val path: Path? = path(editor)
            val previous: MutableMap<ExceptionSites.Location, Decoration> =
                owned.computeIfAbsent(editor, { ignored -> LinkedHashMap() })
            val visible: MutableMap<ExceptionSites.Location, ExceptionSites.Site> =
                LinkedHashMap()
            if (enabled && path != null) {
                for (site in byFile.getOrDefault(path, listOf())) visible.put(site.location(), site)
                if (focus != null && focus!!.location.file.equals(path)) visible.put(focus!!.location, focus!!)
            }
            val desired: MutableMap<ExceptionSites.Location, Spec> =
                LinkedHashMap()
            val targets: MutableList<VariableHints.Target> = ArrayList()
            val selectedDecorations: MutableList<Decoration> = ArrayList()
            val beforeHints: HintKey? = hintKeys.get(editor)
            val proofChanged = beforeHints == null || beforeHints.stamp != editor.getDocument()
                .getModificationStamp() || beforeHints.proofRevision != proofRevision.get() || beforeHints.event != selectedEvent
            for (site in visible.values) {
                locations++
                val line: Int = site.location().line() - 1
                if (line < 0 || line >= editor.getDocument().getLineCount()) continue
                val selected: Boolean = selectedLocations.contains(site.location())
                val messages: List<String> =
                    ExceptionPresentation.messages(site, connection, projectRoot, selectedEvent)
                val spec = Spec(selected, tooltip(site, messages), ExceptionPresentation.inline(site, messages), style)
                desired.put(site.location(), spec)
                var current = previous.get(site.location())
                if (current != null && (!current.marker.isValid() || !current.spec.equals(spec) || editor.getDocument()
                        .getLineNumber(current.marker.getStartOffset()) != line)
                ) {
                    dispose(current)
                    previous.remove(site.location())
                    current = null
                    removed++
                }
                if (current == null) {
                    val attributes = TextAttributes()
                    attributes.setBackgroundColor(
                        if (selected)
                            JBColor(Color.decode(style.selectedLight), Color.decode(style.selectedDark))
                        else
                            JBColor(Color.decode(style.light), Color.decode(style.dark))
                    )
                    val marker: RangeHighlighter =
                        editor.getMarkupModel().addLineHighlighter(line, HighlighterLayer.CARET_ROW + 1, attributes)
                    if (style.stripe) marker.setErrorStripeMarkColor(STRIPE)
                    marker.setErrorStripeTooltip(spec.tooltip)
                    if (style.gutter) marker.setGutterIconRenderer(
                        SiteRenderer(site, spec.tooltip)
                    )
                    current = Decoration(spec, marker, fallback(editor, line, spec))
                    previous.put(site.location(), current)
                    created++
                } else if (selected && style.values && proofChanged && current.fallback != null && !current.fallback.isValid()) {
                    current = Decoration(spec, current.marker, fallback(editor, line, spec))
                    previous.put(site.location(), current)
                }
                if (selected && style.details && style.values && selectedEvent != null && current.fallback != null) {
                    val details =
                        ExceptionDetails.chain(selectedEvent!!).filter { part ->
                            part.origin?.let { it.line() == site.location().line() && site.location().file() == it.resolve(connection, projectRoot) } == true
                        }.map { ExceptionSites.Detail(it.type, it.message) }
                    if (!details.isEmpty()) {
                        targets.add(VariableHints.Target(editor, line + 1, details, current.fallback)
                        )
                        selectedDecorations.add(current)
                    }
                }
            }
            for (key in ArrayList(previous.keys)) if (!desired.containsKey(key)) {
                previous.remove(key)?.let(::dispose)
                removed++
            }
            if (previous.isEmpty()) owned.remove(editor)
            if (targets.isEmpty()) {
                if (hintKeys.remove(editor) != null) variableHints.clear(editor)
            } else {
                val nextHints = HintKey(
                    selectedEvent,
                    editor.getDocument().getModificationStamp(),
                    proofRevision.get(),
                    java.util.List.copyOf(selectedDecorations)
                )
                if (!nextHints.equals(beforeHints)) {
                    hintKeys.put(editor, nextHints)
                    variableHints.refresh(editor, targets)
                }
            }
        }
        stats = RefreshStats(visited, locations, created, removed, System.nanoTime() - started)
    }

    private fun fallback(
        editor: Editor,
        line: Int,
        spec: Spec
    ): Inlay<ExceptionInlineRenderer>? {
        return if (!spec.style.details || spec.inline.isBlank()) null else editor.getInlayModel()
            .addAfterLineEndElement<ExceptionInlineRenderer>(
                editor.getDocument().getLineEndOffset(line),
                true,
                ExceptionInlineRenderer(spec.inline, spec.selected)
            )
    }

    private fun tooltip(site: ExceptionSites.Site, messages: List<String>): String {
        val count =
            if (sites.containsKey(site.location)) site.count.toString() + " event(s) in period" else "Selected stack frame"
        return ("<html><b>Graylog · " + count + "</b><br>" + escape(site.types.joinToString(", "))
                + (if (messages.isEmpty()) "" else "<br><b>Exception details:</b><br>" + messages.stream()
            .map({ value: String -> escape(value) })
            .collect(java.util.stream.Collectors.joining("<br>")))
                + "<br>" + escape(context) + "<br>" + escape(
            site.location.file.fileName.toString() + ":" + site.location.line
        )
                + "<br>Click to show up to " + options.examplesPerLocation + " related examples</html>")
    }

    private fun dispose(decoration: Decoration) {
        decoration.marker.dispose()
        if (decoration.fallback != null && decoration.fallback.isValid()) decoration.fallback.dispose()
    }

    private fun remove(editor: Editor) {
        val decorations =
            owned.remove(editor)
        if (decorations != null) decorations.values.forEach(this::dispose)
        hintKeys.remove(editor)
        variableHints.clear(editor)
    }

    override fun dispose() {
        disposed = true
        for (editor in ArrayList(owned.keys)) remove(editor)
        variableHints.clear()
        hintKeys.clear()
        sites.clear()
        byFile.clear()
        focus = null
        synchronized(pending) { pending.clear() }
    }

    private inner class SiteRenderer(
        private val site: ExceptionSites.Site,
        private val tooltip: String
    ) : GutterIconRenderer(), DumbAware {
        override fun getIcon(): Icon = ICON

        override fun getTooltipText(): String? = tooltip

        override fun getAccessibleName(): String = "Graylog exception logs"

        override fun isNavigateAction(): Boolean = true

        override fun getClickAction(): AnAction = object : DumbAwareAction("Show Related Graylog Logs") {
                override fun actionPerformed(event: AnActionEvent) {
                    if (!disposed) onClick.accept(sites.getOrDefault(site.location, site))
                }
            }

        override fun equals(other: Any?): Boolean {
            return other is SiteRenderer &&
                    other.site.location.equals(site.location) && other.tooltip.equals(tooltip)
        }

        override fun hashCode(): Int = Objects.hash(site.location, tooltip)
    }

    companion object {
        private val STRIPE: Color = JBColor(Color(190, 65, 45), Color(240, 117, 102))
        private val ICON: Icon = IconLoader.getIcon("/icons/graylog.svg", ExceptionHighlights::class.java)

        private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
