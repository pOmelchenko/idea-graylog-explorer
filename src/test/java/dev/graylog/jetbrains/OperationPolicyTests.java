package dev.graylog.jetbrains;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

final class OperationPolicyTests {
    private static int assertions;
    static int run() {
        var tasks = new ControllerTests.Queue(); var api = new ControllerTests.Api();
        var connection = new Connection(); connection.id = "one"; connection.apiUrl = "https://logs.example.org/api";
        var options = new IdeOptions(); var original = OperationConfig.capture(connection, options);
        var request = new SearchRequest("*", "stream", Instant.EPOCH, Instant.EPOCH.plusSeconds(900), 0, 100);
        var catalog = new SourceCatalog(tasks, c -> api, () -> 0, () -> {});
        var index = new PeriodIndexController(tasks, c -> api, state -> {});
        catalog.load(original, request, List.of(), false); tasks.finish(0);
        index.start(original, request, Path.of("/local"), false); tasks.finish(1);
        Object sources = catalog.state().getValues(), sites = index.state().getResult();
        connection.name = "Renamed"; connection.webUrl = "https://web.example.org";
        var renamed = OperationConfig.capture(connection, options);
        catalog.load(renamed, request.page(100), List.of(), false); index.start(renamed, request.page(100), Path.of("/local"), false);
        eq(2, tasks.jobs.size()); same(sources, catalog.state().getValues()); same(sites, index.state().getResult());
        connection.mappings.add(new PathMapping("/application", "/other"));
        var mapped = OperationConfig.capture(connection, options);
        catalog.load(mapped, request, List.of(), false); eq(2, tasks.jobs.size());
        index.start(mapped, request, Path.of("/local"), false); eq(3, tasks.jobs.size()); tasks.finish(2);
        index.start(mapped, request, Path.of("/different-project"), false); eq(4, tasks.jobs.size()); tasks.finish(3);
        streams(); coordinator(original, request); return assertions;
    }
    private static void streams() {
        var tasks = new ControllerTests.Queue(); var api = new ControllerTests.Api();
        var streams = new StreamCatalog(tasks, c -> api, () -> {});
        var connection = new Connection(); connection.id = "stream-server"; connection.apiUrl = "https://logs.example.org/api";
        var config = OperationConfig.capture(connection, new IdeOptions());
        streams.load(config); streams.load(config); eq(true, tasks.jobs.get(0).future.isCancelled());
        tasks.finish(0); eq(true, streams.entry(connection.id).getLoading());
        tasks.finish(1); eq(false, streams.entry(connection.id).getLoading());
        streams.load(config); streams.remove(connection.id); tasks.finish(2); eq(null, streams.entry(connection.id));
        streams.load(config); streams.clear(); streams.load(config); tasks.finish(3); eq(true, streams.entry(connection.id).getLoading());
        tasks.finish(4); eq(false, streams.entry(connection.id).getLoading());
        streams.load(config); streams.close(); tasks.finish(5); eq(Map.of(), streams.values());
    }
    private static void coordinator(OperationConfig config, SearchRequest request) {
        var tasks = new ControllerTests.Queue(); var api = new ControllerTests.Api();
        var current = new AtomicReference<>(config); var owner = new ExplorerCoordinator[1];
        var sources = new SourceCatalog(tasks, c -> api, () -> 0, () -> {});
        var index = new PeriodIndexController(tasks, c -> api, state -> {});
        var search = new SearchController(tasks, c -> api, state -> owner[0].searchChanged(state, current.get(), true, true));
        owner[0] = new ExplorerCoordinator(search, sources, index, () -> Path.of("/local"));
        var scope = new SearchScope("*", "stream", request.from(), request.to(), "source", Set.of());
        search.load(new SearchController.Session(config, scope, 0, 100)); tasks.finish(0); tasks.finish(1); tasks.finish(2);
        var page = search.state().getPage(); var period = index.state().getResult();
        var renamedServer = config.server().connection(); renamedServer.name = "Renamed";
        var renamed = OperationConfig.capture(renamedServer, new IdeOptions()); current.set(renamed);
        owner[0].settingsChanged(config, renamed, true, true, Set.of(), false, false);
        same(page, search.state().getPage()); same(period, index.state().getResult()); eq(3, tasks.jobs.size());
        search.page(1, renamed); tasks.finish(3); eq(4, tasks.jobs.size()); same(period, index.state().getResult());
        var nextOptions = new IdeOptions(); nextOptions.requestTimeoutSeconds++;
        var network = OperationConfig.capture(renamedServer, nextOptions); current.set(network);
        owner[0].settingsChanged(renamed, network, true, true, Set.of(), false, false);
        eq(SearchController.Phase.LOADING, search.state().getPhase());
        eq(request.from(), search.state().getRequested().getScope().from()); eq(100, search.state().getRequested().getOffset());
        tasks.finish(4); tasks.finish(5); tasks.finish(6); eq(PeriodIndexController.Phase.READY, index.state().getPhase());
        search.close(); sources.close(); index.close();
    }
    private static void same(Object a, Object b) { eq(true, a == b); }
    private static void eq(Object a, Object b) { assertions++; if (!Objects.equals(a, b)) throw new AssertionError("Expected " + a + ", got " + b); }
}
