package app.aaps.plugins.source.libre.protocol

import app.aaps.plugins.source.libre.data.LibreGlucoseReading
import app.aaps.plugins.source.libre.data.LibreSensorInfo

/**
 * Abstract base class for Libre sensor communication protocols.
 *
 * Different Libre sensor generations use different BLE protocols:
 * - Libre 2: Uses encrypted communication with sensor-specific keys
 * - Libre 3: Uses a different encryption scheme and message format
 *
 * Implementations are adapted from xDrip+ open source code.
 */
abstract class LibreProtocol {

    /**
     * Protocol state
     */
    enum class State {
        IDLE,
        AUTHENTICATING,
        AUTHENTICATED,
        READING,
        ERROR
    }

    protected var state: State = State.IDLE

    /**
     * Callback for protocol events
     */
    interface Callback {
        fun onGlucoseData(readings: List<LibreGlucoseReading>)
        fun onSensorInfo(info: LibreSensorInfo)
        fun onAuthenticationComplete(success: Boolean)
        fun onError(message: String)
        fun sendData(data: ByteArray)
    }

    protected var callback: Callback? = null

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    /**
     * Initialize the protocol with sensor info (if available from NFC)
     */
    abstract fun initialize(sensorInfo: LibreSensorInfo?)

    /**
     * Handle data received from the sensor
     */
    abstract fun handleData(data: ByteArray)

    /**
     * Start the authentication/unlock process
     */
    abstract fun startAuthentication()

    /**
     * Request glucose data from the sensor
     */
    abstract fun requestGlucoseData()

    /**
     * Parse raw glucose data into readings
     */
    abstract fun parseGlucoseData(data: ByteArray): List<LibreGlucoseReading>

    /**
     * Parse sensor info from data
     */
    abstract fun parseSensorInfo(data: ByteArray): LibreSensorInfo?

    /**
     * Reset protocol state
     */
    open fun reset() {
        state = State.IDLE
    }

    /**
     * Check if protocol is ready to read data
     */
    fun isAuthenticated(): Boolean = state == State.AUTHENTICATED

    /**
     * Get current state
     */
    val currentState: State get() = state

    companion object {
        // Libre sensor constants
        const val LIBRE_SENSOR_LIFESPAN_MINUTES = 14 * 24 * 60 // 14 days
        const val LIBRE_WARMUP_MINUTES = 60 // 1 hour

        // Reading interval
        const val READING_INTERVAL_MINUTES = 1 // Libre updates every minute

        // History size
        const val HISTORY_SIZE = 32 // 32 historic readings (15-min intervals = 8 hours)
        const val TREND_SIZE = 16 // 16 trend readings (1-min intervals = 16 minutes)
    }
}
