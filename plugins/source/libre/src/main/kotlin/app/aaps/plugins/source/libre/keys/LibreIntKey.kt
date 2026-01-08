package app.aaps.plugins.source.libre.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey

enum class LibreIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val calculatedDefaultValue: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val exportable: Boolean = true
) : IntPreferenceKey {

    // Connection lost alert threshold in minutes
    ConnectionLostThresholdMinutes(
        key = "libre_connection_lost_threshold",
        defaultValue = 30,
        min = 5,
        max = 120
    ),

    // Reconnect interval in seconds
    ReconnectIntervalSeconds(
        key = "libre_reconnect_interval",
        defaultValue = 60,
        min = 30,
        max = 300
    )
}
