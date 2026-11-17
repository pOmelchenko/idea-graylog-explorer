package dev.graylog.jetbrains;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deeply immutable effective configuration of one operation; never contains a token or a persistent bean. */
public record OperationConfig(Server server, Transport transport, Scan scan) {
    public OperationConfig {
        Objects.requireNonNull(server); Objects.requireNonNull(transport); Objects.requireNonNull(scan);
    }
    public record Mapping(String remoteRoot, String localRoot) { }
    public record Transport(int connectTimeoutSeconds, int requestTimeoutSeconds) { }
    public record Scan(int exportBatchSize, int examplesPerLocation) { }
    public record Server(String id, String name, String apiUrl, String webUrl, String credentialId,
                         String remoteRoot, String localRoot, String serviceField,
                         List<Mapping> mappings, Map<String, String> fields) {
        public Server { mappings = List.copyOf(mappings); fields = Map.copyOf(fields); }
        public String credentialKey() { return credentialId.isBlank() ? id : credentialId; }
        /** Compatibility adapter for parsers/UI. The returned bean is a detached copy. */
        public Connection connection() {
            Connection value = new Connection();
            value.id = id; value.name = name; value.apiUrl = apiUrl; value.webUrl = webUrl;
            value.credentialId = credentialId; value.remoteRoot = remoteRoot; value.localRoot = localRoot;
            value.serviceField = serviceField;
            value.mappings = new java.util.ArrayList<>(mappings.stream().map(m -> new PathMapping(m.remoteRoot(), m.localRoot())).toList());
            value.fields = format(); return value;
        }
        public LogFormat format() {
            LogFormat value = new LogFormat();
            value.serviceField = fields.get("source"); value.exceptionTypeField = fields.get("exception_class");
            value.exceptionObjectField = fields.get("exception"); value.fileField = fields.get("file");
            value.lineField = fields.get("line"); value.stackTraceField = fields.get("stack_trace");
            value.requestIdField = fields.get("request_id"); value.traceIdField = fields.get("trace_id");
            return value;
        }
    }
    /** Validate at the execution boundary, so invalid imported settings become a task error, not an EDT exception. */
    public void validate() {
        Connection.normalizeApiUrl(server.apiUrl()); server.format().validate();
        IdeOptions values = new IdeOptions(); values.connectTimeoutSeconds = transport.connectTimeoutSeconds();
        values.requestTimeoutSeconds = transport.requestTimeoutSeconds(); values.exportBatchSize = scan.exportBatchSize();
        values.examplesPerLocation = scan.examplesPerLocation(); values.validate();
    }
    public static OperationConfig capture(Connection connection, IdeOptions options) {
        return new OperationConfig(new Server(connection.id, connection.name, connection.apiUrl,
                connection.webUrl, connection.credentialId, connection.remoteRoot, connection.localRoot, connection.serviceField,
                connection.mappings.stream().map(m -> new Mapping(m.remoteRoot, m.localRoot)).toList(), connection.fields.aliases()),
                new Transport(options.connectTimeoutSeconds, options.requestTimeoutSeconds),
                new Scan(options.exportBatchSize, options.examplesPerLocation));
    }
}
