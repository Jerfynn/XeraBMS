@file:OptIn(ExperimentalMaterial3Api::class)

package com.xera.bms.ui.scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xera.bms.ble.ScannedDevice
import com.xera.bms.core.ProtocolRegistry
import com.xera.bms.ui.MainViewModel
import com.xera.bms.ui.components.EmptyState
import com.xera.bms.ui.components.SectionCard

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasPermissions(context: Context) = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun locationOff(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 31) return false
    val lm = context.getSystemService(LocationManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= 28) !lm.isLocationEnabled else false
}

@Composable
fun ScanScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val devices by vm.scanner.devices.collectAsStateWithLifecycle()
    val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
    val scanError by vm.scanner.error.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var permitted by remember { mutableStateOf(hasPermissions(context)) }
    var asked by remember { mutableStateOf(false) }
    var bluetoothOn by remember { mutableStateOf(vm.scanner.isBluetoothOn) }
    var locationDisabled by remember { mutableStateOf(locationOff(context)) }
    var chosen by remember { mutableStateOf<ScannedDevice?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        permitted = hasPermissions(context)
        bluetoothOn = vm.scanner.isBluetoothOn
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothOn = vm.scanner.isBluetoothOn
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permitted = hasPermissions(context)
        bluetoothOn = vm.scanner.isBluetoothOn
        locationDisabled = locationOff(context)
    }
    LaunchedEffect(permitted, bluetoothOn) {
        if (permitted && bluetoothOn) vm.scanner.start()
    }
    DisposableEffect(Unit) { onDispose { vm.scanner.stop() } }

    val shown = if (settings.bmsOnlyFilter) devices.filter { it.protocolHint != null } else devices

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("XeraBMS", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Find your battery",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!permitted) {
                item {
                    SectionCard(title = "Bluetooth access needed") {
                        Text(
                            "XeraBMS talks to your battery over Bluetooth. Allow access so it can find batteries nearby.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val perms = requiredPermissions().toMutableList()
                                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                                permissionLauncher.launch(perms.toTypedArray())
                            }) { Text("Allow Bluetooth access") }
                            if (asked) {
                                OutlinedButton(onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                    )
                                }) { Text("Open app settings") }
                            }
                        }
                    }
                }
            } else if (!bluetoothOn) {
                item {
                    SectionCard(title = "Bluetooth is off") {
                        Text("Turn on Bluetooth to scan for batteries.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            try {
                                enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            } catch (_: SecurityException) {
                            }
                        }) { Text("Turn on Bluetooth") }
                    }
                }
            }
            if (permitted && locationDisabled) {
                item {
                    SectionCard(title = "Location is off") {
                        Text(
                            "Android 11 and older only show Bluetooth devices while Location is on.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                            Text("Open location settings")
                        }
                    }
                }
            }

            val lastAddress = settings.lastAddress
            if (permitted && bluetoothOn && lastAddress != null) {
                item {
                    SectionCard(color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("Last battery", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            settings.lastName ?: lastAddress,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Button(onClick = vm::reconnectLast) { Text("Reconnect") }
                    }
                }
            }

            item {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            enabled = permitted && bluetoothOn,
                            onClick = { if (scanning) vm.scanner.stop() else vm.scanner.start() },
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (scanning) "Stop" else "Scan")
                        }
                        FilterChip(
                            selected = settings.bmsOnlyFilter,
                            onClick = { vm.updateSettings { it.copy(bmsOnlyFilter = !it.bmsOnlyFilter) } },
                            label = { Text("Batteries only") },
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = vm::startDemo) {
                            Icon(Icons.Filled.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Demo")
                        }
                    }
                    if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    scanError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Bluetooth,
                        title = if (scanning) "Looking for batteries…" else "No batteries found yet",
                        body = if (scanning) {
                            "Keep the phone within a few metres of the battery."
                        } else {
                            "Tap Scan. If your battery doesn't appear, wake its BMS by charging or applying a small load, " +
                                "and close any other BMS app – most BMS boards accept only one connection."
                        },
                    )
                }
            } else {
                items(shown, key = { it.address }) { device ->
                    DeviceRow(device, onClick = { chosen = device })
                }
            }
        }
    }

    chosen?.let { device ->
        ConnectDialog(
            device = device,
            initial = if (device.address == settings.lastAddress) settings.lastProtocol else null,
            onDismiss = { chosen = null },
            onConnect = { protocolId ->
                chosen = null
                vm.connect(device, protocolId)
            },
        )
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    device.name ?: "Unnamed device",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(device.address, device.protocolHint).joinToString("   "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.SignalCellularAlt,
                    contentDescription = null,
                    tint = when {
                        device.rssi > -65 -> MaterialTheme.colorScheme.tertiary
                        device.rssi > -82 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                Text("${device.rssi} dBm", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ConnectDialog(
    device: ScannedDevice,
    initial: String?,
    onDismiss: () -> Unit,
    onConnect: (String?) -> Unit,
) {
    val options: List<Pair<String?, String>> =
        listOf<Pair<String?, String>>(null to "Detect automatically (recommended)") +
            ProtocolRegistry.entries.map { it.id to it.label } +
            (ProtocolRegistry.EXPLORER_ID to "Unknown BMS – raw data explorer")
    var selected by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.name ?: device.address) },
        text = {
            Column {
                Text(
                    "Choose the BMS type, or let XeraBMS try each supported protocol.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { (id, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == id, role = Role.RadioButton, onClick = { selected = id })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConnect(selected) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
