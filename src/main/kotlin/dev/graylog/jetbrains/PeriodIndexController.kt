package dev.graylog.jetbrains

import java.nio.file.Path
import java.util.concurrent.Future
import java.util.function.Consumer

/** Owns a period scan's cancellation, stale-result protection, retry and published result. */
class PeriodIndexController(
    private val tasks: TaskRunner,
    private val api: GraylogApi.Factory,
    private val changed: Consumer<State>,
) : AutoCloseable {
    enum class Phase { IDLE, LOADING, READY, FAILED, CANCELED }
    data class State(
        val phase: Phase = Phase.IDLE,
        val connection: Connection? = null,
        val request: SearchRequest? = null,
        val root: Path? = null,
        val result: GraylogApi.PeriodIndex? = null,
        val progress: Long = 0,
        val error: String = "",
    )
    private var current = State()
    private var future: Future<*>? = null
    private var generation = 0L
    private var closed = false
    fun state(): State = current
    fun matches(connection: Connection, request: SearchRequest) = current.connection != null && SettingsValues.same(current.connection, connection) && request.sameScope(current.request)

    fun start(connection: Connection, request: SearchRequest, root: Path?, force: Boolean) {
        if (closed || (!force && matches(connection, request) && current.phase in listOf(Phase.LOADING, Phase.READY))) return
        stop()
        val revision = generation
        val snapshot = connection.copy()
        publish(State(Phase.LOADING, snapshot, request, root))
        future = tasks.submit({
            api.open(snapshot).use { client -> client.indexPeriod(request, snapshot, root) { count ->
                tasks.ui { if (!closed && revision == generation && current.phase == Phase.LOADING) publish(current.copy(progress = count)) }
            } }
        }, { result ->
            if (!closed && revision == generation) { future = null; publish(current.copy(phase = Phase.READY, result = result)) }
        }, { error ->
            if (!closed && revision == generation) {
                future = null; publish(current.copy(phase = Phase.FAILED, error = error.message ?: error.javaClass.simpleName))
            }
        })
    }
    fun cancel() {
        if (closed || current.phase != Phase.LOADING) return
        stop(); publish(current.copy(phase = Phase.CANCELED, result = null))
    }
    fun clear() { stop(); if (!closed) publish(State()) }
    private fun stop() { generation++; future?.cancel(true); future = null }
    private fun publish(value: State) { current = value; changed.accept(value) }
    override fun close() { closed = true; stop(); current = State() }
}
