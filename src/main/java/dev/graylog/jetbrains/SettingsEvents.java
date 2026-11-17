package dev.graylog.jetbrains;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;

public final class SettingsEvents {
    public interface Listener { void changed(Project project); }
    static final Topic<Listener> TOPIC = Topic.create("Graylog Explorer settings", Listener.class);
    private SettingsEvents() { }
    static void fire(Project project) {
        var app = ApplicationManager.getApplication();
        app.invokeLater(() -> { if (!app.isDisposed()) app.getMessageBus().syncPublisher(TOPIC).changed(project); });
    }
}
