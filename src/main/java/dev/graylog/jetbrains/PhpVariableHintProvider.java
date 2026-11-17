package dev.graylog.jetbrains;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.*;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import java.util.*;

/** Source-backed PHP mapping: throw new ExactClass($var) -> sprintf('...%s...', $parameter). */
public final class PhpVariableHintProvider implements VariableHintProvider {
    @Override public List<Hint> hints(Project project, VirtualFile file, Document document, int line,
                                      List<ExceptionSites.Detail> exceptions) {
        if (!"php".equalsIgnoreCase(file.getExtension())) return List.of();
        var psi = PsiManager.getInstance(project).findFile(file);
        if (psi == null || line < 1 || line > document.getLineCount()) return List.of();
        int start = document.getLineStartOffset(line - 1), end = document.getLineEndOffset(line - 1);
        Set<NewExpression> calls = new LinkedHashSet<>();
        // Walk just this line, including a surrounding expression if the line begins inside it.
        for (int offset = start; offset < end;) {
            PsiElement leaf = psi.findElementAt(offset);
            if (leaf == null) break;
            NewExpression call = PsiTreeUtil.getParentOfType(leaf, NewExpression.class, false);
            if (call != null && call.getTextRange().getStartOffset() >= start) calls.add(call);
            offset = Math.max(offset + 1, leaf.getTextRange().getEndOffset());
        }
        Map<Integer, String> found = new LinkedHashMap<>();
        for (ExceptionSites.Detail exception : exceptions) {
            List<NewExpression> matching = calls.stream().filter(call -> {
                var ref = call.getClassReference();
                PhpClass cls = resolvedClass(ref);
                return cls != null && sameClass(cls.getFQN(), exception.type());
            }).toList();
            if (matching.size() != 1) continue;
            NewExpression call = matching.getFirst();
            if (!positional(call) || !(call.getParent() instanceof PhpThrowExpression thrown) || thrown.getArgument() != call) continue;
            PhpClass cls = resolvedClass(call.getClassReference());
            Method constructor = cls.getOwnConstructor();
            if (constructor == null || unsaved(constructor) || !unmodifiedParent(cls)) continue;
            GroupStatement body = PsiTreeUtil.getChildOfType(constructor, GroupStatement.class);
            if (body == null) continue;
            PsiElement[] statements = body.getStatements();
            if (statements.length == 0) continue;
            MethodReference parent = directCall(statements[statements.length - 1]);
            if (parent == null || !positional(parent) || !"__construct".equalsIgnoreCase(parent.getName()) || parent.getClassReference() == null
                    || !"parent".equalsIgnoreCase(parent.getClassReference().getText()) || parent.getParameters().length != 1) continue;
            if (!(parent.getParameter(0) instanceof FunctionReference format) || !positional(format) || format instanceof MethodReference
                    || !(format.resolve() instanceof Function function) || !sameClass(function.getFQN(), "sprintf")
                    || format.getParameters().length != 2 || !(format.getParameter(0) instanceof StringLiteralExpression literal)
                    || !literal.isSingleQuote() || !(format.getParameter(1) instanceof Variable parameterUse)) continue;
            Parameter[] parameters = constructor.getParameters();
            int index = -1;
            for (int i = 0; i < parameters.length; i++) if (parameters[i].getName().equals(parameterUse.getName())) index = i;
            if (index < 0 || parameters[index].isPassByRef() || parameters[index].isVariadic()
                    || !PhpType.STRING.equals(parameters[index].getDeclaredType())) continue;
            String parameterName = parameters[index].getName();
            if (PsiTreeUtil.findChildrenOfType(body, Variable.class).stream().filter(v -> v.getName().equals(parameterName)).count() != 1) continue;
            boolean simple = true;
            for (int i = 0; i < statements.length - 1; i++) {
                MethodReference setup = directCall(statements[i]);
                if (setup == null || setup.getClassReference() == null || !"$this".equals(setup.getClassReference().getText())
                        || Arrays.stream(setup.getParameters()).anyMatch(p -> !(p instanceof StringLiteralExpression s) || !s.isSingleQuote())) simple = false;
            }
            if (!simple || !(call.getParameter(index) instanceof Variable variable)
                    || !variable.getText().matches("\\$[A-Za-z_][A-Za-z0-9_]*")) continue;
            // Named/unpacked arguments, expressions and argument aliases require a separate mapping rule.
            if (Arrays.stream(call.getParameters()).anyMatch(p -> !(p instanceof Variable || p instanceof StringLiteralExpression))) continue;
            String template = LoggedStringTemplate.singleQuoted(literal.getText());
            String value = LoggedStringTemplate.extract(template, exception.message());
            if (value == null) continue;
            int offset = variable.getTextRange().getEndOffset();
            if (found.containsKey(offset) && !Objects.equals(found.get(offset), value)) return List.of();
            found.put(offset, value);
        }
        return found.entrySet().stream().map(e -> new Hint(e.getKey(), e.getValue())).toList();
    }

    private static boolean positional(ParameterListOwner call) {
        var list = call.getParameterList();
        if (list == null) return false;
        String text = list.getText();
        int start = list.getTextRange().getStartOffset(), cursor = 0, count = 0;
        for (var parameter : call.getParameters()) {
            int at = parameter.getTextRange().getStartOffset() - start;
            if (at < cursor || !text.substring(cursor, at).matches(count++ == 0 ? "\\s*\\(?\\s*" : "\\s*,\\s*")) return false;
            cursor = parameter.getTextRange().getEndOffset() - start;
        }
        return text.substring(cursor).matches("\\s*,?\\s*\\)?\\s*");
    }
    private static PhpClass resolvedClass(ClassReference reference) {
        if (reference == null) return null;
        var results = reference.multiResolve(false);
        if (results.length != 1 || !results[0].isValidResult()) return null;
        var element = results[0].getElement();
        if (element instanceof PhpClass cls) return cls;
        // In a new expression PHP PSI resolves the class reference to its constructor.
        if (element instanceof Method method && "__construct".equalsIgnoreCase(method.getName())) return method.getContainingClass();
        return null;
    }
    private static boolean unsaved(PsiElement element) {
        var document = com.intellij.psi.PsiDocumentManager.getInstance(element.getProject()).getDocument(element.getContainingFile());
        return document != null && com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().isDocumentUnsaved(document);
    }
    private static MethodReference directCall(PsiElement statement) {
        MethodReference call = PsiTreeUtil.findChildOfType(statement, MethodReference.class);
        return call != null && statement.getText().trim().equals(call.getText() + ";") ? call : null;
    }
    private static boolean sameClass(String a, String b) { return a.replaceFirst("^\\\\", "").equalsIgnoreCase(b.replaceFirst("^\\\\", "")); }
    private static boolean unmodifiedParent(PhpClass cls) {
        Set<String> seen = new HashSet<>();
        for (int depth = 0; depth < 32; depth++) {
            var parents = cls.getSuperClasses();
            if (parents.size() != 1) return false;
            cls = parents.iterator().next();
            if (!seen.add(cls.getFQN())) return false;
            if (sameClass(cls.getFQN(), "RuntimeException") || sameClass(cls.getFQN(), "Exception")) return true;
            if (unsaved(cls) || cls.getOwnConstructor() != null || hasTraitConstructor(cls, new HashSet<>())) return false;
        }
        return false;
    }
    private static boolean hasTraitConstructor(PhpClass cls, Set<String> seen) {
        if (unsaved(cls) || !seen.add(cls.getFQN()) || cls.getTraits().length != cls.getTraitNames().length) return true;
        for (PhpClass trait : cls.getTraits()) if (trait.getOwnConstructor() != null || hasTraitConstructor(trait, seen)) return true;
        return false;
    }
}
