package app.aaps.plugins.source.libre

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.source.libre.data.LibreConnectionState
import app.aaps.plugins.source.libre.data.LibreGlucoseReading
import app.aaps.plugins.source.libre.data.LibreSensorInfo
import app.aaps.plugins.source.libre.data.LibreSensorState
import app.aaps.plugins.source.libre.data.LibreSensorType
import app.aaps.plugins.source.libre.keys.LibreLongNonKey
import app.aaps.plugins.source.libre.keys.LibreStringNonKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State container for Libre CGM data and connection status.
 * Uses StateFlow for reactive UI updates.
 */
@Singleton
class LibreState @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences
) {

    // Connection state flow
    private val _connectionState = MutableStateFlow(LibreConnectionState.DISCONNECTED)
    val connectionStateFlow: StateFlow<LibreConnectionState> = _connectionState
    var connectionState: LibreConnectionState
        get() = _connectionState.value
        set(value) {
            aapsLogger.debug(LTag.BGSOURCE, "Connection state changed: ${_connectionState.value} -> $value")
            _connectionState.value = value
        }

    // Sensor state flow
    private val _sensorState = MutableStateFlow(LibreSensorState.NONE)
    val sensorStateFlow: StateFlow<LibreSensorState> = _sensorState
    var sensorState: LibreSensorState
        get() = _sensorState.value
        set(value) {
            aapsLogger.debug(LTag.BGSOURCE, "Sensor state changed: ${_sensorState.value} -> $value")
            _sensorState.value = value
        }

    // Last glucose reading flow (for UI updates)
    private val _lastGlucoseReading = MutableStateFlow<LibreGlucoseReading?>(null)
    val lastGlucoseReadingFlow: StateFlow<LibreGlucoseReading?> = _lastGlucoseReading
    var lastGlucoseReading: LibreGlucoseReading?
        get() = _lastGlucoseReading.value
        set(value) {
            _lastGlucoseReading.value = value
        }

    // Sensor type
    private var _sensorType = LibreSensorType.UNKNOWN
    var sensorType: LibreSensorType
        get() = _sensorType
        set(value) {
            _sensorType = value
        }

    // Sensor serial number (persisted)
    private var _sensorSerialNumber: String = ""
    var sensorSerialNumber: String
        get() = _sensorSerialNumber
        set(value) {
            _sensorSerialNumber = value
            preferences.put(LibreStringNonKey.SensorSerialNumber, value)
        }

    // Sensor start time (persisted)
    private var _sensorStartTime: Long = 0L
    var sensorStartTime: Long
        get() = _sensorStartTime
        set(value) {
            _sensorStartTime = value
            preferences.put(LibreLongNonKey.SensorStartTime, value)
        }

    // Sensor expiry time (persisted)
    private var _sensorExpiryTime: Long = 0L
    var sensorExpiryTime: Long
        get() = _sensorExpiryTime
        set(value) {
            _sensorExpiryTime = value
            preferences.put(LibreLongNonKey.SensorExpiryTime, value)
        }

    // Last connection time (persisted)
    private var _lastConnectionTime: Long = 0L
    var lastConnectionTime: Long
        get() = _lastConnectionTime
        set(value) {
            _lastConnectionTime = value
            preferences.put(LibreLongNonKey.LastConnectionTime, value)
        }

    // BLE device address (persisted for reconnection)
    private var _deviceAddress: String = ""
    var deviceAddress: String
        get() = _deviceAddress
        set(value) {
            _deviceAddress = value
            preferences.put(LibreStringNonKey.DeviceAddress, value)
        }

    // Sensor battery level (-1 if unknown)
    var sensorBatteryPercent: Int = -1

    // Patch info for encryption (from NFC activation)
    var patchInfo: ByteArray? = null

    /**
     * Load persisted state from preferences
     */
    fun loadFromPreferences() {
        _sensorSerialNumber = preferences.get(LibreStringNonKey.SensorSerialNumber)
        _sensorStartTime = preferences.get(LibreLongNonKey.SensorStartTime)
        _sensorExpiryTime = preferences.get(LibreLongNonKey.SensorExpiryTime)
        _lastConnectionTime = preferences.get(LibreLongNonKey.LastConnectionTime)
        _deviceAddress = preferences.get(LibreStringNonKey.DeviceAddress)

        aapsLogger.debug(LTag.BGSOURCE, "Loaded Libre state from preferences: serial=$_sensorSerialNumber")

        // Update sensor state based on loaded data
        updateSensorState()
    }

    /**
     * Update sensor info from device data
     */
    fun updateFromSensorInfo(info: LibreSensorInfo) {
        sensorSerialNumber = info.serialNumber
        sensorStartTime = info.startTime
        sensorExpiryTime = info.expiryTime
        sensorType = info.sensorType

        updateSensorState()
    }

    /**
     * Calculate and update sensor state based on timing
     */
    private fun updateSensorState() {
        if (_sensorStartTime == 0L) {
            sensorState = LibreSensorState.NONE
            return
        }

        val now = System.currentTimeMillis()
        val warmupEnd = _sensorStartTime + LibreSensorInfo.WARMUP_PERIOD_MS

        sensorState = when {
            now < warmupEnd -> LibreSensorState.STARTING
            now >= _sensorExpiryTime -> LibreSensorState.EXPIRED
            _sensorExpiryTime - now < 24 * 60 * 60 * 1000L -> LibreSensorState.ENDING // Last 24 hours
            else -> LibreSensorState.READY
        }
    }

    /**
     * Clear all sensor data (for sensor removal)
     */
    fun clearSensorData() {
        sensorSerialNumber = ""
        sensorStartTime = 0L
        sensorExpiryTime = 0L
        sensorType = LibreSensorType.UNKNOWN
        sensorBatteryPercent = -1
        lastGlucoseReading = null
        sensorState = LibreSensorState.NONE

        aapsLogger.debug(LTag.BGSOURCE, "Cleared Libre sensor data")
    }

    /**
     * Check if we have a valid sensor configured
     */
    fun hasSensor(): Boolean = _sensorSerialNumber.isNotEmpty() && _sensorStartTime > 0

    /**
     * Get remaining sensor time in milliseconds
     */
    fun getRemainingTimeMs(): Long {
        if (_sensorExpiryTime == 0L) return 0L
        return maxOf(0L, _sensorExpiryTime - System.currentTimeMillis())
    }

    /**
     * Check if sensor is expired
     */
    fun isExpired(): Boolean = _sensorExpiryTime > 0 && System.currentTimeMillis() >= _sensorExpiryTime

    /**
     * Update latest glucose reading
     */
    fun updateLatestReading(reading: LibreGlucoseReading) {
        lastGlucoseReading = reading
    }

    /**
     * Update connection state
     */
    fun updateConnectionState(state: LibreConnectionState) {
        connectionState = state
    }

    /**
     * Update sensor state
     */
    fun updateSensorState(state: LibreSensorState) {
        sensorState = state
    }

    /**
     * Save current state to preferences for persistence
     */
    fun saveToPreferences() {
        preferences.put(LibreStringNonKey.SensorSerialNumber, _sensorSerialNumber)
        preferences.put(LibreLongNonKey.SensorStartTime, _sensorStartTime)
        preferences.put(LibreLongNonKey.SensorExpiryTime, _sensorExpiryTime)
        preferences.put(LibreLongNonKey.LastConnectionTime, _lastConnectionTime)
        preferences.put(LibreStringNonKey.DeviceAddress, _deviceAddress)
        aapsLogger.debug(LTag.BGSOURCE, "Saved Libre state to preferences")
    }
}
