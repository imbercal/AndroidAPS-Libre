package app.aaps.plugins.source.libre.protocol

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cryptographic utilities for Libre sensor data decryption.
 *
 * FreeStyle Libre sensors use AES encryption for BLE communication.
 * The encryption keys are derived from sensor-specific information
 * obtained during NFC activation.
 *
 * Based on xDrip+ decryption algorithms.
 */
@Singleton
class LibreDecryption @Inject constructor(
    private val aapsLogger: AAPSLogger
) {

    companion object {
        // AES block size
        private const val AES_BLOCK_SIZE = 16

        // Key derivation constants (from xDrip+)
        private val KEY_DERIVATION_TABLE = byteArrayOf(
            0xA0.toByte(), 0xC5.toByte(), 0x06.toByte(), 0x0E.toByte(),
            0x14.toByte(), 0xB7.toByte(), 0x22.toByte(), 0x60.toByte(),
            0x08.toByte(), 0xCE.toByte(), 0x93.toByte(), 0x12.toByte(),
            0x56.toByte(), 0x40.toByte(), 0x33.toByte(), 0xF7.toByte()
        )

        // Libre 2 specific constants
        private val LIBRE2_UNLOCK_KEY_PREFIX = byteArrayOf(
            0x21.toByte(), 0xD0.toByte(), 0x00.toByte()
        )

        // Libre 3 specific constants
        private val LIBRE3_KEY_SALT = byteArrayOf(
            0x89.toByte(), 0x81.toByte(), 0x0C.toByte(), 0xC4.toByte(),
            0x08.toByte(), 0x98.toByte(), 0x17.toByte(), 0x7A.toByte()
        )
    }

    /**
     * Generate unlock key for Libre 2 BLE communication
     * @param patchInfo Sensor patch information (from NFC)
     * @return Unlock key bytes
     */
    fun generateUnlockKey(patchInfo: ByteArray): ByteArray {
        if (patchInfo.size < 6) {
            aapsLogger.warn(LTag.BGSOURCE, "Patch info too short for key generation")
            return ByteArray(0)
        }

        // Key generation based on xDrip+ algorithm
        val key = ByteArray(8)

        // Use patch info bytes to derive key
        for (i in 0 until 8) {
            val patchByte = patchInfo[i % patchInfo.size].toInt() and 0xFF
            val tableByte = KEY_DERIVATION_TABLE[i].toInt() and 0xFF
            key[i] = (patchByte xor tableByte).toByte()
        }

        // Combine with prefix
        val unlockKey = ByteArray(LIBRE2_UNLOCK_KEY_PREFIX.size + key.size)
        System.arraycopy(LIBRE2_UNLOCK_KEY_PREFIX, 0, unlockKey, 0, LIBRE2_UNLOCK_KEY_PREFIX.size)
        System.arraycopy(key, 0, unlockKey, LIBRE2_UNLOCK_KEY_PREFIX.size, key.size)

        return unlockKey
    }

    /**
     * Decrypt Libre 2 BLE data
     * @param encryptedData Encrypted data from sensor
     * @param patchInfo Sensor patch information
     * @return Decrypted data
     */
    fun decryptLibre2(encryptedData: ByteArray, patchInfo: ByteArray): ByteArray {
        if (encryptedData.size < AES_BLOCK_SIZE) {
            return encryptedData // Too short to decrypt
        }

        try {
            val key = deriveAesKey(patchInfo)
            val iv = deriveIv(patchInfo)

            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            // Decrypt in blocks
            val decrypted = ByteArray(encryptedData.size)
            var offset = 0

            // First few bytes may be header (unencrypted)
            val headerSize = 8
            System.arraycopy(encryptedData, 0, decrypted, 0, headerSize)
            offset = headerSize

            // Decrypt remaining data
            val encryptedPart = encryptedData.copyOfRange(headerSize, encryptedData.size)
            if (encryptedPart.size >= AES_BLOCK_SIZE) {
                val alignedSize = (encryptedPart.size / AES_BLOCK_SIZE) * AES_BLOCK_SIZE
                val decryptedPart = cipher.doFinal(encryptedPart.copyOfRange(0, alignedSize))
                System.arraycopy(decryptedPart, 0, decrypted, offset, decryptedPart.size)
                offset += decryptedPart.size

                // Copy any remaining unaligned bytes
                if (encryptedPart.size > alignedSize) {
                    System.arraycopy(
                        encryptedPart, alignedSize,
                        decrypted, offset,
                        encryptedPart.size - alignedSize
                    )
                }
            } else {
                System.arraycopy(encryptedPart, 0, decrypted, offset, encryptedPart.size)
            }

            return decrypted
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Libre 2 decryption failed", e)
            return encryptedData // Return original on error
        }
    }

    /**
     * Decrypt Libre 3 BLE data
     * @param encryptedData Encrypted data from sensor
     * @param sessionKey Session key established during connection
     * @return Decrypted data
     */
    fun decryptLibre3(encryptedData: ByteArray, sessionKey: ByteArray): ByteArray {
        if (encryptedData.size < AES_BLOCK_SIZE || sessionKey.size < AES_BLOCK_SIZE) {
            return encryptedData
        }

        try {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val secretKey = SecretKeySpec(sessionKey.copyOfRange(0, AES_BLOCK_SIZE), "AES")

            // Libre 3 uses CTR mode with counter in first block
            val counter = encryptedData.copyOfRange(0, AES_BLOCK_SIZE)
            val ivSpec = IvParameterSpec(counter)

            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val ciphertext = encryptedData.copyOfRange(AES_BLOCK_SIZE, encryptedData.size)
            return cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Libre 3 decryption failed", e)
            return encryptedData
        }
    }

    /**
     * Derive AES key from patch info
     */
    private fun deriveAesKey(patchInfo: ByteArray): ByteArray {
        val key = ByteArray(AES_BLOCK_SIZE)

        for (i in 0 until AES_BLOCK_SIZE) {
            val patchByte = if (i < patchInfo.size) patchInfo[i].toInt() and 0xFF else 0
            val tableByte = KEY_DERIVATION_TABLE[i].toInt() and 0xFF
            key[i] = (patchByte xor tableByte).toByte()
        }

        return key
    }

    /**
     * Derive IV from patch info
     */
    private fun deriveIv(patchInfo: ByteArray): ByteArray {
        val iv = ByteArray(AES_BLOCK_SIZE)

        // IV derivation uses different bytes from patch info
        for (i in 0 until AES_BLOCK_SIZE) {
            val offset = (i + 8) % patchInfo.size
            val patchByte = if (offset < patchInfo.size) patchInfo[offset].toInt() and 0xFF else 0
            iv[i] = (patchByte xor (KEY_DERIVATION_TABLE[(i + 8) % AES_BLOCK_SIZE].toInt() and 0xFF)).toByte()
        }

        return iv
    }

    /**
     * Generate session key for Libre 3
     * @param deviceInfo Device-specific information
     * @param random Random bytes from sensor
     * @return Session key
     */
    fun generateLibre3SessionKey(deviceInfo: ByteArray, random: ByteArray): ByteArray {
        val combined = ByteArray(deviceInfo.size + random.size + LIBRE3_KEY_SALT.size)
        var offset = 0

        System.arraycopy(deviceInfo, 0, combined, offset, deviceInfo.size)
        offset += deviceInfo.size

        System.arraycopy(random, 0, combined, offset, random.size)
        offset += random.size

        System.arraycopy(LIBRE3_KEY_SALT, 0, combined, offset, LIBRE3_KEY_SALT.size)

        // Simple hash-based key derivation (actual implementation may use HKDF)
        return simpleHash(combined)
    }

    /**
     * Simple hash function for key derivation
     */
    private fun simpleHash(data: ByteArray): ByteArray {
        val result = ByteArray(AES_BLOCK_SIZE)

        for (i in data.indices) {
            result[i % AES_BLOCK_SIZE] = (result[i % AES_BLOCK_SIZE].toInt() xor data[i].toInt()).toByte()
        }

        // Mix bytes
        for (i in 0 until AES_BLOCK_SIZE) {
            val next = (i + 1) % AES_BLOCK_SIZE
            result[next] = (result[next].toInt() xor ((result[i].toInt() and 0xFF) shr 3)).toByte()
        }

        return result
    }

    /**
     * Calculate CRC-16 for data validation
     */
    fun calculateCrc16(data: ByteArray): Int {
        var crc = 0xFFFF

        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if (crc and 1 != 0) {
                    (crc shr 1) xor 0x8408
                } else {
                    crc shr 1
                }
            }
        }

        return crc xor 0xFFFF
    }

    /**
     * Verify CRC-16 checksum
     */
    fun verifyCrc16(data: ByteArray, expectedCrc: Int): Boolean {
        return calculateCrc16(data) == expectedCrc
    }
}
