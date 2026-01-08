package app.aaps.plugins.source.libre.alerts

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.source.libre.LibreState
import app.aaps.plugins.source.libre.R
import app.aaps.plugins.source.libre.data.LibreConnectionState
import app.aaps.plugins.source.libre.keys.LibreBooleanKey
import app.aaps.plugins.source.libre.keys.LibreIntKey
import app.aaps.plugins.source.libre.keys.LibreLongNonKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages alerts for Libre sensor status:
 * - Sensor expiry warnings (24h, 12h, 1h)
 * - Connection lost alerts
 * - Signal quality warnings
 */
@Singleton
class LibreAlertManager @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val rh: ResourceHelper,
    private val preferences: Preferences,
    private val libreState: LibreState,
    private val dateUtil: DateUtil
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitoringJob: Job? = null
    private var connectionMonitorJob: Job? = null

    companion object {
        // Check interval for sensor expiry (5 minutes)
        private const val EXPIRY_CHECK_INTERVAL_MS = 5 * 60 * 1000L

        // Connection lost check interval (1 minute)
        private const val CONNECTION_CHECK_INTERVAL_MS = 60 * 1000L

        // Expiry thresholds
        private val EXPIRY_24H_MS = T.hours(24).msecs()
        private val EXPIRY_12H_MS = T.hours(12).msecs()
        private val EXPIRY_1H_MS = T.hours(1).msecs()
    }

    /**
     * Start monitoring for alerts
     */
    fun startMonitoring() {
        aapsLogger.debug(LTag.BGSOURCE, "LibreAlertManager starting monitoring")

        // Start sensor expiry monitoring
        monitoringJob?.cancel()
        monitoringJob = scope.launch {
            while (isActive) {
                checkSensorExpiry()
                delay(EXPIRY_CHECK_INTERVAL_MS)
            }
        }

        // Start connection monitoring
        connectionMonitorJob?.cancel()
        connectionMonitorJob = scope.launch {
            while (isActive) {
                checkConnectionStatus()
                delay(CONNECTION_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop all alert monitoring
     */
    fun stopMonitoring() {
        aapsLogger.debug(LTag.BGSOURCE, "LibreAlertManager stopping monitoring")
        monitoringJob?.cancel()
        connectionMonitorJob?.cancel()
    }

    /**
     * Check sensor expiry and send appropriate alerts
     */
    private fun checkSensorExpiry() {
        val expiryTime = libreState.sensorExpiryTime
        if (expiryTime == 0L) return

        val now = dateUtil.now()
        val remaining = expiryTime - now

        when {
            remaining <= 0 -> {
                sendExpiryAlert(
                    Notification.LIBRE_SENSOR_EXPIRED,
                    Notification.URGENT,
                    R.string.libre_sensor_expired,
                    playSound = true
                )
            }
            remaining <= EXPIRY_1H_MS -> {
                if (preferences.get(LibreBooleanKey.ExpiryWarning1h) && shouldSendAlert(LibreLongNonKey.NextExpiry1hAlert)) {
                    sendExpiryAlert(
                        Notification.LIBRE_SENSOR_EXPIRY_1H,
                        Notification.URGENT,
                        R.string.libre_expiry_1h_message,
                        playSound = true
                    )
                    scheduleNextAlert(LibreLongNonKey.NextExpiry1hAlert, T.mins(30).msecs())
                }
            }
            remaining <= EXPIRY_12H_MS -> {
                if (preferences.get(LibreBooleanKey.ExpiryWarning12h) && shouldSendAlert(LibreLongNonKey.NextExpiry12hAlert)) {
                    sendExpiryAlert(
                        Notification.LIBRE_SENSOR_EXPIRY_12H,
                        Notification.NORMAL,
                        R.string.libre_expiry_12h_message,
                        playSound = false
                    )
                    scheduleNextAlert(LibreLongNonKey.NextExpiry12hAlert, T.hours(6).msecs())
                }
            }
            remaining <= EXPIRY_24H_MS -> {
                if (preferences.get(LibreBooleanKey.ExpiryWarning24h) && shouldSendAlert(LibreLongNonKey.NextExpiry24hAlert)) {
                    sendExpiryAlert(
                        Notification.LIBRE_SENSOR_EXPIRY_24H,
                        Notification.LOW,
                        R.string.libre_expiry_24h_message,
                        playSound = false
                    )
                    scheduleNextAlert(LibreLongNonKey.NextExpiry24hAlert, T.hours(12).msecs())
                }
            }
        }
    }

    /**
     * Check connection status and alert if disconnected too long
     */
    private fun checkConnectionStatus() {
        if (!preferences.get(LibreBooleanKey.ConnectionLostAlert)) return

        val connectionState = libreState.connectionState
        val lastConnection = libreState.lastConnectionTime

        if (lastConnection == 0L) return

        val now = dateUtil.now()
        val thresholdMinutes = preferences.get(LibreIntKey.ConnectionLostThresholdMinutes)
        val threshold = T.mins(thresholdMinutes.toLong()).msecs()

        val isDisconnected = connectionState != LibreConnectionState.CONNECTED
        val isTimeout = (now - lastConnection) > threshold

        if (isDisconnected && isTimeout && shouldSendAlert(LibreLongNonKey.NextConnectionLostAlert)) {
            val minutesSinceConnection = (now - lastConnection) / 60000
            val message = rh.gs(R.string.libre_connection_lost_message, minutesSinceConnection)

            val notification = Notification(
                Notification.LIBRE_CONNECTION_LOST,
                message,
                Notification.NORMAL
            )

            rxBus.send(EventNewNotification(notification))
            aapsLogger.warn(LTag.BGSOURCE, "Connection lost alert: $message")

            // Schedule next alert (don't spam every minute)
            scheduleNextAlert(LibreLongNonKey.NextConnectionLostAlert, T.mins(15).msecs())
        } else if (!isDisconnected || !isTimeout) {
            // Clear connection lost alert when connected
            rxBus.send(EventDismissNotification(Notification.LIBRE_CONNECTION_LOST))
        }
    }

    /**
     * Send alert for signal quality issues
     */
    fun sendSignalQualityAlert(quality: String) {
        if (!preferences.get(LibreBooleanKey.SignalQualityAlert)) return

        val message = rh.gs(R.string.libre_signal_quality_message, quality)
        val notification = Notification(
            Notification.LIBRE_SIGNAL_QUALITY,
            message,
            Notification.NORMAL
        )

        rxBus.send(EventNewNotification(notification))
        aapsLogger.warn(LTag.BGSOURCE, "Signal quality alert: $message")
    }

    /**
     * Dismiss signal quality alert
     */
    fun dismissSignalQualityAlert() {
        rxBus.send(EventDismissNotification(Notification.LIBRE_SIGNAL_QUALITY))
    }

    /**
     * Send sensor error alert
     */
    fun sendSensorErrorAlert(error: String) {
        val notification = Notification(
            Notification.LIBRE_SENSOR_ERROR,
            rh.gs(R.string.libre_sensor_error_message, error),
            Notification.URGENT
        ).also { it.soundId = app.aaps.core.ui.R.raw.alarm }

        rxBus.send(EventNewNotification(notification))
        aapsLogger.error(LTag.BGSOURCE, "Sensor error alert: $error")
    }

    /**
     * Reset all alert timers (call on sensor change)
     */
    fun resetAlertTimers() {
        preferences.put(LibreLongNonKey.NextExpiry24hAlert, 0L)
        preferences.put(LibreLongNonKey.NextExpiry12hAlert, 0L)
        preferences.put(LibreLongNonKey.NextExpiry1hAlert, 0L)
        preferences.put(LibreLongNonKey.NextConnectionLostAlert, 0L)

        // Dismiss all current alerts
        rxBus.send(EventDismissNotification(Notification.LIBRE_SENSOR_EXPIRY_24H))
        rxBus.send(EventDismissNotification(Notification.LIBRE_SENSOR_EXPIRY_12H))
        rxBus.send(EventDismissNotification(Notification.LIBRE_SENSOR_EXPIRY_1H))
        rxBus.send(EventDismissNotification(Notification.LIBRE_SENSOR_EXPIRED))
        rxBus.send(EventDismissNotification(Notification.LIBRE_CONNECTION_LOST))
        rxBus.send(EventDismissNotification(Notification.LIBRE_SIGNAL_QUALITY))
        rxBus.send(EventDismissNotification(Notification.LIBRE_SENSOR_ERROR))

        aapsLogger.debug(LTag.BGSOURCE, "Alert timers reset")
    }

    private fun sendExpiryAlert(id: Int, level: Int, messageRes: Int, playSound: Boolean) {
        val remaining = libreState.getRemainingTimeMs()
        val hoursRemaining = remaining / T.hours(1).msecs()
        val minutesRemaining = (remaining % T.hours(1).msecs()) / T.mins(1).msecs()

        val timeString = if (hoursRemaining > 0) {
            rh.gs(R.string.libre_time_hours_minutes, hoursRemaining, minutesRemaining)
        } else {
            rh.gs(R.string.libre_time_minutes, minutesRemaining)
        }

        val message = rh.gs(messageRes, timeString)

        val notification = Notification(id, message, level)
        if (playSound) {
            notification.soundId = app.aaps.core.ui.R.raw.alarm
        }

        rxBus.send(EventNewNotification(notification))
        aapsLogger.info(LTag.BGSOURCE, "Expiry alert: $message")
    }

    private fun shouldSendAlert(key: LibreLongNonKey): Boolean {
        val nextAlertTime = preferences.get(key)
        return dateUtil.now() >= nextAlertTime
    }

    private fun scheduleNextAlert(key: LibreLongNonKey, delayMs: Long) {
        preferences.put(key, dateUtil.now() + delayMs)
    }
}
