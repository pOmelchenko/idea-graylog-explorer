package dev.graylog.jetbrains;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Delivers canceled completions deliberately: real network/UI queues can finish out of order. */
final class ControllerTests {
    private static int assertions;
    static int run() {
        sources(); index();
        return assertions;
    }
    private static Connection connection() {
        Connection value = new Connection(); value.id = "fixture"; value.name = "Fixture"; value.apiUrl = "https://logs.example.org/api";
        return value;
    }
    private static SearchRequest scope(String query) {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        return new SearchRequest(query, "stream", from, from.plusSeconds(900), 0, 100);
    }
    private static void sources() {
        Queue tasks = new Queue(); Api api = new Api(); AtomicLong time = new AtomicLong(); AtomicInteger changes = new AtomicInteger();
        SourceCatalog catalog = new SourceCatalog(tasks, ignored -> api, time::get, changes::incrementAndGet);
        Connection connection = connection(); SearchRequest first = scope("first"), second = scope("second");
        catalog.load(connection, first, List.of(), false);
        eq(true, catalog.state().getLoading()); eq(1, tasks.jobs.size());
        catalog.load(connection, first.page(100), List.of(), false); eq(1, tasks.jobs.size());
        tasks.finish(0); eq("first", catalog.state().getValues().values().getFirst().name()); eq(false, catalog.state().getLoading());
        catalog.load(connection, first, List.of(), false); eq(1, tasks.jobs.size());
        time.set(30_001); catalog.load(connection, first, List.of(), false); eq(2, tasks.jobs.size());
        catalog.load(connection, second, List.of(), false); eq(true, tasks.jobs.get(1).future.isCancelled());
        tasks.finish(1); eq(true, catalog.state().getLoading()); eq(null, catalog.state().getValues());
        tasks.finish(2); eq("second", catalog.state().getValues().values().getFirst().name());
        connection.credentialId = "rotated"; catalog.load(connection, second, List.of(), false); eq(4, tasks.jobs.size());
        tasks.finish(3);
        catalog.load(connection, second, List.of(), true); eq(5, tasks.jobs.size()); tasks.finish(4);

        api.failSources = true;
        var a = new com.google.gson.JsonObject(); a.addProperty("source", "worker");
        var b = new com.google.gson.JsonObject(); b.addProperty("source", "api");
        catalog.load(connection, scope("failure"), List.of(new LogEvent("1", "", a), new LogEvent("2", "", a), new LogEvent("3", "", b)), false);
        tasks.finish(5);
        eq("aggregation unavailable", catalog.state().getError());
        eq("worker", catalog.state().getValues().values().get(1).name()); eq(2L, catalog.state().getValues().values().get(1).count());
        eq(true, catalog.state().getSummary().contains("loaded-page"));
        api.failSources = false;
        catalog.load(connection, second, List.of(), true); int before = changes.get();
        catalog.close(); eq(true, tasks.jobs.get(6).future.isCancelled());
        tasks.finish(6); eq(before, changes.get()); eq(null, catalog.state().getValues());
        catalog.load(connection, first, List.of(), false); eq(7, tasks.jobs.size());
    }
    private static void index() {
        Queue tasks = new Queue(); Api api = new Api(); AtomicInteger changes = new AtomicInteger();
        PeriodIndexController controller = new PeriodIndexController(tasks, ignored -> api, state -> changes.incrementAndGet());
        Connection connection = connection(); SearchRequest first = scope("first"), second = scope("second"); Path root = Path.of("/fixture");
        controller.cancel(); eq(PeriodIndexController.Phase.IDLE, controller.state().getPhase());
        controller.start(connection, first, root, false); eq(PeriodIndexController.Phase.LOADING, controller.state().getPhase());
        controller.start(connection, first.page(100), root, false); eq(1, tasks.jobs.size());
        controller.start(connection, second, root, false); eq(true, tasks.jobs.getFirst().future.isCancelled());
        tasks.finish(0); tasks.drainUi(); eq(0L, controller.state().getProgress()); eq(null, controller.state().getResult());
        tasks.finish(1); int completedChanges = changes.get(); tasks.drainUi(); eq(completedChanges, changes.get());
        eq(PeriodIndexController.Phase.READY, controller.state().getPhase());
        eq(6L, controller.state().getResult().messages());
        controller.start(connection, second, root, false); eq(2, tasks.jobs.size());
        connection.apiUrl = "https://other.example.org/api";
        controller.start(connection, second, root, false); eq(3, tasks.jobs.size()); tasks.finish(2);

        api.failIndex = true; controller.start(connection, first, root, false); tasks.finish(3);
        eq(PeriodIndexController.Phase.FAILED, controller.state().getPhase()); eq("export incomplete", controller.state().getError());
        api.failIndex = false; controller.start(connection, first, root, false); eq(5, tasks.jobs.size()); tasks.finish(4);
        eq(PeriodIndexController.Phase.READY, controller.state().getPhase());
        controller.cancel(); eq(PeriodIndexController.Phase.READY, controller.state().getPhase());
        controller.start(connection, first, root, true); controller.cancel();
        eq(PeriodIndexController.Phase.CANCELED, controller.state().getPhase());
        tasks.finish(5); tasks.drainUi(); eq(PeriodIndexController.Phase.CANCELED, controller.state().getPhase());
        controller.clear(); eq(PeriodIndexController.Phase.IDLE, controller.state().getPhase()); eq(null, controller.state().getResult());
        controller.start(connection, second, root, false); int before = changes.get(); controller.close();
        tasks.finish(6); tasks.drainUi(); eq(before, changes.get()); eq(PeriodIndexController.Phase.IDLE, controller.state().getPhase());
    }
    private static final class Api implements GraylogApi {
        boolean failSources, failIndex;
        @Override public List<Stream> streams() { return List.of(); }
        @Override public Result search(SearchRequest request) { return new Result(List.of(), 0); }
        @Override public Sources sources(SearchRequest request, String field) throws IOException {
            if (failSources) throw new IOException("aggregation unavailable");
            return new Sources(List.of(new SourceValue(request.query(), 42)), false);
        }
        @Override public PeriodIndex indexPeriod(SearchRequest request, Connection connection, Path root, LongConsumer progress) throws IOException {
            progress.accept(500);
            if (failIndex) throw new IOException("export incomplete");
            return new PeriodIndex(List.of(), request.query().length(), 0);
        }
        @Override public void close() { }
    }
    private static final class Queue implements TaskRunner {
        final List<Job<?>> jobs = new ArrayList<>();
        final List<Runnable> ui = new ArrayList<>();
        @Override public <T> Future<?> submit(Callable<T> work, Consumer<T> success, Consumer<Throwable> failure) {
            Job<T> job = new Job<>(work, success, failure); jobs.add(job); return job.future;
        }
        @Override public void ui(Runnable action) { ui.add(action); }
        void finish(int index) { jobs.get(index).finish(); }
        void drainUi() { List<Runnable> pending = List.copyOf(ui); ui.clear(); pending.forEach(Runnable::run); }
    }
    private static final class Job<T> {
        final Callable<T> work;
        final Consumer<T> success;
        final Consumer<Throwable> failure;
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Job(Callable<T> work, Consumer<T> success, Consumer<Throwable> failure) { this.work = work; this.success = success; this.failure = failure; }
        void finish() {
            try { success.accept(work.call()); }
            catch (Exception error) { failure.accept(error); }
        }
    }
    private static void eq(Object expected, Object actual) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
