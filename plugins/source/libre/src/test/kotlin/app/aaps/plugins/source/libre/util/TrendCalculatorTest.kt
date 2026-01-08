package app.aaps.plugins.source.libre.util

import app.aaps.core.data.model.TrendArrow
import app.aaps.plugins.source.libre.data.GlucoseQuality
import app.aaps.plugins.source.libre.data.LibreGlucoseReading
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TrendCalculatorTest {

    private lateinit var trendCalculator: TrendCalculator

    @BeforeEach
    fun setup() {
        trendCalculator = TrendCalculator()
    }

    @Test
    fun `calculateTrend returns NONE when not enough readings`() {
        val readings = listOf(
            createReading(100.0, 0),
            createReading(102.0, 1)
        )
        assertThat(trendCalculator.calculateTrend(readings)).isEqualTo(TrendArrow.NONE)
    }

    @Test
    fun `calculateTrend returns NONE for empty list`() {
        assertThat(trendCalculator.calculateTrend(emptyList())).isEqualTo(TrendArrow.NONE)
    }

    @Test
    fun `calculateTrend returns FLAT for stable glucose`() {
        val now = System.currentTimeMillis()
        val readings = listOf(
            createReading(100.0, 0, now - 10 * 60 * 1000),
            createReading(100.5, 0, now - 8 * 60 * 1000),
            createReading(100.2, 0, now - 6 * 60 * 1000),
            createReading(99.8, 0, now - 4 * 60 * 1000),
            createReading(100.1, 0, now - 2 * 60 * 1000),
            createReading(100.0, 0, now)
        )
        assertThat(trendCalculator.calculateTrend(readings)).isEqualTo(TrendArrow.FLAT)
    }

    @Test
    fun `calculateTrend returns SINGLE_UP for rising glucose around 2 mg per min`() {
        val now = System.currentTimeMillis()
        // Rising at ~2 mg/dL per minute
        val readings = listOf(
            createReading(100.0, 0, now - 10 * 60 * 1000),
            createReading(104.0, 0, now - 8 * 60 * 1000),
            createReading(108.0, 0, now - 6 * 60 * 1000),
            createReading(112.0, 0, now - 4 * 60 * 1000),
            createReading(116.0, 0, now - 2 * 60 * 1000),
            createReading(120.0, 0, now)
        )
        assertThat(trendCalculator.calculateTrend(readings)).isEqualTo(TrendArrow.SINGLE_UP)
    }

    @Test
    fun `calculateTrend returns DOUBLE_UP for rapidly rising glucose`() {
        val now = System.currentTimeMillis()
        // Rising at >3 mg/dL per minute
        val readings = listOf(
            createReading(100.0, 0, now - 10 * 60 * 1000),
            createReading(106.0, 0, now - 8 * 60 * 1000),
            createReading(114.0, 0, now - 6 * 60 * 1000),
            createReading(122.0, 0, now - 4 * 60 * 1000),
            createReading(130.0, 0, now - 2 * 60 * 1000),
            createReading(140.0, 0, now)
        )
        assertThat(trendCalculator.calculateTrend(readings)).isEqualTo(TrendArrow.DOUBLE_UP)
    }

    @Test
    fun `calculateTrend returns FORTY_FIVE_UP for moderately rising glucose`() {
        val now = System.currentTimeMillis()
        // Rising at ~1.5 mg/dL per minute
        val readings = listOf(
            createReading(100.0, 0, now - 10 * 60 * 1000),
            createReading(103.0, 0, now - 8 * 60 * 1000),
            createReading(106.0, 0, now - 6 * 60 * 1000),
            createReading(109.0, 0, now - 4 * 60 * 1000),
            createReading(112.0, 0, now - 2 * 60 * 1000),
            createReading(115.0, 0, now)
        )
        assertThat(trendCalculator.calculateTrend(readings)).isEqualTo(TrendArrow.FORTY_FIVE_UP)
    }

    @Test
    fun `calculateTrend returns SINGLE_DOWN for falling glucose`() {
        val now = System.currentTimeMillis()
        // Falling at ~2 mg/dL per minute
        val readings = listOf(
            createReading(120.0, 0, now - 10 * 60 * 1000),
            createReading(116.0, 0, now - 8 * 60 * 1000),
            createReading(112.0, 0, now - 6 * 60 * 1000),
            createReading(108.0, 0, now - 4 * 60 * 1000),
            createReading(104.0, 0, now - 2 * 60 * 1000),
            createReading(100.0, 0, now)
        )
        assertThat(trendCalculator.calculateTrend(readings)).isEqualTo(TrendArrow.SINGLE_DOWN)
    }

    @Test
    fun `calculateTrend returns DOUBLE_DOWN for rapidly falling glucose`() {
        val now = System.currentTimeMillis()
        // Falling at >3 mg/dL per minute
        val readings = listOf(
            createReading(150.0, 0, now - 10 * 60 * 1000),
            createReading(142.0, 0, now - 8 * 60 * 1000),
            createReading(132.0, 0, now - 6 * 60 * 1000),
            createReading(122.0, 0, now - 4 * 60 * 1000),
            createReading(110.0, 0, now - 2 * 60 * 1000),
            createReading(100.0, 0, now)
        )
        assertThat(trendCalculator.calculateTrend(readings)).isEqualTo(TrendArrow.DOUBLE_DOWN)
    }

    @Test
    fun `calculateTrend filters out unreliable readings`() {
        val now = System.currentTimeMillis()
        val readings = listOf(
            createReading(100.0, 0, now - 10 * 60 * 1000, GlucoseQuality.GOOD),
            createReading(200.0, 0, now - 8 * 60 * 1000, GlucoseQuality.UNRELIABLE), // Should be filtered
            createReading(100.5, 0, now - 6 * 60 * 1000, GlucoseQuality.GOOD),
            createReading(300.0, 0, now - 4 * 60 * 1000, GlucoseQuality.UNRELIABLE), // Should be filtered
            createReading(100.2, 0, now - 2 * 60 * 1000, GlucoseQuality.GOOD),
            createReading(100.0, 0, now, GlucoseQuality.GOOD)
        )
        // With unreliable readings filtered, glucose is stable
        assertThat(trendCalculator.calculateTrend(readings)).isEqualTo(TrendArrow.FLAT)
    }

    @Test
    fun `calculateNoiseLevel returns GOOD for clean data`() {
        val now = System.currentTimeMillis()
        val readings = listOf(
            createReading(100.0, 0, now - 10 * 60 * 1000),
            createReading(101.0, 0, now - 8 * 60 * 1000),
            createReading(102.0, 0, now - 6 * 60 * 1000),
            createReading(103.0, 0, now - 4 * 60 * 1000),
            createReading(104.0, 0, now - 2 * 60 * 1000),
            createReading(105.0, 0, now)
        )
        assertThat(trendCalculator.calculateNoiseLevel(readings)).isEqualTo(GlucoseQuality.GOOD)
    }

    @Test
    fun `calculateNoiseLevel returns DEGRADED for noisy data`() {
        val now = System.currentTimeMillis()
        // Add significant noise to readings
        val readings = listOf(
            createReading(100.0, 0, now - 10 * 60 * 1000),
            createReading(110.0, 0, now - 8 * 60 * 1000),
            createReading(95.0, 0, now - 6 * 60 * 1000),
            createReading(115.0, 0, now - 4 * 60 * 1000),
            createReading(90.0, 0, now - 2 * 60 * 1000),
            createReading(105.0, 0, now)
        )
        assertThat(trendCalculator.calculateNoiseLevel(readings)).isEqualTo(GlucoseQuality.DEGRADED)
    }

    @Test
    fun `updateTrends adds trends to all readings`() {
        val now = System.currentTimeMillis()
        val readings = listOf(
            createReading(100.0, 0, now - 10 * 60 * 1000),
            createReading(102.0, 0, now - 8 * 60 * 1000),
            createReading(104.0, 0, now - 6 * 60 * 1000),
            createReading(106.0, 0, now - 4 * 60 * 1000),
            createReading(108.0, 0, now - 2 * 60 * 1000)
        )

        val updated = trendCalculator.updateTrends(readings)

        assertThat(updated).hasSize(readings.size)
        // First few readings won't have enough data for trend
        assertThat(updated[0].trend).isEqualTo(TrendArrow.NONE)
        assertThat(updated[1].trend).isEqualTo(TrendArrow.NONE)
        // Later readings should have trends calculated
        assertThat(updated[4].trend).isNotEqualTo(TrendArrow.NONE)
    }

    private fun createReading(
        glucose: Double,
        raw: Int,
        timestamp: Long = System.currentTimeMillis(),
        quality: GlucoseQuality = GlucoseQuality.GOOD
    ): LibreGlucoseReading {
        return LibreGlucoseReading(
            timestamp = timestamp,
            glucoseValue = glucose,
            trend = TrendArrow.NONE,
            quality = quality,
            rawValue = raw.toDouble()
        )
    }
}
