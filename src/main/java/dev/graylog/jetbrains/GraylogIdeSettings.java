package dev.graylog.jetbrains;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service(Service.Level.APP)
@State(name = "dev.graylog.jetbrains.IdeSettings", storages = @Storage("graylog-explorer.xml"))
public final class GraylogIdeSettings implements PersistentStateComponent<GraylogIdeSettings.Data> {
    public static final class Data {
        public List<ServerConnection> connections = new ArrayList<>();
        public IdeOptions options = new IdeOptions();
        public List<String> removedConnections = new ArrayList<>();
        public Data copy() { return SettingsValues.copy(this, Data.class); }
        public void validate() {
            options.validate(); java.util.Set<String> ids = new java.util.HashSet<>();
            for (ServerConnection connection : connections) {
                connection.validate(); if (!ids.add(connection.id)) throw new IllegalArgumentException("Duplicate connection ID.");
            }
            if (!options.defaultConnection.isBlank() && !ids.contains(options.defaultConnection)) throw new IllegalArgumentException("Choose an available default connection.");
        }
    }
    private Data data = new Data();
    public static GraylogIdeSettings get() { return ApplicationManager.getApplication().getService(GraylogIdeSettings.class); }
    @Override public synchronized @org.jetbrains.annotations.NotNull Data getState() { return data.copy(); }
    @Override public synchronized void loadState(Data state) { data = state.copy(); }
    void compareAndReplace(Data expected, Data state) {
        state.validate(); Data before, after;
        synchronized (this) {
            if (!SettingsValues.same(data, expected)) throw new ConnectionUpdates.Conflict("Graylog IDE settings changed while saving credentials. Reopen Settings before applying your changes.");
            before = data.copy();
            for (var old : data.connections) if (state.connections.stream().noneMatch(c -> c.id.equals(old.id)) && !state.removedConnections.contains(old.id)) state.removedConnections.add(old.id);
            data = state.copy(); after = data.copy();
        }
        SettingsEvents.ide(SettingsChange.ide(before, after));
    }
    public void replace(Data state) {
        state.validate(); Data before, after;
        synchronized (this) {
            before = data.copy();
            for (ServerConnection old : data.connections) if (state.connections.stream().noneMatch(c -> c.id.equals(old.id))
                    && !state.removedConnections.contains(old.id)) state.removedConnections.add(old.id);
            data = state.copy(); after = data.copy();
        }
        SettingsEvents.ide(SettingsChange.ide(before, after));
    }
    public void save(ServerConnection server) {
        Data before, after;
        synchronized (this) {
            before = data.copy();
            int position = -1;
            for (int i = 0; i < data.connections.size(); i++) if (data.connections.get(i).id.equals(server.id)) { position = i; break; }
            if (position < 0) data.connections.add(server.copy()); else data.connections.set(position, server.copy());
            data.removedConnections.remove(server.id); after = data.copy();
        }
        SettingsEvents.ide(SettingsChange.ide(before, after));
    }
    public void remove(String id) {
        Data before, after;
        synchronized (this) {
            before = data.copy();
            data.connections.removeIf(c -> c.id.equals(id));
            if (!data.removedConnections.contains(id)) data.removedConnections.add(id);
            if (data.options.defaultConnection.equals(id)) data.options.defaultConnection = "";
            after = data.copy();
        }
        SettingsEvents.ide(SettingsChange.ide(before, after));
    }

    static Map<String, String> mergeLegacy(Data global, List<Connection> legacy) {
        Map<String, String> remap = new LinkedHashMap<>();
        for (Connection old : legacy) {
            ServerConnection server = ServerConnection.from(old);
            ServerConnection existing = global.connections.stream().filter(c -> c.id.equals(old.id)).findFirst().orElse(null);
            if (existing != null && !existing.apiUrl.equals(server.apiUrl)) {
                server.id = UUID.nameUUIDFromBytes((old.id + "\n" + old.apiUrl).getBytes(StandardCharsets.UTF_8)).toString();
                server.credentialId = old.id; // Keep access to the existing Password Safe entry; no secret read on EDT.
            }
            String id = server.id;
            if (!global.removedConnections.contains(old.id) && !global.removedConnections.contains(id)
                    && global.connections.stream().noneMatch(c -> c.id.equals(id))) global.connections.add(server);
            remap.put(old.id, id);
        }
        return remap;
    }
    synchronized void migrateProject(GraylogSettings.Data local) { GraylogSettings.migrate(local, data); }
}
