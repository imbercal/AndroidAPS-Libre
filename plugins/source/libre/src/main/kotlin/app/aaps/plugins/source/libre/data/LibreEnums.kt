package app.aaps.plugins.source.libre.data

/**
 * Connection state for Libre BLE communication
 */
enum class LibreConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    AUTHENTICATING,
    RECONNECTING
}

/**
 * Sensor operational state
 */
enum class LibreSensorState {
    NONE,           // No sensor detected
    STARTING,       // Sensor warming up (first hour)
    READY,          // Sensor operational
    ENDING,         // Sensor near end of life
    EXPIRED,        // Sensor expired
    ERROR,          // Sensor error state
    UNKNOWN         // Unknown state
}

/**
 * Libre sensor type/generation
 */
enum class LibreSensorType {
    UNKNOWN,
    LIBRE_2,        // FreeStyle Libre 2
    LIBRE_3         // FreeStyle Libre 3
}

/**
 * Glucose reading quality indicator
 */
enum class GlucoseQuality {
    GOOD,           // Normal reading
    DEGRADED,       // Reduced accuracy (e.g., sensor settling)
    UNRELIABLE      // Reading should not be trusted
}
