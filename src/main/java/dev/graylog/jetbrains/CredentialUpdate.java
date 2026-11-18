package dev.graylog.jetbrains;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plans credential changes separately from Settings UI and restores touched entries on failure. */
final class CredentialUpdate {
    interface Store {
        String load(String key);
        void save(String key, String value);
    }
    private final Map<String, String> writes = new LinkedHashMap<>();
    private final Set<String> removals = new LinkedHashSet<>();

    CredentialUpdate(List<ServerConnection> before, List<ServerConnection> after, Map<String, char[]> tokens) {
        Set<String> retained = new LinkedHashSet<>();
        for (ServerConnection server : after) {
            retained.add(server.credentialKey());
            char[] token = tokens.get(server.id);
            if (token != null) writes.put(server.credentialKey(), new String(token));
        }
        for (ServerConnection server : before) {
            boolean deleted = after.stream().noneMatch(current -> current.id.equals(server.id));
            // Unopened legacy projects may still use the old ID-based Password Safe entry.
            if (!retained.contains(server.credentialKey()) && (deleted || !server.credentialId.isBlank())) removals.add(server.credentialKey());
        }
    }
    boolean hasChanges() { return !writes.isEmpty() || !removals.isEmpty(); }
    void apply(Store store) { apply(store, () -> {}); }
    void apply(Store store, Runnable commit) {
        Map<String, String> previous = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(writes.keySet()); keys.addAll(removals);
        // All reads precede writes: a failed snapshot must not alter the credential store.
        for (String key : keys) previous.put(key, store.load(key));
        List<String> touched = new ArrayList<>();
        try {
            for (var entry : writes.entrySet()) { touched.add(entry.getKey()); store.save(entry.getKey(), entry.getValue()); }
            for (String key : removals) { touched.add(key); store.save(key, null); }
            commit.run();
        } catch (RuntimeException error) {
            for (int i = touched.size() - 1; i >= 0; i--) {
                String key = touched.get(i);
                try { store.save(key, previous.get(key)); }
                catch (RuntimeException rollback) { if (rollback != error) error.addSuppressed(rollback); }
            }
            throw error;
        }
    }
}
