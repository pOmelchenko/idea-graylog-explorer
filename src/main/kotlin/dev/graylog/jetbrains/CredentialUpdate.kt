package dev.graylog.jetbrains

/** Snapshots every touched credential before writing and restores in reverse order on failure. */
class CredentialUpdate(before: List<ServerConnection>, after: List<ServerConnection>, tokens: Map<String, CharArray>) {
    interface Store {
        fun load(key: String): String?
        fun save(key: String, value: String?)
    }
    private val writes = linkedMapOf<String, String>()
    private val removals = linkedSetOf<String>()
    init {
        val retained = after.mapTo(hashSetOf()) { it.credentialKey() }
        after.forEach { server -> tokens[server.id]?.let { writes[server.credentialKey()] = String(it) } }
        before.forEach { server ->
            val deleted = after.none { it.id == server.id }
            // Unopened legacy projects may still use the old ID-based Password Safe entry.
            if (server.credentialKey() !in retained && (deleted || server.credentialId.isNotBlank())) removals.add(server.credentialKey())
        }
    }
    fun hasChanges() = writes.isNotEmpty() || removals.isNotEmpty()
    @JvmOverloads fun apply(store: Store, commit: Runnable = Runnable {}) {
        val previous = (writes.keys + removals).associateWith { store.load(it) }
        val touched = mutableListOf<String>()
        try {
            writes.forEach { (key, value) -> touched.add(key); store.save(key, value) }
            removals.forEach { key -> touched.add(key); store.save(key, null) }
            commit.run()
        } catch (error: RuntimeException) {
            touched.asReversed().forEach { key ->
                try { store.save(key, previous[key]) }
                catch (rollback: RuntimeException) { if (rollback !== error) error.addSuppressed(rollback) }
            }
            throw error
        }
    }
}
