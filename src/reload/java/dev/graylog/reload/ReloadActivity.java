package dev.graylog.reload;

import com.intellij.credentialStore.ProviderType;
import com.intellij.credentialStore.PasswordSafeSettings;
import com.intellij.ide.plugins.DynamicPlugins;
import com.intellij.ide.plugins.PluginDescriptorLoader;
import com.intellij.ide.plugins.PluginMainDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.util.BuildNumber;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.ToolWindowManager;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Independent test plugin: never links to or keeps strong references to Graylog classes. */
@SuppressWarnings({"deprecation", "removal"})
public final class ReloadActivity implements StartupActivity.DumbAware {
    private static final PluginId ID = PluginId.getId("dev.omelchenko.graylogexplorer");
    private static final String QUERY = "request_id:\"abc-123\"";
    private Project project;
    private Path report;
    private int cycle;
    private long deadline;
    private WeakReference<ClassLoader> previousLoader;

    @Override public void runActivity(Project project) {
        this.project = project;
        report = Path.of(System.getProperty("graylog.smoke.report"));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (ApplicationManager.getApplication().getService(PasswordSafeSettings.class).getProviderType() != ProviderType.MEMORY_ONLY) throw new AssertionError("Smoke configuration must select memory-only Password Safe before startup");
                seed();
                later(this::open);
            } catch (Throwable error) { fail(error); }
        });
    }

    private Class<?> type(String simple) throws Exception {
        return descriptor().getPluginClassLoader().loadClass("dev.graylog.jetbrains." + simple);
    }
    private PluginMainDescriptor descriptor() {
        return (PluginMainDescriptor) PluginManagerCore.getPlugin(ID);
    }
    private void seed() throws Exception {
        Object connection = type("Connection").getConstructor().newInstance();
        set(connection, "id", "reload-demo"); set(connection, "name", "Reload fixture");
        set(connection, "apiUrl", System.getProperty("graylog.smoke.api"));
        set(connection, "webUrl", System.getProperty("graylog.smoke.api").replaceFirst("/api$", ""));
        var credentials = type("GraylogCredentials").getDeclaredMethod("save", String.class, String.class);
        credentials.setAccessible(true); credentials.invoke(null, "reload-demo", "demo-token");
        Object settings = type("GraylogSettings").getMethod("get", Project.class).invoke(null, project);
        settings.getClass().getMethod("save", type("Connection")).invoke(settings, connection);
        settings.getClass().getMethod("remember", String.class, String.class, int.class).invoke(settings, "reload-demo", QUERY, 900);
    }

    private void open() {
        try {
            check(descriptor() != null && descriptor().isEnabled(), "Plugin enabled");
            var window = ToolWindowManager.getInstance(project).getToolWindow("Graylog");
            check(window != null, "Tool window registered");
            window.show(() -> {
                try {
                    var file = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath() + "/src/Domain/Phone.php");
                    check(file != null, "Fixture source exists");
                    FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, 45, 0), true);
                    Object panel = panel();
                    check(QUERY.equals(((JTextField) component(panel, "explorer.query")).getText()), "Query survived load");
                    panel.getClass().getMethod("searchText", String.class).invoke(panel, QUERY);
                    deadline = System.currentTimeMillis() + 60000;
                    poll(this::ready);
                } catch (Throwable error) { fail(error); }
            });
        } catch (Throwable error) { fail(error); }
    }

    private boolean ready() throws Exception {
        Object panel = panel();
        JTable events = (JTable) component(panel, "explorer.events");
        if (events.getRowCount() == 0 || searchBusy(panel) || !((JLabel) component(panel, "explorer.indexStatus")).getText().startsWith("Whole period")) return false;
        for (int row = 0; row < events.getRowCount(); row++) {
            if (String.valueOf(events.getValueAt(row, 3)).contains("Invalid phone")) {
                events.setRowSelectionInterval(row, row); break;
            }
        }
        if (markers() == 0 || inlineHints() == 0) return false;
        check(ActionManager.getInstance().getAction("Graylog.SearchSelection") != null, "Action registered");
        var providers = (com.intellij.openapi.extensions.ExtensionPointName<?>) type("VariableHintProvider").getField("EP").get(null);
        check(providers.getExtensionList().stream().anyMatch(it -> it.getClass().getName().endsWith("PhpVariableHintProvider")), "PHP extension available");
        var live = findAction((Container) panel, "Live");
        var liveEvent = AnActionEvent.createFromAnAction(live, null, "GraylogReload", DataContext.EMPTY_CONTEXT);
        ((ToggleAction) live).setSelected(liveEvent, true);
        AbstractButton button = sourceButton((Container) panel);
        check(button != null, "Sources button available");
        button.doClick();
        Object view = panel.getClass().getMethod("viewState").invoke(panel);
        check(Boolean.TRUE.equals(view.getClass().getMethod("sourcesPopupVisible").invoke(view)), "Sources popup opened");
        step("Cycle " + cycle + ": search, index, action, popup, Live and editor hints active");
        later(this::unload);
        return true;
    }

    private void unload() {
        try {
            String reason = DynamicPlugins.INSTANCE.validateCanUnloadWithoutRestart(descriptor());
            check(reason == null, "Dynamic unload allowed: " + reason);
            previousLoader = new WeakReference<>(descriptor().getPluginClassLoader());
            check(DynamicPlugins.INSTANCE.unloadPlugin(descriptor()), "Platform unloaded plugin without restart");
            check(ToolWindowManager.getInstance(project).getToolWindow("Graylog") == null, "Tool window removed");
            check(ActionManager.getInstance().getAction("Graylog.SearchSelection") == null, "Action removed");
            check(markers() == 0 && hints() == 0, "Owned editor decorations removed");
            step("Cycle " + cycle + ": dynamic unload succeeded; window, action and decorations removed");
            later(this::load);
        } catch (Throwable error) { fail(error); }
    }

    private void collect() {
        try {
            // Platform disabled-icon caches retain CachedImageIcon keys beyond successful unload.
            // Flush platform caches before looking for plugin-owned strong-reference leaks.
            com.intellij.openapi.util.IconLoader.clearCache();
            for (int attempt = 0; attempt < 20 && previousLoader.get() != null; attempt++) {
                System.gc(); Thread.sleep(250);
            }
            check(previousLoader.get() == null, "Old plugin classloader collected after platform cache cleanup");
            step("Cycle " + cycle + ": old classloader collected after platform icon cache cleanup");
            later(this::continueAfterLoad);
        } catch (Throwable error) { fail(error); }
    }

    private void load() {
        try {
            Path installed = Path.of(System.getProperty("graylog.reload.plugin"));
            if (cycle == 0) {
                try (var old = Files.list(installed.resolve("lib"))) { for (Path jar : old.toList()) Files.delete(jar); }
                Path upgrade = Path.of(System.getProperty("graylog.reload.upgrade"));
                try (var files = Files.list(upgrade.resolve("lib"))) {
                    for (Path jar : files.toList()) Files.copy(jar, installed.resolve("lib").resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            PluginMainDescriptor next;
            try (var jars = Files.list(installed.resolve("lib"))) {
                next = PluginDescriptorLoader.loadDescriptorFromArtifact(jars.findFirst().orElseThrow(), BuildNumber.currentVersion());
            }
            check(next != null && next.getVersion().endsWith("-reload-test"), "Replacement version read: " + (next == null ? "null" : next.getVersion()));
            check(DynamicPlugins.INSTANCE.loadPlugin(next, project), "Replacement loaded without restart");
            step("Cycle " + cycle + ": replacement " + next.getVersion() + " loaded in JVM " + ProcessHandle.current().pid());
            check(previousLoader.get() != descriptor().getPluginClassLoader(), "New classloader used");
            cycle++;
            ApplicationManager.getApplication().executeOnPooledThread(this::collect);
        } catch (Throwable error) { fail(error); }
    }

    private void continueAfterLoad() {
        if (cycle < 2) { open(); return; }
        try {
            var window = ToolWindowManager.getInstance(project).getToolWindow("Graylog");
            check(window != null, "Reloaded window registered");
            window.show(() -> {
                try {
                    check(QUERY.equals(((JTextField) component(panel(), "explorer.query")).getText()), "Query retained after update and reload");
                    check(ActionManager.getInstance().getAction("Graylog.SearchSelection") != null, "Reloaded action registered once");
                    Files.writeString(report.resolve("result.txt"), "PASS: Production plugin updated and reloaded in the same JVM; both old classloaders collected after platform icon cache cleanup. Open Explorer, Live, source popup, index, PHP hints, actions and saved query checked.\n");
                } catch (Throwable error) { fail(error); }
            });
        } catch (Throwable error) { fail(error); }
    }

    private Object panel() {
        return ToolWindowManager.getInstance(project).getToolWindow("Graylog").getContentManager().getContents()[0].getComponent();
    }
    private int markers() {
        int count = 0;
        for (var editor : EditorFactory.getInstance().getAllEditors()) {
            for (var marker : editor.getMarkupModel().getAllHighlighters()) {
                var renderer = marker.getGutterIconRenderer();
                if (renderer != null && owned(renderer)) count++;
            }
        }
        return count;
    }
    private int inlineHints() {
        int count = 0;
        for (var editor : EditorFactory.getInstance().getAllEditors())
            for (var hint : editor.getInlayModel().getInlineElementsInRange(0, editor.getDocument().getTextLength()))
                if (owned(hint.getRenderer())) count++;
        return count;
    }
    private int hints() {
        int count = 0;
        for (var editor : EditorFactory.getInstance().getAllEditors()) {
            int end = editor.getDocument().getTextLength();
            for (var hint : editor.getInlayModel().getInlineElementsInRange(0, end)) if (owned(hint.getRenderer())) count++;
            for (var hint : editor.getInlayModel().getAfterLineEndElementsInRange(0, end)) if (owned(hint.getRenderer())) count++;
        }
        return count;
    }
    private static boolean searchBusy(Object panel) throws Exception {
        Object view = panel.getClass().getMethod("viewState").invoke(panel);
        Object state = view.getClass().getMethod("search").invoke(view);
        return state.getClass().getMethod("getPhase").invoke(state).toString().equals("LOADING");
    }
    private static boolean owned(Object object) { return object.getClass().getName().startsWith("dev.graylog.jetbrains."); }
    private static AnAction findAction(Container parent, String prefix) {
        if (parent instanceof ActionToolbar toolbar) for (AnAction action : toolbar.getActions()) {
            String title = action.getTemplatePresentation().getText(); if (title != null && title.startsWith(prefix)) return action;
        }
        for (Component child : parent.getComponents()) if (child instanceof Container nested) { var found = findAction(nested, prefix); if (found != null) return found; }
        return null;
    }
    private static AbstractButton sourceButton(Container parent) {
        if (parent instanceof AbstractButton b && b.getText() != null && b.getText().startsWith("Source")) return b;
        for (Component child : parent.getComponents()) if (child instanceof Container nested) { var found = sourceButton(nested); if (found != null) return found; }
        return null;
    }
    private static Component component(Object owner, String id) {
        if (owner instanceof Component c && id.equals(c.getName())) return c;
        if (owner instanceof Container container) for (Component child : container.getComponents()) {
            Component found = component(child, id); if (found != null) return found;
        }
        return null;
    }
    private static void set(Object object, String name, Object value) throws Exception { object.getClass().getField(name).set(object, value); }
    private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    private void step(String message) throws Exception {
        Files.writeString(report.resolve("reload-steps.txt"), message + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
    @FunctionalInterface private interface Condition { boolean ready() throws Exception; }
    private void poll(Condition condition) {
        Timer timer = new Timer(250, null);
        timer.addActionListener(e -> {
            try {
                if (condition.ready()) timer.stop();
                else if (System.currentTimeMillis() > deadline) { timer.stop(); throw new AssertionError("Reload fixture did not become ready"); }
            } catch (Throwable error) { timer.stop(); fail(error); }
        });
        timer.start();
    }
    private void later(Runnable runnable) { ApplicationManager.getApplication().invokeLater(runnable); }
    private void fail(Throwable error) {
        try {
            var text = new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(text));
            Files.writeString(report.resolve("result.txt"), "FAIL: " + text);
        } catch (Exception ignored) { }
    }
}
