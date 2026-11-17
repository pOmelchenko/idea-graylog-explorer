package dev.graylog.jetbrains

import java.util.concurrent.Future
import java.util.function.Consumer

/** Owns submitted requests, the last successful page and the independently displayed examples. */
class SearchController(
    private val tasks: TaskRunner,
    private val api: GraylogApi.Factory,
    private val changed: Consumer<State>,
) : AutoCloseable {
    enum class Phase { IDLE, LOADING, READY, FAILED }
    data class Session(val config: OperationConfig, val scope: SearchScope, val offset: Int, val limit: Int) {
        private val request = scope.request(offset, limit)
        fun request(): SearchRequest = request
    }
    data class Page(val session: Session, val result: GraylogApi.Result)
    sealed interface Display {
        data class Loaded(val page: Page) : Display
        data class Examples(val site: ExceptionSites.Site) : Display
    }
    data class State(
        val phase: Phase = Phase.IDLE,
        val requested: Session? = null,
        val page: Page? = null,
        val display: Display? = null,
        val error: String = "",
    ) {
        fun canPrevious() = phase != Phase.LOADING && display is Display.Loaded && page != null && page.session.offset > 0
        fun canNext(): Boolean {
            val loaded = page ?: return false
            val session = loaded.session
            return phase != Phase.LOADING && display is Display.Loaded &&
                session.offset + session.limit < loaded.result.total() && loaded.result.events().size == session.limit &&
                session.offset + 2 * session.limit <= 10_000
        }
        fun rows(): List<LogEvent> = when (val shown = display) {
            is Display.Loaded -> shown.page.result.events()
            is Display.Examples -> shown.site.events()
            null -> emptyList()
        }
    }
    private var current = State()
    private var future: Future<*>? = null
    private var generation = 0L
    private var closed = false
    fun state(): State = current
    fun load(session: Session) {
        if (closed) return
        val request = session.request() // Validate before changing the existing state.
        stop()
        val revision = generation
        publish(current.copy(phase = Phase.LOADING, requested = session, error = ""))
        future = tasks.submit({ api.open(session.config).use { it.search(request) } }, { result ->
            if (!closed && revision == generation) {
                future = null
                val page = Page(session, GraylogApi.Result(result.events().toList(), result.total()))
                publish(State(Phase.READY, session, page, Display.Loaded(page)))
            }
        }, { error ->
            if (!closed && revision == generation) {
                future = null
                publish(current.copy(phase = Phase.FAILED, error = error.message ?: error.javaClass.simpleName))
            }
        })
    }
    fun page(direction: Int) { current.page?.session?.config?.let { page(direction, it) } }
    fun page(direction: Int, config: OperationConfig) {
        if (closed || direction !in listOf(-1, 1) || direction < 0 && !current.canPrevious() || direction > 0 && !current.canNext()) return
        val session = current.page!!.session
        load(session.copy(config = config, offset = session.offset + direction * session.limit))
    }
    fun showExamples(site: ExceptionSites.Site) {
        if (closed || current.page == null) return
        stop(); publish(current.copy(phase = Phase.READY, display = Display.Examples(site), error = ""))
    }
    fun showLoadedPage() {
        val page = current.page ?: return
        if (closed) return
        stop(); publish(current.copy(phase = Phase.READY, display = Display.Loaded(page), error = ""))
    }
    fun cancel() {
        if (closed) return
        stop()
        if (current.phase == Phase.LOADING) publish(current.copy(phase = if (current.page == null) Phase.IDLE else Phase.READY))
    }
    fun clear() { stop(); if (!closed) publish(State()) }
    private fun stop() { generation++; future?.cancel(true); future = null }
    private fun publish(value: State) { current = value; changed.accept(value) }
    override fun close() { closed = true; stop(); current = State() }
}
