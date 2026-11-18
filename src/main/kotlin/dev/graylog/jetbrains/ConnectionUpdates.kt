package dev.graylog.jetbrains

/** One credential/settings transaction shared by every connection editing entry point. */
object ConnectionUpdates {
    class Conflict(message: String) : IllegalStateException(message)
    interface Settings {
        fun snapshot(): GraylogIdeSettings.Data
        fun commit(expected: GraylogIdeSettings.Data, value: GraylogIdeSettings.Data)
    }
    @JvmStatic fun replace(expected: GraylogIdeSettings.Data, desired: GraylogIdeSettings.Data,
                           tokens: Map<String, CharArray>, credentials: CredentialUpdate.Store, settings: Settings): GraylogIdeSettings.Data {
        val value = desired.copy().also { it.validate() }
        if (!SettingsValues.same(expected, settings.snapshot())) throw Conflict("Graylog IDE settings changed in another window. Reopen Settings before applying your changes.")
        try { CredentialUpdate(expected.connections, value.connections, tokens).apply(credentials, Runnable { settings.commit(expected, value) }) }
        catch (conflict: Conflict) { throw conflict }
        catch (failure: RuntimeException) {
            // Password-store diagnostics can echo secrets; expose only controlled text to UI.
            throw IllegalStateException("Could not update Password Safe; connection settings were not applied.", failure)
        }
        return value.copy()
    }
}
