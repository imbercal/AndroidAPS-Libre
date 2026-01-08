package app.aaps.plugins.source.libre.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.source.libre.LibreState
import app.aaps.plugins.source.libre.R
import app.aaps.plugins.source.libre.ble.LibreBleCallback
import app.aaps.plugins.source.libre.ble.LibreBleComm
import app.aaps.plugins.source.libre.data.LibreSensorType
import app.aaps.plugins.source.libre.service.LibreService
import dagger.android.support.DaggerAppCompatActivity
import javax.inject.Inject

/**
 * Activity for pairing with a new Libre sensor.
 *
 * Flow:
 * 1. Check/request Bluetooth permissions
 * 2. Scan for nearby Libre sensors
 * 3. Display found devices in a list
 * 4. User selects a device to pair
 * 5. Initiate connection via LibreService
 */
class LibrePairingActivity : DaggerAppCompatActivity(), LibreBleCallback {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var libreBleComm: LibreBleComm
    @Inject lateinit var libreState: LibreState

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var deviceList: RecyclerView
    private lateinit var btnScan: Button
    private lateinit var btnCancel: Button

    private val deviceAdapter = DeviceAdapter { device -> onDeviceSelected(device) }
    private val foundDevices = mutableListOf<ScannedDevice>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startScan()
        } else {
            statusText.text = rh.gs(R.string.libre_permission_denied)
        }
    }

    data class ScannedDevice(
        val device: BluetoothDevice,
        val name: String,
        val rssi: Int,
        val sensorType: LibreSensorType
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_libre_pairing)

        // Bind views
        statusText = findViewById(R.id.pairing_status)
        progressBar = findViewById(R.id.pairing_progress)
        deviceList = findViewById(R.id.device_list)
        btnScan = findViewById(R.id.btn_scan)
        btnCancel = findViewById(R.id.btn_cancel)

        // Setup RecyclerView
        deviceList.layoutManager = LinearLayoutManager(this)
        deviceList.adapter = deviceAdapter

        // Setup buttons
        btnScan.setOnClickListener { checkPermissionsAndScan() }
        btnCancel.setOnClickListener { finish() }

        // Set initial state
        statusText.text = rh.gs(R.string.libre_tap_scan)
        progressBar.visibility = View.GONE

        libreBleComm.setCallback(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        libreBleComm.stopScan()
    }

    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startScan()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startScan() {
        aapsLogger.debug(LTag.BGSOURCE, "Starting Libre sensor scan")

        foundDevices.clear()
        deviceAdapter.updateDevices(foundDevices)

        statusText.text = rh.gs(R.string.libre_state_scanning)
        progressBar.visibility = View.VISIBLE
        btnScan.isEnabled = false

        libreBleComm.startScan()

        // Stop scan after 30 seconds
        btnScan.postDelayed({
            stopScan()
        }, 30000)
    }

    private fun stopScan() {
        libreBleComm.stopScan()
        progressBar.visibility = View.GONE
        btnScan.isEnabled = true

        if (foundDevices.isEmpty()) {
            statusText.text = rh.gs(R.string.libre_no_devices_found)
        } else {
            statusText.text = rh.gs(R.string.libre_select_device)
        }
    }

    private fun onDeviceSelected(device: ScannedDevice) {
        aapsLogger.info(LTag.BGSOURCE, "Device selected: ${device.name} (${device.device.address})")

        stopScan()
        statusText.text = rh.gs(R.string.libre_state_connecting)
        progressBar.visibility = View.VISIBLE

        // Connect via service
        val intent = Intent(this, LibreService::class.java).apply {
            action = LibreService.ACTION_CONNECT
            putExtra(LibreService.EXTRA_DEVICE_ADDRESS, device.device.address)
            putExtra(LibreService.EXTRA_SENSOR_TYPE, device.sensorType.name)
        }
        startService(intent)

        // Close activity - LibreFragment will show connection status
        finish()
    }

    // LibreBleCallback implementation

    override fun onScanResult(device: BluetoothDevice, rssi: Int) {
        val name = device.name ?: "Unknown"

        // Check if it's a Libre device
        val sensorType = detectSensorType(name)
        if (sensorType == LibreSensorType.UNKNOWN) {
            return // Not a Libre sensor
        }

        // Check if already in list
        val existing = foundDevices.find { it.device.address == device.address }
        if (existing != null) {
            // Update RSSI
            val index = foundDevices.indexOf(existing)
            foundDevices[index] = existing.copy(rssi = rssi)
        } else {
            foundDevices.add(ScannedDevice(device, name, rssi, sensorType))
            aapsLogger.debug(LTag.BGSOURCE, "Found Libre device: $name ($sensorType)")
        }

        // Sort by signal strength
        foundDevices.sortByDescending { it.rssi }
        deviceAdapter.updateDevices(foundDevices)
    }

    override fun onScanFailed(errorCode: Int) {
        aapsLogger.error(LTag.BGSOURCE, "Scan failed: $errorCode")
        runOnUiThread {
            progressBar.visibility = View.GONE
            btnScan.isEnabled = true
            statusText.text = rh.gs(R.string.libre_scan_failed)
        }
    }

    override fun onConnectionStateChanged(connected: Boolean, device: BluetoothDevice?) {
        // Not used in pairing activity
    }

    override fun onServicesDiscovered() {
        // Not used in pairing activity
    }

    override fun onDataReceived(data: ByteArray) {
        // Not used in pairing activity
    }

    override fun onError(error: String) {
        aapsLogger.error(LTag.BGSOURCE, "BLE error: $error")
    }

    private fun detectSensorType(name: String): LibreSensorType {
        return when {
            name.contains("ABBOTT", ignoreCase = true) -> LibreSensorType.LIBRE_2
            name.contains("Libre 2", ignoreCase = true) -> LibreSensorType.LIBRE_2
            name.contains("Libre2", ignoreCase = true) -> LibreSensorType.LIBRE_2
            name.contains("Libre 3", ignoreCase = true) -> LibreSensorType.LIBRE_3
            name.contains("Libre3", ignoreCase = true) -> LibreSensorType.LIBRE_3
            name.startsWith("BLU", ignoreCase = true) -> LibreSensorType.LIBRE_3
            else -> LibreSensorType.UNKNOWN
        }
    }

    // RecyclerView Adapter
    inner class DeviceAdapter(
        private val onItemClick: (ScannedDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        private var devices = listOf<ScannedDevice>()

        fun updateDevices(newDevices: List<ScannedDevice>) {
            devices = newDevices.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_libre_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(devices[position])
        }

        override fun getItemCount() = devices.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameText: TextView = itemView.findViewById(R.id.device_name)
            private val infoText: TextView = itemView.findViewById(R.id.device_info)

            fun bind(device: ScannedDevice) {
                nameText.text = device.name
                infoText.text = "${device.sensorType.name} | Signal: ${device.rssi} dBm"
                itemView.setOnClickListener { onItemClick(device) }
            }
        }
    }
}
