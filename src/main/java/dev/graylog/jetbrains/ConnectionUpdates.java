package dev.graylog.jetbrains;

import java.util.Map;

/** One credential/settings transaction shared by every connection editing entry point. */
final class ConnectionUpdates {
    static final class Conflict extends IllegalStateException { Conflict(String message) { super(message); } }
    interface Settings {
        GraylogIdeSettings.Data snapshot();
        void commit(GraylogIdeSettings.Data expected, GraylogIdeSettings.Data value);
    }
    private ConnectionUpdates() { }
    static GraylogIdeSettings.Data replace(GraylogIdeSettings.Data expected, GraylogIdeSettings.Data desired,
                                           Map<String, char[]> tokens, CredentialUpdate.Store credentials, Settings settings) {
        var value = desired.copy(); value.validate();
        if (!SettingsValues.same(expected, settings.snapshot())) throw new Conflict("Graylog IDE settings changed in another window. Reopen Settings before applying your changes.");
        try { new CredentialUpdate(expected.connections, value.connections, tokens).apply(credentials, () -> settings.commit(expected, value)); }
        catch (Conflict conflict) { throw conflict; }
        catch (RuntimeException failure) {
            // Password-store diagnostics can contain echoed values. Only our controlled conflict text reaches UI.
            throw new IllegalStateException("Could not update Password Safe; connection settings were not applied.", failure);
        }
        // Return this commit, not a later concurrent mutation that the UI has never displayed.
        return value.copy();
    }
}
