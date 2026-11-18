package dev.graylog.jetbrains;

import java.util.*;

final class ConnectionUpdatesTests {
    private static int assertions;
    static int run() {
        var before = new GraylogIdeSettings.Data(); var server = new ServerConnection();
        server.id = "server"; server.name = "Fixture"; server.apiUrl = "https://logs.example.org/api"; server.credentialId = "old"; before.connections.add(server);
        var after = before.copy(); after.connections.getFirst().credentialId = "new";
        var values = new HashMap<String, String>(); values.put("old", "old-value");
        CredentialUpdate.Store store = new CredentialUpdate.Store() {
            public String load(String key) { return values.get(key); }
            public void save(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        };
        var settings = new Settings(before);
        ConnectionUpdates.replace(before, after, Map.of("server", "new-value".toCharArray()), store, settings);
        eq(Map.of("new", "new-value"), values); eq("new", settings.value.connections.getFirst().credentialId);
        // A conflict after OS-store writes rolls back both the newly written key and the removed old key.
        values.clear(); values.put("old", "old-value"); settings = new Settings(before); settings.conflict = true;
        try { ConnectionUpdates.replace(before, after, Map.of("server", "new-value".toCharArray()), store, settings); throw new AssertionError("Conflict accepted"); }
        catch (IllegalStateException expected) { assertions++; }
        eq(Map.of("old", "old-value"), values); eq("old", settings.value.connections.getFirst().credentialId);
        var unrelated = before.copy(); unrelated.options.liveIntervalSeconds++;
        settings = new Settings(unrelated);
        try { ConnectionUpdates.replace(before, after, Map.of("server", "new-value".toCharArray()), store, settings); throw new AssertionError("Stale draft accepted"); }
        catch (IllegalStateException expected) { assertions++; }
        eq(Map.of("old", "old-value"), values);
        var racing = new Settings(before); racing.afterCommitChange = true;
        var committed = ConnectionUpdates.replace(before, after, Map.of("server", "new-value".toCharArray()), store, racing);
        eq(before.options.liveIntervalSeconds, committed.options.liveIntervalSeconds);
        eq(before.options.liveIntervalSeconds + 1, racing.value.options.liveIntervalSeconds);
        CredentialUpdate.Store failing = new CredentialUpdate.Store() {
            public String load(String key) { throw new IllegalStateException("sensitive-fixture-value"); }
            public void save(String key, String value) { throw new AssertionError("No writes after failed credential read"); }
        };
        try { ConnectionUpdates.replace(before, after, Map.of(), failing, new Settings(before)); throw new AssertionError("Store failure swallowed"); }
        catch (IllegalStateException expected) { eq(false, expected.getMessage().contains("sensitive-fixture-value")); }
        return assertions;
    }
    private static final class Settings implements ConnectionUpdates.Settings {
        GraylogIdeSettings.Data value; boolean conflict, afterCommitChange;
        Settings(GraylogIdeSettings.Data value) { this.value = value.copy(); }
        public GraylogIdeSettings.Data snapshot() { return value.copy(); }
        public void commit(GraylogIdeSettings.Data expected, GraylogIdeSettings.Data after) {
            if (conflict) throw new ConnectionUpdates.Conflict("Concurrent change"); value = after.copy(); if (afterCommitChange) value.options.liveIntervalSeconds++;
        }
    }
    private static void eq(Object a, Object b) { assertions++; if (!Objects.equals(a, b)) throw new AssertionError("Expected " + a + ", got " + b); }
}
