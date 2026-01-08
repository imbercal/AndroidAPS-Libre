package app.aaps.plugins.source.libre.data

/**
 * Metadata about a Libre sensor
 */
data class LibreSensorInfo(
    val serialNumber: String,               // Sensor serial number
    val startTime: Long,                    // Sensor start timestamp (milliseconds)
    val expiryTime: Long,                   // Sensor expiry timestamp (milliseconds)
    val sensorType: LibreSensorType,        // Libre 2 or Libre 3
    val firmwareVersion: String? = null,    // Sensor firmware (if available)
    val patchInfo: ByteArray? = null        // Raw patch info bytes (for protocol handling)
) {
    companion object {
        // Libre sensors have a 14-day lifespan
        const val SENSOR_LIFESPAN_MS = 14L * 24 * 60 * 60 * 1000 // 14 days in milliseconds

        // Warm-up period after insertion
        const val WARMUP_PERIOD_MS = 60L * 60 * 1000 // 1 hour in milliseconds
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LibreSensorInfo

        if (serialNumber != other.serialNumber) return false
        if (startTime != other.startTime) return false
        if (expiryTime != other.expiryTime) return false
        if (sensorType != other.sensorType) return false
        if (firmwareVersion != other.firmwareVersion) return false
        if (patchInfo != null) {
            if (other.patchInfo == null) return false
            if (!patchInfo.contentEquals(other.patchInfo)) return false
        } else if (other.patchInfo != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = serialNumber.hashCode()
        result = 31 * result + startTime.hashCode()
        result = 31 * result + expiryTime.hashCode()
        result = 31 * result + sensorType.hashCode()
        result = 31 * result + (firmwareVersion?.hashCode() ?: 0)
        result = 31 * result + (patchInfo?.contentHashCode() ?: 0)
        return result
    }
}
