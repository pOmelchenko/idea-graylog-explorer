package dev.graylog.jetbrains

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.*
import com.jetbrains.php.lang.psi.resolve.types.PhpType

/** Source proof: throw new ExactClass($var) -> sprintf('...%s...', $parameter). */
class PhpVariableHintProvider : VariableHintProvider {
    override fun hints(project: Project, file: VirtualFile, document: Document, line: Int,
                       exceptions: List<ExceptionSites.Detail>): List<VariableHintProvider.Hint> {
        if (!file.extension.equals("php", ignoreCase = true)) return emptyList()
        val psi = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        if (line !in 1..document.lineCount) return emptyList()
        val start = document.getLineStartOffset(line - 1); val end = document.getLineEndOffset(line - 1)
        val calls = linkedSetOf<NewExpression>()
        var offset = start
        while (offset < end) {
            val leaf = psi.findElementAt(offset) ?: break
            val call = PsiTreeUtil.getParentOfType(leaf, NewExpression::class.java, false)
            if (call != null && call.textRange.startOffset >= start) calls.add(call)
            offset = maxOf(offset + 1, leaf.textRange.endOffset)
        }
        val found = linkedMapOf<Int, String>()
        for (exception in exceptions) {
            val matching = calls.filter { call -> resolvedClass(call.classReference)?.let { sameClass(it.fqn, exception.type()) } == true }
            val call = matching.singleOrNull() ?: continue
            val thrown = call.parent as? PhpThrowExpression ?: continue
            if (!positional(call) || thrown.argument !== call) continue
            val cls = resolvedClass(call.classReference) ?: continue
            val constructor = cls.ownConstructor ?: continue
            if (unsaved(constructor) || !unmodifiedParent(cls)) continue
            val body = PsiTreeUtil.getChildOfType(constructor, GroupStatement::class.java) ?: continue
            val statements = body.statements
            val parent = statements.lastOrNull()?.let(::directCall) ?: continue
            if (!positional(parent) || !parent.name.equals("__construct", true) ||
                !parent.classReference?.text.equals("parent", true) || parent.parameters.size != 1) continue
            val format = parent.getParameter(0) as? FunctionReference ?: continue
            if (!positional(format) || format is MethodReference || format.parameters.size != 2) continue
            val function = format.resolve() as? com.jetbrains.php.lang.psi.elements.Function ?: continue
            if (!sameClass(function.fqn, "sprintf")) continue
            val literal = format.getParameter(0) as? StringLiteralExpression ?: continue
            val parameterUse = format.getParameter(1) as? Variable ?: continue
            if (!literal.isSingleQuote) continue
            val parameters = constructor.parameters
            val index = parameters.indexOfLast { it.name == parameterUse.name }
            if (index < 0) continue
            val parameter = parameters[index]
            if (parameter.isPassByRef || parameter.isVariadic || parameter.declaredType != PhpType.STRING) continue
            if (PsiTreeUtil.findChildrenOfType(body, Variable::class.java).count { it.name == parameter.name } != 1) continue
            if (statements.dropLast(1).any { statement ->
                val setup = directCall(statement)
                setup == null || setup.classReference?.text != "\$this" ||
                    setup.parameters.any { it !is StringLiteralExpression || !it.isSingleQuote }
            }) continue
            val variable = call.getParameter(index) as? Variable ?: continue
            if (!VARIABLE.matches(variable.text)) continue
            // Named/unpacked arguments, expressions and aliases need a separate proof rule.
            if (call.parameters.any { it !is Variable && it !is StringLiteralExpression }) continue
            val template = LoggedStringTemplate.singleQuoted(literal.text)
            val value = LoggedStringTemplate.extract(template, exception.message()) ?: continue
            val at = variable.textRange.endOffset
            if (found.containsKey(at) && found[at] != value) return emptyList()
            found[at] = value
        }
        return found.map { (at, value) -> VariableHintProvider.Hint(at, value) }
    }
    private fun positional(call: ParameterListOwner): Boolean {
        val list = call.parameterList ?: return false
        val text = list.text; val start = list.textRange.startOffset
        var cursor = 0; var count = 0
        for (parameter in call.parameters) {
            val at = parameter.textRange.startOffset - start
            if (at < cursor || !(if (count++ == 0) FIRST_ARGUMENT else NEXT_ARGUMENT).matches(text.substring(cursor, at))) return false
            cursor = parameter.textRange.endOffset - start
        }
        return ARGUMENT_END.matches(text.substring(cursor))
    }
    private fun resolvedClass(reference: ClassReference?): PhpClass? {
        val result = reference?.multiResolve(false)?.singleOrNull()?.takeIf { it.isValidResult } ?: return null
        return when (val element = result.element) {
            is PhpClass -> element
            is Method -> element.containingClass.takeIf { element.name.equals("__construct", true) }
            else -> null
        }
    }
    private fun unsaved(element: PsiElement): Boolean = PsiDocumentManager.getInstance(element.project)
        .getDocument(element.containingFile)?.let { FileDocumentManager.getInstance().isDocumentUnsaved(it) } == true
    private fun directCall(statement: PsiElement): MethodReference? = PsiTreeUtil.findChildOfType(statement, MethodReference::class.java)
        ?.takeIf { statement.text.trim() == it.text + ";" }
    private fun sameClass(a: String, b: String) = a.removePrefix("\\").equals(b.removePrefix("\\"), true)
    private fun unmodifiedParent(initial: PhpClass): Boolean {
        var cls = initial
        val seen = hashSetOf<String>()
        repeat(32) {
            cls = cls.superClasses.singleOrNull() ?: return false
            if (!seen.add(cls.fqn)) return false
            if (sameClass(cls.fqn, "RuntimeException") || sameClass(cls.fqn, "Exception")) return true
            if (unsaved(cls) || cls.ownConstructor != null || hasTraitConstructor(cls, hashSetOf())) return false
        }
        return false
    }
    private fun hasTraitConstructor(cls: PhpClass, seen: MutableSet<String>): Boolean {
        if (unsaved(cls) || !seen.add(cls.fqn) || cls.traits.size != cls.traitNames.size) return true
        return cls.traits.any { it.ownConstructor != null || hasTraitConstructor(it, seen) }
    }
    companion object {
        private val VARIABLE = Regex("""\$[A-Za-z_][A-Za-z0-9_]*""")
        private val FIRST_ARGUMENT = Regex("""\s*\(?\s*""")
        private val NEXT_ARGUMENT = Regex("""\s*,\s*""")
        private val ARGUMENT_END = Regex("""\s*,?\s*\)?\s*""")
    }
}
