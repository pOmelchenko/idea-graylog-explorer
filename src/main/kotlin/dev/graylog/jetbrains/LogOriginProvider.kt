package dev.graylog.jetbrains

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** Optional language adapter. Returns absolute local source paths, never deployed paths or PSI objects. */
interface LogOriginProvider {
    data class Candidate(val localPath: Path, val line: Int, val description: String)
    /** Runs in a committed smart read action. Candidates must refer to project source files. */
    fun candidates(project: Project, symbol: LogOriginMetadata.Symbol, event: LogEvent, options: LogNavigationOptions): List<Candidate>
    companion object {
        @JvmField val EP: ExtensionPointName<LogOriginProvider> = ExtensionPointName.create("dev.omelchenko.graylogexplorer.logOriginProvider")
    }
}
