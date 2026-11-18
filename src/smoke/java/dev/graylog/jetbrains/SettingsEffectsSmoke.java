package dev.graylog.jetbrains;

import com.intellij.openapi.project.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import javax.swing.JTextField;
import javax.swing.Timer;

/** Proves Settings affect only the corresponding operation and do not submit an edited query draft. */
final class SettingsEffectsSmoke {
    static void run(Project project, GraylogPanel panel, Path report, Runnable done) {
        try {
            var original = GraylogIdeSettings.get().getState();
            var page = SmokeUi.search(panel).getPage(); var index = SmokeUi.index(panel).getResult();
            JTextField query = (JTextField) SmokeUi.component(panel, "explorer.query"); String text = query.getText();
            query.setText("unsent_query_draft");
            var style = original.copy(); style.options.backgroundLight = style.options.backgroundLight.equals("#ABCDEF") ? "#FEDCBA" : "#ABCDEF";
            GraylogIdeSettings.get().replace(style);
            await(report, () -> ((IdeOptions) panel.viewState().options()).backgroundLight.equals(style.options.backgroundLight), () -> {
                same(page, SmokeUi.search(panel).getPage(), "Style changed the page"); same(index, SmokeUi.index(panel).getResult(), "Style restarted the index");
                var schedule = style.copy(); schedule.options.liveIntervalSeconds++;
                GraylogIdeSettings.get().replace(schedule);
                await(report, () -> panel.viewState().liveDelay() == schedule.options.liveIntervalSeconds * 1000, () -> {
                    same(page, SmokeUi.search(panel).getPage(), "Live schedule changed the page"); same(index, SmokeUi.index(panel).getResult(), "Live schedule restarted the index");
                    var limits = schedule.copy(); limits.options.examplesPerLocation++;
                    GraylogIdeSettings.get().replace(limits);
                    await(report, () -> ready(panel) && SmokeUi.index(panel).getResult() != index, () -> {
                        same(page, SmokeUi.search(panel).getPage(), "Sample limit reloaded the page");
                        if (SmokeUi.index(panel).getConfig().scan().examplesPerLocation() != limits.options.examplesPerLocation) throw new AssertionError("Index reused stale scan options");
                        var network = limits.copy(); network.options.requestTimeoutSeconds++;
                        GraylogIdeSettings.get().replace(network);
                        await(report, () -> ready(panel) && SmokeUi.search(panel).getPage() != page, () -> {
                            var refreshed = SmokeUi.search(panel).getPage();
                            if (!refreshed.getSession().request().sameScope(page.getSession().request())) throw new AssertionError("Network change moved the search window or submitted the query draft");
                            if (!query.getText().equals("unsent_query_draft") || refreshed.getResult().events().isEmpty()) throw new AssertionError("Settings consumed the unsent query draft");
                            if (refreshed.getSession().getConfig().transport().requestTimeoutSeconds() != network.options.requestTimeoutSeconds
                                    || SmokeUi.index(panel).getConfig().scan().examplesPerLocation() != limits.options.examplesPerLocation) throw new AssertionError("A new operation reused stale configuration");
                            GraylogIdeSettings.get().replace(original);
                            await(report, () -> ready(panel) && SmokeUi.search(panel).getPage() != refreshed
                                    && SmokeUi.search(panel).getPage().getSession().getConfig().transport().requestTimeoutSeconds() == original.options.requestTimeoutSeconds, () -> {
                                query.setText(text);
                                try { Files.writeString(report.resolve("settings-effects-result.txt"), "PASS: Style and Live interval preserve page/index identities. Sample limits rebuild only the index. Network changes retain the submitted query/window and unsent draft while using fresh immutable options. Restoring Settings restores the original configuration.\n"); }
                                catch (Exception error) { throw new RuntimeException(error); }
                                mappingEffects(project, panel, report, done);
                            }, "Restoring Settings effects");
                        }, "Applying immutable network settings");
                    }, "Index-only Settings change");
                }, "Live schedule Settings change");
            }, "Presentation-only Settings change");
        } catch (Throwable error) { fail(report, error); }
    }
    private static void mappingEffects(Project project, GraylogPanel panel, Path report, Runnable done) {
        var workspace = GraylogSettings.get(project); var original = workspace.getState(); var mapped = original.copy();
        var page = SmokeUi.search(panel).getPage(); var index = SmokeUi.index(panel).getResult();
        String id = SmokeUi.index(panel).getConfig().server().id();
        var profile = mapped.profiles.stream().filter(value -> value.connectionId.equals(id)).findFirst().orElse(mapped.defaults);
        Path target = Path.of(project.getBasePath()).resolve("remapped"); profile.mappings.getFirst().localRoot = target.toString();
        workspace.replace(mapped);
        await(report, () -> ready(panel) && SmokeUi.index(panel).getResult() != index, () -> {
            same(page, SmokeUi.search(panel).getPage(), "Path mapping reloaded the page");
            if (SmokeUi.index(panel).getResult().sites().stream().noneMatch(site -> site.location().file().startsWith(target))) throw new AssertionError("Index did not apply the new mapping");
            var label = (javax.swing.JLabel) SmokeUi.component(panel, "inspector.sourceMapping");
            if (!label.getText().contains(target.toString())) throw new AssertionError("Inspector kept the previous local mapping");
            var current = SmokeUi.index(panel).getResult(); workspace.replace(original);
            await(report, () -> ready(panel) && SmokeUi.index(panel).getResult() != current, () -> {
                same(page, SmokeUi.search(panel).getPage(), "Restoring path mapping reloaded the page");
                if (SmokeUi.index(panel).getResult().sites().stream().anyMatch(site -> site.location().file().startsWith(target))) throw new AssertionError("Index kept the temporary mapping");
                try { Files.writeString(report.resolve("mapping-settings-result.txt"), "PASS: Path mapping changes rebuild only the index, update the inspector's local path, preserve the loaded page and restore the original mappings.\n"); }
                catch (Exception error) { throw new RuntimeException(error); }
                done.run();
            }, "Restoring path mappings");
        }, "Applying path mappings without reloading the page");
    }

    private static boolean ready(GraylogPanel panel) {
        return SmokeUi.search(panel).getPhase() == SearchController.Phase.READY
                && SmokeUi.index(panel).getPhase() == PeriodIndexController.Phase.READY;
    }
    private static void same(Object expected, Object actual, String message) { if (expected != actual) throw new AssertionError(message); }
    private static void await(Path report, BooleanSupplier condition, Runnable next, String description) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        Timer timer = new Timer(100, null);
        timer.addActionListener(ignored -> {
            try {
                if (condition.getAsBoolean()) { timer.stop(); next.run(); }
                else if (System.nanoTime() >= deadline) { timer.stop(); throw new AssertionError("Timed out: " + description); }
            } catch (Throwable error) { timer.stop(); fail(report, error); }
        }); timer.start();
    }
    private static void fail(Path report, Throwable error) {
        try { var trace = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace)); Files.writeString(report.resolve("result.txt"), "FAIL: " + trace); }
        catch (Exception ignored) { }
    }
}
