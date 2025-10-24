package com.example.smartbottle

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.*
import com.example.smartbottle.util.DateTimeUtils
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BleManager(private val context: Context) {

    // Auto-reconnect state
    private var reconnectAttempts = 0
    private val maxReconnects = 6
    private var lastConnectedDevice: BluetoothDevice? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    // Bluetooth has just turned ON, run auto-reconnect
                    Log.d(TAG, "Bluetooth turned ON, try auto-reconnect!")
                    triggerAutoReconnect()
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(bluetoothStateReceiver, filter)
    }

    fun cleanupReceivers() {
        context.unregisterReceiver(bluetoothStateReceiver)
    }


    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun safeStartScan() {
        stopScan()
        Handler(Looper.getMainLooper()).postDelayed({
            startScan()
        }, 750)
    }

    private val bleScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null

    // UUIDs matching ESP32 code
    private val SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val CHARACTERISTIC_UUID = UUID.fromString("abcd1234-5678-1234-5678-123456789abc")
    private val TIME_SYNC_UUID = UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00")

    private val AUTH_KEY_UUID = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210")

    private val privateKey = "mysecretkey123"



    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _temperatureReadings = MutableStateFlow<List<TemperatureReading>>(emptyList())
    val temperatureReadings: StateFlow<List<TemperatureReading>> = _temperatureReadings.asStateFlow()

    private val _currentReading = MutableStateFlow<TemperatureReading?>(null)
    val currentReading: StateFlow<TemperatureReading?> = _currentReading.asStateFlow()

    private val _foundDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val foundDevices: StateFlow<List<BluetoothDevice>> = _foundDevices.asStateFlow()

    private val deviceSet = mutableSetOf<String>()

    // BLE scan callback
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasRequiredPermissions()) return
            val device = result.device
            val deviceId = device.address
            if (device.name != null && !deviceSet.contains(deviceId)) {
                deviceSet.add(deviceId)
                val updatedList = _foundDevices.value.toMutableList()
                updatedList.add(device)
                _foundDevices.value = updatedList
                Log.d(TAG, "Found device: ${device.name} (${device.address})")
            }

            // If auto-reconnect triggered, attempt connect if matches last known address
            lastConnectedDevice?.let {
                if (device.address == it.address && _connectionState.value == BleConnectionState.Scanning) {
                    stopScan()
                    Handler(Looper.getMainLooper()).postDelayed({
                        connectToDevice(device)
                    }, 500) // let scan clear up before connecting
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _connectionState.value = BleConnectionState.Error("Scan failed: $errorCode")
        }
    }

    // GATT callback
    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server.")
                    reconnectAttempts = 0
                    lastConnectedDevice = gatt.device
                    _connectionState.value = BleConnectionState.Connected

                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server.")
                    _connectionState.value = BleConnectionState.Disconnected
                    cleanup()
                    triggerAutoReconnect()
                }
            }
        }
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == AUTH_KEY_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Authorization key written successfully")
                    // Now enable notifications for sensor data
                    val service = gatt.getService(SERVICE_UUID)
                    val notifyChar = service?.getCharacteristic(CHARACTERISTIC_UUID)
                    if (notifyChar != null) {
                        enableNotifications(gatt, notifyChar)
                    } else {
                        Log.e(TAG, "Sensor data characteristic not found after auth")
                    }
                } else {
                    Log.e(TAG, "Failed to write authorization key")
                    // Optionally disconnect or show error to user
                }
            }
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun sendAuthKeyToDevice(gatt: BluetoothGatt) {
            val service = gatt.getService(SERVICE_UUID)
            val authChar = service?.getCharacteristic(AUTH_KEY_UUID)
            if (authChar == null) {
                Log.e(ContentValues.TAG, "Auth characteristic not found")
                return
            }
            authChar.value = privateKey.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(authChar, authChar.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(authChar)
            }
            Log.d(ContentValues.TAG, "Sent auth key: $privateKey")
        }


        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu bytes")
            } else {
                Log.e(TAG, "MTU change failed with status $status, using default")
            }

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    gatt.discoverServices()
                    Log.d(TAG, "Starting service discovery...")
                } catch (e: SecurityException) {
                    Log.e(TAG, "Bluetooth permission issue", e)
                }
            }, 500)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered.")
                sendAuthKeyToDevice(gatt)
                // Do NOT enable notifications here anymore!
            } else {
                Log.e(TAG, "Service discovery failed with status $status.")
            }
        }
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun syncTimeToDevice(gatt: BluetoothGatt) {
            val service = gatt.getService(SERVICE_UUID)
            if (service != null) {
                val timeCharacteristic = service.getCharacteristic(TIME_SYNC_UUID)
                if (timeCharacteristic != null) {
                    val currentTimeSeconds = System.currentTimeMillis() / 1000
                    val timeBytes = currentTimeSeconds.toString().toByteArray(Charsets.UTF_8)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(timeCharacteristic, timeBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        timeCharacteristic.value = timeBytes
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(timeCharacteristic)
                    }
                    Log.d(TAG, "Time synced to device: $currentTimeSeconds")
                } else {
                    Log.e(TAG, "Time sync characteristic not found")
                }
            }
        }
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            try {
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                    Log.d(TAG, "Notifications successfully enabled.")

                    Handler(Looper.getMainLooper()).postDelayed({
                        syncTimeToDevice(gatt)
                    }, 500)
                } else {
                    Log.e(TAG, "Descriptor not found for notification setup.")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission issue enabling notifications.", e)
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "onCharacteristicChanged called. Raw value: ${value.contentToString()}")
            handleIncomingData(value)
        }

        @RequiresApi(Build.VERSION_CODES.O)
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                Log.d(TAG, "onCharacteristicChanged (legacy) called. Raw: ${value?.contentToString()}")
                if (value != null) handleIncomingData(value)
            }
        }

        private val buffer = StringBuilder()
        @RequiresApi(Build.VERSION_CODES.O)
        private fun handleIncomingData(value: ByteArray) {
            val chunk = String(value, Charsets.UTF_8)
            Log.d(TAG, "Chunk received: $chunk")
            buffer.append(chunk)

            var newlineIndex = buffer.indexOf("\n")
            while (newlineIndex != -1) {
                val jsonStr = buffer.substring(0, newlineIndex).trim()
                buffer.delete(0, newlineIndex + 1)
                Log.d(TAG, "Assembled JSON message: $jsonStr")

                try {
                    val jsonObject = JSONObject(jsonStr)
                    val temperature = jsonObject.getDouble("t")
                    val timestampStr = jsonObject.getString("ts")

                    val epochSeconds = DateTimeUtils.parseToEpochSeconds(timestampStr)
                    val formattedTimestamp = DateTimeUtils.epochToDisplay(epochSeconds)

                    val reading = TemperatureReading(temperature, epochSeconds, formattedTimestamp)
                    _currentReading.value = reading

                    val currentList = _temperatureReadings.value.toMutableList()
                    currentList.add(0, reading)
                    if (currentList.size > 100) {
                        currentList.removeAt(currentList.size - 1)
                    }
                    _temperatureReadings.value = currentList

                    Log.d(TAG, "Successfully parsed: $reading")
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing JSON: ${e.message}")
                }
                newlineIndex = buffer.indexOf("\n")
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        if (!hasRequiredPermissions()) {
            _connectionState.value = BleConnectionState.Error("Missing Bluetooth permissions")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            _connectionState.value = BleConnectionState.Error("Bluetooth disabled")
            return
        }

        _connectionState.value = BleConnectionState.Scanning
        deviceSet.clear()
        _foundDevices.value = emptyList()

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(null, settings, scanCallback)
        Log.d(TAG, "Started BLE scan...")

        Handler(Looper.getMainLooper()).postDelayed({
            if (_connectionState.value is BleConnectionState.Scanning) {
                stopScan()
                if (_foundDevices.value.isEmpty()) {
                    _connectionState.value = BleConnectionState.Error("No devices found. Check Smart Bottle power.")
                    Log.e(TAG, "Scan timeout - no devices found.")
                }
            }
        }, 15000)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        try {
            bleScanner?.stopScan(scanCallback)
            Log.d(TAG, "Stopped BLE scan.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scan: ${e.message}")
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        lastConnectedDevice = device
        saveLastConnectedDeviceAddress(device.address)  // Save persistently
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
            Log.d(TAG, "Connecting to ${device.name ?: "Unknown"} (${device.address})...")
        } catch (e: SecurityException) {
            Log.e(TAG, "Connect permission issue", e)
        }
    }

    fun restoreLastConnectedDevice(): BluetoothDevice? {
        val prefs = context.getSharedPreferences("smartbottle_prefs", Context.MODE_PRIVATE)
        val address = prefs.getString("last_connected_device", null) ?: return null

        val adapter = bluetoothAdapter ?: return null
        return adapter.getRemoteDevice(address)
    }

    private fun saveLastConnectedDeviceAddress(address: String) {
        val prefs = context.getSharedPreferences("smartbottle_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_connected_device", address).apply()
    }




    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            cleanup()
            _connectionState.value = BleConnectionState.Disconnected
            Log.d(TAG, "Disconnected from GATT.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Error disconnecting", e)
        }
        reconnectAttempts = 0
    }

    private fun cleanup() {
        bluetoothGatt = null
    }

    fun clearReadings() {
        _temperatureReadings.value = emptyList()
        _currentReading.value = null
        Log.d(TAG, "Cleared all temperature readings")
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerAutoReconnect() {
        val last = lastConnectedDevice
        if (last != null && reconnectAttempts < maxReconnects) {
            reconnectAttempts++
            Log.d(TAG, "Trying auto-reconnect (attempt $reconnectAttempts of $maxReconnects)...")
            Handler(Looper.getMainLooper()).postDelayed({
                // Start a scan to find our device; scanCallback auto-connects on match
                safeStartScan()
            }, 2000L * reconnectAttempts) // backoff delay
        } else if (reconnectAttempts >= maxReconnects) {
            Log.e(TAG, "Auto-reconnect failed after $maxReconnects attempts.")
        }
    }

    companion object {
        private const val TAG = "BleManager"
    }
}
