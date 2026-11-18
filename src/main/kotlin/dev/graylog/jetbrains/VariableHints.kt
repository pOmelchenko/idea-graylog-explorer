package dev.graylog.jetbrains

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Keeps selected-event values separate from aggregated period markers. */
class VariableHints(private val project: Project) : Disposable {
    @JvmRecord data class Target(val editor: Editor, val line: Int, val exceptions: List<ExceptionSites.Detail>, val fallback: Inlay<*>?)
    private data class Result(val target: Target, val stamp: Long, val hints: List<VariableHintProvider.Hint>)
    private val owned = mutableMapOf<Editor, MutableList<Inlay<*>>>()
    private val revisions = ConcurrentHashMap<Editor, Long>()
    private val revision = AtomicLong()
    @Volatile private var disposed = false
    fun clear() {
        revisions.clear()
        owned.values.forEach { values -> values.forEach { it.dispose() } }
        owned.clear()
    }
    fun clear(editor: Editor) { revisions.remove(editor); owned.remove(editor)?.forEach { it.dispose() } }
    fun invalidateProofs() {
        revisions.clear()
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) clear() else app.invokeLater { if (!disposed) clear() }
    }
    fun refresh(editor: Editor, targets: List<Target>) {
        clear(editor)
        if (targets.isEmpty() || VariableHintProvider.EP.extensionList.isEmpty()) return
        val generation = revision.incrementAndGet(); revisions[editor] = generation
        ReadAction.nonBlocking<List<Result>> {
            val results = mutableListOf<Result>()
            for (target in targets) {
                if (target.editor.isDisposed) continue
                val document = target.editor.document
                val manager = FileDocumentManager.getInstance()
                val file = manager.getFile(document) ?: continue
                if (manager.isDocumentUnsaved(document)) continue
                for (provider in VariableHintProvider.EP.extensionList) {
                    val hints = provider.hints(project, file, document, target.line, target.exceptions)
                    if (hints.isNotEmpty()) { results.add(Result(target, document.modificationStamp, hints)); break }
                }
            }
            results
        }.inSmartMode(project).withDocumentsCommitted(project).expireWith(this)
            .expireWhen { disposed || revisions[editor] != generation }
            .finishOnUiThread(ModalityState.any()) { results ->
                if (disposed || revisions[editor] != generation) return@finishOnUiThread
                for (result in results) {
                    val targetEditor = result.target.editor
                    if (targetEditor.isDisposed || targetEditor.document.modificationStamp != result.stamp) continue
                    var added = false
                    for (hint in result.hints) {
                        if (hint.offset() !in 0..targetEditor.document.textLength) continue
                        val inlay = targetEditor.inlayModel.addInlineElement(hint.offset(), true,
                            ExceptionInlineRenderer(LoggedStringTemplate.display(hint.value()), true))
                        if (inlay != null) { owned.getOrPut(targetEditor) { mutableListOf() }.add(inlay); added = true }
                    }
                    result.target.fallback?.takeIf { added && it.isValid }?.dispose()
                }
            }.submit(AppExecutorUtil.getAppExecutorService())
    }
    override fun dispose() { disposed = true; clear() }
}
