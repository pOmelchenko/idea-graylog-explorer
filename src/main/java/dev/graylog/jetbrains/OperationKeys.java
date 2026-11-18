package dev.graylog.jetbrains;

import java.util.List;
import java.util.Map;

/** Result identities exclude labels and browser URLs. Execution still receives the full snapshot. */
public final class OperationKeys {
    private OperationKeys() { }
    public record Endpoint(String id, String api, String credential) { }
    public record Page(Endpoint endpoint, OperationConfig.Transport transport, String service, Map<String, String> fields) { public Page { fields = Map.copyOf(fields); } }
    public record Sources(Endpoint endpoint, OperationConfig.Transport transport, String service) { }
    public record Index(Page page, List<OperationConfig.Mapping> mappings, String remote, String local, OperationConfig.Scan scan) { public Index { mappings = List.copyOf(mappings); } }
    public static Endpoint endpoint(OperationConfig c) { var s = c.server(); return new Endpoint(s.id(), s.apiUrl(), s.credentialKey()); }
    public static Page page(OperationConfig c) { return new Page(endpoint(c), c.transport(), c.server().serviceField(), c.server().fields()); }
    public static Sources sources(OperationConfig c) { return new Sources(endpoint(c), c.transport(), c.server().serviceField()); }
    public static Index index(OperationConfig c) { var s = c.server(); return new Index(page(c), s.mappings(), s.remoteRoot(), s.localRoot(), c.scan()); }
}
