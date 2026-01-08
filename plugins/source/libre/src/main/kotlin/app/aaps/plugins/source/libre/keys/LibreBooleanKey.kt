package app.aaps.plugins.source.libre.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey

enum class LibreBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
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
) : BooleanPreferenceKey {

    // Expiry warning toggles
    ExpiryWarning24h("libre_expiry_warning_24h", true),
    ExpiryWarning12h("libre_expiry_warning_12h", true),
    ExpiryWarning1h("libre_expiry_warning_1h", true),

    // Alert toggles
    ConnectionLostAlert("libre_connection_lost_alert", true),
    SignalQualityAlert("libre_signal_quality_alert", false),

    // Connection behavior
    AutoReconnect("libre_auto_reconnect", true),

    // Nightscout integration
    UploadToNightscout("libre_upload_to_ns", true),
    CreateSensorChangeEvents("libre_create_sensor_change", true)
}
