package dev.graylog.jetbrains;

import java.util.List;

/** Effects on the active operation, independent of how the Settings UI produced the change. */
public record SettingsImpact(boolean page, boolean index, boolean sources, boolean streams) {
    public static SettingsImpact between(OperationConfig before, OperationConfig after) {
        var a = before.server(); var b = after.server();
        boolean endpoint = !List.of(a.id(), a.apiUrl(), a.credentialKey()).equals(List.of(b.id(), b.apiUrl(), b.credentialKey()));
        boolean network = !before.transport().equals(after.transport());
        boolean service = !a.serviceField().equals(b.serviceField());
        boolean format = !a.fields().equals(b.fields());
        boolean mapping = !a.mappings().equals(b.mappings()) || !a.remoteRoot().equals(b.remoteRoot()) || !a.localRoot().equals(b.localRoot());
        boolean page = endpoint || network || service || format;
        return new SettingsImpact(page, page || mapping || !before.scan().equals(after.scan()), endpoint || network || service, endpoint);
    }
}
