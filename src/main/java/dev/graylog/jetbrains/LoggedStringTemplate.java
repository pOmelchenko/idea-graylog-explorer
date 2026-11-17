package dev.graylog.jetbrains;

/** Inverts only a lossless sprintf template with one plain %s slot. */
final class LoggedStringTemplate {
    private LoggedStringTemplate() { }
    static String extract(String format, String message) {
        StringBuilder prefix = new StringBuilder(), suffix = new StringBuilder();
        boolean slot = false;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '%') {
                if (++i == format.length()) return null;
                c = format.charAt(i);
                if (c == 's' && !slot) { slot = true; continue; }
                if (c != '%') return null;
            }
            (slot ? suffix : prefix).append(c);
        }
        if (!slot || !message.startsWith(prefix.toString()) || !message.endsWith(suffix.toString())
                || message.length() < prefix.length() + suffix.length()) return null;
        return message.substring(prefix.length(), message.length() - suffix.length());
    }
    static String singleQuoted(String literal) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 1; i < literal.length() - 1; i++) {
            char c = literal.charAt(i);
            if (c == '\\' && i + 1 < literal.length() - 1 && (literal.charAt(i + 1) == '\\' || literal.charAt(i + 1) == '\'')) c = literal.charAt(++i);
            decoded.append(c);
        }
        return decoded.toString();
    }
    static String display(String value) {
        var gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
        int points = value.codePointCount(0, value.length());
        String shown = points <= 96 ? value : value.substring(0, value.offsetByCodePoints(0, 96)) + "…";
        String safe = gson.toJson(shown);
        while (safe.codePointCount(0, safe.length()) > 100) {
            int length = shown.codePointCount(0, shown.length());
            shown = shown.substring(0, shown.offsetByCodePoints(0, length - 2)) + "…";
            safe = gson.toJson(shown);
        }
        return " = " + safe + " · Graylog";
    }
}
