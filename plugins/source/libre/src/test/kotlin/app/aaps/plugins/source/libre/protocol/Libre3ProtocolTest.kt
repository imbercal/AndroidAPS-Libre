package app.aaps.plugins.source.libre.protocol

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.source.libre.data.GlucoseQuality
import app.aaps.plugins.source.libre.data.LibreSensorType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class Libre3ProtocolTest {

    @Mock
    private lateinit var aapsLogger: AAPSLogger

    @Mock
    private lateinit var libreDecryption: LibreDecryption

    private lateinit var libre3Protocol: Libre3Protocol

    @BeforeEach
    fun setup() {
        MockitoAnnotations.openMocks(this)
        libre3Protocol = Libre3Protocol(aapsLogger, libreDecryption)
        libre3Protocol.initialize(null)
    }

    @Test
    fun `parseGlucoseData returns empty list for insufficient data`() {
        val data = ByteArray(4) // Too short
        val readings = libre3Protocol.parseGlucoseData(data)
        assertThat(readings).isEmpty()
    }

    @Test
    fun `parseGlucoseData parses single reading correctly`() {
        // Create a mock glucose reading:
        // Bytes 0-1: Glucose value (1200 = 120.0 mg/dL when scaled by 0.1)
        // Bytes 2-3: Quality flags (0x0000 = GOOD)
        // Bytes 4-7: Timestamp (seconds since sensor start)
        val data = ByteArray(8)
        data[0] = 0xB0.toByte() // 1200 & 0xFF = 176
        data[1] = 0x04.toByte() // 1200 >> 8 = 4
        data[2] = 0x00 // Quality flags low
        data[3] = 0x00 // Quality flags high (GOOD)
        data[4] = 0x10 // Timestamp bytes
        data[5] = 0x27
        data[6] = 0x00
        data[7] = 0x00 // 10000 seconds

        val readings = libre3Protocol.parseGlucoseData(data)

        assertThat(readings).hasSize(1)
        assertThat(readings[0].glucoseValue).isEqualTo(120.0)
        assertThat(readings[0].quality).isEqualTo(GlucoseQuality.GOOD)
    }

    @Test
    fun `parseGlucoseData identifies unreliable quality`() {
        val data = ByteArray(8)
        data[0] = 0xB0.toByte()
        data[1] = 0x04.toByte()
        data[2] = 0x00
        data[3] = 0x80.toByte() // Flag 0x8000 = UNRELIABLE
        data[4] = 0x10
        data[5] = 0x27
        data[6] = 0x00
        data[7] = 0x00

        val readings = libre3Protocol.parseGlucoseData(data)

        assertThat(readings).hasSize(1)
        assertThat(readings[0].quality).isEqualTo(GlucoseQuality.UNRELIABLE)
    }

    @Test
    fun `parseGlucoseData identifies degraded quality`() {
        val data = ByteArray(8)
        data[0] = 0xB0.toByte()
        data[1] = 0x04.toByte()
        data[2] = 0x00
        data[3] = 0x40.toByte() // Flag 0x4000 = DEGRADED
        data[4] = 0x10
        data[5] = 0x27
        data[6] = 0x00
        data[7] = 0x00

        val readings = libre3Protocol.parseGlucoseData(data)

        assertThat(readings).hasSize(1)
        assertThat(readings[0].quality).isEqualTo(GlucoseQuality.DEGRADED)
    }

    @Test
    fun `parseGlucoseData skips invalid glucose values`() {
        val data = ByteArray(8)
        // Invalid glucose (0)
        data[0] = 0x00
        data[1] = 0x00
        data[2] = 0x00
        data[3] = 0x00
        data[4] = 0x10
        data[5] = 0x27
        data[6] = 0x00
        data[7] = 0x00

        val readings = libre3Protocol.parseGlucoseData(data)

        assertThat(readings).isEmpty()
    }

    @Test
    fun `parseGlucoseData parses multiple readings`() {
        val data = ByteArray(16)
        // First reading: 120 mg/dL
        data[0] = 0xB0.toByte()
        data[1] = 0x04.toByte()
        data[2] = 0x00
        data[3] = 0x00
        data[4] = 0x10
        data[5] = 0x27
        data[6] = 0x00
        data[7] = 0x00

        // Second reading: 100 mg/dL
        data[8] = 0xE8.toByte() // 1000 & 0xFF = 232
        data[9] = 0x03.toByte() // 1000 >> 8 = 3
        data[10] = 0x00
        data[11] = 0x00
        data[12] = 0x20
        data[13] = 0x4E
        data[14] = 0x00
        data[15] = 0x00

        val readings = libre3Protocol.parseGlucoseData(data)

        assertThat(readings).hasSize(2)
        assertThat(readings[0].glucoseValue).isEqualTo(120.0)
        assertThat(readings[1].glucoseValue).isEqualTo(100.0)
    }

    @Test
    fun `parseSensorInfo returns null for insufficient data`() {
        val data = ByteArray(20) // Needs 24 bytes
        val info = libre3Protocol.parseSensorInfo(data)
        assertThat(info).isNull()
    }

    @Test
    fun `parseSensorInfo extracts serial number`() {
        val data = ByteArray(24)
        // Serial number in bytes 0-9
        val serial = "ABCD123456"
        serial.toByteArray().copyInto(data, 0)

        // Fill rest with dummy data
        data[10] = 0x00 // Start timestamp bytes
        data[11] = 0x00
        data[12] = 0x00
        data[13] = 0x00
        data[14] = 0x3C // Sensor age: 60 minutes
        data[15] = 0x00
        data[16] = 0x00
        data[17] = 0x00
        data[18] = 0x20 // Max life: 20160 minutes (14 days)
        data[19] = 0x4E
        data[20] = 0x00
        data[21] = 0x00
        data[22] = 0x00
        data[23] = 0x00

        val info = libre3Protocol.parseSensorInfo(data)

        assertThat(info).isNotNull()
        assertThat(info!!.serialNumber).isEqualTo("ABCD123456")
        assertThat(info.sensorType).isEqualTo(LibreSensorType.LIBRE_3)
    }

    @Test
    fun `protocol state starts as IDLE`() {
        assertThat(libre3Protocol.getState()).isEqualTo(LibreProtocol.State.IDLE)
    }

    @Test
    fun `reset returns state to IDLE`() {
        // Force state change by starting authentication
        libre3Protocol.startAuthentication()
        assertThat(libre3Protocol.getState()).isEqualTo(LibreProtocol.State.AUTHENTICATING)

        libre3Protocol.reset()
        assertThat(libre3Protocol.getState()).isEqualTo(LibreProtocol.State.IDLE)
    }

    @Test
    fun `isAuthenticated returns false when not authenticated`() {
        assertThat(libre3Protocol.isAuthenticated()).isFalse()
    }
}
