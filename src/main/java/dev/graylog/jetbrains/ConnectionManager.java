package dev.graylog.jetbrains;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import java.util.Map;
import java.util.UUID;

/** Background-only mutation boundary; persists the result even if the initiating view is closed. */
@Service(Service.Level.APP)
public final class ConnectionManager {
    private final Object mutation = new Object();
    public static ConnectionManager get() { return ApplicationManager.getApplication().getService(ConnectionManager.class); }
    private final ConnectionUpdates.Settings settings = new ConnectionUpdates.Settings() {
        public GraylogIdeSettings.Data snapshot() { return GraylogIdeSettings.get().getState(); }
        public void commit(GraylogIdeSettings.Data expected, GraylogIdeSettings.Data value) { GraylogIdeSettings.get().compareAndReplace(expected, value); }
    };
    public GraylogIdeSettings.Data replace(GraylogIdeSettings.Data expected, GraylogIdeSettings.Data value, Map<String, char[]> tokens) {
        synchronized (mutation) { return ConnectionUpdates.replace(expected, value, tokens, GraylogCredentials.STORE, settings); }
    }
    ServerConnection save(ServerConnection entered, char[] token) {
        synchronized (mutation) {
            var before = settings.snapshot(); var after = before.copy(); var server = entered.copy();
            if (token.length > 0) server.credentialId = UUID.randomUUID().toString();
            int position = -1;
            for (int i = 0; i < after.connections.size(); i++) if (after.connections.get(i).id.equals(server.id)) { position = i; break; }
            if (position < 0) after.connections.add(server); else after.connections.set(position, server);
            after.removedConnections.remove(server.id);
            ConnectionUpdates.replace(before, after, token.length == 0 ? Map.of() : Map.of(server.id, token), GraylogCredentials.STORE, settings);
            return server.copy();
        }
    }
    void remove(String id) {
        synchronized (mutation) {
            var before = settings.snapshot(); var after = before.copy(); after.connections.removeIf(c -> c.id.equals(id));
            if (after.options.defaultConnection.equals(id)) after.options.defaultConnection = "";
            ConnectionUpdates.replace(before, after, Map.of(), GraylogCredentials.STORE, settings);
        }
    }
}
