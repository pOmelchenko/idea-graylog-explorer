package dev.graylog.jetbrains;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;

public final class SettingsEvents {
    public enum Scope { IDE, PROJECT }
    public record Event(Scope scope, Project project, SettingsChange changes) {
        public Event {
            java.util.Objects.requireNonNull(scope); java.util.Objects.requireNonNull(changes);
            if (scope == Scope.PROJECT) java.util.Objects.requireNonNull(project);
        }
        boolean appliesTo(Project value) { return scope == Scope.IDE || project == value; }
    }
    public interface Listener { void changed(Event event); }
    static final Topic<Listener> TOPIC = Topic.create("Graylog Explorer settings", Listener.class);
    private SettingsEvents() { }
    static void ide(SettingsChange changes) { fire(new Event(Scope.IDE, null, changes)); }
    static void project(Project project, SettingsChange changes) { fire(new Event(Scope.PROJECT, project, changes)); }
    private static void fire(Event event) {
        if (event.changes().isEmpty()) return;
        var app = ApplicationManager.getApplication();
        app.invokeLater(() -> { if (!app.isDisposed()) app.getMessageBus().syncPublisher(TOPIC).changed(event); });
    }
}
