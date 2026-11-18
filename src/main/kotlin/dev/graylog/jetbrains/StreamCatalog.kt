package dev.graylog.jetbrains

import java.util.concurrent.Future

/** Per-connection stream requests, retained results and stale-completion protection. */
class StreamCatalog(private val tasks: TaskRunner, private val api: GraylogApi.Factory, private val changed: Runnable) : AutoCloseable {
    data class Entry(val endpoint: OperationKeys.Endpoint, val streams: List<GraylogApi.Stream> = emptyList(), val loading: Boolean = false, val error: String = "")
    private val entries = linkedMapOf<String, Entry>()
    private val jobs = mutableMapOf<String, Future<*>>()
    private val revisions = mutableMapOf<String, Long>()
    private var revision = 0L
    private var closed = false
    fun entry(id: String): Entry? = entries[id]
    fun contains(id: String) = entries.containsKey(id)
    fun values(): Map<String, List<GraylogApi.Stream>> = entries.mapValues { it.value.streams }
    fun load(config: OperationConfig) {
        if (closed) return
        val id = config.server().id(); val key = OperationKeys.endpoint(config)
        jobs.remove(id)?.cancel(true); val sequence = ++revision; revisions[id] = sequence
        val retained = entries[id]?.takeIf { it.endpoint == key }?.streams.orEmpty()
        entries[id] = Entry(key, retained, loading = true); changed.run()
        jobs[id] = tasks.submit({ api.open(config).use { java.util.List.copyOf(it.streams()) } }, { result ->
            if (!closed && revisions[id] == sequence) { jobs.remove(id); entries[id] = Entry(key, result); changed.run() }
        }, { error ->
            if (!closed && revisions[id] == sequence) { jobs.remove(id); entries[id] = Entry(key, retained, error = error.message ?: error.javaClass.simpleName); changed.run() }
        })
    }
    fun remove(id: String) { jobs.remove(id)?.cancel(true); revisions.remove(id); entries.remove(id); if (!closed) changed.run() }
    fun invalidatePending() { jobs.values.forEach { it.cancel(true) }; jobs.clear(); revisions.clear() }
    fun clear() { invalidatePending(); entries.clear(); if (!closed) changed.run() }
    override fun close() { closed = true; invalidatePending(); entries.clear() }
}
