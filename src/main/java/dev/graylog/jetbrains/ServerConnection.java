package dev.graylog.jetbrains;

import java.util.UUID;

/** Application-level connection metadata. Credentials live only in Password Safe. */
public final class ServerConnection {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String apiUrl = "";
    public String webUrl = "";
    public String credentialId = "";

    public ServerConnection copy() { return SettingsValues.copy(this, ServerConnection.class); }
    public String credentialKey() { return credentialId.isBlank() ? id : credentialId; }
    public static ServerConnection from(Connection c) {
        ServerConnection server = new ServerConnection(); server.id = c.id; server.name = c.name;
        server.apiUrl = c.apiUrl; server.webUrl = c.webUrl; server.credentialId = c.credentialId; return server;
    }
    public Connection connection() {
        Connection c = new Connection(); c.id = id; c.name = name; c.apiUrl = apiUrl;
        c.webUrl = webUrl; c.credentialId = credentialId; return c;
    }
    public void validate() {
        if (id.isBlank() || name.isBlank()) throw new IllegalArgumentException("Each connection needs an ID and name.");
        apiUrl = Connection.normalizeApiUrl(apiUrl);
        if (webUrl.isBlank()) webUrl = apiUrl.replaceFirst("/api$", "");
        else Connection.normalizeApiUrl(webUrl);
    }
    @Override public String toString() { return name; }
}
