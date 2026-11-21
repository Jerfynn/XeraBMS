@file:OptIn(ExperimentalMaterial3Api::class)

package com.xera.bms.ui.more

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xera.bms.BuildConfig
import com.xera.bms.core.ProtocolRegistry
import com.xera.bms.data.SessionStatus
import com.xera.bms.ui.MainViewModel
import com.xera.bms.ui.MoreDest
import com.xera.bms.ui.components.EmptyState
import com.xera.bms.ui.components.SectionCard
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------- More

@Composable
fun MoreScreen(vm: MainViewModel, status: SessionStatus, onOpen: (MoreDest) -> Unit) {
    val alarms by vm.session.alarmLog.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            MoreItem(Icons.Filled.Warning, "Alarms", if (alarms.isEmpty()) "No alarms recorded" else "${alarms.size} events") {
                onOpen(MoreDest.ALARMS)
            }
        }
        item {
            MoreItem(Icons.Filled.Terminal, "Data console", "Raw bytes to and from the BMS, send your own commands") {
                onOpen(MoreDest.CONSOLE)
            }
        }
        item {
            MoreItem(Icons.Filled.Description, "Log files", "CSV recordings you can share or open in Excel") {
                onOpen(MoreDest.LOGS)
            }
        }
        item {
            MoreItem(Icons.Filled.Settings, "App settings", "Polling, logging, units, theme, PIN") {
                onOpen(MoreDest.SETTINGS)
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Connected to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status.deviceName ?: status.address.orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text(status.protocolName.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = vm.session::disconnect) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// ---------------------------------------------------------------------- Alarms

@Composable
fun AlarmsScreen(vm: MainViewModel) {
    val snap by vm.session.snapshot.collectAsStateWithLifecycle()
    val log by vm.session.alarmLog.collectAsStateWithLifecycle()
    val format = remember { SimpleDateFormat("dd MMM HH:mm:ss", Locale.US) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(
                title = "Active now",
                color = if (snap.alarms.isEmpty()) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.errorContainer,
            ) {
                if (snap.alarms.isEmpty()) {
                    Text("No protection or fault is active.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    snap.alarms.forEach {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        if (log.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.NotificationsNone,
                    title = "No alarm history",
                    body = "When the BMS raises or clears an alarm during this connection, it shows up here with the time.",
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth()) {
                    Text("History", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm.session::clearAlarmLog) { Text("Clear") }
                }
            }
            items(log) { e ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        format.format(Date(e.time)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.4f),
                    )
                    Text(
                        (if (e.active) "Raised: " else "Cleared: ") + e.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (e.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- Logs

@Composable
fun LogsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var files by remember { mutableStateOf(vm.logFiles()) }
    val format = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US) }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share log"))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionCard {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Record to CSV", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "One row every ${settings.logIntervalSec} s while connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.csvLogging, onCheckedChange = { on -> vm.updateSettings { it.copy(csvLogging = on) } })
                }
                TextButton(onClick = { files = vm.logFiles() }) { Text("Refresh list") }
            }
        }
        if (files.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Description,
                    title = "No log files",
                    body = "Turn on CSV recording, and a new file starts each time you connect.",
                )
            }
        } else {
            items(files, key = { it.absolutePath }) { f ->
                ListItem(
                    headlineContent = { Text(f.name, style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = { Text("${format.format(Date(f.lastModified()))}   ${f.length() / 1024} KB") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { share(f) }) { Icon(Icons.Filled.Share, contentDescription = "Share ${f.name}") }
                            IconButton(onClick = {
                                f.delete()
                                files = vm.logFiles()
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${f.name}") }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- Settings

@Composable
fun AppSettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var changePin by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(title = "Connection") {
                ChoiceRow("Read the BMS every", listOf(1000L to "1 s", 2000L to "2 s", 5000L to "5 s", 10_000L to "10 s"), s.pollIntervalMs) { v ->
                    vm.updateSettings { it.copy(pollIntervalMs = v) }
                }
                ToggleRow("Reconnect automatically", "Keeps retrying if the link drops", s.autoReconnect) { on ->
                    vm.updateSettings { it.copy(autoReconnect = on) }
                }
                ToggleRow(
                    "Keep monitoring in the background",
                    "Shows a notification with live readings and alarm alerts",
                    s.backgroundMonitoring,
                ) { on -> vm.updateSettings { it.copy(backgroundMonitoring = on) } }
            }
        }
        item {
            SectionCard(title = "Recording") {
                ToggleRow("Record to CSV", "Saved under Android/data/com.xera.bms", s.csvLogging) { on ->
                    vm.updateSettings { it.copy(csvLogging = on) }
                }
                ChoiceRow("Write a row every", listOf(1 to "1 s", 5 to "5 s", 10 to "10 s", 60 to "1 min"), s.logIntervalSec) { v ->
                    vm.updateSettings { it.copy(logIntervalSec = v) }
                }
            }
        }
        item {
            SectionCard(title = "Display") {
                ChoiceRow("Temperature", listOf(false to "°C", true to "°F"), s.fahrenheit) { v ->
                    vm.updateSettings { it.copy(fahrenheit = v) }
                }
                ChoiceRow("Theme", listOf(0 to "System", 1 to "Dark", 2 to "Light"), s.theme) { v ->
                    vm.updateSettings { it.copy(theme = v) }
                }
                ToggleRow("Keep screen on", "Useful on a bench or a boat dashboard", s.keepScreenOn) { on ->
                    vm.updateSettings { it.copy(keepScreenOn = on) }
                }
            }
        }
        item {
            SectionCard(title = "Security") {
                Text(
                    "The PIN protects the Limits screen from accidental edits.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { changePin = true }) { Text("Change PIN") }
            }
        }
        item {
            SectionCard(title = "About") {
                Text("XeraBMS ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text("XERA Robotics Pvt. Ltd.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Supported: " + ProtocolRegistry.entries.joinToString(", ") { it.label } +
                        ". Other BMS types can be inspected with the data console.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (changePin) {
        ChangePinDialog(
            onDismiss = { changePin = false },
            onSave = { pin ->
                changePin = false
                vm.updateSettings { it.copy(paramPin = pin) }
            },
        )
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun ChangePinDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New PIN") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                label = { Text("4 to 8 digits") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { Button(onClick = { onSave(pin) }, enabled = pin.length >= 4) { Text("Save PIN") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
