package dev.graylog.jetbrains;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service(Service.Level.PROJECT)
@com.intellij.openapi.components.State(name = "GraylogExplorer", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class GraylogSettings implements PersistentStateComponent<GraylogSettings.Data> {
    public static final class SavedSearch {
        public String id = UUID.randomUUID().toString();
        public String connectionId = "";
        public String name = "";
        public String query = "*";
        public String streamId = "";
        public int rangeSeconds = 900;
        public SavedSearch copy() {
            SavedSearch s = new SavedSearch();
            s.id = id; s.connectionId = connectionId; s.name = name;
            s.query = query; s.streamId = streamId; s.rangeSeconds = rangeSeconds;
            return s;
        }
    }

    public static final class Data {
        public int schemaVersion = 0;
        // Read for migration from 0.1–0.3; never populated after migration.
        public List<Connection> connections = new ArrayList<>();
        public List<SavedSearch> searches = new ArrayList<>();
        public String selectedConnection = "";
        public String preferredConnection = "";
        public String lastQuery = "*";
        public int rangeSeconds = 900;
        public boolean highlightExceptions = true;
        public int highlightMode = 0; // 0 = IDE default, 1 = enabled, 2 = disabled.
        public int pageSize = 0;
        public ProjectProfile defaults = new ProjectProfile();
        public List<ProjectProfile> profiles = new ArrayList<>();
        Data copy() {
            return SettingsValues.copy(this, Data.class);
        }
        public void validate() {
            if (highlightMode < 0 || highlightMode > 2) throw new IllegalArgumentException("Choose a valid project highlight mode.");
            defaults.validate(); for (ProjectProfile profile : profiles) profile.validate();
        }
    }

    private Data data = new Data();
    private final Project project;
    public GraylogSettings(Project project) { this.project = project; }

    public static GraylogSettings get(Project project) {
        GraylogSettings settings = project.getService(GraylogSettings.class); settings.ensureMigrated(); return settings;
    }
    @Override public Data getState() { ensureMigrated(); synchronized (this) { return data.copy(); } }
    @Override public synchronized void loadState(Data state) { data = state.copy(); }
    public void save(Connection connection) {
        synchronized (this) { data.selectedConnection = connection.id; }
        GraylogIdeSettings.get().save(ServerConnection.from(connection));
    }
    public void remove(String id) {
        synchronized (this) { if (data.selectedConnection.equals(id)) data.selectedConnection = ""; }
        GraylogIdeSettings.get().remove(id);
    }
    public synchronized void saveSearch(SavedSearch search) { data.searches.add(search.copy()); }
    public synchronized void removeSearch(String id) { data.searches.removeIf(s -> s.id.equals(id)); }
    public synchronized void remember(String connectionId, String query, int seconds) {
        data.selectedConnection = connectionId; data.lastQuery = query; data.rangeSeconds = seconds;
    }
    public synchronized void setHighlightExceptions(boolean enabled) { data.highlightMode = enabled ? 1 : 2; }
    public synchronized void rememberPageSize(int value) { data.pageSize = value; }
    public boolean highlightsEnabled() {
        int mode = getState().highlightMode;
        return mode == 0 ? GraylogIdeSettings.get().getState().options.highlightByDefault : mode == 1;
    }
    public void replace(Data state) {
        state.validate(); synchronized (this) { data = state.copy(); data.schemaVersion = 1; data.connections.clear(); }
        SettingsEvents.fire(project);
    }
    public List<Connection> connections() {
        Data local = getState();
        List<Connection> result = new ArrayList<>();
        for (ServerConnection server : GraylogIdeSettings.get().getState().connections) {
            ProjectProfile profile = local.profiles.stream().filter(p -> p.connectionId.equals(server.id)).findFirst().orElse(local.defaults);
            Connection connection = server.connection();
            connection.mappings = new ArrayList<>(profile.mappings.stream().map(PathMapping::copy).toList());
            connection.fields = profile.fields.copy(); connection.serviceField = profile.fields.serviceField;
            if (!profile.mappings.isEmpty()) {
                connection.remoteRoot = profile.mappings.getFirst().remoteRoot; connection.localRoot = profile.mappings.getFirst().localRoot;
            }
            result.add(connection);
        }
        return result;
    }

    private synchronized void ensureMigrated() {
        if (data.schemaVersion >= 1) return;
        GraylogIdeSettings.get().migrateProject(data); SettingsEvents.fire(null);
    }
    static void migrate(Data local, GraylogIdeSettings.Data global) {
        if (local.schemaVersion >= 1) return;
        boolean used = !local.connections.isEmpty() || !local.searches.isEmpty() || !local.selectedConnection.isBlank()
                || !local.lastQuery.equals("*") || local.rangeSeconds != 900 || !local.highlightExceptions;
        var ids = GraylogIdeSettings.mergeLegacy(global, local.connections);
        for (Connection old : local.connections) {
            ProjectProfile profile = new ProjectProfile(); profile.connectionId = ids.get(old.id);
            profile.mappings = new ArrayList<>(List.of(new PathMapping(old.remoteRoot, old.localRoot)));
            profile.fields.serviceField = old.serviceField;
            if (local.profiles.stream().noneMatch(p -> p.connectionId.equals(profile.connectionId))) local.profiles.add(profile);
        }
        local.selectedConnection = ids.getOrDefault(local.selectedConnection, local.selectedConnection);
        for (SavedSearch search : local.searches) search.connectionId = ids.getOrDefault(search.connectionId, search.connectionId);
        if (used) local.highlightMode = local.highlightExceptions ? 1 : 2;
        else local.rangeSeconds = 0; // Fresh projects use the IDE defaults.
        local.connections.clear(); local.highlightExceptions = true; local.schemaVersion = 1;
    }
}
