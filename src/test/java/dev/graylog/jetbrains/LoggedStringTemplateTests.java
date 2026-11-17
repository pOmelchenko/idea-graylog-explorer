package dev.graylog.jetbrains;

import java.util.Objects;
final class LoggedStringTemplateTests {
    private static int count;
    static int run() {
        eq("+12025550123", LoggedStringTemplate.extract("Invalid phone: %s", "Invalid phone: +12025550123"));
        eq("hello!", LoggedStringTemplate.extract("Bad: %s!", "Bad: hello!!"));
        eq("", LoggedStringTemplate.extract("Bad: %s!", "Bad: !"));
        eq("a\nb", LoggedStringTemplate.extract("%s", "a\nb"));
        eq("x%y", LoggedStringTemplate.extract("100%% %s %%", "100% x%y %"));
        for (String format : new String[]{"%s %s", "%d", "%.3s", "%1$s", "%s%", "fixed", "%10s", "%S", "%s%q"}) eq(null, LoggedStringTemplate.extract(format, "value"));
        eq(null, LoggedStringTemplate.extract("Bad: %s!", "Wrong: x!"));
        eq(null, LoggedStringTemplate.extract("Bad: %s!", "Bad: x?"));
        eq(null, LoggedStringTemplate.extract("aa%saa", "aaa"));
        eq("can't \\n", LoggedStringTemplate.singleQuoted("'can\\'t \\n'"));
        eq("a\\'b", LoggedStringTemplate.singleQuoted("'a\\\\\\'b'"));
        eq(" = \"a\\nb\\t\\\"\" · Graylog", LoggedStringTemplate.display("a\nb\t\""));
        if (LoggedStringTemplate.display("🙂".repeat(200)).codePointCount(0, LoggedStringTemplate.display("🙂".repeat(200)).length()) > 115) throw new AssertionError("Value display is unbounded");
        return count + 1;
    }
    private static void eq(Object expected, Object actual) { count++; if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual); }
}
