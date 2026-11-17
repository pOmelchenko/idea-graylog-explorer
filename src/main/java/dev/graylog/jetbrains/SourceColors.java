package dev.graylog.jetbrains;

import java.awt.Color;
import java.util.Map;

/** Colors depend only on the exact service/source name, never page order or severity. */
final class SourceColors {
    private static final String[] PALETTE = {"#4B86C6", "#E49A49", "#6A9955", "#C76262", "#9A74C2", "#4CA6A8", "#C86C9B", "#A5994C", "#768BC3", "#A37C62", "#5AA684", "#B176B5"};
    private SourceColors() { }
    static Color color(String source, Map<String, String> overrides) {
        if (source == null || source.isBlank()) return null;
        String hex = overrides.getOrDefault(source, PALETTE[Math.floorMod(source.hashCode(), PALETTE.length)]);
        try { return Color.decode(hex); } catch (NumberFormatException e) { return Color.decode(PALETTE[Math.floorMod(source.hashCode(), PALETTE.length)]); }
    }
    static String hex(Color color) { return String.format("#%06X", color.getRGB() & 0xFFFFFF); }
}
