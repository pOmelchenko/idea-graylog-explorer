package dev.graylog.jetbrains;

/** Effects and cache reuse share the same operation identities. */
public record SettingsImpact(boolean page, boolean index, boolean sources, boolean streams) {
    public static SettingsImpact between(OperationConfig before, OperationConfig after) {
        return new SettingsImpact(!OperationKeys.page(before).equals(OperationKeys.page(after)),
                !OperationKeys.index(before).equals(OperationKeys.index(after)),
                !OperationKeys.sources(before).equals(OperationKeys.sources(after)),
                !OperationKeys.endpoint(before).equals(OperationKeys.endpoint(after)));
    }
}
