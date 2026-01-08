package app.aaps.plugins.source.libre.protocol

import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.source.libre.data.GlucoseQuality
import app.aaps.plugins.source.libre.data.LibreGlucoseReading
import app.aaps.plugins.source.libre.data.LibreSensorInfo
import app.aaps.plugins.source.libre.data.LibreSensorType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Protocol handler for FreeStyle Libre 2 BLE communication.
 *
 * Libre 2 uses encrypted BLE communication. The sensor must first be
 * activated via NFC, which provides the encryption keys needed for BLE.
 *
 * Protocol flow:
 * 1. Connect to sensor via BLE
 * 2. Send unlock command with sensor-specific key
 * 3. Receive acknowledgment
 * 4. Request and receive glucose data
 * 5. Decrypt and parse readings
 *
 * Based on xDrip+ Libre2 implementation.
 */
@Singleton
class Libre2Protocol @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val libreDecryption: LibreDecryption
) : LibreProtocol() {

    private var sensorInfo: LibreSensorInfo? = null
    private var unlockKey: ByteArray? = null
    private var pendingData = ByteArray(0)

    companion object {
        // Command types
        private const val CMD_UNLOCK: Byte = 0x07
        private const val CMD_GET_PATCH_INFO: Byte = 0x01
        private const val CMD_GET_GLUCOSE: Byte = 0x02

        // Response types
        private const val RSP_UNLOCK_SUCCESS: Byte = 0x08
        private const val RSP_PATCH_INFO: Byte = 0x01
        private const val RSP_GLUCOSE_DATA: Byte = 0x02

        // Data offsets in glucose response
        private const val OFFSET_TREND_INDEX = 26
        private const val OFFSET_HISTORY_INDEX = 27
        private const val OFFSET_TREND_DATA = 28
        private const val OFFSET_HISTORY_DATA = OFFSET_TREND_DATA + (TREND_SIZE * 6) // 6 bytes per reading

        // Glucose conversion factor
        private const val GLUCOSE_CONV_FACTOR = 1.0 // Raw to mg/dL (Libre 2 provides direct mg/dL)
    }

    override fun initialize(sensorInfo: LibreSensorInfo?) {
        this.sensorInfo = sensorInfo
        this.unlockKey = sensorInfo?.patchInfo?.let { generateUnlockKey(it) }
        state = State.IDLE
        pendingData = ByteArray(0)

        aapsLogger.debug(LTag.BGSOURCE, "Libre2Protocol initialized" +
            (sensorInfo?.serialNumber?.let { " for sensor $it" } ?: ""))
    }

    override fun handleData(data: ByteArray) {
        aapsLogger.debug(LTag.BGSOURCE, "Libre2 received ${data.size} bytes")

        // Accumulate data (messages may be fragmented)
        pendingData = pendingData + data

        // Try to process complete messages
        processMessages()
    }

    private fun processMessages() {
        if (pendingData.isEmpty()) return

        val messageType = pendingData[0]

        when (messageType) {
            RSP_UNLOCK_SUCCESS -> handleUnlockResponse()
            RSP_PATCH_INFO -> handlePatchInfoResponse()
            RSP_GLUCOSE_DATA -> handleGlucoseResponse()
            else -> {
                aapsLogger.warn(LTag.BGSOURCE, "Unknown message type: ${messageType.toInt() and 0xFF}")
                pendingData = ByteArray(0)
            }
        }
    }

    private fun handleUnlockResponse() {
        aapsLogger.debug(LTag.BGSOURCE, "Unlock successful")
        state = State.AUTHENTICATED
        pendingData = ByteArray(0)
        protocolCallback?.onAuthenticationComplete(true)
    }

    private fun handlePatchInfoResponse() {
        if (pendingData.size < 20) return // Need more data

        aapsLogger.debug(LTag.BGSOURCE, "Received patch info")

        val info = parseSensorInfo(pendingData)
        if (info != null) {
            sensorInfo = info
            unlockKey = info.patchInfo?.let { generateUnlockKey(it) }
            protocolCallback?.onSensorInfo(info)
        }

        pendingData = ByteArray(0)
    }

    private fun handleGlucoseResponse() {
        // Libre 2 glucose response is typically ~350 bytes
        if (pendingData.size < 344) return // Need more data

        aapsLogger.debug(LTag.BGSOURCE, "Received glucose data")

        try {
            // Decrypt if we have keys
            val decryptedData = if (unlockKey != null && sensorInfo?.patchInfo != null) {
                libreDecryption.decryptLibre2(pendingData, sensorInfo!!.patchInfo!!)
            } else {
                pendingData
            }

            val readings = parseGlucoseData(decryptedData)
            if (readings.isNotEmpty()) {
                protocolCallback?.onGlucoseData(readings)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Error parsing glucose data", e)
            protocolCallback?.onError("Failed to parse glucose data: ${e.message}")
        }

        pendingData = ByteArray(0)
    }

    override fun startAuthentication() {
        if (unlockKey == null) {
            aapsLogger.error(LTag.BGSOURCE, "Cannot authenticate: no unlock key")
            protocolCallback?.onError("No unlock key available. Sensor may need NFC activation.")
            state = State.ERROR
            return
        }

        state = State.AUTHENTICATING
        aapsLogger.debug(LTag.BGSOURCE, "Sending unlock command")

        val unlockCmd = createUnlockCommand(unlockKey!!)
        protocolCallback?.sendData(unlockCmd)
    }

    override fun requestGlucoseData() {
        if (state != State.AUTHENTICATED) {
            aapsLogger.warn(LTag.BGSOURCE, "Cannot request glucose: not authenticated")
            return
        }

        state = State.READING
        aapsLogger.debug(LTag.BGSOURCE, "Requesting glucose data")

        val cmd = byteArrayOf(CMD_GET_GLUCOSE)
        protocolCallback?.sendData(cmd)
    }

    override fun parseGlucoseData(data: ByteArray): List<LibreGlucoseReading> {
        val readings = mutableListOf<LibreGlucoseReading>()

        if (data.size < 344) {
            aapsLogger.warn(LTag.BGSOURCE, "Glucose data too short: ${data.size}")
            return readings
        }

        val now = System.currentTimeMillis()

        // Parse trend data (most recent 16 minutes, 1-minute intervals)
        val trendIndex = data[OFFSET_TREND_INDEX].toInt() and 0xFF
        for (i in 0 until TREND_SIZE) {
            val index = (trendIndex - i + TREND_SIZE) % TREND_SIZE
            val offset = OFFSET_TREND_DATA + (index * 6)

            if (offset + 6 <= data.size) {
                val reading = parseSingleReading(data, offset, now - (i * 60 * 1000L))
                if (reading != null) {
                    readings.add(reading)
                }
            }
        }

        // Parse history data (8 hours, 15-minute intervals)
        val historyIndex = data[OFFSET_HISTORY_INDEX].toInt() and 0xFF
        for (i in 0 until HISTORY_SIZE) {
            val index = (historyIndex - i + HISTORY_SIZE) % HISTORY_SIZE
            val offset = OFFSET_HISTORY_DATA + (index * 6)

            if (offset + 6 <= data.size) {
                // History readings are 15 minutes apart, starting after trend data
                val timestamp = now - ((TREND_SIZE + i * 15) * 60 * 1000L)
                val reading = parseSingleReading(data, offset, timestamp)
                if (reading != null) {
                    readings.add(reading)
                }
            }
        }

        aapsLogger.debug(LTag.BGSOURCE, "Parsed ${readings.size} glucose readings")
        return readings.sortedBy { it.timestamp }
    }

    private fun parseSingleReading(data: ByteArray, offset: Int, timestamp: Long): LibreGlucoseReading? {
        if (offset + 6 > data.size) return null

        // Libre 2 reading format (6 bytes):
        // Bytes 0-1: Raw glucose value (little-endian)
        // Bytes 2-3: Quality/flags
        // Bytes 4-5: Temperature (optional)

        val rawValue = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)

        // Check for invalid reading
        if (rawValue <= 0 || rawValue > 500) return null

        val glucoseValue = rawValue * GLUCOSE_CONV_FACTOR

        // Quality flags
        val flags = ((data[offset + 3].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF)
        val quality = when {
            flags and 0x8000 != 0 -> GlucoseQuality.UNRELIABLE
            flags and 0x4000 != 0 -> GlucoseQuality.DEGRADED
            else -> GlucoseQuality.GOOD
        }

        // Temperature (if available)
        val tempRaw = ((data[offset + 5].toInt() and 0xFF) shl 8) or (data[offset + 4].toInt() and 0xFF)
        val temperature = if (tempRaw > 0) tempRaw / 100.0f else null

        return LibreGlucoseReading(
            timestamp = timestamp,
            glucoseValue = glucoseValue,
            trend = TrendArrow.NONE, // Trend calculated separately
            quality = quality,
            rawValue = rawValue.toDouble(),
            temperature = temperature
        )
    }

    override fun parseSensorInfo(data: ByteArray): LibreSensorInfo? {
        if (data.size < 20) return null

        try {
            // Extract serial number (typically bytes 3-12)
            val serialBytes = data.copyOfRange(3, 13)
            val serialNumber = String(serialBytes).trim()

            // Extract sensor start time and calculate expiry
            val startTimeMinutes = ((data[14].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)
            val startTime = System.currentTimeMillis() - (startTimeMinutes * 60 * 1000L)
            val expiryTime = startTime + (LIBRE_SENSOR_LIFESPAN_MINUTES * 60 * 1000L)

            // Store patch info for encryption
            val patchInfo = data.copyOfRange(0, minOf(data.size, 24))

            return LibreSensorInfo(
                serialNumber = serialNumber,
                startTime = startTime,
                expiryTime = expiryTime,
                sensorType = LibreSensorType.LIBRE_2,
                patchInfo = patchInfo
            )
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Error parsing sensor info", e)
            return null
        }
    }

    private fun generateUnlockKey(patchInfo: ByteArray): ByteArray {
        // Generate unlock key from patch info
        // This is a simplified version - actual implementation depends on xDrip+ algorithms
        return libreDecryption.generateUnlockKey(patchInfo)
    }

    private fun createUnlockCommand(key: ByteArray): ByteArray {
        val cmd = ByteArray(1 + key.size)
        cmd[0] = CMD_UNLOCK
        System.arraycopy(key, 0, cmd, 1, key.size)
        return cmd
    }

    override fun reset() {
        super.reset()
        pendingData = ByteArray(0)
    }
}
