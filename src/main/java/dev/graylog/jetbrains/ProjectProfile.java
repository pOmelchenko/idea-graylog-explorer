package dev.graylog.jetbrains;

import java.util.ArrayList;
import java.util.List;

public final class ProjectProfile {
    public String connectionId = "";
    public List<PathMapping> mappings = new ArrayList<>(List.of(new PathMapping()));
    public LogFormat fields = new LogFormat();
    public ProjectProfile copy() { return SettingsValues.copy(this, ProjectProfile.class); }
    public void validate() {
        if (mappings.isEmpty()) throw new IllegalArgumentException("Add at least one source path mapping.");
        fields.validate();
        java.util.Set<String> remote = new java.util.HashSet<>();
        for (PathMapping mapping : mappings) {
            mapping.validate();
            if (!remote.add(mapping.remoteRoot.replace('\\', '/').replaceAll("/+$", ""))) throw new IllegalArgumentException("Duplicate remote source root: " + mapping.remoteRoot);
        }
    }
}
