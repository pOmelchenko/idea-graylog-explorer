package dev.graylog.jetbrains;

import java.net.URI;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

public final class Connection {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String apiUrl = "";
    public String webUrl = "";
    public String credentialId = "";
    public String remoteRoot = "/application";
    public String localRoot = "";
    public String serviceField = "source";
    public List<PathMapping> mappings = new ArrayList<>();
    public LogFormat fields = new LogFormat();

    public Connection copy() {
        Connection c = new Connection();
        c.id = id; c.name = name; c.apiUrl = apiUrl; c.webUrl = webUrl;
        c.remoteRoot = remoteRoot; c.localRoot = localRoot; c.serviceField = serviceField;
        c.credentialId = credentialId; c.mappings = new ArrayList<>(mappings.stream().map(PathMapping::copy).toList()); c.fields = fields.copy();
        return c;
    }

    public static String normalizeApiUrl(String input) {
        String value = input.trim().replaceAll("/+$", "");
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Enter a valid Graylog API URL."); }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Use an http(s) URL without credentials, query parameters or fragment.");
        }
        return value.endsWith("/api") ? value : value + "/api";
    }

    @Override public String toString() { return name; }
}
