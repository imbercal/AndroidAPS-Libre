package app.aaps.plugins.source.libre.protocol

import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class LibreDecryptionTest {

    @Mock
    private lateinit var aapsLogger: AAPSLogger

    private lateinit var libreDecryption: LibreDecryption

    @BeforeEach
    fun setup() {
        MockitoAnnotations.openMocks(this)
        libreDecryption = LibreDecryption(aapsLogger)
    }

    @Test
    fun `generateUnlockKey returns empty array for short patch info`() {
        val patchInfo = ByteArray(3) // Too short
        val key = libreDecryption.generateUnlockKey(patchInfo)
        assertThat(key).isEmpty()
    }

    @Test
    fun `generateUnlockKey returns key with correct length`() {
        val patchInfo = ByteArray(8) { i -> i.toByte() }
        val key = libreDecryption.generateUnlockKey(patchInfo)

        // Unlock key should be prefix (3 bytes) + derived key (8 bytes) = 11 bytes
        assertThat(key).hasLength(11)
    }

    @Test
    fun `generateUnlockKey produces consistent output for same input`() {
        val patchInfo = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)

        val key1 = libreDecryption.generateUnlockKey(patchInfo)
        val key2 = libreDecryption.generateUnlockKey(patchInfo)

        assertThat(key1).isEqualTo(key2)
    }

    @Test
    fun `generateUnlockKey produces different output for different input`() {
        val patchInfo1 = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val patchInfo2 = byteArrayOf(0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18)

        val key1 = libreDecryption.generateUnlockKey(patchInfo1)
        val key2 = libreDecryption.generateUnlockKey(patchInfo2)

        assertThat(key1).isNotEqualTo(key2)
    }

    @Test
    fun `decryptLibre2 returns original data if too short`() {
        val data = ByteArray(8) { i -> i.toByte() } // Less than AES block size
        val patchInfo = ByteArray(16) { i -> i.toByte() }

        val result = libreDecryption.decryptLibre2(data, patchInfo)

        assertThat(result).isEqualTo(data)
    }

    @Test
    fun `decryptLibre3 returns original data if too short`() {
        val data = ByteArray(8) { i -> i.toByte() }
        val sessionKey = ByteArray(16) { i -> (i + 0x10).toByte() }

        val result = libreDecryption.decryptLibre3(data, sessionKey)

        assertThat(result).isEqualTo(data)
    }

    @Test
    fun `decryptLibre3 returns original data if session key too short`() {
        val data = ByteArray(32) { i -> i.toByte() }
        val sessionKey = ByteArray(8) { i -> i.toByte() } // Too short

        val result = libreDecryption.decryptLibre3(data, sessionKey)

        assertThat(result).isEqualTo(data)
    }

    @Test
    fun `generateLibre3SessionKey produces 16 byte key`() {
        val deviceInfo = ByteArray(16) { i -> i.toByte() }
        val random = ByteArray(8) { i -> (i + 0x10).toByte() }

        val sessionKey = libreDecryption.generateLibre3SessionKey(deviceInfo, random)

        assertThat(sessionKey).hasLength(16)
    }

    @Test
    fun `generateLibre3SessionKey produces consistent output`() {
        val deviceInfo = ByteArray(16) { i -> i.toByte() }
        val random = ByteArray(8) { i -> (i + 0x10).toByte() }

        val key1 = libreDecryption.generateLibre3SessionKey(deviceInfo, random)
        val key2 = libreDecryption.generateLibre3SessionKey(deviceInfo, random)

        assertThat(key1).isEqualTo(key2)
    }

    @Test
    fun `calculateCrc16 produces consistent output`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        val crc1 = libreDecryption.calculateCrc16(data)
        val crc2 = libreDecryption.calculateCrc16(data)

        assertThat(crc1).isEqualTo(crc2)
    }

    @Test
    fun `calculateCrc16 produces different values for different data`() {
        val data1 = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val data2 = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x06)

        val crc1 = libreDecryption.calculateCrc16(data1)
        val crc2 = libreDecryption.calculateCrc16(data2)

        assertThat(crc1).isNotEqualTo(crc2)
    }

    @Test
    fun `verifyCrc16 returns true for matching CRC`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val expectedCrc = libreDecryption.calculateCrc16(data)

        assertThat(libreDecryption.verifyCrc16(data, expectedCrc)).isTrue()
    }

    @Test
    fun `verifyCrc16 returns false for non-matching CRC`() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val wrongCrc = 0x1234

        assertThat(libreDecryption.verifyCrc16(data, wrongCrc)).isFalse()
    }

    @Test
    fun `calculateCrc16 handles empty array`() {
        val data = ByteArray(0)
        // Should not throw, and should return a valid CRC
        val crc = libreDecryption.calculateCrc16(data)
        assertThat(crc).isAtLeast(0)
    }
}
