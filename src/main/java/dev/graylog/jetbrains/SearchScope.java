package dev.graylog.jetbrains;

import java.time.Instant;
import java.util.Set;

/** Submitted search window. The source facet intentionally excludes its own source selection. */
public record SearchScope(String query, String streamId, Instant from, Instant to, String serviceField, Set<String> sources) {
    public SearchScope {
        SearchRequest normalized = new SearchRequest(query, streamId, from, to, 0, 1);
        query = normalized.query(); streamId = normalized.streamId(); sources = Set.copyOf(sources);
    }
    public SearchRequest request(int offset, int limit) {
        SourceFilter filter = new SourceFilter(); filter.replace(sources);
        return new SearchRequest(filter.query(query, serviceField), streamId, from, to, offset, limit);
    }
    public SearchRequest facet(int limit) { return new SearchRequest(query, streamId, from, to, 0, limit); }
    public boolean matchesInput(String text, String stream, Set<String> selected) {
        String normalized = text == null || text.isBlank() ? "*" : text.trim();
        return query.equals(normalized) && streamId.equals(stream) && sources.equals(selected);
    }
}
