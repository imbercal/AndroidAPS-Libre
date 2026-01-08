package app.aaps.plugins.source.libre.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.source.libre.data.LibreSensorType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles Bluetooth Low Energy communication with FreeStyle Libre sensors.
 *
 * Supports:
 * - Libre 2: NFC-activated with optional BLE for real-time readings
 * - Libre 3: Direct BLE communication (no NFC needed for readings)
 */
@Singleton
class LibreBleComm @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val context: Context
) {

    companion object {
        private const val WRITE_DELAY_MILLIS: Long = 50
        private const val SCAN_TIMEOUT_MS: Long = 30000 // 30 seconds
        private const val CONNECTION_TIMEOUT_MS: Long = 30000

        // Libre 2 BLE UUIDs (from xDrip+)
        // Note: Libre 2 uses proprietary Abbott services
        private val LIBRE2_SERVICE_UUID = UUID.fromString("0000FDE3-0000-1000-8000-00805F9B34FB")
        private val LIBRE2_WRITE_UUID = UUID.fromString("F001-0000-1000-8000-00805F9B34FB")
        private val LIBRE2_NOTIFY_UUID = UUID.fromString("F002-0000-1000-8000-00805F9B34FB")

        // Libre 3 BLE UUIDs (from xDrip+)
        private val LIBRE3_SERVICE_UUID = UUID.fromString("089810CC-EF89-11E9-81B4-2A2AE2DBCCE4")
        private val LIBRE3_WRITE_UUID = UUID.fromString("08981338-EF89-11E9-81B4-2A2AE2DBCCE4")
        private val LIBRE3_NOTIFY_UUID = UUID.fromString("0898177A-EF89-11E9-81B4-2A2AE2DBCCE4")

        // Standard BLE descriptor for enabling notifications
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Abbott manufacturer ID for scan filtering
        private const val ABBOTT_MANUFACTURER_ID = 0x0007 // 7 = Abbott
    }

    private val handler = Handler(
        HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper
    )

    private val bluetoothManager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var callback: LibreBleCallback? = null

    private var isScanning = false
    private var isConnected = false
    private var isConnecting = false

    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private var targetSensorType: LibreSensorType = LibreSensorType.UNKNOWN
    private var targetDeviceAddress: String? = null

    // Pending write queue
    private val writeQueue = mutableListOf<ByteArray>()
    private var isWriting = false
    private val writeLock = Any()

    /**
     * Set the callback for BLE events
     */
    fun setCallback(callback: LibreBleCallback?) {
        this.callback = callback
    }

    /**
     * Check if Bluetooth is enabled
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Check if we have required Bluetooth permissions
     */
    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Start scanning for Libre sensors
     * @param sensorType Type of sensor to scan for (LIBRE_2, LIBRE_3, or UNKNOWN for any)
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startScan(sensorType: LibreSensorType = LibreSensorType.UNKNOWN): Boolean {
        if (!hasPermissions()) {
            aapsLogger.error(LTag.BGSOURCE, "Missing Bluetooth permissions")
            callback?.onError(LibreBleError.PERMISSION_DENIED, "Missing Bluetooth permissions")
            return false
        }

        if (!isBluetoothEnabled()) {
            aapsLogger.error(LTag.BGSOURCE, "Bluetooth is not enabled")
            callback?.onError(LibreBleError.BLUETOOTH_DISABLED, "Bluetooth is not enabled")
            return false
        }

        if (isScanning) {
            aapsLogger.debug(LTag.BGSOURCE, "Already scanning")
            return true
        }

        targetSensorType = sensorType
        aapsLogger.debug(LTag.BGSOURCE, "Starting scan for Libre sensors (type: $sensorType)")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = buildScanFilters(sensorType)

        try {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
            isScanning = true

            // Set scan timeout
            handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)

            return true
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Failed to start scan", e)
            callback?.onError(LibreBleError.SCAN_FAILED, "Failed to start scan: ${e.message}")
            return false
        }
    }

    private fun buildScanFilters(sensorType: LibreSensorType): List<ScanFilter> {
        val filters = mutableListOf<ScanFilter>()

        when (sensorType) {
            LibreSensorType.LIBRE_2 -> {
                filters.add(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(LIBRE2_SERVICE_UUID))
                        .build()
                )
            }
            LibreSensorType.LIBRE_3 -> {
                filters.add(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(LIBRE3_SERVICE_UUID))
                        .build()
                )
            }
            else -> {
                // Scan for both types
                filters.add(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(LIBRE2_SERVICE_UUID))
                        .build()
                )
                filters.add(
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(LIBRE3_SERVICE_UUID))
                        .build()
                )
            }
        }

        return filters
    }

    /**
     * Stop scanning
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopScan() {
        if (!isScanning) return

        aapsLogger.debug(LTag.BGSOURCE, "Stopping scan")
        handler.removeCallbacks(scanTimeoutRunnable)

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Error stopping scan", e)
        }
        isScanning = false
    }

    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            aapsLogger.warn(LTag.BGSOURCE, "Scan timeout reached")
            stopScan()
            callback?.onError(LibreBleError.SENSOR_NOT_FOUND, "No Libre sensor found")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"

            aapsLogger.debug(LTag.BGSOURCE, "Scan result: $name (${device.address})")

            // Determine sensor type from advertised services
            val sensorType = determineSensorType(result)
            if (sensorType != LibreSensorType.UNKNOWN) {
                aapsLogger.info(LTag.BGSOURCE, "Found Libre sensor: $name ($sensorType)")
                stopScan()
                connectToDevice(device, sensorType)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            aapsLogger.error(LTag.BGSOURCE, "Scan failed with error code: $errorCode")
            isScanning = false
            callback?.onError(LibreBleError.SCAN_FAILED, "Scan failed with error: $errorCode")
        }
    }

    private fun determineSensorType(result: ScanResult): LibreSensorType {
        val serviceUuids = result.scanRecord?.serviceUuids ?: return LibreSensorType.UNKNOWN

        for (uuid in serviceUuids) {
            when (uuid.uuid) {
                LIBRE2_SERVICE_UUID -> return LibreSensorType.LIBRE_2
                LIBRE3_SERVICE_UUID -> return LibreSensorType.LIBRE_3
            }
        }
        return LibreSensorType.UNKNOWN
    }

    /**
     * Connect to a specific device by address
     */
    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String, sensorType: LibreSensorType): Boolean {
        if (!hasPermissions()) {
            callback?.onError(LibreBleError.PERMISSION_DENIED, "Missing Bluetooth permissions")
            return false
        }

        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            callback?.onError(LibreBleError.CONNECTION_FAILED, "Device not found: $address")
            return false
        }

        return connectToDevice(device, sensorType)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice, sensorType: LibreSensorType): Boolean {
        if (isConnecting || isConnected) {
            aapsLogger.debug(LTag.BGSOURCE, "Already connecting or connected")
            return false
        }

        aapsLogger.debug(LTag.BGSOURCE, "Connecting to ${device.address}")
        isConnecting = true
        targetDeviceAddress = device.address
        targetSensorType = sensorType

        // Set connection timeout
        handler.postDelayed(connectionTimeoutRunnable, CONNECTION_TIMEOUT_MS)

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        return bluetoothGatt != null
    }

    private val connectionTimeoutRunnable = Runnable {
        if (isConnecting && !isConnected) {
            aapsLogger.warn(LTag.BGSOURCE, "Connection timeout")
            disconnect("Connection timeout")
            callback?.onError(LibreBleError.TIMEOUT, "Connection timeout")
        }
    }

    /**
     * Disconnect from the sensor
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun disconnect(reason: String = "User requested") {
        aapsLogger.debug(LTag.BGSOURCE, "Disconnecting: $reason")

        handler.removeCallbacks(connectionTimeoutRunnable)
        handler.removeCallbacks(scanTimeoutRunnable)

        isConnecting = false
        isConnected = false
        isWriting = false
        writeQueue.clear()
        writeCharacteristic = null
        notifyCharacteristic = null

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "Error during disconnect", e)
        }
        bluetoothGatt = null

        callback?.onDisconnected(reason)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.removeCallbacks(connectionTimeoutRunnable)

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    aapsLogger.debug(LTag.BGSOURCE, "GATT connected")
                    isConnecting = false
                    isConnected = true
                    // Discover services
                    handler.postDelayed({
                        gatt.discoverServices()
                    }, 500) // Small delay for connection stability
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    aapsLogger.debug(LTag.BGSOURCE, "GATT disconnected (status: $status)")
                    val wasConnected = isConnected
                    isConnecting = false
                    isConnected = false
                    bluetoothGatt = null

                    if (wasConnected) {
                        callback?.onDisconnected("Connection lost (status: $status)")
                    } else {
                        callback?.onError(LibreBleError.CONNECTION_FAILED, "Failed to connect (status: $status)")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.error(LTag.BGSOURCE, "Service discovery failed: $status")
                disconnect("Service discovery failed")
                return
            }

            aapsLogger.debug(LTag.BGSOURCE, "Services discovered")

            // Find the appropriate service and characteristics based on sensor type
            val service = when (targetSensorType) {
                LibreSensorType.LIBRE_2 -> gatt.getService(LIBRE2_SERVICE_UUID)
                LibreSensorType.LIBRE_3 -> gatt.getService(LIBRE3_SERVICE_UUID)
                else -> gatt.getService(LIBRE3_SERVICE_UUID) ?: gatt.getService(LIBRE2_SERVICE_UUID)
            }

            if (service == null) {
                aapsLogger.error(LTag.BGSOURCE, "Libre service not found")
                disconnect("Service not found")
                callback?.onError(LibreBleError.GATT_ERROR, "Libre service not found")
                return
            }

            // Get write and notify characteristics
            when (targetSensorType) {
                LibreSensorType.LIBRE_2 -> {
                    writeCharacteristic = service.getCharacteristic(LIBRE2_WRITE_UUID)
                    notifyCharacteristic = service.getCharacteristic(LIBRE2_NOTIFY_UUID)
                }
                LibreSensorType.LIBRE_3 -> {
                    writeCharacteristic = service.getCharacteristic(LIBRE3_WRITE_UUID)
                    notifyCharacteristic = service.getCharacteristic(LIBRE3_NOTIFY_UUID)
                }
                else -> {
                    // Try Libre 3 first, fall back to Libre 2
                    writeCharacteristic = service.getCharacteristic(LIBRE3_WRITE_UUID)
                        ?: service.getCharacteristic(LIBRE2_WRITE_UUID)
                    notifyCharacteristic = service.getCharacteristic(LIBRE3_NOTIFY_UUID)
                        ?: service.getCharacteristic(LIBRE2_NOTIFY_UUID)
                }
            }

            if (notifyCharacteristic == null) {
                aapsLogger.error(LTag.BGSOURCE, "Notify characteristic not found")
                disconnect("Characteristic not found")
                callback?.onError(LibreBleError.GATT_ERROR, "Required characteristics not found")
                return
            }

            // Enable notifications
            enableNotifications(gatt, notifyCharacteristic!!)
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                aapsLogger.warn(LTag.BGSOURCE, "CCCD descriptor not found")
                // Still notify connected
                callback?.onConnected()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.debug(LTag.BGSOURCE, "Notifications enabled")
                callback?.onConnected()
            } else {
                aapsLogger.error(LTag.BGSOURCE, "Failed to enable notifications: $status")
                callback?.onError(LibreBleError.GATT_ERROR, "Failed to enable notifications")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (data != null && data.isNotEmpty()) {
                aapsLogger.debug(LTag.BGSOURCE, "Received notification: ${data.size} bytes")
                handleReceivedData(data)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            synchronized(writeLock) {
                isWriting = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    aapsLogger.debug(LTag.BGSOURCE, "Write successful")
                    processWriteQueue()
                } else {
                    aapsLogger.error(LTag.BGSOURCE, "Write failed: $status")
                }
            }
        }
    }

    private fun handleReceivedData(data: ByteArray) {
        // Data will be processed by the protocol handler
        // This is a placeholder - actual implementation depends on protocol
        aapsLogger.debug(LTag.BGSOURCE, "Received ${data.size} bytes from sensor")
        // TODO: Pass to protocol handler for parsing
    }

    /**
     * Write data to the sensor
     */
    @SuppressLint("MissingPermission")
    fun write(data: ByteArray): Boolean {
        if (!isConnected || writeCharacteristic == null) {
            aapsLogger.error(LTag.BGSOURCE, "Cannot write: not connected")
            return false
        }

        synchronized(writeLock) {
            writeQueue.add(data)
            if (!isWriting) {
                processWriteQueue()
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun processWriteQueue() {
        synchronized(writeLock) {
            if (writeQueue.isEmpty() || isWriting) return

            val data = writeQueue.removeAt(0)
            isWriting = true

            handler.postDelayed({
                writeCharacteristic?.let { char ->
                    char.value = data
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    bluetoothGatt?.writeCharacteristic(char)
                }
            }, WRITE_DELAY_MILLIS)
        }
    }

    /**
     * Check if currently connected
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Get the address of the connected device
     */
    fun getConnectedAddress(): String? = if (isConnected) targetDeviceAddress else null

    /**
     * Get the type of connected sensor
     */
    fun getConnectedSensorType(): LibreSensorType = if (isConnected) targetSensorType else LibreSensorType.UNKNOWN
}
