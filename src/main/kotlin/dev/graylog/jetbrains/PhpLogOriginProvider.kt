package dev.graylog.jetbrains

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass

/** Category scopes the search; resolving the callee distinguishes logging from message construction. */
class PhpLogOriginProvider : LogOriginProvider {
    override fun candidates(project: Project, symbol: LogOriginMetadata.Symbol, event: LogEvent, options: LogNavigationOptions): List<LogOriginProvider.Candidate> {
        val result = mutableListOf<LogOriginProvider.Candidate>()
        val methods = LogNavigationOptions.names(options.loggerMethods).map { it.lowercase() }.toSet()
        for (cls in PhpIndex.getInstance(project).getClassesByFQN("\\" + symbol.owner).take(20)) {
            ProgressManager.checkCanceled()
            val method = cls.findMethodByName(symbol.method) ?: continue
            val file = method.containingFile?.virtualFile ?: continue
            if (file.fileSystem !is LocalFileSystem) continue
            val index = ProjectFileIndex.getInstance(project)
            if (!index.isInContent(file) || index.isExcluded(file)) continue
            val document = PsiDocumentManager.getInstance(project).getDocument(method.containingFile) ?: continue
            val localPath = file.toNioPath()
            val calls = PsiTreeUtil.findChildrenOfType(method, MethodReference::class.java).filter { call ->
                ProgressManager.checkCanceled()
                if (PsiTreeUtil.getParentOfType(call, Function::class.java) !== method) false
                else {
                    val target = call.resolve() as? Method
                    call.name?.lowercase() in methods &&
                        target?.containingClass?.let { loggerClass(it, options) } == true
                }
            }
            if (calls.isEmpty()) result += LogOriginProvider.Candidate(
                localPath, document.getLineNumber(method.textOffset) + 1,
                "Method from ${symbol.field}; exact log call not identified")
            else for (call in calls) {
                result += LogOriginProvider.Candidate(localPath, document.getLineNumber(call.textOffset) + 1,
                    "${call.name} logger call in ${symbol.owner}::${symbol.method}; candidate from ${symbol.field}")
                if (result.size >= 100) return result
            }
        }
        return result.distinctBy { it.localPath to it.line }
    }

    private fun loggerClass(initial: PhpClass, options: LogNavigationOptions): Boolean {
        val accepted = LogNavigationOptions.names(options.loggerClasses).map { it.removePrefix("\\").lowercase() }.toSet() +
            if (options.knownLoggers) LOGGER_CLASSES else emptySet()
        val queue = ArrayDeque<PhpClass>(); queue.add(initial)
        val seen = hashSetOf<String>()
        while (queue.isNotEmpty() && seen.size < 64) {
            ProgressManager.checkCanceled()
            val cls = queue.removeFirst(); val name = cls.fqn.removePrefix("\\").lowercase()
            if (!seen.add(name)) continue
            if (name in accepted) return true
            queue.addAll(cls.supers)
        }
        return false
    }

    companion object {
        private val LOGGER_CLASSES = setOf("yii", "yii\\baseyii", "yii\\log\\logger", "psr\\log\\loggerinterface",
            "psr\\log\\abstractlogger", "monolog\\logger")
    }
}
