package app.aaps.plugins.source.libre.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.plugins.source.libre.LibreState
import app.aaps.plugins.source.libre.R
import app.aaps.plugins.source.libre.alerts.LibreAlertManager
import app.aaps.plugins.source.libre.ble.LibreBleCallback
import app.aaps.plugins.source.libre.ble.LibreBleComm
import app.aaps.plugins.source.libre.data.GlucoseQuality
import app.aaps.plugins.source.libre.data.LibreConnectionState
import app.aaps.plugins.source.libre.data.LibreGlucoseReading
import app.aaps.plugins.source.libre.data.LibreSensorInfo
import app.aaps.plugins.source.libre.data.LibreSensorState
import app.aaps.plugins.source.libre.data.LibreSensorType
import app.aaps.plugins.source.libre.keys.LibreBooleanKey
import app.aaps.plugins.source.libre.protocol.Libre2Protocol
import app.aaps.plugins.source.libre.protocol.Libre3Protocol
import app.aaps.plugins.source.libre.protocol.LibreProtocol
import app.aaps.plugins.source.libre.util.TrendCalculator
import dagger.android.DaggerService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.min

/**
 * Background service for Libre sensor communication.
 *
 * Manages the BLE connection lifecycle, handles protocol communication,
 * and persists glucose data to the database.
 *
 * State machine flow:
 * IDLE -> SCANNING -> CONNECTING -> AUTHENTICATING -> CONNECTED
 *                                                         |
 *                                                         v
 *                                              RECONNECTING (on disconnect)
 */
class LibreService : DaggerService(), LibreBleCallback {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var libreState: LibreState
    @Inject lateinit var libreBleComm: LibreBleComm
    @Inject lateinit var libre2Protocol: Libre2Protocol
    @Inject lateinit var libre3Protocol: Libre3Protocol
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var context: Context
    @Inject lateinit var libreAlertManager: LibreAlertManager
    @Inject lateinit var preferences: Preferences

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val disposable = CompositeDisposable()
    private val handlerThread = HandlerThread("LibreService").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    private var currentProtocol: LibreProtocol? = null
    private var reconnectAttempt = 0
    private var isServiceRunning = false

    // Recent readings buffer for trend calculation
    private val recentReadings = mutableListOf<LibreGlucoseReading>()
    private val maxReadingsBuffer = 100

    // Service state
    sealed class ServiceState {
        object Idle : ServiceState()
        object Scanning : ServiceState()
        object Connecting : ServiceState()
        object Authenticating : ServiceState()
        object Connected : ServiceState()
        object Reconnecting : ServiceState()
        data class Error(val message: String) : ServiceState()
    }

    private var serviceState: ServiceState = ServiceState.Idle
        set(value) {
            field = value
            updateConnectionState(value)
            aapsLogger.debug(LTag.BGSOURCE, "LibreService state: $value")
        }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "libre_service_channel"
        private const val NOTIFICATION_ID = 7654
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val CONNECTION_TIMEOUT_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
        private const val READINGS_CLEANUP_INTERVAL_MS = 60 * 60 * 1000L // 1 hour

        const val ACTION_START = "app.aaps.plugins.source.libre.START"
        const val ACTION_STOP = "app.aaps.plugins.source.libre.STOP"
        const val ACTION_CONNECT = "app.aaps.plugins.source.libre.CONNECT"
        const val ACTION_DISCONNECT = "app.aaps.plugins.source.libre.DISCONNECT"
        const val ACTION_SCAN = "app.aaps.plugins.source.libre.SCAN"

        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_SENSOR_TYPE = "sensor_type"
    }

    inner class LocalBinder : Binder() {
        val service: LibreService get() = this@LibreService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        aapsLogger.debug(LTag.BGSOURCE, "LibreService created")

        createNotificationChannel()
        libreBleComm.setCallback(this)

        // Schedule periodic cleanup of old readings
        scope.launch {
            while (true) {
                delay(READINGS_CLEANUP_INTERVAL_MS)
                cleanupOldReadings()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val sensorType = intent.getStringExtra(EXTRA_SENSOR_TYPE)
                    ?.let { LibreSensorType.valueOf(it) }
                    ?: LibreSensorType.UNKNOWN
                if (address != null) {
                    connect(address, sensorType)
                }
            }
            ACTION_DISCONNECT -> disconnect()
            ACTION_SCAN -> startScan()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        aapsLogger.debug(LTag.BGSOURCE, "LibreService destroyed")
        stopService()
        scope.cancel()
        handlerThread.quitSafely()
        disposable.clear()
        super.onDestroy()
    }

    private fun startService() {
        if (isServiceRunning) return

        aapsLogger.info(LTag.BGSOURCE, "Starting LibreService")
        isServiceRunning = true

        startForeground(NOTIFICATION_ID, createNotification("Libre CGM Active"))

        // Start alert monitoring
        libreAlertManager.startMonitoring()

        // Restore previous connection if available
        val savedAddress = libreState.deviceAddress
        if (savedAddress.isNotEmpty()) {
            aapsLogger.debug(LTag.BGSOURCE, "Restoring connection to $savedAddress")
            connect(savedAddress, libreState.sensorType)
        }
    }

    private fun stopService() {
        if (!isServiceRunning) return

        aapsLogger.info(LTag.BGSOURCE, "Stopping LibreService")
        isServiceRunning = false

        // Stop alert monitoring
        libreAlertManager.stopMonitoring()

        disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun startScan() {
        if (serviceState != ServiceState.Idle && serviceState != ServiceState.Error::class) {
            aapsLogger.warn(LTag.BGSOURCE, "Cannot scan: service busy")
            return
        }

        serviceState = ServiceState.Scanning
        libreBleComm.startScan()

        // Schedule scan timeout
        handler.postDelayed({
            if (serviceState == ServiceState.Scanning) {
                aapsLogger.warn(LTag.BGSOURCE, "Scan timeout")
                libreBleComm.stopScan()
                serviceState = ServiceState.Idle
            }
        }, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        libreBleComm.stopScan()
        if (serviceState == ServiceState.Scanning) {
            serviceState = ServiceState.Idle
        }
    }

    fun connect(deviceAddress: String, sensorType: LibreSensorType = LibreSensorType.UNKNOWN) {
        aapsLogger.info(LTag.BGSOURCE, "Connecting to $deviceAddress (type: $sensorType)")

        // Stop any ongoing scan
        libreBleComm.stopScan()

        // Save device address for reconnection
        libreState.deviceAddress = deviceAddress

        // Initialize protocol based on sensor type
        currentProtocol = when (sensorType) {
            LibreSensorType.LIBRE_2 -> libre2Protocol
            LibreSensorType.LIBRE_3 -> libre3Protocol
            else -> {
                // Try to auto-detect based on device name/characteristics
                libre3Protocol // Default to Libre 3 for now
            }
        }

        val sensorInfo = if (libreState.sensorSerialNumber.isNotEmpty()) {
            LibreSensorInfo(
                serialNumber = libreState.sensorSerialNumber,
                startTime = libreState.sensorStartTime,
                expiryTime = libreState.sensorExpiryTime,
                sensorType = libreState.sensorType,
                patchInfo = libreState.patchInfo
            )
        } else null

        currentProtocol?.initialize(sensorInfo)
        currentProtocol?.setCallback(createProtocolCallback())

        serviceState = ServiceState.Connecting

        val success = libreBleComm.connect(deviceAddress)
        if (!success) {
            serviceState = ServiceState.Error("Failed to initiate connection")
            scheduleReconnect()
        }

        // Schedule connection timeout
        handler.postDelayed({
            if (serviceState == ServiceState.Connecting) {
                aapsLogger.warn(LTag.BGSOURCE, "Connection timeout")
                libreBleComm.disconnect()
                scheduleReconnect()
            }
        }, CONNECTION_TIMEOUT_MS)
    }

    fun disconnect() {
        aapsLogger.info(LTag.BGSOURCE, "Disconnecting")
        handler.removeCallbacksAndMessages(null)
        libreBleComm.disconnect()
        currentProtocol?.reset()
        serviceState = ServiceState.Idle
        reconnectAttempt = 0
    }

    private fun scheduleReconnect() {
        if (!isServiceRunning) return

        reconnectAttempt++
        if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS) {
            aapsLogger.error(LTag.BGSOURCE, "Max reconnect attempts reached")
            serviceState = ServiceState.Error("Connection failed after $MAX_RECONNECT_ATTEMPTS attempts")
            libreState.updateConnectionState(LibreConnectionState.DISCONNECTED)
            return
        }

        val delayMs = min(
            RECONNECT_MAX_DELAY_MS,
            RECONNECT_BASE_DELAY_MS * (1L shl (reconnectAttempt - 1))
        )

        aapsLogger.info(LTag.BGSOURCE, "Scheduling reconnect attempt $reconnectAttempt in ${delayMs}ms")
        serviceState = ServiceState.Reconnecting

        handler.postDelayed({
            val address = libreState.deviceAddress
            if (address.isNotEmpty() && isServiceRunning) {
                connect(address, libreState.sensorType)
            }
        }, delayMs)
    }

    private fun createProtocolCallback(): LibreProtocol.Callback {
        return object : LibreProtocol.Callback {
            override fun sendData(data: ByteArray) {
                libreBleComm.writeCharacteristic(data)
            }

            override fun onGlucoseData(readings: List<LibreGlucoseReading>) {
                processGlucoseReadings(readings)
            }

            override fun onSensorInfo(info: LibreSensorInfo) {
                processSensorInfo(info)
            }

            override fun onAuthenticationComplete(success: Boolean) {
                if (success) {
                    aapsLogger.info(LTag.BGSOURCE, "Authentication successful")
                    serviceState = ServiceState.Connected
                    reconnectAttempt = 0
                    libreState.lastConnectionTime = System.currentTimeMillis()
                    updateNotification("Connected to ${libreState.sensorSerialNumber}")

                    // Request initial glucose data
                    currentProtocol?.requestGlucoseData()
                } else {
                    aapsLogger.error(LTag.BGSOURCE, "Authentication failed")
                    serviceState = ServiceState.Error("Authentication failed")
                    scheduleReconnect()
                }
            }

            override fun onError(error: String) {
                aapsLogger.error(LTag.BGSOURCE, "Protocol error: $error")
                serviceState = ServiceState.Error(error)
            }
        }
    }

    // LibreBleCallback implementation

    override fun onConnectionStateChanged(connected: Boolean, device: BluetoothDevice?) {
        aapsLogger.debug(LTag.BGSOURCE, "Connection state changed: connected=$connected")

        if (connected) {
            libreState.lastConnectionTime = System.currentTimeMillis()
        } else {
            if (serviceState == ServiceState.Connected || serviceState == ServiceState.Authenticating) {
                aapsLogger.warn(LTag.BGSOURCE, "Unexpected disconnection")
                scheduleReconnect()
            }
        }
    }

    override fun onServicesDiscovered() {
        aapsLogger.debug(LTag.BGSOURCE, "Services discovered, starting authentication")
        serviceState = ServiceState.Authenticating
        currentProtocol?.startAuthentication()
    }

    override fun onDataReceived(data: ByteArray) {
        currentProtocol?.handleData(data)
    }

    override fun onScanResult(device: BluetoothDevice, rssi: Int) {
        aapsLogger.debug(LTag.BGSOURCE, "Scan result: ${device.name} (${device.address}) RSSI: $rssi")
        // Scan results are typically handled by UI layer
        // Here we just log them; UI can observe libreState
    }

    override fun onScanFailed(errorCode: Int) {
        aapsLogger.error(LTag.BGSOURCE, "Scan failed with error: $errorCode")
        serviceState = ServiceState.Error("Scan failed: $errorCode")
    }

    override fun onError(error: String) {
        aapsLogger.error(LTag.BGSOURCE, "BLE error: $error")
        serviceState = ServiceState.Error(error)

        if (isServiceRunning && libreState.deviceAddress.isNotEmpty()) {
            scheduleReconnect()
        }
    }

    private fun processGlucoseReadings(readings: List<LibreGlucoseReading>) {
        if (readings.isEmpty()) return

        aapsLogger.debug(LTag.BGSOURCE, "Processing ${readings.size} glucose readings")

        // Add to recent readings buffer
        synchronized(recentReadings) {
            recentReadings.addAll(readings)
            // Keep only recent readings (within trend window)
            val cutoff = System.currentTimeMillis() - (30 * 60 * 1000L) // 30 minutes
            recentReadings.removeAll { it.timestamp < cutoff }
        }

        // Calculate trends for new readings
        val readingsWithTrends = synchronized(recentReadings) {
            readings.map { reading ->
                val subset = recentReadings.filter { it.timestamp <= reading.timestamp }
                val trend = trendCalculator.calculateTrend(subset)
                reading.copy(trend = trend)
            }
        }

        // Filter out unreliable readings for persistence
        val reliableReadings = readingsWithTrends.filter {
            it.quality != GlucoseQuality.UNRELIABLE
        }

        if (reliableReadings.isEmpty()) {
            aapsLogger.warn(LTag.BGSOURCE, "No reliable readings to persist")
            return
        }

        // Update state with latest reading
        val latestReading = reliableReadings.maxByOrNull { it.timestamp }
        if (latestReading != null) {
            libreState.updateLatestReading(latestReading)
        }

        // Convert to GV format and persist
        val glucoseValues = reliableReadings.map { reading ->
            GV(
                timestamp = reading.timestamp,
                value = reading.glucoseValue,
                trendArrow = reading.trend,
                raw = reading.rawValue,
                sourceSensor = when (libreState.sensorType) {
                    LibreSensorType.LIBRE_2 -> SourceSensor.LIBRE_2_DIRECT
                    LibreSensorType.LIBRE_3 -> SourceSensor.LIBRE_3_DIRECT
                    else -> SourceSensor.UNKNOWN
                }
            )
        }

        // Persist to database
        disposable.add(
            persistenceLayer.insertCgmSourceData(
                source = Sources.LibreSource,
                glucoseValues = glucoseValues,
                calibrations = emptyList(),
                sensorInsertionTime = libreState.sensorStartTime.takeIf { it > 0 }
            )
                .subscribeOn(aapsSchedulers.io)
                .observeOn(aapsSchedulers.main)
                .subscribe(
                    { result ->
                        aapsLogger.debug(LTag.BGSOURCE, "Persisted ${glucoseValues.size} readings: $result")
                    },
                    { error ->
                        aapsLogger.error(LTag.BGSOURCE, "Failed to persist readings", error)
                    }
                )
        )
    }

    private fun processSensorInfo(info: LibreSensorInfo) {
        aapsLogger.info(LTag.BGSOURCE, "Sensor info received: ${info.serialNumber}")

        // Check if this is a new sensor
        val previousSerial = libreState.sensorSerialNumber
        val isNewSensor = previousSerial.isEmpty() || previousSerial != info.serialNumber

        // Update state
        libreState.sensorSerialNumber = info.serialNumber
        libreState.sensorStartTime = info.startTime
        libreState.sensorExpiryTime = info.expiryTime
        libreState.sensorType = info.sensorType
        info.patchInfo?.let { libreState.patchInfo = it }

        // Update sensor state based on expiry
        val now = System.currentTimeMillis()
        val sensorState = when {
            info.expiryTime <= now -> LibreSensorState.EXPIRED
            info.expiryTime - now <= 60 * 60 * 1000L -> LibreSensorState.ENDING // Within 1 hour
            info.startTime + 60 * 60 * 1000L > now -> LibreSensorState.STARTING // Within 1 hour of start
            else -> LibreSensorState.READY
        }
        libreState.updateSensorState(sensorState)

        // Create sensor change therapy event if this is a new sensor
        if (isNewSensor && preferences.get(LibreBooleanKey.CreateSensorChangeEvents)) {
            createSensorChangeEvent(info)
            libreAlertManager.resetAlertTimers()
        }

        // Save to preferences for persistence across restarts
        libreState.saveToPreferences()

        updateNotification("Sensor: ${info.serialNumber}")
    }

    private fun createSensorChangeEvent(info: LibreSensorInfo) {
        aapsLogger.info(LTag.BGSOURCE, "Creating sensor change event for ${info.serialNumber}")

        val therapyEvent = TE(
            timestamp = info.startTime,
            type = TE.Type.SENSOR_CHANGE,
            glucoseUnit = GlucoseUnit.MGDL,
            note = "Libre sensor: ${info.serialNumber}"
        )

        disposable.add(
            persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                therapyEvent = therapyEvent,
                timestamp = info.startTime,
                action = Action.CAREPORTAL,
                source = Sources.LibreSource,
                note = "Libre sensor: ${info.serialNumber}",
                listValues = listOf(ValueWithUnit.TEType(TE.Type.SENSOR_CHANGE))
            )
                .subscribeOn(aapsSchedulers.io)
                .observeOn(aapsSchedulers.main)
                .subscribe(
                    { result ->
                        aapsLogger.debug(LTag.BGSOURCE, "Sensor change event created: $result")
                    },
                    { error ->
                        aapsLogger.error(LTag.BGSOURCE, "Failed to create sensor change event", error)
                    }
                )
        )
    }

    private fun updateConnectionState(state: ServiceState) {
        val connectionState = when (state) {
            is ServiceState.Idle -> LibreConnectionState.DISCONNECTED
            is ServiceState.Scanning -> LibreConnectionState.SCANNING
            is ServiceState.Connecting -> LibreConnectionState.CONNECTING
            is ServiceState.Authenticating -> LibreConnectionState.AUTHENTICATING
            is ServiceState.Connected -> LibreConnectionState.CONNECTED
            is ServiceState.Reconnecting -> LibreConnectionState.RECONNECTING
            is ServiceState.Error -> LibreConnectionState.DISCONNECTED
        }
        libreState.updateConnectionState(connectionState)
    }

    private fun cleanupOldReadings() {
        synchronized(recentReadings) {
            val cutoff = System.currentTimeMillis() - (30 * 60 * 1000L)
            val sizeBefore = recentReadings.size
            recentReadings.removeAll { it.timestamp < cutoff }
            if (sizeBefore != recentReadings.size) {
                aapsLogger.debug(LTag.BGSOURCE, "Cleaned up ${sizeBefore - recentReadings.size} old readings")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Libre CGM Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous glucose monitoring service"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.libre_source))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_libre_sensor)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }
}
