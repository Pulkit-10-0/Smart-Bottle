package com.example.smartbottle

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import com.example.smartbottle.BleManager


class TemperatureViewModel(
    private val bleManager: BleManager
) : ViewModel() {
    val connectionState: StateFlow<BleConnectionState> get() = bleManager.connectionState
    val temperatureReadings: StateFlow<List<TemperatureReading>> get() = bleManager.temperatureReadings
    val currentReading: StateFlow<TemperatureReading?> get() = bleManager.currentReading
    val foundDevices: StateFlow<List<android.bluetooth.BluetoothDevice>> get() = bleManager.foundDevices

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() = bleManager.startScan()
    fun disconnect() = bleManager.disconnect()
    fun clearReadings() = bleManager.clearReadings()
    fun connectToDevice(device: android.bluetooth.BluetoothDevice) = bleManager.connectToDevice(device)
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun safeScan() = bleManager.safeStartScan()

}
