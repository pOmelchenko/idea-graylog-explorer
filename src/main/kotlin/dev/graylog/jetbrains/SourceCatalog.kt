package dev.graylog.jetbrains

import java.util.concurrent.Future
import java.util.function.LongSupplier

/** Whole-scope source aggregation and its cache; no Swing, IDE services or credentials. */
class SourceCatalog(
    private val tasks: TaskRunner,
    private val api: GraylogApi.Factory,
    private val clock: LongSupplier,
    private val changed: Runnable,
) : AutoCloseable {
    data class State(
        val values: GraylogApi.Sources? = null,
        val loading: Boolean = false,
        val error: String = "",
        val summary: String = "Loading sources…",
    )
    private data class Cache(val context: String, val scope: SearchRequest, val result: GraylogApi.Sources, val created: Long)
    private var current = State()
    private var cache: Cache? = null
    private var scope: SearchRequest? = null
    private var context = ""
    private var fallback = emptyList<LogEvent>()
    private var future: Future<*>? = null
    private var generation = 0L
    private var closed = false

    fun state(): State = current

    fun load(connection: Connection, request: SearchRequest, page: List<LogEvent>, force: Boolean) {
        if (closed) return
        fallback = page.toList()
        val key = listOf(connection.id, connection.apiUrl, connection.credentialId, connection.serviceField).joinToString("\n")
        if (!force && current.loading && context == key && request.sameScope(scope)) return
        val cached = cache
        if (!force && cached != null && cached.context == key && request.sameScope(cached.scope) && clock.asLong - cached.created < 30_000) {
            stop()
            scope = request; context = key
            publish(State(cached.result, summary = summary(cached.result)))
            return
        }
        stop()
        scope = request; context = key
        val revision = generation
        val snapshot = connection.copy()
        publish(State(loading = true, summary = "Loading sources for this period…"))
        future = tasks.submit({ api.open(snapshot).use { it.sources(request, snapshot.serviceField) } }, { result ->
            if (closed || revision != generation) return@submit
            future = null
            cache = Cache(key, request, result, clock.asLong)
            publish(State(result, summary = summary(result)))
        }, { error ->
            if (closed || revision != generation) return@submit
            future = null
            val values = fallback.map { it.service(snapshot.serviceField) }.filter(String::isNotBlank)
                .groupingBy { it }.eachCount().toSortedMap().map { (name, count) -> GraylogApi.SourceValue(name, count.toLong()) }
            publish(State(GraylogApi.Sources(values, false), error = error.message ?: error.javaClass.simpleName,
                summary = "Aggregation unavailable · loaded-page sources only"))
        })
    }

    fun clear() {
        stop(); scope = null; context = ""; fallback = emptyList()
        if (!closed) publish(State())
    }
    private fun stop() { generation++; future?.cancel(true); future = null }
    private fun publish(value: State) { current = value; changed.run() }
    private fun summary(result: GraylogApi.Sources) = if (result.limited())
        "Top ${result.values().size} sources · use Sources / Add by name for others"
        else "${result.values().size} source(s) in this period"
    override fun close() { closed = true; stop(); cache = null; fallback = emptyList(); current = State() }
}
