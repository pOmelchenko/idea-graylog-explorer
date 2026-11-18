package dev.graylog.jetbrains;

import com.intellij.ide.actions.ShowSettingsUtilImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil;
import com.intellij.openapi.options.ex.ConfigurableWrapper;
import com.intellij.openapi.options.newEditor.SettingsDialog;
import com.intellij.openapi.options.newEditor.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Settings integration runs in the isolated IDE, with its own memory-only Password Safe. */
@SuppressWarnings("deprecation")
final class SettingsSmoke {
    private static int checks;
    static void run(Project project, GraylogPanel explorer, Path report, Runnable complete) {
        try {
            ConnectionDialogSmoke.run(project, report);
            GraylogIdeConfigurable ide = new GraylogIdeConfigurable(); ide.createComponent(); ide.reset();
            check(!ide.isModified(), "IDE reset must be unmodified");
            String currentToken = GraylogIdeSettings.get().getState().connections.getFirst().credentialKey();
            ((JSpinner) SmokeUi.component(ide, "settings.liveInterval")).setValue(12); check(ide.isModified(), "IDE draft detection");
            ide.reset(); check(!ide.isModified(), "Reset discards Settings draft");
            ((com.intellij.ui.ColorPanel) SmokeUi.component(ide, "settings.backgroundDark")).setSelectedColor(java.awt.Color.MAGENTA);
            check(ide.isModified(), "Native color picker updates the draft");
            ide.reset();
            SmokeUi.addSourceColor(ide, "", "#123456"); check(ide.isModified(), "Invalid source color draft is reported as modified");
            try { ide.apply(); throw new AssertionError("Blank source name accepted"); } catch (ConfigurationException expected) { checks++; }
            ide.reset();
            JTable sourceTable = SmokeUi.addSourceColor(ide, "demo-worker", "#4C9A66");
            sourceTable.editCellAt(0, 0);
            check(ide.isModified() && sourceTable.isEditing(), "Settings modification polling preserves source-name editing");
            sourceTable.getCellEditor().stopCellEditing();
            ((JSpinner) SmokeUi.component(ide, "settings.liveInterval")).setValue(9);
            ((JSpinner) SmokeUi.component(ide, "settings.samples")).setValue(3);
            ((JSpinner) SmokeUi.component(ide, "settings.batchSize")).setValue(37);
            ((JCheckBox) SmokeUi.component(ide, "settings.inlineValues")).setSelected(false);
            ide.apply();
            check(!GraylogIdeSettings.get().getState().options.inlineValues, "Inline variable setting persists through Apply");
            ide.reset();
            ((JCheckBox) SmokeUi.component(ide, "settings.inlineValues")).setSelected(true);
            check(ide.isModified(), "Inline variable setting participates in modification detection");
            ide.apply();
            check(GraylogIdeSettings.get().getState().options.inlineValues, "Inline variable setting restored");
            check(!ide.isModified(), "Apply commits IDE settings");
            check(GraylogIdeSettings.get().getState().options.examplesPerLocation == 3, "Sample setting persisted");
            check(GraylogIdeSettings.get().getState().options.sourceColors.get("demo-worker").equals("#4C9A66"), "Source color setting persisted");
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    ServerConnection entered = GraylogIdeSettings.get().getState().connections.getFirst();
                    char[] token = "demo-token".toCharArray();
                    ServerConnection first, second;
                    try {
                        first = ConnectionManager.get().save(entered, token);
                        second = ConnectionManager.get().save(first, token);
                    } finally { java.util.Arrays.fill(token, '\0'); }
                    check(GraylogCredentials.load(second.credentialKey()).equals("demo-token"), "Shared connection transaction stores token");
                    check(GraylogCredentials.load(first.credentialKey()) == null, "Replacing token removes obsolete nonlegacy key");
                    check(GraylogCredentials.load(currentToken).equals("demo-token"), "Legacy token remains available for migration");
                    ApplicationManager.getApplication().invokeLater(() -> { ide.reset(); testConnection(project, explorer, report, ide, complete); });
                } catch (Throwable e) { fail(report, e); }
            });
        } catch (Throwable e) { fail(report, e); }
    }

    private static void testConnection(Project project, GraylogPanel explorer, Path report, GraylogIdeConfigurable ide, Runnable complete) {
        try {
            button((Container) ide.component(), "Test connection").doClick();
            await(report, () -> ((JLabel) SmokeUi.component(ide, "settings.testStatus")).getText().startsWith("Connected:"), () -> {
                check(true, "Connection test uses the applied token and local fixture"); ide.disposeUIResources();
                GraylogProjectConfigurable local = new GraylogProjectConfigurable(project); local.createComponent(); local.reset();
                check(!local.isModified(), "Project reset must be unmodified");
                JComboBox<?> profile = (JComboBox<?>) SmokeUi.component(local, "project.profile");
                profile.setSelectedIndex(1);
                check(!((JCheckBox) SmokeUi.component(local, "project.customize")).isSelected(), "Connection inherits project defaults");
                JTable mappingTable = (JTable) SmokeUi.component(local, "project.mappings");
                check(!mappingTable.isEnabled(), "Inherited mappings remain read-only");
                ((JCheckBox) SmokeUi.component(local, "project.customize")).doClick();
                check(mappingTable.isEnabled(), "Custom mappings become editable");
                mappingTable.editCellAt(0, 0);
                check(local.isModified() && mappingTable.isEditing(), "Settings modification polling preserves mapping editing");
                mappingTable.getCellEditor().stopCellEditing();
                var pathEditor = (com.intellij.util.ui.LocalPathCellEditor) mappingTable.getColumnModel().getColumn(1).getCellEditor();
                check(pathEditor.getFileChooserDescriptor().isChooseFolders() && !pathEditor.getFileChooserDescriptor().isChooseFiles(), "Local roots use a native folder chooser");
                ((JTextField) SmokeUi.component(local, "project.requestId")).setText("ctx.request_id");
                ((JComboBox<?>) SmokeUi.component(local, "project.highlight")).setSelectedIndex(1);
                check(local.isModified(), "Project override detection");
                var workspace = GraylogSettings.get(project); var history = workspace.getState();
                workspace.remember(history.selectedConnection, "runtime query while Settings is open", 300); workspace.rememberPageSize(500);
                local.apply(); check(!local.isModified(), "Project Apply resets modification state");
                check(workspace.getState().lastQuery.equals("runtime query while Settings is open") && workspace.getState().pageSize == 500, "Settings Apply preserves concurrent workspace history");
                workspace.remember(history.selectedConnection, history.lastQuery, history.rangeSeconds); workspace.rememberPageSize(history.pageSize);
                check(GraylogSettings.get(project).connections().getFirst().fields.requestIdField.equals("ctx.request_id"), "Effective project field format");
                profile.setSelectedIndex(0); profile.setSelectedIndex(1);
                check(((JTextField) SmokeUi.component(local, "project.requestId")).getText().equals("ctx.request_id"), "Override survives profile switching");
                ((JTextField) SmokeUi.component(local, "project.requestId")).setText("bad field");
                try { local.apply(); throw new AssertionError("Invalid field accepted"); } catch (ConfigurationException expected) { checks++; }
                local.reset(); check(!local.isModified(), "Project Reset discards invalid draft");
                local.disposeUIResources();
                String appXml = JDOMUtil.writeElement(XmlSerializer.serialize(GraylogIdeSettings.get().getState()));
                String projectXml = JDOMUtil.writeElement(XmlSerializer.serialize(GraylogSettings.get(project).getState()));
                check(!appXml.contains("ctx.request_id") && projectXml.contains("ctx.request_id"), "Project format stored only in project XML");
                check(!appXml.contains("demo-token") && !projectXml.contains("demo-token"), "Tokens never serialized");
                await(report, () -> ((IdeOptions) explorer.viewState().options()).liveIntervalSeconds == 9
                        && !(SmokeUi.search(explorer).getPhase() == SearchController.Phase.LOADING) && SmokeUi.index(explorer).getResult() != null,
                        () -> nativeSettings(project, report, complete), "Explorer reload after Apply");
            }, "Testing Graylog connection");
        } catch (Throwable e) { fail(report, e); }
    }

    private static void nativeSettings(Project project, Path report, Runnable complete) {
        try {
            SmokeUi.prepare(project);
            String rootPath = ConfigurableExtensionPointUtil.getConfigurablePath(GraylogIdeConfigurable.class, project);
            String childPath = ConfigurableExtensionPointUtil.getConfigurablePath(GraylogProjectConfigurable.class, project);
            check(rootPath.contains("Tools") && rootPath.contains("Graylog Explorer"), "IDE Settings hierarchy: " + rootPath);
            check(childPath.contains("Graylog Explorer") && childPath.contains("Project settings"), "Project Settings hierarchy: " + childPath);
            var groups = java.util.Arrays.asList(ShowSettingsUtilImpl.getConfigurableGroups(project, true));
            Configurable root = null;
            for (var group : groups) { root = find(group.getConfigurables(), GraylogIdeConfigurable.ID); if (root != null) break; }
            check(root != null, "Graylog root registered");
            SettingsDialog dialog = (SettingsDialog) ShowSettingsUtilImpl.getDialog(project, groups, root);
            dialog.setModal(false); dialog.show();
                            GraylogIdeConfigurable page = ConfigurableWrapper.cast(GraylogIdeConfigurable.class, root);
                            Configurable child = child(root);
                            check(child != null, "Project settings attached to IDE root");
                            await(report, () -> page.component() instanceof Component c && c.isShowing(), () -> {
                                    check(dialog != null && dialog.isShowing(), "Native Settings dialog shown");
                                    screenshot(report.resolve("settings-ide.png"));
                                    JTabbedPane sourceTabs = tabs((Container) page.component());
                                    captureSettingsTabs(sourceTabs, report, 0, () -> {
                                    try {
                                    ((SettingsEditor) dialog.getEditor()).select(child);
                                    await(report, () -> ConfigurableWrapper.castIfCreated(GraylogProjectConfigurable.class, child) != null, () -> {
                                        Timer projectCapture = new Timer(1000, ignored -> {
                                            try {
                                                screenshot(report.resolve("settings-project.png"));
                                                GraylogProjectConfigurable projectPage = ConfigurableWrapper.cast(GraylogProjectConfigurable.class, child);
                                                JTable visibleMappings = (JTable) SmokeUi.component(projectPage, "project.mappings");
                                                check(visibleMappings.getVisibleRect().width >= visibleMappings.getWidth() - 2, "Project mappings fit the native Settings width");
                                                ((JTextField) SmokeUi.component(projectPage, "project.requestId")).setText("cancel.this.change");
                                                dialog.doCancelAction();
                                                check(!GraylogSettings.get(project).getState().defaults.fields.requestIdField.equals("cancel.this.change"), "Native Cancel preserves project settings");
                                                Files.writeString(report.resolve("settings-result.txt"), "PASS: " + checks + " settings checks; native paths: " + rootPath + " / " + childPath + "\n");
                                                complete.run();
                                            } catch (Throwable failure) { fail(report, failure); }
                                        }); projectCapture.setRepeats(false); projectCapture.start();
                                    }, "Selecting native Project settings");
                                    } catch (Throwable failure) { fail(report, failure); }
                                    });
                            }, "Opening native Settings dialog");
        } catch (Throwable e) { fail(report, e); }
    }

    private static Configurable find(Configurable[] values, String id) {
        for (Configurable c : values) {
            if (c instanceof SearchableConfigurable s && s.getId().equals(id)) return c;
            if (c instanceof Configurable.Composite parent) { Configurable found = find(parent.getConfigurables(), id); if (found != null) return found; }
        } return null;
    }
    private static void captureSettingsTabs(JTabbedPane tabs, Path report, int index, Runnable done) {
        int[] order = {3, 2, 1, 4};
        String[] names = {"settings-sources.png", "settings-highlighting.png", "settings-search.png", "settings-network.png"};
        if (index == order.length) { done.run(); return; }
        tabs.setSelectedIndex(order[index]);
        Timer timer = new Timer(450, e -> {
            try { screenshot(report.resolve(names[index])); captureSettingsTabs(tabs, report, index + 1, done); }
            catch (Throwable failure) { fail(report, failure); }
        }); timer.setRepeats(false); timer.start();
    }

    private static Configurable child(Configurable root) {
        if (root instanceof Configurable.Composite parent) for (Configurable c : parent.getConfigurables()) {
            if (c instanceof SearchableConfigurable s && s.getId().equals(GraylogProjectConfigurable.ID)) return c;
        }
        return null;
    }
    private static JButton button(Container parent, String text) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JButton b && b.getText().equals(text)) return b;
            if (c instanceof Container nested) { JButton b = button(nested, text); if (b != null) return b; }
        } return null;
    }
    private static JTabbedPane tabs(Container parent) {
        if (parent instanceof JTabbedPane tabs) return tabs;
        for (Component child : parent.getComponents()) if (child instanceof Container container) { var found = tabs(container); if (found != null) return found; }
        return null;
    }

    private static void screenshot(Path path) throws Exception { ImageIO.write(new Robot().createScreenCapture(new Rectangle(0, 0, 1780, 1060)), "png", path.toFile()); }
    @FunctionalInterface private interface Check { boolean test() throws Exception; }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void await(Path report, Check ready, Action action, String label) {
        long deadline = System.currentTimeMillis() + 30000; Timer timer = new Timer(200, null);
        timer.addActionListener(e -> { try { if (ready.test()) { timer.stop(); action.run(); }
            else if (System.currentTimeMillis() > deadline) { timer.stop(); throw new AssertionError(label + " timed out"); }
        } catch(Throwable error) { timer.stop(); fail(report, error); } }); timer.start();
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void fail(Path report, Throwable e) {
        try { screenshot(report.resolve("settings-failure.png")); } catch (Exception ignored) { }
        try { java.io.StringWriter s = new java.io.StringWriter(); e.printStackTrace(new java.io.PrintWriter(s)); Files.writeString(report.resolve("result.txt"), "FAIL: " + s); }
        catch (Exception ignored) { }
    }
}
