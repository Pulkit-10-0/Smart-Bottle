// MainActivity.kt
package com.example.smartbottle

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.bluetooth.BluetoothDevice
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartbottle.ui.theme.SmartBottleTheme

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleManager
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BleManager(this)
        setContent {
            SmartBottleTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Smart Bottle", fontWeight = FontWeight.Bold) },
                            actions = {
                                IconButton(onClick = {}) {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                }
                            }
                        )
                    }
                ) { padding ->
                    AppContent(bleManager = bleManager, modifier = Modifier.padding(padding))
                }
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanupReceivers()
    }

}



@SuppressLint("MissingPermission")
@Composable
fun AppContent(bleManager: BleManager, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: TemperatureViewModel = viewModel { TemperatureViewModel(bleManager) }

    val connectionState    by viewModel.connectionState.collectAsState()
    val currentReading     by viewModel.currentReading.collectAsState()
    val temperatureReadings by viewModel.temperatureReadings.collectAsState()
    val foundDevices       by viewModel.foundDevices.collectAsState()

    var hasPermissions by remember { mutableStateOf(PermissionUtils.hasAllPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms -> hasPermissions = perms.all { it.value } }

    // Auto reconnect on app start if possible
    LaunchedEffect(hasPermissions) {
        if (hasPermissions && connectionState is BleConnectionState.Disconnected) {
            val deviceToReconnect = bleManager.restoreLastConnectedDevice()
            if (deviceToReconnect != null) {
                bleManager.connectToDevice(deviceToReconnect)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!hasPermissions) {
            PermissionRequestCard { permissionLauncher.launch(PermissionUtils.getRequiredPermissions()) }
        } else {
            ConnectionControlPanel(
                connectionState,
                onScan = {
                    if (!PermissionUtils.hasAllPermissions(context)) {
                        permissionLauncher.launch(PermissionUtils.getRequiredPermissions())
                    } else viewModel.safeScan()
                },
                onDisconnect = { viewModel.disconnect() },
                onClear      = { viewModel.clearReadings() }
            )
            Spacer(Modifier.height(16.dp))
            when (connectionState) {
                is BleConnectionState.Scanning  ->
                    DeviceSelectionList(foundDevices) { viewModel.connectToDevice(it) }
                is BleConnectionState.Connected ->
                    CurrentTemperatureCard(currentReading).also {
                        Spacer(Modifier.height(16.dp))
                        TemperatureReadingsList(temperatureReadings)
                    }
                else -> Unit
            }
        }
    }
}

@Composable
fun PermissionRequestCard(onRequestPermissions: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("", fontSize = 48.sp)
            Text("Bluetooth Permissions Required", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "This app needs Bluetooth permissions to connect to your Smart Bottle device.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ℹ️", fontSize = 20.sp)
                        Text(
                            "Location permission is required by Android for Bluetooth scanning. Your location is never tracked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            Button(onClick = onRequestPermissions, Modifier.fillMaxWidth()) {
                Text("Grant Permissions")
            }
        }
    }
}





@Composable
fun ConnectionControlPanel(
    connectionState: BleConnectionState,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val (statusText, statusColor, statusIcon) = when (connectionState) {
                is BleConnectionState.Disconnected ->
                    Triple("Disconnected", Color.Gray, "⚫")
                is BleConnectionState.Scanning ->
                    Triple("Scanning...", Color(0xFFFFA726), "")
                is BleConnectionState.Connected ->
                    Triple("Connected", Color(0xFF66BB6A), "🔵")
                is BleConnectionState.Error ->
                    Triple("Error: ${connectionState.message}", Color(0xFFEF5350), "🔴")
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = statusIcon, fontSize = 20.sp)
                Text(
                    text = statusText,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (connectionState) {
                    is BleConnectionState.Disconnected, is BleConnectionState.Error -> {
                        Button(
                            onClick = onScan,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Scan for Devices")
                        }
                    }
                    is BleConnectionState.Connected -> {
                        Button(
                            onClick = onDisconnect,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF5350)
                            )
                        ) {
                            Text("Disconnect")
                        }
                        OutlinedButton(
                            onClick = onClear,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear Data")
                        }
                    }
                    is BleConnectionState.Scanning -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceSelectionList(
    devices: List<BluetoothDevice>,
    onDeviceSelected: (BluetoothDevice) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Nearby Bluetooth Devices",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        if (devices.isEmpty()) {
            Text(
                text = "No devices found. Make sure your device is powered on.",
                color = Color.Gray,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn {
                items(devices) { device ->
                    val name = device.name ?: "Unknown"
                    val address = device.address
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onDeviceSelected(device) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = name, fontWeight = FontWeight.Bold)
                            Text(text = address, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CurrentTemperatureCard(currentReading: TemperatureReading?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Current Temperature",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            if (currentReading != null) {
                Text(
                    text = "${currentReading.temperature}°C",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "${currentReading.formattedTimestamp}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            } else {
                Text(
                    text = "--°C",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f)
                )
                Text(
                    text = "Waiting for data...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun TemperatureReadingsList(readings: List<TemperatureReading>) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recent Readings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (readings.isNotEmpty()) {
                Text(
                    text = "${readings.size} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }

        if (readings.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No readings yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray
                    )
                    Text(
                        text = "Connect to Smart Bottle to see data",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(readings) { reading ->
                    TemperatureReadingItem(reading)
                }
            }
        }
    }
}

@Composable
fun TemperatureReadingItem(reading: TemperatureReading) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Temp:", fontSize = 24.sp)
                Text(
                    text = "${reading.temperature}°C",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = reading.formattedTimestamp,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        }
    }
}




@Preview(showBackground = true)
@Composable
fun PreviewPermissionRequestCard() {
    SmartBottleTheme {
        PermissionRequestCard(onRequestPermissions = {})
    }
}


@Preview(showBackground = true)
@Composable
fun PreviewConnectionControlPanelDisconnected() {
    SmartBottleTheme {
        ConnectionControlPanel(
            connectionState = BleConnectionState.Disconnected,
            onScan = {},
            onDisconnect = {},
            onClear = {}
        )
    }
}


@Preview(showBackground = true)
@Composable
fun PreviewConnectionControlPanelScanning() {
    SmartBottleTheme {
        ConnectionControlPanel(
            connectionState = BleConnectionState.Scanning,
            onScan = {},
            onDisconnect = {},
            onClear = {}
        )
    }
}


@Preview(showBackground = true)
@Composable
fun PreviewConnectionControlPanelConnected() {
    SmartBottleTheme {
        ConnectionControlPanel(
            connectionState = BleConnectionState.Connected,
            onScan = {},
            onDisconnect = {},
            onClear = {}
        )
    }
}
