package dev.graylog.jetbrains;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongConsumer;

/** Operations used by Explorer; implementations own transport and authentication. */
public interface GraylogApi extends AutoCloseable {
    record Stream(String id, String title) { @Override public String toString() { return title; } }
    record Result(List<LogEvent> events, long total) { public Result { events = List.copyOf(events); } }
    record PeriodIndex(List<ExceptionSites.Site> sites, long messages, long exceptions) { public PeriodIndex { sites = List.copyOf(sites); } }
    record SourceValue(String name, long count) { }
    record Sources(List<SourceValue> values, boolean limited) { public Sources { values = List.copyOf(values); } }

    List<Stream> streams() throws IOException, InterruptedException;
    Result search(SearchRequest request) throws IOException, InterruptedException;
    Sources sources(SearchRequest scope, String field) throws IOException, InterruptedException;
    PeriodIndex indexPeriod(SearchRequest request, OperationConfig config, Path root, LongConsumer progress)
            throws IOException, InterruptedException;
    @Override void close();

    @FunctionalInterface interface Factory { GraylogApi open(OperationConfig config) throws Exception; }
}
