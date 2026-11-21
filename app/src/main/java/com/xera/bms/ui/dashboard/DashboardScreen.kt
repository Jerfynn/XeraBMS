@file:OptIn(ExperimentalMaterial3Api::class)

package com.xera.bms.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xera.bms.core.BmsSnapshot
import com.xera.bms.data.AppSettings
import com.xera.bms.data.SessionStatus
import com.xera.bms.ui.MainViewModel
import com.xera.bms.ui.components.BatteryTank
import com.xera.bms.ui.components.EmptyState
import com.xera.bms.ui.components.SectionCard
import com.xera.bms.ui.components.StatTile
import com.xera.bms.ui.theme.fmt
import com.xera.bms.ui.theme.formatHours
import com.xera.bms.ui.theme.formatTemp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun DashboardScreen(vm: MainViewModel, onOpenAlarms: () -> Unit, onOpenConsole: () -> Unit) {
    val snap by vm.session.snapshot.collectAsStateWithLifecycle()
    val status by vm.session.status.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    if (status.explorer) {
        EmptyState(
            icon = Icons.Filled.Terminal,
            title = "Unknown BMS",
            body = "XeraBMS connected, but none of its protocols got an answer. Open the data console to see " +
                "the raw bytes this battery sends and try commands by hand.",
            actionLabel = "Open data console",
            onAction = onOpenConsole,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    if (!snap.hasData) {
        EmptyState(
            icon = Icons.Filled.HourglassEmpty,
            title = "Waiting for the first reading",
            body = "The battery is connected. Data usually arrives within a few seconds.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Hero(snap) }
        item { Stats(snap) }
        if (snap.alarms.isNotEmpty()) item { AlarmCard(snap.alarms, onOpenAlarms) }
        if (snap.temperatures.isNotEmpty() || snap.mosTemperature != null) item { Temperatures(snap, settings) }
        item { DeviceInfo(snap, status) }
    }
}

@Composable
private fun Hero(s: BmsSnapshot) {
    val current = s.current ?: 0.0
    val charging = current > 0.05
    val discharging = current < -0.05
    val flowColor = when {
        charging -> MaterialTheme.colorScheme.tertiary
        discharging -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BatteryTank(
            soc = s.soc,
            charging = charging,
            modifier = Modifier.width(128.dp).height(216.dp),
        )
        Spacer(Modifier.width(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Pack voltage", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(s.voltage.fmt(2), style = MaterialTheme.typography.displaySmall)
                Text(" V", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    charging -> "Charging"
                    discharging -> "Discharging"
                    else -> "Idle"
                },
                style = MaterialTheme.typography.labelMedium,
                color = flowColor,
            )
            Text("${abs(current).fmt(2)} A", style = MaterialTheme.typography.headlineSmall, color = flowColor)
            Text("${abs(s.power ?: 0.0).fmt(0)} W", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            s.remainingAh?.let { remaining ->
                Text("Remaining", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${formatMah(remaining)} mAh", style = MaterialTheme.typography.titleMedium)
                s.nominalAh?.let { nominal ->
                    Text(
                        "of ${formatMah(nominal)} mAh",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            s.timeEstimate()?.let { est ->
                Text(
                    (if (est.toFull) "Full in " else "Empty in ") + formatHours(est.hours),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stats(s: BmsSnapshot) {
    val delta = s.cellDeltaMv
    val deltaColor = when {
        delta == null -> Color.Unspecified
        delta > 100 -> MaterialTheme.colorScheme.error
        delta > 40 -> MaterialTheme.colorScheme.secondary
        else -> Color.Unspecified
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(s.cycles?.toString() ?: "--", "Cycles", Modifier.weight(1f))
        StatTile(delta?.let { "${it.fmt(0)} mV" } ?: "--", "Cell spread", Modifier.weight(1f), deltaColor)
        StatTile(s.cellAvg?.let { "${it.fmt(3)} V" } ?: "--", "Average cell", Modifier.weight(1f))
        StatTile(
            s.soh?.let { "$it%" } ?: if (s.balancingCells.isNotEmpty()) "Active" else "--",
            if (s.soh != null) "Health" else "Balancing",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun AlarmCard(alarms: List<String>, onOpen: () -> Unit) {
    SectionCard(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            if (alarms.size == 1) "1 active alarm" else "${alarms.size} active alarms",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        alarms.take(4).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        TextButton(onClick = onOpen) { Text("View alarm history") }
    }
}

@Composable
private fun Temperatures(s: BmsSnapshot, settings: AppSettings) {
    SectionCard(title = "Temperatures") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.temperatures.forEachIndexed { i, t ->
                TempChip("Sensor ${i + 1}", formatTemp(t, settings.fahrenheit))
            }
            s.mosTemperature?.let { TempChip("MOSFET", formatTemp(it, settings.fahrenheit)) }
        }
    }
}

@Composable
private fun TempChip(label: String, value: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceInfo(s: BmsSnapshot, status: SessionStatus) {
    SectionCard(title = "Device") {
        InfoRow("Protocol", status.protocolName)
        InfoRow("Model", s.model)
        InfoRow("Hardware", s.hardwareVersion)
        InfoRow("Firmware", s.softwareVersion)
        InfoRow("Manufactured", s.manufactureDate)
        InfoRow("Cells in series", s.cellVoltages.size.takeIf { it > 0 }?.toString())
        InfoRow("Address", status.address)
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

/** 84.2 Ah -> "84,200" */
private fun formatMah(ah: Double): String = String.format(Locale.US, "%,d", (ah * 1000).roundToLong())
