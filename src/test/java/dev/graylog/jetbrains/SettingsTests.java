package dev.graylog.jetbrains;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Settings model tests do not create an IDE application or access a credential store. */
final class SettingsTests {
    private static int assertions;
    static int run() throws Exception { migration(); profilesAndFields(); validation(); serialization(); return assertions; }

    private static void migration() {
        GraylogIdeSettings.Data global = new GraylogIdeSettings.Data();
        GraylogSettings.Data a = legacy("one", "https://logs.example/api", "/work/project-a");
        a.connections.getFirst().serviceField = "application"; a.highlightExceptions = false;
        GraylogSettings.SavedSearch saved = new GraylogSettings.SavedSearch(); saved.connectionId = "one"; saved.query = "level:3";
        a.searches.add(saved); a.lastQuery = "request_id:abc";
        GraylogSettings.migrate(a, global);
        eq(1, a.schemaVersion); eq(0, a.connections.size()); eq(1, global.connections.size());
        eq("one", global.connections.getFirst().credentialKey()); eq("one", a.selectedConnection);
        eq("/work/project-a", a.profiles.getFirst().mappings.getFirst().localRoot);
        eq("application", a.profiles.getFirst().fields.serviceField); eq(2, a.highlightMode);
        eq("level:3", a.searches.getFirst().query); eq("request_id:abc", a.lastQuery);
        GraylogSettings.migrate(a, global); eq(1, global.connections.size()); eq(1, a.profiles.size());
        GraylogSettings.Data b = legacy("one", "https://logs.example/api", "/work/project-b");
        GraylogSettings.migrate(b, global); eq(1, global.connections.size());
        eq("/work/project-b", b.profiles.getFirst().mappings.getFirst().localRoot);
        eq("/work/project-a", a.profiles.getFirst().mappings.getFirst().localRoot);

        GraylogSettings.Data conflict = legacy("one", "https://other.example/api", "/work/other");
        GraylogSettings.SavedSearch oldSearch = new GraylogSettings.SavedSearch(); oldSearch.connectionId = "one";
        conflict.searches.add(oldSearch); GraylogSettings.migrate(conflict, global);
        eq(2, global.connections.size()); check(!conflict.selectedConnection.equals("one"), "A conflicting legacy connection gets a separate identity");
        eq(conflict.selectedConnection, conflict.profiles.getFirst().connectionId);
        eq(conflict.selectedConnection, conflict.searches.getFirst().connectionId);
        eq("one", global.connections.get(1).credentialKey());
        eq("https://logs.example/api", global.connections.getFirst().apiUrl);
        GraylogSettings.migrate(legacy("one", "https://other.example/api", "/work/third"), global); eq(2, global.connections.size());

        global.connections.removeIf(c -> c.id.equals("one")); global.removedConnections.add("one");
        GraylogSettings.migrate(legacy("one", "https://logs.example/api", "/work/unopened"), global);
        eq(1, global.connections.size());
        GraylogSettings.Data fresh = new GraylogSettings.Data(); GraylogSettings.migrate(fresh, global);
        eq(0, fresh.rangeSeconds); eq(0, fresh.highlightMode);
        GraylogSettings.Data copy = a.copy(); copy.profiles.getFirst().mappings.getFirst().localRoot = "/changed";
        eq("/work/project-a", a.profiles.getFirst().mappings.getFirst().localRoot);
        GraylogIdeSettings.Data globalCopy = global.copy(); globalCopy.options.liveIntervalSeconds = 42;
        eq(5, global.options.liveIntervalSeconds);
    }

    private static void profilesAndFields() {
        Connection c = new Connection(); c.localRoot = "/work/app";
        c.mappings = new ArrayList<>(List.of(new PathMapping("/application", "/work/app"),
                new PathMapping("/application/vendor", "/dependencies"), new PathMapping("/packages", "/work/packages")));
        eq(Path.of("/dependencies/Lib.php"), new SourceLocation("/application/vendor/Lib.php", 1).resolve(c, Path.of("/work")));
        eq(Path.of("/work/app/src/X.php"), new SourceLocation("/application/src/X.php", 1).resolve(c, Path.of("/work")));
        eq(null, new SourceLocation("/application/vendor/../../escape.php", 1).resolve(c, Path.of("/work")));
        eq(null, new SourceLocation("/application-other/X.php", 1).resolve(c, Path.of("/work")));
        eq(Path.of("/dependencies/Lib.php"), new SourceLocation("/dependencies/Lib.php", 1).resolve(c, Path.of("/work")));
        c.mappings = new ArrayList<>(List.of(new PathMapping("/", "/work/root")));
        eq(Path.of("/work/root/folder/X.php"), new SourceLocation("/folder/X.php", 1).resolve(c, Path.of("/work")));

        LogFormat format = new LogFormat(); format.serviceField = "service.name"; format.exceptionTypeField = "err.kind";
        format.exceptionObjectField = "err"; format.fileField = "err.file"; format.lineField = "err.line";
        format.requestIdField = "ctx.rid"; format.stackTraceField = "details.trace"; format.validate();
        JsonObject data = JsonParser.parseString("""
                {"service":{"name":"checkout"},"err":{"kind":"OutOfStock","file":"/application/src/X.php","line":17},
                 "ctx":{"rid":"abc"},"details":{"trace":"#0 /application/src/X.php:17"}}
                """).getAsJsonObject();
        LogEvent event = new LogEvent("id", "index", data, format);
        eq("checkout", event.service(format.serviceField)); eq("abc", event.text("request_id")); eq("ctx.rid", event.fieldName("request_id"));
        eq("OutOfStock", ExceptionSites.exceptionName(event)); eq("/application/src/X.php", SourceLocation.find(event).getFirst().path());
        eq(17, SourceLocation.find(event).getFirst().line());
        check(format.exportFields().containsAll(List.of("ctx.rid", "ctx", "err.kind", "err", "details")), "Export includes nested roots for lossless JSON hydration");
        check(!event.fields().has("request_id"), "Configured lookup leaves original JSON untouched");
        data.addProperty("ctx.rid", "literal-dot-field"); event = new LogEvent("id", "index", data, format); eq("literal-dot-field", event.text("request_id"));
        data.remove("ctx.rid"); data.remove("ctx"); data.addProperty("request_id", "legacy");
        event = new LogEvent("id", "index", data, format);
        eq("legacy", event.text("request_id")); eq("request_id", event.fieldName("request_id"));
        data.add("ctx", JsonParser.parseString("{\"rid\":null}")); event = new LogEvent("id", "index", data, format); eq("request_id", event.fieldName("request_id"));
        c = new Connection(); c.localRoot = "/work";
        ExceptionSites.Accumulator accumulator = new ExceptionSites.Accumulator(c, Path.of("/work"), 3);
        for (int i = 0; i < 25; i++) accumulator.accept(new LogEvent("id-" + i, "index", data, format));
        eq(25L, accumulator.finish().getFirst().count()); eq(3, accumulator.finish().getFirst().events().size());
    }

    private static void validation() {
        IdeOptions options = new IdeOptions(); options.validate(); assertions++;
        options.liveIntervalSeconds = 0; rejects(options::validate); options.liveIntervalSeconds = 5;
        options.connectTimeoutSeconds = -1; rejects(options::validate); options.connectTimeoutSeconds = 10;
        options.examplesPerLocation = 0; rejects(options::validate); options.examplesPerLocation = 20;
        options.backgroundDark = "red"; rejects(options::validate); options.backgroundDark = "#3D272A";
        options.defaultPageSize = 999; rejects(options::validate);
        LogFormat fields = new LogFormat(); fields.fileField = "bad field"; rejects(fields::validate);
        ProjectProfile profile = new ProjectProfile(); profile.mappings.add(new PathMapping("/application", "/work")); rejects(profile::validate);
        rejects(() -> new PathMapping("relative/path", "/work").validate());
        rejects(() -> new PathMapping("/application", "relative").validate());
        GraylogIdeSettings.Data global = new GraylogIdeSettings.Data(); global.options.defaultConnection = "missing"; rejects(global::validate);
        ServerConnection server = new ServerConnection(); server.name = "bad"; server.apiUrl = "https://user:password@host"; rejects(server::validate);
    }

    private static void serialization() throws Exception {
        GraylogIdeSettings.Data global = new GraylogIdeSettings.Data(); GraylogSettings.Data local = legacy("id", "https://logs.example/api", "/private/project");
        GraylogSettings.migrate(local, global);
        global.options.sourceColors.put("demo-api", "#C05090"); global.options.sourceStripes = false;
        String globalXml = JDOMUtil.writeElement(XmlSerializer.serialize(global));
        check(!globalXml.contains("/private/project"), "IDE storage contains no project source paths");
        check(!globalXml.contains("remoteRoot") && !globalXml.contains("serviceField"), "Project mappings and log fields remain project-scoped");
        String projectXml = JDOMUtil.writeElement(XmlSerializer.serialize(local));
        check(projectXml.contains("/private/project"), "Project storage preserves source mappings");
        check(!projectXml.contains("https://logs.example"), "Server URLs are removed from migrated project state");
        GraylogIdeSettings.Data restored = XmlSerializer.deserialize(JDOMUtil.load(globalXml), GraylogIdeSettings.Data.class);
        eq(global.connections.getFirst().id, restored.connections.getFirst().id);
        eq("#C05090", restored.options.sourceColors.get("demo-api")); eq(false, restored.options.sourceStripes);
        GraylogSettings.Data projectRestored = XmlSerializer.deserialize(JDOMUtil.load(projectXml), GraylogSettings.Data.class);
        eq("/private/project", projectRestored.profiles.getFirst().mappings.getFirst().localRoot);
        check(!new Gson().toJson(global).contains("password"), "Persisted settings models have no password field");
    }
    private static GraylogSettings.Data legacy(String id, String api, String local) {
        GraylogSettings.Data data = new GraylogSettings.Data(); Connection c = new Connection();
        c.id = id; c.name = "Stage"; c.apiUrl = api; c.webUrl = api.replaceFirst("/api$", ""); c.localRoot = local;
        data.connections.add(c); data.selectedConnection = id; return data;
    }
    private static void rejects(Runnable action) { try { action.run(); throw new AssertionError("Expected invalid settings"); } catch (IllegalArgumentException expected) { assertions++; } }
    private static void eq(Object expected, Object actual) { check(java.util.Objects.equals(expected, actual), "Expected " + expected + ", got " + actual); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
