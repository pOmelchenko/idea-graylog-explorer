package dev.graylog.jetbrains;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CredentialUpdateTests {
    private static int assertions;
    static int run() {
        ServerConnection legacy = server("legacy", ""), rotated = server("legacy", "new-key");
        Store store = new Store(); store.values.put("legacy", "old-fixture");
        new CredentialUpdate(List.of(legacy), List.of(rotated), Map.of("legacy", "new-fixture".toCharArray())).apply(store);
        eq("old-fixture", store.load("legacy")); eq("new-fixture", store.load("new-key"));

        ServerConnection current = server("current", "current-key"), replacement = server("current", "replacement-key");
        store.values.put("current-key", "current-fixture");
        store.fail = "current-key";
        CredentialUpdate update = new CredentialUpdate(List.of(current), List.of(replacement), Map.of("current", "replacement-fixture".toCharArray()));
        boolean rejected = false;
        try { update.apply(store); } catch (IllegalStateException error) { rejected = true; }
        eq(true, rejected); eq("current-fixture", store.load("current-key")); eq(null, store.load("replacement-key"));
        eq(false, new CredentialUpdate(List.of(current), List.of(current), Map.of()).hasChanges());

        store.failRead = "current-key"; int writes = store.writes;
        try { new CredentialUpdate(List.of(current), List.of(), Map.of()).apply(store); }
        catch (IllegalStateException expected) { }
        eq(writes, store.writes); store.failRead = "";

        ServerConnection shared = server("shared", "current-key");
        new CredentialUpdate(List.of(current, shared), List.of(shared), Map.of()).apply(store);
        eq("current-fixture", store.load("current-key"));
        new CredentialUpdate(List.of(shared), List.of(), Map.of()).apply(store);
        eq(null, store.load("current-key"));
        return assertions;
    }
    private static ServerConnection server(String id, String key) {
        ServerConnection server = new ServerConnection(); server.id = id; server.credentialId = key; return server;
    }
    private static final class Store implements CredentialUpdate.Store {
        final Map<String, String> values = new LinkedHashMap<>(); String fail = "", failRead = ""; int writes;
        @Override public String load(String key) {
            if (key.equals(failRead)) throw new IllegalStateException("fixture read failure");
            return values.get(key);
        }
        @Override public void save(String key, String value) {
            writes++;
            if (value == null) values.remove(key); else values.put(key, value);
            if (key.equals(fail)) { fail = ""; throw new IllegalStateException("fixture failure after mutation"); }
        }
    }
    private static void eq(Object expected, Object actual) {
        assertions++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Credential transaction invariant failed");
    }
}
