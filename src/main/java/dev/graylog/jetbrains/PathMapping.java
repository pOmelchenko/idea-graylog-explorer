package dev.graylog.jetbrains;

import java.nio.file.Path;

public final class PathMapping {
    public String remoteRoot = "/application";
    public String localRoot = "";
    public PathMapping() { }
    public PathMapping(String remote, String local) { remoteRoot = remote; localRoot = local; }
    public PathMapping copy() { return new PathMapping(remoteRoot, localRoot); }
    public void validate() {
        if (!(remoteRoot.startsWith("/") || remoteRoot.matches("[A-Za-z]:[\\\\/].*"))) {
            throw new IllegalArgumentException("Remote source roots must be absolute paths.");
        }
        if (!localRoot.isBlank() && !Path.of(localRoot).isAbsolute()) throw new IllegalArgumentException("Local source roots must be absolute, or blank for the project directory.");
    }
}
