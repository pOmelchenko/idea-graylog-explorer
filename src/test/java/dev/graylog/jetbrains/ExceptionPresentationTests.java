package dev.graylog.jetbrains;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ExceptionPresentationTests {
    private static int assertions;
    static int run() throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(Path.of("src/test/resources/yii-exception.json"))).getAsJsonObject();
        Connection connection = new Connection(); Path root = Path.of("/work");
        LogEvent first = event(fixture, "first", "+12025550123");
        LogEvent second = event(fixture, "second", "+12025550124");
        LogEvent third = event(fixture, "third", "+12025550125");
        var sites = CoreTests.sites(List.of(first), connection, root);
        var domain = at(sites, 46); var application = at(sites, 91); var wrapper = at(sites, 59);
        eq(List.of("Invalid phone: +12025550123"), ExceptionPresentation.messages(domain, connection, root, first));
        eq(List.of("Phone number validation failed."), ExceptionPresentation.messages(application, connection, root, first));
        eq(List.of("Invalid phone number."), ExceptionPresentation.messages(wrapper, connection, root, first));
        eq("Graylog: Invalid phone: +12025550123", ExceptionPresentation.inline(domain, ExceptionPresentation.messages(domain, connection, root, first)));
        var all = new ExceptionSites.Accumulator(connection, root); all.accept(first); all.accept(second); all.accept(first);
        domain = at(all.finish(), 46); eq(3L, domain.count());
        eq(List.of("Invalid phone: +12025550123", "Invalid phone: +12025550124"), ExceptionPresentation.messages(domain, connection, root, null));
        eq("Graylog: Invalid phone: +12025550124 · 3 events", ExceptionPresentation.inline(domain, ExceptionPresentation.messages(domain, connection, root, second)));
        var limited = new ExceptionSites.Accumulator(connection, root, 1); limited.accept(first); limited.accept(second); limited.accept(third);
        domain = at(limited.finish(), 46); eq(1, domain.events().size());
        eq(List.of("Invalid phone: +12025550125", "Invalid phone: +12025550123"), ExceptionPresentation.messages(domain, connection, root, third));
        JsonObject different = first.fields().deepCopy();
        JsonObject chain = JsonParser.parseString(different.get("ctxt_exception").getAsString()).getAsJsonObject();
        chain.getAsJsonObject("previous").getAsJsonObject("previous").addProperty("file", "/application/src/Other.php:7");
        different.addProperty("ctxt_exception", chain.toString());
        eq(List.of("Invalid phone: +12025550123"), ExceptionPresentation.messages(domain, connection, root, new LogEvent("different", "", different)));
        chain.getAsJsonObject("previous").getAsJsonObject("previous").addProperty("file", chain.get("file").getAsString());
        different.addProperty("ctxt_exception", chain.toString()); LogEvent shared = new LogEvent("shared", "", different);
        var sharedSite = at(CoreTests.sites(List.of(shared), connection, root), 59);
        eq(List.of("Invalid phone: +12025550123", "Invalid phone number."), ExceptionPresentation.messages(sharedSite, connection, root, shared));
        JsonObject simple = JsonParser.parseString("{\"exception_class\":\"Error\",\"exception_file\":\"/application/src/Worker.php\",\"exception_line\":9,\"message\":\"value=bad\\nnext\\tline\"}").getAsJsonObject();
        LogEvent legacy = new LogEvent("legacy", "", simple);
        var legacySite = CoreTests.sites(List.of(legacy), connection, root).getFirst();
        eq(List.of("value=bad next line"), ExceptionPresentation.messages(legacySite, connection, root, legacy));
        simple.addProperty("message", "🙂".repeat(600)); legacy = new LogEvent("legacy", "", simple);
        var longSite = CoreTests.sites(List.of(legacy), connection, root).getFirst();
        var messages = ExceptionPresentation.messages(longSite, connection, root, legacy);
        eq(501, messages.getFirst().codePointCount(0, messages.getFirst().length()));
        eq("Graylog: " + "🙂".repeat(160) + "…", ExceptionPresentation.inline(longSite, messages));
        simple.addProperty("message", "Error"); legacy = new LogEvent("legacy", "", simple); longSite = CoreTests.sites(List.of(legacy), connection, root).getFirst();
        eq(List.of(), ExceptionPresentation.messages(longSite, connection, root, legacy));
        eq("", ExceptionPresentation.inline(longSite, List.of())); return assertions;
    }
    private static ExceptionSites.Site at(List<ExceptionSites.Site> sites, int line) { return sites.stream().filter(s -> s.location().line() == line).findFirst().orElseThrow(); }
    private static LogEvent event(JsonObject fixture, String id, String phone) {
        JsonObject fields = fixture.deepCopy();
        JsonObject chain = JsonParser.parseString(fields.get("ctxt_exception").getAsString()).getAsJsonObject();
        chain.getAsJsonObject("previous").addProperty("message", "Phone number validation failed.");
        JsonObject cause = new JsonObject(); cause.addProperty("class", "Demo\\Domain\\PhoneNumberInvalidException");
        cause.addProperty("file", "/application/src/Domain/Phone.php:46"); cause.addProperty("message", "Invalid phone: " + phone);
        chain.getAsJsonObject("previous").add("previous", cause); fields.addProperty("ctxt_exception", chain.toString());
        return new LogEvent(id, "graylog_fixture", fields);
    }
    private static void eq(Object expected, Object actual) { assertions++; if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual); }
}
