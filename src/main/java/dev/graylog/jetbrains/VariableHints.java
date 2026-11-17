package dev.graylog.jetbrains;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.util.ArrayList;
import java.util.List;

/** Keeps selected-event values separate from the aggregated period markers. */
final class VariableHints implements Disposable {
    record Target(Editor editor, int line, List<ExceptionSites.Detail> exceptions, Inlay<?> fallback) { }
    private record Result(Target target, long stamp, List<VariableHintProvider.Hint> hints) { }
    private final Project project;
    private final List<Inlay<?>> owned = new ArrayList<>();
    private volatile long revision;
    private volatile boolean disposed;
    VariableHints(Project project) { this.project = project; }
    void clear() { revision++; owned.forEach(Inlay::dispose); owned.clear(); }
    void refresh(List<Target> targets) {
        clear();
        if (targets.isEmpty() || VariableHintProvider.EP.getExtensionList().isEmpty()) return;
        long generation = revision;
        ReadAction.nonBlocking(() -> {
            List<Result> results = new ArrayList<>();
            for (Target target : targets) {
                if (target.editor().isDisposed()) continue;
                var document = target.editor().getDocument();
                var manager = FileDocumentManager.getInstance();
                var file = manager.getFile(document);
                if (file == null || manager.isDocumentUnsaved(document)) continue;
                for (var provider : VariableHintProvider.EP.getExtensionList()) {
                    var hints = provider.hints(project, file, document, target.line(), target.exceptions());
                    if (!hints.isEmpty()) { results.add(new Result(target, document.getModificationStamp(), hints)); break; }
                }
            }
            return results;
        }).inSmartMode(project).withDocumentsCommitted(project).expireWith(this)
                .expireWhen(() -> disposed || generation != revision)
                .finishOnUiThread(ModalityState.any(), results -> {
                    if (disposed || generation != revision) return;
                    for (Result result : results) {
                        Editor editor = result.target().editor();
                        if (editor.isDisposed() || editor.getDocument().getModificationStamp() != result.stamp()) continue;
                        boolean added = false;
                        for (var hint : result.hints()) {
                            if (hint.offset() < 0 || hint.offset() > editor.getDocument().getTextLength()) continue;
                            var inlay = editor.getInlayModel().addInlineElement(hint.offset(), true,
                                    new ExceptionInlineRenderer(LoggedStringTemplate.display(hint.value()), true));
                            if (inlay != null) { owned.add(inlay); added = true; }
                        }
                        if (added && result.target().fallback() != null && result.target().fallback().isValid()) result.target().fallback().dispose();
                    }
                }).submit(AppExecutorUtil.getAppExecutorService());
    }
    @Override public void dispose() { disposed = true; clear(); }
}
