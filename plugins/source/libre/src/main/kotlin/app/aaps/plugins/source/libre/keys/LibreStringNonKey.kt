package app.aaps.plugins.source.libre.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class LibreStringNonKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = true
) : StringNonPreferenceKey {

    // Device identification
    SensorSerialNumber("libre_sensor_serial", ""),
    DeviceAddress("libre_device_address", "")
}
