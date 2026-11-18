package dev.graylog.jetbrains

import java.nio.file.Path
import java.util.function.Supplier

/** Cross-operation policy, independent of Swing: one submitted window drives facets and exception marks. */
class ExplorerCoordinator(
    private val search: SearchController,
    private val sources: SourceCatalog,
    private val index: PeriodIndexController,
    private val root: Supplier<Path?>,
) {
    private var observed: SearchController.Page? = null
    fun searchChanged(state: SearchController.State, current: OperationConfig?, marks: Boolean, automatic: Boolean) {
        if (state.phase == SearchController.Phase.LOADING) {
            state.requested?.let { if (!index.matches(it.config, it.request())) index.clear() }
        }
        if (state.page !== observed) {
            observed = state.page
            val page = state.page ?: return
            if (state.phase == SearchController.Phase.READY && state.requested === page.session && current != null) {
                sources.load(current, page.session.scope.facet(page.session.limit), page.result.events(), false)
                if (marks && automatic) index.start(current, page.session.request(), root.get(), false)
            }
        }
    }
    fun settingsChanged(before: OperationConfig, after: OperationConfig, marks: Boolean, automatic: Boolean,
                        selectedSources: Set<String>, highlightModeChanged: Boolean, indexPolicyChanged: Boolean): SettingsImpact {
        val impact = SettingsImpact.between(before, after)
        if (impact.sources()) sources.clear()
        if (!marks || impact.index()) index.clear()
        val submitted = search.state().requested
        if (impact.page() && submitted != null) {
            val scope = submitted.scope
            search.load(SearchController.Session(after, SearchScope(scope.query(), scope.streamId(), scope.from(), scope.to(), after.server().serviceField(), selectedSources), submitted.offset, submitted.limit))
        } else if (marks && search.state().phase != SearchController.Phase.LOADING && search.state().page != null &&
                   (impact.index() || highlightModeChanged || indexPolicyChanged && automatic)) {
            if (automatic) index.start(after, search.state().page!!.session.request(), root.get(), impact.index()) else index.clear()
        }
        return impact
    }
}
