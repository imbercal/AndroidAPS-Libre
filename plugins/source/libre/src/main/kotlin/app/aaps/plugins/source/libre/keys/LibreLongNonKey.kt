package app.aaps.plugins.source.libre.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class LibreLongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    // Sensor timing
    SensorStartTime("libre_sensor_start_time", 0L),
    SensorExpiryTime("libre_sensor_expiry_time", 0L),
    LastConnectionTime("libre_last_connection_time", 0L),

    // Alert snooze tracking
    NextExpiry24hAlert("libre_next_expiry_24h_alert", 0L),
    NextExpiry12hAlert("libre_next_expiry_12h_alert", 0L),
    NextExpiry1hAlert("libre_next_expiry_1h_alert", 0L),
    NextConnectionLostAlert("libre_next_connection_lost_alert", 0L)
}
