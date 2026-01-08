package app.aaps.plugins.source.libre

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class LibreSourcePluginTest {

    @Mock
    private lateinit var rh: ResourceHelper

    @Mock
    private lateinit var aapsLogger: AAPSLogger

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var libreState: LibreState

    private lateinit var libreSourcePlugin: LibreSourcePlugin

    @BeforeEach
    fun setup() {
        MockitoAnnotations.openMocks(this)
        libreSourcePlugin = LibreSourcePlugin(rh, aapsLogger, context, libreState)
    }

    @Test
    fun `advancedFilteringSupported returns true`() {
        assertThat(libreSourcePlugin.advancedFilteringSupported()).isTrue()
    }

    @Test
    fun `sensorBatteryLevel returns value from libreState`() {
        org.mockito.Mockito.`when`(libreState.sensorBatteryPercent).thenReturn(75)
        assertThat(libreSourcePlugin.sensorBatteryLevel).isEqualTo(75)
    }

    @Test
    fun `sensorBatteryLevel returns -1 when unknown`() {
        org.mockito.Mockito.`when`(libreState.sensorBatteryPercent).thenReturn(-1)
        assertThat(libreSourcePlugin.sensorBatteryLevel).isEqualTo(-1)
    }
}
