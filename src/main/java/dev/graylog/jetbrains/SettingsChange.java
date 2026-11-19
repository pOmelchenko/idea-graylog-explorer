package dev.graylog.jetbrains;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

/** Pure change classification. Runtime selection/history is deliberately outside Settings changes. */
public record SettingsChange(Set<Area> areas) {
    public enum Area { CONNECTIONS, NETWORK, INDEX_OPTIONS, INDEX_POLICY, LIVE, SEARCH_DEFAULTS,
        SOURCE_STYLE, HIGHLIGHT_STYLE, HIGHLIGHT_MODE, MAPPINGS, LOG_FORMAT, LOG_NAVIGATION, PREFERENCE }
    public SettingsChange { areas = Set.copyOf(areas); }
    public boolean has(Area area) { return areas.contains(area); }
    public boolean isEmpty() { return areas.isEmpty(); }
    public boolean affectsRequests() {
        return has(Area.CONNECTIONS) || has(Area.NETWORK) || has(Area.MAPPINGS) || has(Area.LOG_FORMAT) || has(Area.PREFERENCE);
    }
    public static SettingsChange all() { return new SettingsChange(EnumSet.allOf(Area.class)); }
    public static SettingsChange ide(GraylogIdeSettings.Data before, GraylogIdeSettings.Data after) {
        var changed = EnumSet.noneOf(Area.class); IdeOptions a = before.options, b = after.options;
        if (!SettingsValues.same(before.connections, after.connections)) changed.add(Area.CONNECTIONS);
        if (a.connectTimeoutSeconds != b.connectTimeoutSeconds || a.requestTimeoutSeconds != b.requestTimeoutSeconds) changed.add(Area.NETWORK);
        if (a.exportBatchSize != b.exportBatchSize || a.examplesPerLocation != b.examplesPerLocation) changed.add(Area.INDEX_OPTIONS);
        if (a.autoIndex != b.autoIndex) changed.add(Area.INDEX_POLICY);
        if (a.liveIntervalSeconds != b.liveIntervalSeconds) changed.add(Area.LIVE);
        if (!List.of(a.defaultConnection, a.defaultPageSize, a.defaultPeriodSeconds, a.liveOnOpen)
                .equals(List.of(b.defaultConnection, b.defaultPageSize, b.defaultPeriodSeconds, b.liveOnOpen))) changed.add(Area.SEARCH_DEFAULTS);
        if (a.sourceStripes != b.sourceStripes || !a.sourceColors.equals(b.sourceColors)) changed.add(Area.SOURCE_STYLE);
        if (a.highlightByDefault != b.highlightByDefault) changed.add(Area.HIGHLIGHT_MODE);
        if (!List.of(a.gutterIcons, a.errorStripe, a.inlineDetails, a.inlineValues, a.backgroundLight, a.backgroundDark, a.selectedLight, a.selectedDark)
                .equals(List.of(b.gutterIcons, b.errorStripe, b.inlineDetails, b.inlineValues, b.backgroundLight, b.backgroundDark, b.selectedLight, b.selectedDark))) changed.add(Area.HIGHLIGHT_STYLE);
        return new SettingsChange(changed);
    }
    public static SettingsChange project(GraylogSettings.Data before, GraylogSettings.Data after) {
        var changed = EnumSet.noneOf(Area.class);
        if (before.highlightMode != after.highlightMode) changed.add(Area.HIGHLIGHT_MODE);
        if (!before.preferredConnection.equals(after.preferredConnection)) changed.add(Area.PREFERENCE);
        Map<String, ProjectProfile> a = profiles(before), b = profiles(after);
        Set<String> keys = new java.util.HashSet<>(a.keySet()); keys.addAll(b.keySet());
        for (String key : keys) {
            ProjectProfile x = a.get(key), y = b.get(key);
            if (x == null || y == null || !SettingsValues.same(x.mappings, y.mappings)) changed.add(Area.MAPPINGS);
            if (x == null || y == null || !x.fields.aliases().equals(y.fields.aliases())) changed.add(Area.LOG_FORMAT);
            if (x == null || y == null || !SettingsValues.same(x.navigation, y.navigation)) changed.add(Area.LOG_NAVIGATION);
        }
        return new SettingsChange(changed);
    }
    private static Map<String, ProjectProfile> profiles(GraylogSettings.Data state) {
        Map<String, ProjectProfile> result = new LinkedHashMap<>(); result.put("", state.defaults);
        state.profiles.forEach(p -> result.putIfAbsent(p.connectionId, p)); return result;
    }
}
