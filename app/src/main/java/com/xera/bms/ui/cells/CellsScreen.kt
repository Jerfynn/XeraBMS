package com.xera.bms.ui.cells

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xera.bms.ui.MainViewModel
import com.xera.bms.ui.components.EmptyState
import com.xera.bms.ui.components.StatTile
import com.xera.bms.ui.theme.fmt
import kotlin.math.max

@Composable
fun CellsScreen(vm: MainViewModel) {
    val snap by vm.session.snapshot.collectAsStateWithLifecycle()
    val cells = snap.cellVoltages
    if (cells.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.GridView,
            title = "No cell data yet",
            body = "Cell voltages appear here as soon as the BMS reports them.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val hi = snap.cellMax ?: 0.0
    val lo = snap.cellMin ?: 0.0
    val pad = max(0.01, (hi - lo) * 0.25)
    val floor = lo - pad
    val span = (hi - lo) + 2 * pad

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("${hi.fmt(3)} V", "Highest, cell ${snap.maxCellIndex + 1}", Modifier.weight(1f))
                    StatTile("${lo.fmt(3)} V", "Lowest, cell ${snap.minCellIndex + 1}", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("${(snap.cellDeltaMv ?: 0.0).fmt(0)} mV", "Spread", Modifier.weight(1f))
                    StatTile("${snap.cellAvg.fmt(3)} V", "Average", Modifier.weight(1f))
                }
                Text(
                    "Bars are scaled to the spread between your highest and lowest cell, so small differences stand out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        itemsIndexed(cells) { index, v ->
            val isHigh = index == snap.maxCellIndex && hi - lo > 0.0005
            val isLow = index == snap.minCellIndex && hi - lo > 0.0005
            CellTile(
                number = index + 1,
                voltage = v,
                fraction = ((v - floor) / span).toFloat().coerceIn(0.03f, 1f),
                accent = when {
                    isHigh -> MaterialTheme.colorScheme.secondary
                    isLow -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
                tag = when {
                    isHigh -> "highest"
                    isLow -> "lowest"
                    else -> null
                },
                balancing = index in snap.balancingCells,
                resistanceMilliOhm = snap.cellResistances.getOrNull(index)?.let { it * 1000 },
            )
        }
    }
}

@Composable
private fun CellTile(
    number: Int,
    voltage: Double,
    fraction: Float,
    accent: Color,
    tag: String?,
    balancing: Boolean,
    resistanceMilliOhm: Double?,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
        modifier = if (balancing) Modifier.border(1.5.dp, scheme.primary, RoundedCornerShape(12.dp)) else Modifier,
    ) {
        Column(Modifier.padding(10.dp)) {
            Row {
                Text("Cell $number", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (tag != null) Text(tag, style = MaterialTheme.typography.labelSmall, color = accent)
            }
            Text("${voltage.fmt(3)} V", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(scheme.surfaceContainerHighest),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(accent),
                )
            }
            val footer = listOfNotNull(
                if (balancing) "balancing" else null,
                resistanceMilliOhm?.takeIf { it > 0 }?.let { "${it.fmt(0)} mΩ" },
            ).joinToString("   ")
            if (footer.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(footer, style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
        }
    }
}
