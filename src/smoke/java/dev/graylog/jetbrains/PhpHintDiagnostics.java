package dev.graylog.jetbrains;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.*;

final class PhpHintDiagnostics {
    static String describe(Project project, Editor editor) {
        StringBuilder out = new StringBuilder();
        out.append("dumb=").append(com.intellij.openapi.project.DumbService.isDumb(project));
        out.append(" providers=").append(VariableHintProvider.EP.getExtensionList());
        var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        out.append(" unsaved=").append(FileDocumentManager.getInstance().isDocumentUnsaved(editor.getDocument()));
        var psi = PsiManager.getInstance(project).findFile(file);
        for (NewExpression call : PsiTreeUtil.findChildrenOfType(psi, NewExpression.class)) {
            out.append("\ncall=").append(call.getText()).append(" parent=").append(call.getParent().getClass());
            var resolved = call.getClassReference().resolve(); out.append(" resolved=").append(resolved);
            PhpClass cls = resolved instanceof PhpClass c ? c : resolved instanceof Method m ? m.getContainingClass() : null;
            if (cls == null) continue;
            Method ctor = cls.getOwnConstructor();
            out.append(" ctor=").append(ctor);
            if (ctor == null) continue;
            for (var p : ctor.getParameters()) out.append(" param=").append(p.getName()).append(" type=").append(p.getDeclaredType()).append(" string=").append(com.jetbrains.php.lang.psi.resolve.types.PhpType.STRING.equals(p.getDeclaredType()));
            var body = PsiTreeUtil.getChildOfType(ctor, GroupStatement.class);
            out.append(" body=").append(body);
            if (body != null) for (var s : body.getStatements()) out.append(" stmt=").append(s.getClass()).append(":").append(s.getText());
            if (body != null) for (var r : PsiTreeUtil.findChildrenOfType(body, FunctionReference.class)) out.append(" ref=").append(r.getClass()).append(":").append(r.getText()).append(" resolves=").append(r.resolve());
            for (int i=0; i<8 && cls != null; i++,cls=cls.getSuperClass()) out.append(" ancestor=").append(cls.getFQN()).append(" ownCtor=").append(cls.getOwnConstructor()).append(" superclasses=").append(cls.getSuperClasses());
        }
        return out.toString();
    }
}
