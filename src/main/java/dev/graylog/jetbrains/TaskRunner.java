package dev.graylog.jetbrains;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Background work with completion/progress dispatched onto the owning UI thread. */
public interface TaskRunner {
    <T> Future<?> submit(Callable<T> work, Consumer<T> success, Consumer<Throwable> failure);
    void ui(Runnable action);
}
