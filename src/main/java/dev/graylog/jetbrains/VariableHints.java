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
    private final java.util.Map<Editor, List<Inlay<?>>> owned = new java.util.HashMap<>();
    private final java.util.concurrent.ConcurrentMap<Editor, Long> revisions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean disposed;
    VariableHints(Project project) { this.project = project; }
    void clear() { revisions.clear(); owned.values().forEach(values -> values.forEach(Inlay::dispose)); owned.clear(); }
    void clear(Editor editor) {
        revisions.remove(editor); var values = owned.remove(editor); if (values != null) values.forEach(Inlay::dispose);
    }
    void invalidateProofs() {
        revisions.clear();
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread()) clear();
        else com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> { if (!disposed) clear(); });
    }
    void refresh(Editor editor, List<Target> targets) {
        clear(editor);
        if (targets.isEmpty() || VariableHintProvider.EP.getExtensionList().isEmpty()) return;
        long generation = revision.incrementAndGet(); revisions.put(editor, generation);
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
                .expireWhen(() -> disposed || !java.util.Objects.equals(revisions.get(editor), generation))
                .finishOnUiThread(ModalityState.any(), results -> {
                    if (disposed || !java.util.Objects.equals(revisions.get(editor), generation)) return;
                    for (Result result : results) {
                        Editor targetEditor = result.target().editor();
                        if (targetEditor.isDisposed() || targetEditor.getDocument().getModificationStamp() != result.stamp()) continue;
                        boolean added = false;
                        for (var hint : result.hints()) {
                            if (hint.offset() < 0 || hint.offset() > targetEditor.getDocument().getTextLength()) continue;
                            var inlay = targetEditor.getInlayModel().addInlineElement(hint.offset(), true,
                                    new ExceptionInlineRenderer(LoggedStringTemplate.display(hint.value()), true));
                            if (inlay != null) { owned.computeIfAbsent(targetEditor, ignored -> new ArrayList<>()).add(inlay); added = true; }
                        }
                        if (added && result.target().fallback() != null && result.target().fallback().isValid()) result.target().fallback().dispose();
                    }
                }).submit(AppExecutorUtil.getAppExecutorService());
    }
    @Override public void dispose() { disposed = true; clear(); }
}
