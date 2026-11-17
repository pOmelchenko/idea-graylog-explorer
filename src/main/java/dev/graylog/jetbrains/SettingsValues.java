package dev.graylog.jetbrains;

import com.google.gson.Gson;

final class SettingsValues {
    private static final Gson JSON = new Gson();
    private SettingsValues() { }
    static <T> T copy(T value, Class<T> type) { return JSON.fromJson(JSON.toJsonTree(value), type); }
    static boolean same(Object a, Object b) { return JSON.toJsonTree(a).equals(JSON.toJsonTree(b)); }
}
