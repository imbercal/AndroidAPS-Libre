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
 * Protocol handler for FreeStyle Libre 3 BLE communication.
 *
 * Libre 3 is a fully streaming CGM that communicates directly via BLE
 * without requiring NFC activation for data access. It provides real-time
 * glucose readings every minute.
 *
 * Protocol flow:
 * 1. Connect to sensor via BLE
 * 2. Perform key exchange/authentication
 * 3. Receive continuous glucose stream
 * 4. Decrypt and parse readings
 *
 * Based on xDrip+ Libre3 implementation.
 */
@Singleton
class Libre3Protocol @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val libreDecryption: LibreDecryption
) : LibreProtocol() {

    private var sensorInfo: LibreSensorInfo? = null
    private var sessionKey: ByteArray? = null
    private var pendingData = ByteArray(0)
    private var messageCounter: Int = 0

    companion object {
        // Libre 3 message types
        private const val MSG_AUTH_CHALLENGE: Byte = 0x01
        private const val MSG_AUTH_RESPONSE: Byte = 0x02
        private const val MSG_AUTH_SUCCESS: Byte = 0x03
        private const val MSG_GLUCOSE_DATA: Byte = 0x10
        private const val MSG_SENSOR_INFO: Byte = 0x20
        private const val MSG_KEEP_ALIVE: Byte = 0x30

        // Message structure
        private const val HEADER_SIZE = 4 // type (1) + length (2) + counter (1)
        private const val MIN_MESSAGE_SIZE = HEADER_SIZE + 2 // header + CRC

        // Glucose data offsets
        private const val GLUCOSE_VALUE_OFFSET = 0
        private const val GLUCOSE_QUALITY_OFFSET = 2
        private const val GLUCOSE_TIMESTAMP_OFFSET = 4

        // Glucose conversion (Libre 3 reports in mg/dL * 10)
        private const val GLUCOSE_SCALE_FACTOR = 0.1
    }

    override fun initialize(sensorInfo: LibreSensorInfo?) {
        this.sensorInfo = sensorInfo
        this.sessionKey = null
        this.messageCounter = 0
        state = State.IDLE
        pendingData = ByteArray(0)

        aapsLogger.debug(LTag.BGSOURCE, "Libre3Protocol initialized" +
            (sensorInfo?.serialNumber?.let { " for sensor $it" } ?: ""))
    }

    override fun handleData(data: ByteArray) {
        aapsLogger.debug(LTag.BGSOURCE, "Libre3 received ${data.size} bytes")

        // Accumulate data
        pendingData = pendingData + data

        // Process complete messages
        while (pendingData.size >= MIN_MESSAGE_SIZE) {
            val processed = processNextMessage()
            if (!processed) break
        }
    }

    private fun processNextMessage(): Boolean {
        if (pendingData.size < MIN_MESSAGE_SIZE) return false

        val messageType = pendingData[0]
        val length = ((pendingData[2].toInt() and 0xFF) shl 8) or (pendingData[1].toInt() and 0xFF)

        if (pendingData.size < length + HEADER_SIZE) {
            return false // Need more data
        }

        val messageData = pendingData.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)

        // Remove processed message from buffer
        pendingData = if (pendingData.size > HEADER_SIZE + length) {
            pendingData.copyOfRange(HEADER_SIZE + length, pendingData.size)
        } else {
            ByteArray(0)
        }

        // Handle message by type
        when (messageType) {
            MSG_AUTH_CHALLENGE -> handleAuthChallenge(messageData)
            MSG_AUTH_SUCCESS -> handleAuthSuccess(messageData)
            MSG_GLUCOSE_DATA -> handleGlucoseData(messageData)
            MSG_SENSOR_INFO -> handleSensorInfoMessage(messageData)
            MSG_KEEP_ALIVE -> handleKeepAlive()
            else -> aapsLogger.warn(LTag.BGSOURCE, "Unknown Libre3 message type: ${messageType.toInt() and 0xFF}")
        }

        return true
    }

    private fun handleAuthChallenge(data: ByteArray) {
        aapsLogger.debug(LTag.BGSOURCE, "Received auth challenge")

        if (data.size < 16) {
            protocolCallback?.onError("Invalid auth challenge")
            state = State.ERROR
            return
        }

        state = State.AUTHENTICATING

        // Extract random from challenge
        val sensorRandom = data.copyOfRange(0, 8)

        // Generate device info and session key
        val deviceInfo = generateDeviceInfo()
        sessionKey = libreDecryption.generateLibre3SessionKey(deviceInfo, sensorRandom)

        // Generate response
        val deviceRandom = generateRandom(8)
        val response = createAuthResponse(deviceInfo, deviceRandom, sensorRandom)

        protocolCallback?.sendData(response)
    }

    private fun handleAuthSuccess(data: ByteArray) {
        aapsLogger.debug(LTag.BGSOURCE, "Authentication successful")
        state = State.AUTHENTICATED
        protocolCallback?.onAuthenticationComplete(true)

        // Request initial data
        requestGlucoseData()
    }

    private fun handleGlucoseData(data: ByteArray) {
        if (sessionKey == null) {
            aapsLogger.warn(LTag.BGSOURCE, "Received glucose data but no session key")
            return
        }

        try {
            // Decrypt data
            val decrypted = libreDecryption.decryptLibre3(data, sessionKey!!)

            // Parse readings
            val readings = parseGlucoseData(decrypted)

            if (readings.isNotEmpty()) {
                protocolCallback?.onGlucoseData(readings)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Error processing glucose data", e)
        }
    }

    private fun handleSensorInfoMessage(data: ByteArray) {
        val info = parseSensorInfo(data)
        if (info != null) {
            sensorInfo = info
            protocolCallback?.onSensorInfo(info)
        }
    }

    private fun handleKeepAlive() {
        aapsLogger.debug(LTag.BGSOURCE, "Keep-alive received")
        // Send keep-alive response
        val response = createMessage(MSG_KEEP_ALIVE, ByteArray(0))
        protocolCallback?.sendData(response)
    }

    override fun startAuthentication() {
        state = State.AUTHENTICATING
        aapsLogger.debug(LTag.BGSOURCE, "Starting Libre3 authentication")

        // Libre 3 authentication starts automatically when sensor sends challenge
        // We just need to wait for the challenge message
    }

    override fun requestGlucoseData() {
        if (state != State.AUTHENTICATED) {
            aapsLogger.warn(LTag.BGSOURCE, "Cannot request glucose: not authenticated")
            return
        }

        // Libre 3 streams data automatically after authentication
        // No explicit request needed
        aapsLogger.debug(LTag.BGSOURCE, "Libre3 streams data automatically")
    }

    override fun parseGlucoseData(data: ByteArray): List<LibreGlucoseReading> {
        val readings = mutableListOf<LibreGlucoseReading>()

        // Libre 3 sends individual readings or small batches
        // Each reading is typically 8-12 bytes

        var offset = 0
        val readingSize = 8 // Typical Libre 3 reading size

        while (offset + readingSize <= data.size) {
            val reading = parseSingleLibre3Reading(data, offset)
            if (reading != null) {
                readings.add(reading)
            }
            offset += readingSize
        }

        aapsLogger.debug(LTag.BGSOURCE, "Parsed ${readings.size} Libre3 readings")
        return readings
    }

    private fun parseSingleLibre3Reading(data: ByteArray, offset: Int): LibreGlucoseReading? {
        if (offset + 8 > data.size) return null

        // Libre 3 reading format:
        // Bytes 0-1: Glucose value (scaled by 10)
        // Bytes 2-3: Quality/flags
        // Bytes 4-7: Timestamp (seconds since sensor start)

        val rawGlucose = ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)

        // Check for invalid reading
        if (rawGlucose <= 0 || rawGlucose > 5000) return null

        val glucoseValue = rawGlucose * GLUCOSE_SCALE_FACTOR

        // Quality flags
        val flags = ((data[offset + 3].toInt() and 0xFF) shl 8) or (data[offset + 2].toInt() and 0xFF)
        val quality = when {
            flags and 0x8000 != 0 -> GlucoseQuality.UNRELIABLE
            flags and 0x4000 != 0 -> GlucoseQuality.DEGRADED
            else -> GlucoseQuality.GOOD
        }

        // Timestamp (seconds since sensor start)
        val secondsSinceStart = ((data[offset + 7].toLong() and 0xFF) shl 24) or
            ((data[offset + 6].toLong() and 0xFF) shl 16) or
            ((data[offset + 5].toLong() and 0xFF) shl 8) or
            (data[offset + 4].toLong() and 0xFF)

        val timestamp = if (sensorInfo != null) {
            sensorInfo!!.startTime + (secondsSinceStart * 1000L)
        } else {
            System.currentTimeMillis()
        }

        return LibreGlucoseReading(
            timestamp = timestamp,
            glucoseValue = glucoseValue,
            trend = TrendArrow.NONE, // Trend calculated separately
            quality = quality,
            rawValue = rawGlucose.toDouble()
        )
    }

    override fun parseSensorInfo(data: ByteArray): LibreSensorInfo? {
        if (data.size < 24) return null

        try {
            // Libre 3 sensor info format
            // Bytes 0-9: Serial number
            // Bytes 10-13: Sensor start timestamp
            // Bytes 14-17: Current sensor age (minutes)
            // Bytes 18-21: Max sensor life (minutes)

            val serialBytes = data.copyOfRange(0, 10)
            val serialNumber = String(serialBytes).trim().replace("\u0000", "")

            val startTimestamp = ((data[13].toLong() and 0xFF) shl 24) or
                ((data[12].toLong() and 0xFF) shl 16) or
                ((data[11].toLong() and 0xFF) shl 8) or
                (data[10].toLong() and 0xFF)

            val sensorAgeMinutes = ((data[17].toInt() and 0xFF) shl 24) or
                ((data[16].toInt() and 0xFF) shl 16) or
                ((data[15].toInt() and 0xFF) shl 8) or
                (data[14].toInt() and 0xFF)

            val maxLifeMinutes = ((data[21].toInt() and 0xFF) shl 24) or
                ((data[20].toInt() and 0xFF) shl 16) or
                ((data[19].toInt() and 0xFF) shl 8) or
                (data[18].toInt() and 0xFF)

            val now = System.currentTimeMillis()
            val startTime = now - (sensorAgeMinutes * 60 * 1000L)
            val expiryTime = startTime + (maxLifeMinutes * 60 * 1000L)

            return LibreSensorInfo(
                serialNumber = serialNumber,
                startTime = startTime,
                expiryTime = expiryTime,
                sensorType = LibreSensorType.LIBRE_3
            )
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Error parsing Libre3 sensor info", e)
            return null
        }
    }

    private fun generateDeviceInfo(): ByteArray {
        // Generate unique device identifier
        // In production, this should be consistent per device
        return ByteArray(16) { i -> (i * 17 + 0x42).toByte() }
    }

    private fun generateRandom(size: Int): ByteArray {
        val random = ByteArray(size)
        java.security.SecureRandom().nextBytes(random)
        return random
    }

    private fun createAuthResponse(deviceInfo: ByteArray, deviceRandom: ByteArray, sensorRandom: ByteArray): ByteArray {
        val payload = ByteArray(deviceInfo.size + deviceRandom.size)
        System.arraycopy(deviceInfo, 0, payload, 0, deviceInfo.size)
        System.arraycopy(deviceRandom, 0, payload, deviceInfo.size, deviceRandom.size)

        return createMessage(MSG_AUTH_RESPONSE, payload)
    }

    private fun createMessage(type: Byte, payload: ByteArray): ByteArray {
        messageCounter++

        val message = ByteArray(HEADER_SIZE + payload.size)
        message[0] = type
        message[1] = (payload.size and 0xFF).toByte()
        message[2] = ((payload.size shr 8) and 0xFF).toByte()
        message[3] = (messageCounter and 0xFF).toByte()

        System.arraycopy(payload, 0, message, HEADER_SIZE, payload.size)

        return message
    }

    override fun reset() {
        super.reset()
        sessionKey = null
        messageCounter = 0
        pendingData = ByteArray(0)
    }
}
