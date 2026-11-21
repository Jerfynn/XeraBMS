@file:OptIn(ExperimentalMaterial3Api::class)

package com.xera.bms.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

private const val CELLS_PER_ROW = 5

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hero") { Hero(snap) }
        item(key = "switches") { SwitchStates(snap) }
        item(key = "figures") { KeyFigures(snap) }
        item(key = "status") { StatusInformation(snap.alarms, onOpenAlarms) }
        if (snap.temperatures.isNotEmpty() || snap.mosTemperature != null) {
            item(key = "temps") { Temperatures(snap, settings) }
        }
        if (snap.cellVoltages.isNotEmpty()) item(key = "cells") { Cells(snap) }
        item(key = "device") { DeviceInfo(snap, status) }
    }
}

// ---------------------------------------------------------------------------- hero

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
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryTank(
                soc = s.soc,
                charging = charging,
                modifier = Modifier.width(116.dp).height(200.dp),
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Voltage", s.voltage.fmt(2), "V", large = true)
                Metric(
                    when {
                        charging -> "Charging"
                        discharging -> "Discharging"
                        else -> "Idle"
                    },
                    abs(current).fmt(2), "A", color = flowColor,
                )
                Metric("Power", formatPower(s.power), "")
                s.remainingAh?.let { remaining ->
                    Column {
                        Metric("Remaining", formatMah(remaining), "mAh")
                        s.nominalAh?.let { nominal ->
                            Text(
                                "${remaining.fmt(1)} of ${nominal.fmt(1)} Ah",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                s.timeEstimate()?.let { est ->
                    Text(
                        (if (est.toFull) "Full in " else "Empty in ") + formatHours(est.hours),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, color: Color = Color.Unspecified, large: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color = color,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
                    modifier = Modifier.padding(bottom = if (large) 4.dp else 2.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- switch states

@Composable
private fun SwitchStates(s: BmsSnapshot) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatePill("Charge MOS", s.chargeFet, Modifier.weight(1f))
        StatePill("Discharge MOS", s.dischargeFet, Modifier.weight(1f))
        StatePill("Balancing", if (s.cellVoltages.isEmpty()) null else s.balancingCells.isNotEmpty(), Modifier.weight(1f))
    }
}

@Composable
private fun StatePill(label: String, on: Boolean?, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dot = when (on) {
        true -> scheme.tertiary
        false -> scheme.outline
        null -> scheme.outlineVariant
    }
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainer) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (on) {
                        true -> "On"
                        false -> "Off"
                        null -> "--"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- key figures

@Composable
private fun KeyFigures(s: BmsSnapshot) {
    val delta = s.cellDeltaMv
    val deltaColor = when {
        delta == null -> Color.Unspecified
        delta > 100 -> MaterialTheme.colorScheme.error
        delta > 40 -> MaterialTheme.colorScheme.secondary
        else -> Color.Unspecified
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                s.cellMax?.let { "${it.fmt(3)} V" } ?: "--",
                if (s.maxCellIndex >= 0) "Highest, cell ${s.maxCellIndex + 1}" else "Highest cell",
                Modifier.weight(1f),
            )
            StatTile(
                s.cellMin?.let { "${it.fmt(3)} V" } ?: "--",
                if (s.minCellIndex >= 0) "Lowest, cell ${s.minCellIndex + 1}" else "Lowest cell",
                Modifier.weight(1f),
            )
            StatTile(s.cellAvg?.let { "${it.fmt(3)} V" } ?: "--", "Average cell", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(delta?.let { "${it.fmt(0)} mV" } ?: "--", "Difference", Modifier.weight(1f), deltaColor)
            StatTile(s.cycles?.toString() ?: "--", "Cycles", Modifier.weight(1f))
            if (s.soh != null) {
                StatTile("${s.soh}%", "Health", Modifier.weight(1f))
            } else {
                StatTile(s.nominalAh?.let { "${it.fmt(1)} Ah" } ?: "--", "Capacity", Modifier.weight(1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------- status information

@Composable
private fun StatusInformation(alarms: List<String>, onOpen: () -> Unit) {
    if (alarms.isEmpty()) {
        SectionCard(title = "Status") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(10.dp))
                Text("No alarms. The BMS reports normal operation.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    SectionCard(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            if (alarms.size == 1) "1 active alarm" else "${alarms.size} active alarms",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        alarms.take(6).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        TextButton(onClick = onOpen) { Text("View alarm history") }
    }
}

// ---------------------------------------------------------------------------- temperatures

@Composable
private fun Temperatures(s: BmsSnapshot, settings: AppSettings) {
    SectionCard(title = "Temperatures") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.temperatures.forEachIndexed { i, t -> TempChip("T${i + 1}", formatTemp(t, settings.fahrenheit)) }
            s.mosTemperature?.let { TempChip("MOSFET", formatTemp(it, settings.fahrenheit)) }
        }
    }
}

@Composable
private fun TempChip(label: String, value: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------- cells

@Composable
private fun Cells(s: BmsSnapshot) {
    val scheme = MaterialTheme.colorScheme
    val spread = (s.cellDeltaMv ?: 0.0) >= 1.0
    SectionCard(title = "Cells, ${s.cellVoltages.size} in series") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(scheme.error, "Highest")
            Legend(scheme.primary, "Lowest")
            if (s.balancingCells.isNotEmpty()) Legend(scheme.secondary, "Balancing")
        }
        s.cellVoltages.chunked(CELLS_PER_ROW).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { i, v ->
                    val index = rowIndex * CELLS_PER_ROW + i
                    CellChip(
                        number = index + 1,
                        voltage = v,
                        highlight = when {
                            spread && index == s.maxCellIndex -> scheme.error
                            spread && index == s.minCellIndex -> scheme.primary
                            else -> null
                        },
                        balancing = index in s.balancingCells,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(CELLS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CellChip(number: Int, voltage: Double, highlight: Color?, balancing: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier,
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainer,
        border = highlight?.let { BorderStroke(1.5.dp, it) },
    ) {
        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$number", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                if (balancing) {
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.secondary))
                }
            }
            Text(voltage.fmt(3), style = MaterialTheme.typography.labelLarge, color = highlight ?: scheme.onSurface)
        }
    }
}

// ---------------------------------------------------------------------------- device

@Composable
private fun DeviceInfo(s: BmsSnapshot, status: SessionStatus) {
    SectionCard(title = "Device") {
        val rows = listOf(
            "Battery serial number" to s.batteryCode,
            "Model" to s.model,
            "Hardware version" to s.hardwareVersion,
            "Software version" to s.softwareVersion,
            "Production date" to s.manufactureDate,
            "Protocol" to status.protocolName,
            "Bluetooth address" to status.address,
        ).filter { !it.second.isNullOrBlank() }
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(value.orEmpty(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
            }
        }
    }
}

// ---------------------------------------------------------------------------- formatting

/** 25.5 Ah -> "25,500" */
private fun formatMah(ah: Double): String = String.format(Locale.US, "%,d", (ah * 1000).roundToLong())

private fun formatPower(watts: Double?): String {
    if (watts == null) return "--"
    val w = abs(watts)
    return if (w >= 1000) String.format(Locale.US, "%.2f kW", w / 1000) else String.format(Locale.US, "%.0f W", w)
}
