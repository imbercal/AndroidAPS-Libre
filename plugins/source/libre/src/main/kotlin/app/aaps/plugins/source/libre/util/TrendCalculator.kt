package app.aaps.plugins.source.libre.util

import app.aaps.core.data.model.TrendArrow
import app.aaps.plugins.source.libre.data.GlucoseQuality
import app.aaps.plugins.source.libre.data.LibreGlucoseReading
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Utility for calculating glucose trend arrows from a series of readings.
 *
 * Uses linear regression over recent readings to determine the rate of change,
 * then maps to standard CGM trend arrows.
 */
@Singleton
class TrendCalculator @Inject constructor() {

    companion object {
        // Trend calculation window (milliseconds)
        private const val TREND_WINDOW_MS = 15 * 60 * 1000L // 15 minutes

        // Minimum readings needed for trend calculation
        private const val MIN_READINGS_FOR_TREND = 3

        // Trend thresholds (mg/dL per minute)
        private const val DOUBLE_UP_THRESHOLD = 3.0      // >3 mg/dL/min = ↑↑
        private const val SINGLE_UP_THRESHOLD = 2.0      // 2-3 mg/dL/min = ↑
        private const val FORTY_FIVE_UP_THRESHOLD = 1.0  // 1-2 mg/dL/min = ↗
        private const val FLAT_THRESHOLD = 0.5           // <1 mg/dL/min = →
        // Negative values for down arrows

        // Noise thresholds for quality assessment
        private const val LOW_NOISE_THRESHOLD = 5.0   // mg/dL
        private const val HIGH_NOISE_THRESHOLD = 15.0 // mg/dL
    }

    /**
     * Calculate trend arrow from a list of readings.
     *
     * @param readings List of glucose readings (should be sorted by timestamp)
     * @return TrendArrow indicating direction and rate of glucose change
     */
    fun calculateTrend(readings: List<LibreGlucoseReading>): TrendArrow {
        if (readings.size < MIN_READINGS_FOR_TREND) {
            return TrendArrow.NONE
        }

        val now = System.currentTimeMillis()
        val cutoff = now - TREND_WINDOW_MS

        // Filter to readings within the trend window
        val recentReadings = readings.filter {
            it.timestamp >= cutoff && it.quality != GlucoseQuality.UNRELIABLE
        }.sortedBy { it.timestamp }

        if (recentReadings.size < MIN_READINGS_FOR_TREND) {
            return TrendArrow.NONE
        }

        // Calculate rate of change using linear regression
        val ratePerMinute = calculateRateOfChange(recentReadings)

        return mapRateToTrendArrow(ratePerMinute)
    }

    /**
     * Calculate rate of glucose change using linear regression.
     *
     * @param readings List of readings sorted by timestamp
     * @return Rate of change in mg/dL per minute
     */
    private fun calculateRateOfChange(readings: List<LibreGlucoseReading>): Double {
        if (readings.size < 2) return 0.0

        // Convert timestamps to minutes relative to first reading
        val firstTime = readings.first().timestamp
        val points = readings.map { reading ->
            Pair((reading.timestamp - firstTime) / 60000.0, reading.glucoseValue)
        }

        // Simple linear regression: y = mx + b
        val n = points.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0

        for ((x, y) in points) {
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
        }

        val denominator = n * sumX2 - sumX * sumX
        if (abs(denominator) < 0.0001) {
            return 0.0 // Avoid division by zero
        }

        // Slope (m) = rate of change per minute
        val slope = (n * sumXY - sumX * sumY) / denominator

        return slope
    }

    /**
     * Map rate of change to trend arrow.
     *
     * @param ratePerMinute Rate of glucose change in mg/dL per minute
     * @return Corresponding TrendArrow
     */
    private fun mapRateToTrendArrow(ratePerMinute: Double): TrendArrow {
        return when {
            ratePerMinute >= DOUBLE_UP_THRESHOLD -> TrendArrow.DOUBLE_UP
            ratePerMinute >= SINGLE_UP_THRESHOLD -> TrendArrow.SINGLE_UP
            ratePerMinute >= FORTY_FIVE_UP_THRESHOLD -> TrendArrow.FORTY_FIVE_UP
            ratePerMinute >= FLAT_THRESHOLD -> TrendArrow.FLAT
            ratePerMinute >= -FLAT_THRESHOLD -> TrendArrow.FLAT
            ratePerMinute >= -FORTY_FIVE_UP_THRESHOLD -> TrendArrow.FORTY_FIVE_DOWN
            ratePerMinute >= -SINGLE_UP_THRESHOLD -> TrendArrow.SINGLE_DOWN
            ratePerMinute >= -DOUBLE_UP_THRESHOLD -> TrendArrow.DOUBLE_DOWN
            else -> TrendArrow.DOUBLE_DOWN
        }
    }

    /**
     * Calculate noise level from readings.
     *
     * @param readings List of glucose readings
     * @return GlucoseQuality indicating signal quality
     */
    fun calculateNoiseLevel(readings: List<LibreGlucoseReading>): GlucoseQuality {
        if (readings.size < MIN_READINGS_FOR_TREND) {
            return GlucoseQuality.GOOD
        }

        val now = System.currentTimeMillis()
        val cutoff = now - TREND_WINDOW_MS

        val recentReadings = readings.filter { it.timestamp >= cutoff }
            .sortedBy { it.timestamp }

        if (recentReadings.size < MIN_READINGS_FOR_TREND) {
            return GlucoseQuality.GOOD
        }

        // Calculate standard deviation of residuals from trend line
        val noise = calculateResidualNoise(recentReadings)

        return when {
            noise > HIGH_NOISE_THRESHOLD -> GlucoseQuality.UNRELIABLE
            noise > LOW_NOISE_THRESHOLD -> GlucoseQuality.DEGRADED
            else -> GlucoseQuality.GOOD
        }
    }

    /**
     * Calculate noise as standard deviation of residuals from linear fit.
     */
    private fun calculateResidualNoise(readings: List<LibreGlucoseReading>): Double {
        if (readings.size < 2) return 0.0

        val firstTime = readings.first().timestamp
        val points = readings.map { reading ->
            Pair((reading.timestamp - firstTime) / 60000.0, reading.glucoseValue)
        }

        // Calculate linear regression parameters
        val n = points.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0

        for ((x, y) in points) {
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
        }

        val denominator = n * sumX2 - sumX * sumX
        if (abs(denominator) < 0.0001) return 0.0

        val slope = (n * sumXY - sumX * sumY) / denominator
        val intercept = (sumY - slope * sumX) / n

        // Calculate residuals
        var sumSquaredResiduals = 0.0
        for ((x, y) in points) {
            val predicted = slope * x + intercept
            val residual = y - predicted
            sumSquaredResiduals += residual * residual
        }

        // Standard deviation of residuals
        return kotlin.math.sqrt(sumSquaredResiduals / (n - 2))
    }

    /**
     * Update trend arrows on a list of readings.
     *
     * @param readings List of readings to update (modified in place conceptually)
     * @return New list with trend arrows calculated
     */
    fun updateTrends(readings: List<LibreGlucoseReading>): List<LibreGlucoseReading> {
        if (readings.isEmpty()) return readings

        val sorted = readings.sortedBy { it.timestamp }
        val result = mutableListOf<LibreGlucoseReading>()

        for (i in sorted.indices) {
            // Use readings up to and including current for trend
            val subset = sorted.subList(0, i + 1)
            val trend = calculateTrend(subset)

            result.add(
                sorted[i].copy(trend = trend)
            )
        }

        return result
    }
}
