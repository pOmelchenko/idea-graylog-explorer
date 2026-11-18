package dev.graylog.jetbrains

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/** Optional language adapter. Runs in a committed smart read action; returns no PSI objects. */
interface LogOriginProvider {
    data class Candidate(val location: SourceLocation, val description: String)
    fun candidates(project: Project, symbol: LogOriginMetadata.Symbol, event: LogEvent, options: LogNavigationOptions): List<Candidate>
    companion object {
        @JvmField val EP: ExtensionPointName<LogOriginProvider> = ExtensionPointName.create("dev.omelchenko.graylogexplorer.logOriginProvider")
    }
}
