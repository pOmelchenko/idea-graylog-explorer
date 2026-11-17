package dev.graylog.jetbrains;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/** Shows the log's actual messages; never infers variable bindings from message text. */
final class ExceptionPresentation {
    private ExceptionPresentation() { }
    static List<String> messages(ExceptionSites.Site site, Connection connection, Path root, LogEvent selected) {
        LinkedHashSet<String> messages = new LinkedHashSet<>();
        if (connection != null) {
            if (selected != null) add(messages, selected, site, connection, root);
            for (LogEvent event : site.events()) {
                add(messages, event, site, connection, root);
                if (messages.size() >= 3) break;
            }
        }
        if (messages.isEmpty() && site.types().contains("Selected stack frame") && !site.events().isEmpty()) {
            String message = clean(site.events().getFirst().text("message"));
            if (!message.isBlank()) messages.add(message);
        }
        return messages.stream().limit(3).toList();
    }
    private static void add(LinkedHashSet<String> messages, LogEvent event, ExceptionSites.Site site, Connection connection, Path root) {
        var details = ExceptionSites.detailsAt(event, connection, root, site.location());
        for (int index = details.size() - 1; index >= 0; index--) {
            ExceptionSites.Detail detail = details.get(index);
            String message = clean(detail.message());
            if (!message.isBlank() && !message.equals(detail.type())) messages.add(message);
        }
    }
    static String inline(ExceptionSites.Site site, List<String> messages) {
        if (messages.isEmpty()) return "";
        return "Graylog: " + shorten(messages.getFirst(), 160) + (site.count() > 1 ? " · " + site.count() + " events" : "");
    }
    private static String clean(String message) { return shorten(message.replaceAll("\\p{Cntrl}", " ").strip(), 500); }
    private static String shorten(String value, int length) {
        return value.codePointCount(0, value.length()) > length ? value.substring(0, value.offsetByCodePoints(0, length)) + "…" : value;
    }
}
