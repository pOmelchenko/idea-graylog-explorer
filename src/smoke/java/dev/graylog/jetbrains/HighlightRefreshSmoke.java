package dev.graylog.jetbrains;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Measures actual EDT refresh work; assertions use identities/work counts rather than flaky time limits. */
final class HighlightRefreshSmoke {
    static void run(Project project, GraylogPanel panel, Path report) throws Exception {
        ExceptionHighlights highlights = (ExceptionHighlights) panel.decorations();
        var index = SmokeUi.index(panel); var original = index.getResult().sites();
        String context = (String) highlights.context();
        var connection = index.getConfig().server().connection(); var root = index.getRoot();
        LogEvent selected = (LogEvent) panel.viewState().selected();
        List<ExceptionSites.Site> large = new ArrayList<>(original);
        for (int i = 0; i < 10_000; i++) large.add(new ExceptionSites.Site(
                new ExceptionSites.Location(root.resolve("unopened/Fixture" + i + ".php"), 1), List.of("FixtureException"), List.of()));
        highlights.select(null);
        long start = System.nanoTime(); highlights.replace(large, "Synthetic refresh benchmark", connection, root);
        long total = System.nanoTime() - start;
        var first = highlights.stats(); Map<Editor, List<RangeHighlighter>> markers = new IdentityHashMap<>();
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) if (editor.getProject() == project) markers.put(editor, highlights.markers(editor));
        start = System.nanoTime(); highlights.replace(large, "Synthetic refresh benchmark", connection, root);
        long repeatTotal = System.nanoTime() - start; var repeat = highlights.stats();
        if (repeat.created() != 0 || repeat.removed() != 0) throw new AssertionError("Unchanged markers were recreated: " + repeat);
        if (repeat.locations() >= 10_000) throw new AssertionError("Refresh scanned locations belonging to unopened files: " + repeat);
        for (var entry : markers.entrySet()) if (!entry.getValue().equals(highlights.markers(entry.getKey()))) throw new AssertionError("Marker identities changed on no-op refresh");
        LogEvent example = original.stream().flatMap(site -> site.events().stream()).findFirst().orElseThrow();
        Set<Path> affected = new HashSet<>(); ExceptionSites.locations(example, connection, root).forEach(location -> affected.add(location.file()));
        highlights.select(example); var selection = highlights.stats();
        for (var entry : markers.entrySet()) {
            var file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(entry.getKey().getDocument());
            if (file != null && !affected.contains(Path.of(file.getPath()).toAbsolutePath().normalize())
                    && !entry.getValue().equals(highlights.markers(entry.getKey()))) throw new AssertionError("Selection rebuilt markers in an unrelated file");
        }
        highlights.replace(original, context, connection, root); highlights.select(selected);
        Files.writeString(report.resolve("highlight-refresh-result.txt"), String.format(Locale.ROOT,
                "PASS: 10,000 unopened locations; only %d matching locations visited across %d open editors. Repeat refresh retained all marker identities (created=%d, removed=%d). Selection visited %d editors / %d locations.\nInitial full replace %.3f ms (render %.3f ms); repeated replace %.3f ms (render %.3f ms). Includes per-index grouping; timing has no pass/fail threshold.\n",
                first.locations(), first.editors(), repeat.created(), repeat.removed(), selection.editors(), selection.locations(), total / 1e6, first.nanos() / 1e6, repeatTotal / 1e6, repeat.nanos() / 1e6));
    }
}
