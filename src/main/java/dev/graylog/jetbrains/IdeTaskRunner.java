package dev.graylog.jetbrains;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** IntelliJ threading/lifecycle adapter; controllers do not depend on the platform. */
final class IdeTaskRunner implements TaskRunner, Disposable {
    private final Project project;
    private final ExecutorService executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Graylog Explorer", 3);
    private volatile boolean disposed;

    IdeTaskRunner(Project project, Disposable parent) {
        this.project = project;
        Disposer.register(parent, this);
    }
    @Override public <T> Future<?> submit(Callable<T> work, Consumer<T> success, Consumer<Throwable> failure) {
        return executor.submit(() -> {
            try { T value = work.call(); ui(() -> success.accept(value)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (com.intellij.openapi.progress.ProcessCanceledException canceled) { throw canceled; }
            catch (CancellationException canceled) { /* Canceled work never becomes a user-facing error. */ }
            catch (Exception error) { if (!Thread.currentThread().isInterrupted()) ui(() -> failure.accept(error)); }
        });
    }
    @Override public void ui(Runnable action) {
        if (disposed || project.isDisposed()) return;
        ToolWindowManager.getInstance(project).invokeLater(() -> {
            if (!disposed && !project.isDisposed()) action.run();
        });
    }
    @Override public void dispose() { disposed = true; executor.shutdownNow(); }
}
