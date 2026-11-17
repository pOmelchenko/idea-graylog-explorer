package dev.graylog.jetbrains;

import java.util.List;
import java.util.Set;

final class SettingsModelTests {
    private static int assertions;
    static int run() { operation(); changes(); impact(); draft(); return assertions; }
    private static void operation() {
        Connection bean = new Connection(); bean.apiUrl = "https://logs.example.org"; bean.id = "fixture";
        bean.mappings.add(new PathMapping("/application", "/fixture"));
        IdeOptions options = new IdeOptions(); OperationConfig captured = OperationConfig.capture(bean, options);
        bean.name = "Edited"; bean.mappings.getFirst().localRoot = "/other"; bean.fields.requestIdField = "other"; options.examplesPerLocation = 1;
        eq("https://logs.example.org", captured.server().apiUrl()); eq("", captured.server().name());
        eq("/fixture", captured.server().mappings().getFirst().localRoot()); eq("request_id", captured.server().fields().get("request_id"));
        eq(20, captured.scan().examplesPerLocation()); eq("fixture", captured.server().credentialKey());
        try { captured.server().mappings().clear(); throw new AssertionError("Mutable mappings"); } catch (UnsupportedOperationException expected) { assertions++; }
        try { captured.server().fields().put("source", "other"); throw new AssertionError("Mutable fields"); } catch (UnsupportedOperationException expected) { assertions++; }
        Connection copy = captured.server().connection(); copy.mappings.getFirst().localRoot = "/changed"; copy.fields.fileField = "changed";
        eq("/fixture", captured.server().mappings().getFirst().localRoot()); eq("file", captured.server().format().fileField);
        eq(captured, OperationConfig.capture(captured.server().connection(), new IdeOptions()));
        Connection invalid = new Connection(); invalid.apiUrl = "invalid";
        var invalidSnapshot = OperationConfig.capture(invalid, new IdeOptions());
        try { invalidSnapshot.validate(); throw new AssertionError("Invalid URL accepted at execution boundary"); }
        catch (IllegalArgumentException expected) { assertions++; }
        var values = new java.util.ArrayList<GraylogApi.SourceValue>(); values.add(new GraylogApi.SourceValue("fixture", 1));
        var sources = new GraylogApi.Sources(values, false); values.clear(); eq(1, sources.values().size());
    }
    private static void changes() {
        var base = new GraylogIdeSettings.Data(); eq(Set.of(), SettingsChange.ide(base, base.copy()).areas());
        var value = base.copy(); value.options.sourceColors.put("fixture", "#123456");
        eq(Set.of(SettingsChange.Area.SOURCE_STYLE), SettingsChange.ide(base, value).areas());
        value = base.copy(); value.options.inlineValues = false; eq(Set.of(SettingsChange.Area.HIGHLIGHT_STYLE), SettingsChange.ide(base, value).areas());
        value = base.copy(); value.options.backgroundDark = "#123456"; eq(Set.of(SettingsChange.Area.HIGHLIGHT_STYLE), SettingsChange.ide(base, value).areas());
        value = base.copy(); value.options.connectTimeoutSeconds = 1; eq(Set.of(SettingsChange.Area.NETWORK), SettingsChange.ide(base, value).areas());
        value = base.copy(); value.options.examplesPerLocation = 1; eq(Set.of(SettingsChange.Area.INDEX_OPTIONS), SettingsChange.ide(base, value).areas());
        value = base.copy(); value.options.autoIndex = false; eq(Set.of(SettingsChange.Area.INDEX_POLICY), SettingsChange.ide(base, value).areas());
        value = base.copy(); value.options.liveIntervalSeconds = 1; eq(Set.of(SettingsChange.Area.LIVE), SettingsChange.ide(base, value).areas());
        value = base.copy(); value.options.defaultPageSize = 250; eq(Set.of(SettingsChange.Area.SEARCH_DEFAULTS), SettingsChange.ide(base, value).areas());
        value = base.copy(); value.options.highlightByDefault = false; eq(Set.of(SettingsChange.Area.HIGHLIGHT_MODE), SettingsChange.ide(base, value).areas());
        value = base.copy(); value.connections.add(new ServerConnection()); eq(Set.of(SettingsChange.Area.CONNECTIONS), SettingsChange.ide(base, value).areas());
        var local = new GraylogSettings.Data(); var changed = local.copy(); changed.lastQuery = "other"; changed.pageSize = 250;
        changed.searches.add(new GraylogSettings.SavedSearch()); eq(true, SettingsChange.project(local, changed).isEmpty());
        changed = local.copy(); changed.preferredConnection = "fixture"; eq(Set.of(SettingsChange.Area.PREFERENCE), SettingsChange.project(local, changed).areas());
        changed = local.copy(); changed.defaults.fields.requestIdField = "ctx.request"; eq(Set.of(SettingsChange.Area.LOG_FORMAT), SettingsChange.project(local, changed).areas());
        changed = local.copy(); changed.defaults.mappings.getFirst().localRoot = "/fixture"; eq(Set.of(SettingsChange.Area.MAPPINGS), SettingsChange.project(local, changed).areas());
        eq(false, new SettingsChange(Set.of(SettingsChange.Area.SOURCE_STYLE, SettingsChange.Area.LIVE)).affectsRequests());
        eq(true, new SettingsChange(Set.of(SettingsChange.Area.LOG_FORMAT)).affectsRequests());
    }
    private static void impact() {
        Connection connection = new Connection(); connection.apiUrl = "https://logs.example.org/api";
        IdeOptions options = new IdeOptions(); var before = OperationConfig.capture(connection, options);
        options.backgroundDark = "#123456";
        eq(new SettingsImpact(false, false, false, false), SettingsImpact.between(before, OperationConfig.capture(connection, options)));
        options.examplesPerLocation = 2;
        eq(new SettingsImpact(false, true, false, false), SettingsImpact.between(before, OperationConfig.capture(connection, options)));
        options.examplesPerLocation = 20; connection.mappings.add(new PathMapping("/application", "/local"));
        eq(new SettingsImpact(false, true, false, false), SettingsImpact.between(before, OperationConfig.capture(connection, options)));
        connection.mappings.clear(); options.requestTimeoutSeconds = 10;
        eq(new SettingsImpact(true, true, true, false), SettingsImpact.between(before, OperationConfig.capture(connection, options)));
        options.requestTimeoutSeconds = 30; connection.credentialId = "rotated";
        eq(new SettingsImpact(true, true, true, true), SettingsImpact.between(before, OperationConfig.capture(connection, options)));
        connection.credentialId = ""; connection.name = "Renamed"; connection.webUrl = "https://web.example.org";
        eq(new SettingsImpact(false, false, false, false), SettingsImpact.between(before, OperationConfig.capture(connection, options)));
    }
    private static void draft() {
        var state = new GraylogSettings.Data(); var base = ProjectSettingsDraft.from(state); var edit = base.snapshot();
        edit.getDefaults().fields.requestIdField = "ctx.request";
        eq("request_id", state.defaults.fields.requestIdField); eq(true, base.matches(state)); eq(false, edit.matches(state));
        state.lastQuery = "changed while settings open"; state.pageSize = 500; state.selectedConnection = "runtime-selection";
        state.searches.add(new GraylogSettings.SavedSearch()); eq(true, base.matches(state));
        var merged = edit.merge(state, base.getPreferredConnection());
        eq(state.lastQuery, merged.lastQuery); eq(500, merged.pageSize); eq("runtime-selection", merged.selectedConnection);
        eq(1, merged.searches.size()); eq("ctx.request", merged.defaults.fields.requestIdField);
        edit.setPreferredConnection("preferred"); eq("preferred", edit.merge(state, base.getPreferredConnection()).selectedConnection);
        state.defaults.mappings.add(new PathMapping("/extra", "/local")); eq(false, base.matches(state));
        merged.defaults.fields.requestIdField = "another"; eq("ctx.request", edit.getDefaults().fields.requestIdField);
    }
    private static void eq(Object expected, Object actual) { assertions++; if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual); }
}
