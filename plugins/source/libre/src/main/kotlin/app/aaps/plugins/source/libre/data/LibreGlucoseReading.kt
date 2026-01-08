package app.aaps.plugins.source.libre.data

import app.aaps.core.data.model.TrendArrow

/**
 * Represents a single glucose reading from a Libre sensor
 */
data class LibreGlucoseReading(
    val timestamp: Long,                    // Milliseconds since epoch
    val glucoseValue: Double,               // Glucose value in mg/dL
    val trend: TrendArrow,                  // Trend direction
    val quality: GlucoseQuality,            // Reading quality
    val rawValue: Double? = null,           // Raw sensor value (if available)
    val temperature: Float? = null          // Sensor temperature (if available)
)
