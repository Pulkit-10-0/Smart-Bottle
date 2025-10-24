package com.example.smartbottle

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.bluetooth.BluetoothDevice
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                AppScaffold(bleManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanupReceivers()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun AppScaffold(bleManager: BleManager) {
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

    // Auto-reconnect logic
    LaunchedEffect(hasPermissions) {
        if (hasPermissions && connectionState is BleConnectionState.Disconnected) {
            bleManager.restoreLastConnectedDevice()?.let { device ->
                bleManager.connectToDevice(device)
            }
        }
    }


    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.WaterDrop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Smart Bottle", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { /* Info */ }) {
                        Icon(Icons.Default.Info, contentDescription = "Info")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    is BleConnectionState.Scanning ->
                        DeviceSelectionList(foundDevices) { viewModel.connectToDevice(it) }

                    is BleConnectionState.Connected -> {
                        CollapsingContent(
                            currentReading = currentReading,
                            readings = temperatureReadings
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
fun CollapsingContent(
    currentReading: TemperatureReading?,
    readings: List<TemperatureReading>
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CurrentReadingsGrid(currentReading)
        }

        stickyHeader {
            // recent readings
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Recent Readings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (readings.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "${readings.size}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (readings.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Inbox,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No readings yet", style = MaterialTheme.typography.bodyLarge)
                        Text("Connect to Smart Bottle to see data", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            items(readings) { reading ->
                TemperatureReadingItem(reading)
            }
        }
    }
}
// PERMISSION REQUEST CARD
@Composable
fun PermissionRequestCard(onRequestPermissions: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "Bluetooth Permissions Required",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "This app needs Bluetooth permissions to connect to your Smart Bottle device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "Location permission is required by Android for Bluetooth scanning. Your location is never tracked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Grant Permissions")
            }
        }
    }
}

// CONNECTION CONTROL PANEL
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
                    Triple("Disconnected", Color(0xFF9E9E9E), Icons.Default.BluetoothDisabled)
                is BleConnectionState.Scanning ->
                    Triple("Scanning for devices...", Color(0xFFFFA726), Icons.Default.BluetoothSearching)
                is BleConnectionState.Connected ->
                    Triple("Connected", Color(0xFF66BB6A), Icons.Default.BluetoothConnected)
                is BleConnectionState.Error ->
                    Triple("Error: ${connectionState.message}", Color(0xFFEF5350), Icons.Default.Error)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(28.dp)
                )
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
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Scan Devices")
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
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Disconnect")
                        }
                        OutlinedButton(
                            onClick = onClear,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Clear")
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

// DEVICE SELECTION LIST
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
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        if (devices.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "No devices found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Make sure your device is powered on",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    val name = device.name ?: "Unknown Device"
                    val address = device.address
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(device) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Devices,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = name,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

//CURRENT READINGS GRID
@Composable
fun CurrentReadingsGrid(currentReading: TemperatureReading?) {
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current Readings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (currentReading != null) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF66BB6A),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (currentReading != null) {
                // 2x2 Grid
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ParameterCard(
                            icon = Icons.Default.Thermostat,
                            label = "Temperature",
                            value = "${currentReading.temperature}",
                            unit = "°C",
                            color = Color(0xFFEF5350),
                            modifier = Modifier.weight(1f)
                        )
                        ParameterCard(
                            icon = Icons.Default.LightMode,
                            label = "UV Cycle",
                            value = if (currentReading.uvCycle == 1) "ON" else "OFF",
                            unit = "",
                            color = Color(0xFF9C27B0),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ParameterCard(
                            icon = Icons.Default.BatteryChargingFull,
                            label = "Battery",
                            value = "${currentReading.battery}",
                            unit = "V",
                            color = Color(0xFF66BB6A),
                            modifier = Modifier.weight(1f)
                        )
                        ParameterCard(
                            icon = Icons.Default.WaterDrop,
                            label = "Flow Rate",
                            value = "${currentReading.flow}",
                            unit = "L/m",
                            color = Color(0xFF42A5F5),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Timestamp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = currentReading.formattedTimestamp,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            } else {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Waiting for data...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// PARAMETER CARD
@Composable
fun ParameterCard(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(110.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = value,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }
    }
}

//TEMPERATURE READING ITEM
@Composable
fun TemperatureReadingItem(reading: TemperatureReading) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Timestamp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = reading.formattedTimestamp,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }

            // Parameters grid
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CompactParameterChip(
                        icon = Icons.Default.Thermostat,
                        value = "${reading.temperature}°C",
                        color = Color(0xFFEF5350),
                        modifier = Modifier.weight(1f)
                    )
                    CompactParameterChip(
                        icon = Icons.Default.LightMode,
                        value = if (reading.uvCycle == 1) "UV ON" else "UV OFF",
                        color = Color(0xFF9C27B0),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CompactParameterChip(
                        icon = Icons.Default.BatteryChargingFull,
                        value = "${reading.battery}V",
                        color = Color(0xFF66BB6A),
                        modifier = Modifier.weight(1f)
                    )
                    CompactParameterChip(
                        icon = Icons.Default.WaterDrop,
                        value = "${reading.flow} L/m",
                        color = Color(0xFF42A5F5),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// COMPACT PARAMETER CHIP (List Item)
@Composable
fun CompactParameterChip(
    icon: ImageVector,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}


@Preview(showBackground = true)
@Composable
fun PreviewCurrentReadingsGrid() {
    SmartBottleTheme {
        CurrentReadingsGrid(
            currentReading = TemperatureReading(
                temperature = 27.5,
                uvCycle = 1,
                battery = 3.92,
                flow = 2.3,
                timestampEpoch = 1700000000L,
                formattedTimestamp = "2025-10-21 15:03:00"
            )
        )
    }
}
