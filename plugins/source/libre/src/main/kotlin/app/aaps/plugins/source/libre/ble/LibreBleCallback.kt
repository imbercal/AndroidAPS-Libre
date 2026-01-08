package app.aaps.plugins.source.libre.ble

import app.aaps.plugins.source.libre.data.LibreGlucoseReading
import app.aaps.plugins.source.libre.data.LibreSensorInfo

/**
 * Callback interface for Libre BLE communication events.
 */
interface LibreBleCallback {

    /**
     * Called when BLE connection is established
     */
    fun onConnected()

    /**
     * Called when BLE connection is lost
     * @param reason Description of why connection was lost
     */
    fun onDisconnected(reason: String)

    /**
     * Called when glucose readings are received from the sensor
     * @param readings List of glucose readings (may include historical data)
     */
    fun onGlucoseData(readings: List<LibreGlucoseReading>)

    /**
     * Called when sensor info is received
     * @param info Sensor metadata (serial, start time, etc.)
     */
    fun onSensorInfo(info: LibreSensorInfo)

    /**
     * Called when authentication is complete
     * @param success Whether authentication succeeded
     */
    fun onAuthenticationComplete(success: Boolean)

    /**
     * Called when a BLE error occurs
     * @param error Error type
     * @param message Human-readable error message
     */
    fun onError(error: LibreBleError, message: String)
}

/**
 * Types of BLE errors that can occur
 */
enum class LibreBleError {
    BLUETOOTH_DISABLED,
    PERMISSION_DENIED,
    SCAN_FAILED,
    CONNECTION_FAILED,
    AUTHENTICATION_FAILED,
    GATT_ERROR,
    SENSOR_NOT_FOUND,
    PROTOCOL_ERROR,
    TIMEOUT
}
