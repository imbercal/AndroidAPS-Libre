package app.aaps.plugins.source.libre

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.source.libre.keys.LibreBooleanKey
import app.aaps.plugins.source.libre.service.LibreService
import app.aaps.plugins.source.libre.ui.LibreFragment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BG Source plugin for direct FreeStyle Libre 2/3 connection.
 *
 * This plugin enables direct BLE communication with Libre sensors,
 * eliminating the need for bridge apps like xDrip+ or Juggluco.
 */
@Singleton
class LibreSourcePlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val context: Context,
    private val libreState: LibreState
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(LibreFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_generic_cgm)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.libre_source)
        .shortName(R.string.libre_short)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_libre)
        .visibleByDefault(true)
        .simpleModePosition(PluginDescription.Position.TAB),
    aapsLogger, rh
), BgSource {

    override fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.BGSOURCE, "LibreSourcePlugin started")
        libreState.loadFromPreferences()

        // Start the background service for BLE communication
        startLibreService()
    }

    override fun onStop() {
        super.onStop()
        aapsLogger.debug(LTag.BGSOURCE, "LibreSourcePlugin stopped")

        // Stop the background service
        stopLibreService()
    }

    private fun startLibreService() {
        try {
            val intent = Intent(context, LibreService::class.java).apply {
                action = LibreService.ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
            aapsLogger.info(LTag.BGSOURCE, "LibreService start requested")
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Failed to start LibreService", e)
        }
    }

    private fun stopLibreService() {
        try {
            val intent = Intent(context, LibreService::class.java).apply {
                action = LibreService.ACTION_STOP
            }
            context.startService(intent)
            aapsLogger.info(LTag.BGSOURCE, "LibreService stop requested")
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Failed to stop LibreService", e)
        }
    }

    override fun advancedFilteringSupported(): Boolean = true

    override val sensorBatteryLevel: Int
        get() = libreState.sensorBatteryPercent

    override fun addPreferenceScreen(
        preferenceManager: PreferenceManager,
        parent: PreferenceScreen,
        context: Context,
        requiredKey: String?
    ) {
        if (requiredKey != null) return

        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "libre_source_settings"
            title = rh.gs(R.string.libre_settings)
            initialExpandedChildrenCount = 0

            // Nightscout upload settings
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = LibreBooleanKey.UploadToNightscout,
                    title = app.aaps.core.ui.R.string.do_ns_upload_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = LibreBooleanKey.CreateSensorChangeEvents,
                    title = R.string.libre_log_sensor_change_title
                )
            )
        }

        // Alert settings
        val alertCategory = PreferenceCategory(context)
        parent.addPreference(alertCategory)
        alertCategory.apply {
            key = "libre_alert_settings"
            title = rh.gs(R.string.libre_alert_settings)
            initialExpandedChildrenCount = 0

            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = LibreBooleanKey.ExpiryWarning24h,
                    title = R.string.libre_expiry_24h_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = LibreBooleanKey.ExpiryWarning12h,
                    title = R.string.libre_expiry_12h_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = LibreBooleanKey.ExpiryWarning1h,
                    title = R.string.libre_expiry_1h_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = LibreBooleanKey.ConnectionLostAlert,
                    title = R.string.libre_connection_lost_title
                )
            )
        }

        // Connection settings
        val connectionCategory = PreferenceCategory(context)
        parent.addPreference(connectionCategory)
        connectionCategory.apply {
            key = "libre_connection_settings"
            title = rh.gs(R.string.libre_connection_settings)
            initialExpandedChildrenCount = 0

            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context,
                    booleanKey = LibreBooleanKey.AutoReconnect,
                    title = R.string.libre_auto_reconnect_title
                )
            )
        }
    }
}
