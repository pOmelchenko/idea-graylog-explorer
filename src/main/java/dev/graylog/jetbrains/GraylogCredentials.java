package dev.graylog.jetbrains;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;

final class GraylogCredentials {
    private GraylogCredentials() { }
    private static CredentialAttributes attributes(String id) { return new CredentialAttributes("Graylog Explorer: " + id); }
    // These calls must only run on a background thread: OS password stores may block.
    static String load(String id) { return PasswordSafe.getInstance().getPassword(attributes(id)); }
    static String load(Connection connection) { return load(connection.credentialId.isBlank() ? connection.id : connection.credentialId); }
    static void save(String id, String token) { PasswordSafe.getInstance().setPassword(attributes(id), token); }
    static void remove(String id) { PasswordSafe.getInstance().setPassword(attributes(id), null); }
    static void removeIfUnused(String id) {
        if (GraylogIdeSettings.get().getState().connections.stream().noneMatch(c -> c.credentialKey().equals(id))) remove(id);
    }
}
