package dev.graylog.jetbrains

import com.intellij.find.FindModel
import com.intellij.find.findInProject.FindInProjectManager
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JList

/** On-demand navigation owns no index cache and never adds exception markers. */
class LogOriginNavigator(private val project: Project) : Disposable {
    data class Target(val path: Path, val line: Int, val description: String, val explicit: Boolean = false)
    data class Result(val targets: List<Target>, val status: String, val direct: Boolean)
    @Volatile private var generation = 0L
    @Volatile private var disposed = false
    private var popup: JBPopup? = null
    fun cancel() { generation++; popup?.cancel(); popup = null }

    fun find(event: LogEvent, connection: Connection, component: JComponent, done: (String) -> Unit) {
        cancel()
        val ticket = generation
        ReadAction.nonBlocking<Result> { locate(event, connection) }.withDocumentsCommitted(project)
            .expireWith(this).expireWhen { disposed || generation != ticket }
            .finishOnUiThread(ModalityState.any()) { result ->
                if (disposed || generation != ticket) return@finishOnUiThread
                done(result.status)
                if (result.direct) open(result.targets.single())
                else if (result.targets.isNotEmpty()) choose(result.targets, component)
            }.submit(AppExecutorUtil.getAppExecutorService()).onError {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && generation == ticket) done("Source lookup failed. Check source mappings and use Search message in project to inspect candidates.")
                }
            }
    }

    /** Runs in a committed read action; metadata navigation remains available during indexing. */
    fun locate(event: LogEvent, connection: Connection): Result {
        val metadata = LogOriginMetadata.read(event, connection.navigation)
        val root = project.basePath?.let(Path::of)
        val reported = metadata.reported.filter { it.explicit }
        val targets = reported.mapNotNull { entry ->
            entry.location.resolve(connection, root)?.let { path ->
                target(path, entry.location.line, "${entry.fields}" + if (entry.explicit) " — reported log origin" else " — source role unspecified", entry.explicit)
            }
        }
        if (targets.isNotEmpty()) return Result(targets,
            "${targets.size} reported source location(s). These are coordinates from the event; deployed source revision is not verified.",
            targets.size == 1 && targets.single().explicit && reported.size == 1)
        val generic = metadata.reported.filter { !it.explicit }.mapNotNull { entry ->
            entry.location.resolve(connection, root)?.let { target(it, entry.location.line, "${entry.fields} — source role unspecified") }
        }
        if (!connection.navigation.categoryLookup) return Result(generic, "Method/category lookup is disabled in this profile. Generic coordinates, when present, require choosing a source candidate.", false)
        if (DumbService.isDumb(project)) return Result(generic, "Project indexes are updating. Retry method lookup when indexing completes; reported coordinates do not require indexes.", false)
        val candidates = metadata.symbols.flatMap { symbol ->
            LogOriginProvider.EP.extensionList.flatMap { it.candidates(project, symbol, event, connection.navigation) }
        }.distinctBy { it.location }.take(100).mapNotNull { entry ->
            entry.location.resolve(connection, root)?.let { target(it, entry.location.line, entry.description) }
        }
        val unavailable = if (reported.isEmpty()) "" else "Reported source file/line is unavailable in this checkout. Check Source path mappings and deployed revision. "
        val all = (candidates + generic).distinctBy { it.path to it.line }
        return Result(all, unavailable + if (all.isEmpty())
            "No log call was identified. Configure log statement fields or use Search message in project. Method lookup currently requires the PHP adapter and a Class::method category."
            else "Choose a source candidate (up to 100). Category/method matches do not prove which call produced this event.", false)
    }

    private fun target(path: Path, line: Int, description: String, explicit: Boolean = false): Target? {
        val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
        if (file.isDirectory || !file.isValid) return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (line !in 1..document.lineCount) return null
        return Target(path, line, description, explicit)
    }
    private fun open(target: Target) {
        val file = LocalFileSystem.getInstance().findFileByNioFile(target.path) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        if (target.line !in 1..document.lineCount) return
        OpenFileDescriptor(project, file, target.line - 1, 0).navigate(true)
    }
    private fun choose(targets: List<Target>, component: JComponent) {
        popup = JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
            .setTitle("Log statement candidates")
            .setRenderer(object : ColoredListCellRenderer<Target>() {
                override fun customizeCellRenderer(list: JList<out Target>, value: Target, index: Int, selected: Boolean, focus: Boolean) {
                    append("${value.path.fileName}:${value.line}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    val root = project.basePath?.let(Path::of)
                    val display = if (root != null && value.path.startsWith(root)) root.relativize(value.path) else value.path
                    append("  ${display.parent ?: ""} — ${value.description}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    toolTipText = value.path.toString()
                }
            }).setItemChosenCallback(::open).createPopup().also {
                com.intellij.openapi.util.Disposer.register(this, it)
                it.showInCenterOf(component)
            }
    }
    fun searchMessage(event: LogEvent, component: JComponent) {
        val query = LogOriginMetadata.read(event).messageQuery
        if (query.isBlank()) return
        val model = FindModel()
        model.stringToFind = query; model.isRegularExpressions = false; model.isCaseSensitive = true
        model.isProjectScope = true; model.isWithSubdirectories = true
        FindInProjectManager.getInstance(project).findInProject(DataManager.getInstance().getDataContext(component), model)
    }
    override fun dispose() { disposed = true; cancel() }
}
