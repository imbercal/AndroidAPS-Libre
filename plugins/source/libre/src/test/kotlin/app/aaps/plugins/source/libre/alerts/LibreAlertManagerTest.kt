package app.aaps.plugins.source.libre.alerts

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.source.libre.LibreState
import app.aaps.plugins.source.libre.data.LibreConnectionState
import app.aaps.plugins.source.libre.keys.LibreBooleanKey
import app.aaps.plugins.source.libre.keys.LibreIntKey
import app.aaps.plugins.source.libre.keys.LibreLongNonKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

class LibreAlertManagerTest {

    @Mock
    private lateinit var aapsLogger: AAPSLogger

    @Mock
    private lateinit var rxBus: RxBus

    @Mock
    private lateinit var rh: ResourceHelper

    @Mock
    private lateinit var preferences: Preferences

    @Mock
    private lateinit var libreState: LibreState

    @Mock
    private lateinit var dateUtil: DateUtil

    private lateinit var libreAlertManager: LibreAlertManager

    @BeforeEach
    fun setup() {
        MockitoAnnotations.openMocks(this)

        // Default mock behaviors
        `when`(rh.gs(anyInt())).thenReturn("Test String")
        `when`(rh.gs(anyInt(), any())).thenReturn("Test String")
        `when`(dateUtil.now()).thenReturn(System.currentTimeMillis())

        libreAlertManager = LibreAlertManager(
            aapsLogger,
            rxBus,
            rh,
            preferences,
            libreState,
            dateUtil
        )
    }

    @Test
    fun `resetAlertTimers clears all alert times`() {
        libreAlertManager.resetAlertTimers()

        verify(preferences).put(LibreLongNonKey.NextExpiry24hAlert, 0L)
        verify(preferences).put(LibreLongNonKey.NextExpiry12hAlert, 0L)
        verify(preferences).put(LibreLongNonKey.NextExpiry1hAlert, 0L)
        verify(preferences).put(LibreLongNonKey.NextConnectionLostAlert, 0L)
    }

    @Test
    fun `resetAlertTimers dismisses all notifications`() {
        libreAlertManager.resetAlertTimers()

        // Verify all notification dismissals are sent
        val captor = ArgumentCaptor.forClass(EventDismissNotification::class.java)
        verify(rxBus, org.mockito.Mockito.atLeast(7)).send(captor.capture())

        val dismissedIds = captor.allValues.map { it.id }
        assertThat(dismissedIds).contains(Notification.LIBRE_SENSOR_EXPIRY_24H)
        assertThat(dismissedIds).contains(Notification.LIBRE_SENSOR_EXPIRY_12H)
        assertThat(dismissedIds).contains(Notification.LIBRE_SENSOR_EXPIRY_1H)
        assertThat(dismissedIds).contains(Notification.LIBRE_SENSOR_EXPIRED)
        assertThat(dismissedIds).contains(Notification.LIBRE_CONNECTION_LOST)
        assertThat(dismissedIds).contains(Notification.LIBRE_SIGNAL_QUALITY)
        assertThat(dismissedIds).contains(Notification.LIBRE_SENSOR_ERROR)
    }

    @Test
    fun `sendSignalQualityAlert does nothing when disabled`() {
        `when`(preferences.get(LibreBooleanKey.SignalQualityAlert)).thenReturn(false)

        libreAlertManager.sendSignalQualityAlert("Poor")

        verify(rxBus, never()).send(any(EventNewNotification::class.java))
    }

    @Test
    fun `sendSignalQualityAlert sends notification when enabled`() {
        `when`(preferences.get(LibreBooleanKey.SignalQualityAlert)).thenReturn(true)

        libreAlertManager.sendSignalQualityAlert("Poor")

        val captor = ArgumentCaptor.forClass(EventNewNotification::class.java)
        verify(rxBus).send(captor.capture())

        assertThat(captor.value.notification.id).isEqualTo(Notification.LIBRE_SIGNAL_QUALITY)
    }

    @Test
    fun `dismissSignalQualityAlert sends dismiss event`() {
        libreAlertManager.dismissSignalQualityAlert()

        val captor = ArgumentCaptor.forClass(EventDismissNotification::class.java)
        verify(rxBus).send(captor.capture())

        assertThat(captor.value.id).isEqualTo(Notification.LIBRE_SIGNAL_QUALITY)
    }

    @Test
    fun `sendSensorErrorAlert sends urgent notification`() {
        libreAlertManager.sendSensorErrorAlert("Test error")

        val captor = ArgumentCaptor.forClass(EventNewNotification::class.java)
        verify(rxBus).send(captor.capture())

        val notification = captor.value.notification
        assertThat(notification.id).isEqualTo(Notification.LIBRE_SENSOR_ERROR)
        assertThat(notification.level).isEqualTo(Notification.URGENT)
    }

    @Test
    fun `startMonitoring and stopMonitoring do not throw`() {
        // Just verify no exceptions are thrown
        libreAlertManager.startMonitoring()
        libreAlertManager.stopMonitoring()
    }
}
