package app.aaps.plugins.source.libre.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.source.libre.LibreState
import app.aaps.plugins.source.libre.R
import app.aaps.plugins.source.libre.data.LibreConnectionState
import app.aaps.plugins.source.libre.data.LibreSensorState
import app.aaps.plugins.source.libre.service.LibreService
import dagger.android.support.DaggerFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fragment displaying Libre sensor status and controls.
 *
 * Shows:
 * - Current glucose value and trend
 * - Sensor info (serial, age, time remaining)
 * - Connection status
 * - Pair/disconnect buttons
 */
class LibreFragment : DaggerFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var libreState: LibreState
    @Inject lateinit var dateUtil: DateUtil

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI elements
    private var connectionStatus: TextView? = null
    private var connectionIndicator: View? = null
    private var sensorSerial: TextView? = null
    private var sensorState: TextView? = null
    private var timeRemaining: TextView? = null
    private var sensorProgress: ProgressBar? = null
    private var glucoseValue: TextView? = null
    private var glucoseTrend: TextView? = null
    private var readingTime: TextView? = null
    private var noSensorMessage: TextView? = null
    private var btnPair: Button? = null
    private var btnDisconnect: Button? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_libre, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind views
        connectionStatus = view.findViewById(R.id.connection_status)
        connectionIndicator = view.findViewById(R.id.connection_indicator)
        sensorSerial = view.findViewById(R.id.sensor_serial)
        sensorState = view.findViewById(R.id.sensor_state)
        timeRemaining = view.findViewById(R.id.time_remaining)
        sensorProgress = view.findViewById(R.id.sensor_progress)
        glucoseValue = view.findViewById(R.id.glucose_value)
        glucoseTrend = view.findViewById(R.id.glucose_trend)
        readingTime = view.findViewById(R.id.reading_time)
        noSensorMessage = view.findViewById(R.id.no_sensor_message)
        btnPair = view.findViewById(R.id.btn_pair)
        btnDisconnect = view.findViewById(R.id.btn_disconnect)

        // Set up button listeners
        btnPair?.setOnClickListener { onPairClicked() }
        btnDisconnect?.setOnClickListener { onDisconnectClicked() }

        // Observe state changes
        scope.launch {
            libreState.connectionStateFlow.collect {
                updateUI()
            }
        }
        scope.launch {
            libreState.sensorStateFlow.collect {
                updateUI()
            }
        }
        scope.launch {
            libreState.lastGlucoseReadingFlow.collect {
                updateGlucoseDisplay()
            }
        }

        // Initial UI update
        updateUI()
        updateGlucoseDisplay()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        connectionStatus = null
        connectionIndicator = null
        sensorSerial = null
        sensorState = null
        timeRemaining = null
        sensorProgress = null
        glucoseValue = null
        glucoseTrend = null
        readingTime = null
        noSensorMessage = null
        btnPair = null
        btnDisconnect = null
    }

    private fun updateUI() {
        // Update connection status
        val connState = libreState.connectionState
        connectionStatus?.text = when (connState) {
            LibreConnectionState.DISCONNECTED -> rh.gs(R.string.libre_state_disconnected)
            LibreConnectionState.SCANNING -> rh.gs(R.string.libre_state_scanning)
            LibreConnectionState.CONNECTING -> rh.gs(R.string.libre_state_connecting)
            LibreConnectionState.CONNECTED -> rh.gs(R.string.libre_state_connected)
            LibreConnectionState.AUTHENTICATING -> rh.gs(R.string.libre_state_authenticating)
            LibreConnectionState.RECONNECTING -> rh.gs(R.string.libre_state_reconnecting)
        }

        // Update connection indicator color
        val indicatorColor = when (connState) {
            LibreConnectionState.CONNECTED -> android.R.color.holo_green_light
            LibreConnectionState.DISCONNECTED -> android.R.color.holo_red_light
            else -> android.R.color.holo_orange_light
        }
        connectionIndicator?.setBackgroundColor(
            ContextCompat.getColor(requireContext(), indicatorColor)
        )

        // Update sensor info
        val hasSensor = libreState.hasSensor()
        if (hasSensor) {
            noSensorMessage?.visibility = View.GONE

            sensorSerial?.text = libreState.sensorSerialNumber
            sensorState?.text = when (libreState.sensorState) {
                LibreSensorState.NONE -> rh.gs(R.string.libre_sensor_state_none)
                LibreSensorState.STARTING -> rh.gs(R.string.libre_sensor_state_starting)
                LibreSensorState.READY -> rh.gs(R.string.libre_sensor_state_ready)
                LibreSensorState.ENDING -> rh.gs(R.string.libre_sensor_state_ending)
                LibreSensorState.EXPIRED -> rh.gs(R.string.libre_sensor_state_expired)
                LibreSensorState.ERROR -> rh.gs(R.string.libre_sensor_state_error)
                LibreSensorState.UNKNOWN -> "Unknown"
            }

            // Calculate and display time remaining
            val remainingMs = libreState.getRemainingTimeMs()
            if (remainingMs > 0) {
                val days = remainingMs / (24 * 60 * 60 * 1000)
                val hours = (remainingMs / (60 * 60 * 1000)) % 24
                val minutes = (remainingMs / (60 * 1000)) % 60

                timeRemaining?.text = when {
                    days > 0 -> "${days}d ${hours}h"
                    hours > 0 -> "${hours}h ${minutes}m"
                    else -> "${minutes}m"
                }

                // Progress bar: 14 days = 100%
                val totalLifespan = 14L * 24 * 60 * 60 * 1000
                val elapsed = totalLifespan - remainingMs
                val progress = (elapsed.toFloat() / totalLifespan * 100).toInt().coerceIn(0, 100)
                sensorProgress?.progress = progress
            } else {
                timeRemaining?.text = rh.gs(R.string.libre_sensor_state_expired)
                sensorProgress?.progress = 100
            }
        } else {
            noSensorMessage?.visibility = View.VISIBLE
            sensorSerial?.text = "-"
            sensorState?.text = "-"
            timeRemaining?.text = "-"
            sensorProgress?.progress = 0
        }

        // Update button states
        val isConnected = connState == LibreConnectionState.CONNECTED
        btnDisconnect?.isEnabled = isConnected || connState == LibreConnectionState.CONNECTING
        btnPair?.isEnabled = connState == LibreConnectionState.DISCONNECTED
    }

    private fun updateGlucoseDisplay() {
        val reading = libreState.lastGlucoseReading
        if (reading != null) {
            glucoseValue?.text = reading.glucoseValue.toInt().toString()
            glucoseValue?.visibility = View.VISIBLE

            // Trend arrow
            glucoseTrend?.text = getTrendArrowSymbol(reading.trend)
            glucoseTrend?.visibility = View.VISIBLE

            // Reading time
            val minutesAgo = (System.currentTimeMillis() - reading.timestamp) / 60000
            readingTime?.text = "${minutesAgo}m ago"
            readingTime?.visibility = View.VISIBLE
        } else {
            glucoseValue?.text = "--"
            glucoseTrend?.text = ""
            readingTime?.visibility = View.GONE
        }
    }

    private fun getTrendArrowSymbol(trend: TrendArrow): String {
        return when (trend) {
            TrendArrow.DOUBLE_UP -> "\u21C8" // ⇈
            TrendArrow.SINGLE_UP -> "\u2191" // ↑
            TrendArrow.FORTY_FIVE_UP -> "\u2197" // ↗
            TrendArrow.FLAT -> "\u2192" // →
            TrendArrow.FORTY_FIVE_DOWN -> "\u2198" // ↘
            TrendArrow.SINGLE_DOWN -> "\u2193" // ↓
            TrendArrow.DOUBLE_DOWN -> "\u21CA" // ⇊
            else -> ""
        }
    }

    private fun onPairClicked() {
        aapsLogger.debug(LTag.BGSOURCE, "Pair button clicked")

        // Start scanning via service
        val intent = Intent(requireContext(), LibreService::class.java).apply {
            action = LibreService.ACTION_SCAN
        }
        requireContext().startService(intent)

        // TODO: Launch pairing activity in future (Phase 5 continuation)
        // For now, just start scan
    }

    private fun onDisconnectClicked() {
        aapsLogger.debug(LTag.BGSOURCE, "Disconnect button clicked")

        val intent = Intent(requireContext(), LibreService::class.java).apply {
            action = LibreService.ACTION_DISCONNECT
        }
        requireContext().startService(intent)
    }
}
