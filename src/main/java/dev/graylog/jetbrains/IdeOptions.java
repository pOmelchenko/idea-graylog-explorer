package dev.graylog.jetbrains;

import java.util.List;

/** Shared IDE defaults. Contains no project paths and no credentials. */
public final class IdeOptions {
    public int liveIntervalSeconds = 5;
    public int connectTimeoutSeconds = 10;
    public int requestTimeoutSeconds = 30;
    public int exportBatchSize = 500;
    public int examplesPerLocation = 20;
    public int defaultPeriodSeconds = 900;
    public int defaultPageSize = 100;
    public String defaultConnection = "";
    public boolean liveOnOpen = false;
    public boolean autoIndex = true;
    public boolean highlightByDefault = true;
    public boolean gutterIcons = true;
    public boolean errorStripe = true;
    public boolean inlineDetails = true;
    public boolean inlineValues = true;
    public boolean sourceStripes = true;
    public java.util.Map<String, String> sourceColors = new java.util.LinkedHashMap<>();
    public String backgroundLight = "#FFF1ED";
    public String backgroundDark = "#3D272A";
    public String selectedLight = "#FFD3C6";
    public String selectedDark = "#5F2E30";

    public IdeOptions copy() { return SettingsValues.copy(this, IdeOptions.class); }
    public void validate() {
        range(liveIntervalSeconds, 1, 3600, "Live interval");
        range(connectTimeoutSeconds, 1, 300, "Connect timeout");
        range(requestTimeoutSeconds, 1, 3600, "Request timeout");
        range(exportBatchSize, 1, 5000, "Export batch size");
        range(examplesPerLocation, 1, 500, "Examples per source location");
        if (!List.of(300, 900, 3600, 86400).contains(defaultPeriodSeconds)) throw new IllegalArgumentException("Choose a supported default period.");
        if (!List.of(100, 250, 500).contains(defaultPageSize)) throw new IllegalArgumentException("Choose a page size of 100, 250 or 500.");
        for (String color : List.of(backgroundLight, backgroundDark, selectedLight, selectedDark)) {
            if (!color.matches("#[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("Highlight colors must use #RRGGBB.");
        }
        for (var entry : sourceColors.entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue() == null || !entry.getValue().matches("#[0-9A-Fa-f]{6}")) {
                throw new IllegalArgumentException("Source color overrides require a source name and a #RRGGBB color.");
            }
        }
    }
    private static void range(int value, int min, int max, String label) {
        if (value < min || value > max) throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
    }
}
