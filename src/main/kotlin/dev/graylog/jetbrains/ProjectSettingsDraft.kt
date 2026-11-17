package dev.graylog.jetbrains

/** Editable Settings subset. Search history/selection and saved searches remain owned by the workspace. */
class ProjectSettingsDraft private constructor(
    var preferredConnection: String,
    var highlightMode: Int,
    var defaults: ProjectProfile,
    var profiles: MutableList<ProjectProfile>,
) {
    companion object {
        @JvmStatic fun from(value: GraylogSettings.Data) = ProjectSettingsDraft(value.preferredConnection, value.highlightMode,
            value.defaults.copy(), value.profiles.map { it.copy() }.toMutableList())
    }
    fun snapshot() = ProjectSettingsDraft(preferredConnection, highlightMode, defaults.copy(), profiles.map { it.copy() }.toMutableList())
    fun matches(value: GraylogSettings.Data) = SettingsValues.same(this, from(value))
    fun merge(current: GraylogSettings.Data, previousPreference: String): GraylogSettings.Data = current.copy().also {
        it.preferredConnection = preferredConnection; it.highlightMode = highlightMode
        it.defaults = defaults.copy(); it.profiles = profiles.map { profile -> profile.copy() }.toMutableList()
        if (preferredConnection != previousPreference) it.selectedConnection = preferredConnection
    }
}
