package dev.graylog.jetbrains

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.util.UUID

/** Background mutation boundary that survives closure of the initiating view. */
@Service(Service.Level.APP)
class ConnectionManager {
    private val mutation = Any()
    private val settings = object : ConnectionUpdates.Settings {
        override fun snapshot() = GraylogIdeSettings.get().state
        override fun commit(expected: GraylogIdeSettings.Data, value: GraylogIdeSettings.Data) = GraylogIdeSettings.get().compareAndReplace(expected, value)
    }
    fun replace(expected: GraylogIdeSettings.Data, value: GraylogIdeSettings.Data, tokens: Map<String, CharArray>): GraylogIdeSettings.Data =
        synchronized(mutation) { ConnectionUpdates.replace(expected, value, tokens, GraylogCredentials.STORE, settings) }
    fun save(entered: ServerConnection, token: CharArray): ServerConnection = synchronized(mutation) {
        val before = settings.snapshot(); val after = before.copy(); val server = entered.copy()
        if (token.isNotEmpty()) server.credentialId = UUID.randomUUID().toString()
        val position = after.connections.indexOfFirst { it.id == server.id }
        if (position < 0) after.connections.add(server) else after.connections[position] = server
        after.removedConnections.remove(server.id)
        ConnectionUpdates.replace(before, after, if (token.isEmpty()) emptyMap() else mapOf(server.id to token), GraylogCredentials.STORE, settings)
        server.copy()
    }
    fun remove(id: String) = synchronized(mutation) {
        val before = settings.snapshot(); val after = before.copy()
        after.connections.removeIf { it.id == id }
        if (after.options.defaultConnection == id) after.options.defaultConnection = ""
        ConnectionUpdates.replace(before, after, emptyMap(), GraylogCredentials.STORE, settings)
        Unit
    }
    companion object { @JvmStatic fun get(): ConnectionManager = ApplicationManager.getApplication().getService(ConnectionManager::class.java) }
}
