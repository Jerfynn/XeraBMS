@file:OptIn(ExperimentalMaterial3Api::class)

package com.xera.bms.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xera.bms.ui.MainViewModel
import com.xera.bms.ui.components.EmptyState
import com.xera.bms.ui.components.LineChart
import com.xera.bms.ui.components.SectionCard
import com.xera.bms.ui.theme.fmt

@Composable
fun ChartsScreen(vm: MainViewModel) {
    val history by vm.session.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var rangeMinutes by rememberSaveable { mutableIntStateOf(30) }

    val end = history.lastOrNull()?.t ?: 0L
    val points = if (rangeMinutes == 0) history else history.filter { it.t >= end - rangeMinutes * 60_000L }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5 to "5 min", 30 to "30 min", 120 to "2 h", 0 to "All").forEach { (minutes, label) ->
                    FilterChip(
                        selected = rangeMinutes == minutes,
                        onClick = { rangeMinutes = minutes },
                        label = { Text(label) },
                    )
                }
            }
        }
        if (points.size < 2) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.ShowChart,
                    title = "Collecting data",
                    body = "Charts fill in while the app stays connected. One point is recorded every second.",
                )
            }
        } else {
            item {
                ChartCard("Pack voltage", "V", points.map { it.voltage }, MaterialTheme.colorScheme.primary, 2)
            }
            item {
                ChartCard("Current", "A", points.map { it.current }, MaterialTheme.colorScheme.secondary, 2, includeZero = true)
            }
            item {
                ChartCard("State of charge", "%", points.map { it.soc }, MaterialTheme.colorScheme.tertiary, 0)
            }
            item {
                ChartCard("Cell spread", "mV", points.map { it.deltaMv }, MaterialTheme.colorScheme.error, 0)
            }
            item {
                val temps = if (settings.fahrenheit) {
                    points.map { if (it.maxTemp.isNaN()) Float.NaN else it.maxTemp * 9f / 5f + 32f }
                } else {
                    points.map { it.maxTemp }
                }
                ChartCard("Highest temperature", if (settings.fahrenheit) "°F" else "°C", temps, MaterialTheme.colorScheme.onSurfaceVariant, 1)
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    unit: String,
    values: List<Float>,
    color: Color,
    decimals: Int,
    includeZero: Boolean = false,
) {
    val valid = values.filter { !it.isNaN() }
    if (valid.isEmpty()) return
    SectionCard {
        Row {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${valid.last().fmt(decimals)} $unit", style = MaterialTheme.typography.titleMedium, color = color)
        }
        LineChart(values, color, Modifier.fillMaxWidth().height(140.dp), includeZero)
        Text(
            "Low ${valid.min().fmt(decimals)} $unit     High ${valid.max().fmt(decimals)} $unit",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
