package com.xera.bms.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The signature element: a battery drawn as a tank of water whose level is the state of charge.
 * The surface ripples gently, faster while charging.
 */
@Composable
fun BatteryTank(soc: Double?, charging: Boolean, modifier: Modifier = Modifier) {
    val level = ((soc ?: 0.0).coerceIn(0.0, 100.0) / 100.0).toFloat()
    val animatedLevel by animateFloatAsState(level, tween(900), label = "level")
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(if (charging) 1600 else 3600, easing = LinearEasing)),
        label = "phase",
    )
    val scheme = MaterialTheme.colorScheme
    val fill = when {
        soc == null -> scheme.outline
        soc < 15 -> scheme.error
        soc < 35 -> scheme.secondary
        else -> scheme.primary
    }
    val outline = scheme.onSurface.copy(alpha = 0.85f)
    val glass = scheme.surfaceContainerHigh

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val capHeight = size.height * 0.05f
            val capWidth = size.width * 0.38f
            drawRoundRect(
                color = outline,
                topLeft = Offset((size.width - capWidth) / 2f, 0f),
                size = Size(capWidth, capHeight + stroke),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
            val body = Rect(stroke / 2, capHeight + stroke / 2, size.width - stroke / 2, size.height - stroke / 2)
            val radius = 16.dp.toPx()
            drawRoundRect(glass, body.topLeft, body.size, CornerRadius(radius))

            val inset = stroke + 4.dp.toPx()
            val inner = Rect(body.left + inset, body.top + inset, body.right - inset, body.bottom - inset)
            val innerRadius = max(2f, radius - inset / 2)
            val clip = Path().apply { addRoundRect(RoundRect(inner, CornerRadius(innerRadius))) }
            val surfaceY = inner.bottom - inner.height * animatedLevel
            val amplitude = if (animatedLevel in 0.02f..0.98f) 4.dp.toPx() else 0f
            val water = Path().apply {
                moveTo(inner.left, inner.bottom)
                lineTo(inner.left, surfaceY)
                val steps = 48
                for (i in 0..steps) {
                    val x = inner.left + inner.width * i / steps
                    val y = surfaceY + amplitude * sin(phase + i * (3 * PI / steps)).toFloat()
                    lineTo(x, y)
                }
                lineTo(inner.right, inner.bottom)
                close()
            }
            clipPath(clip) {
                drawPath(
                    water,
                    Brush.verticalGradient(
                        listOf(fill.copy(alpha = 0.95f), fill.copy(alpha = 0.55f)),
                        startY = surfaceY,
                        endY = inner.bottom,
                    ),
                )
            }
            drawRoundRect(outline, body.topLeft, body.size, CornerRadius(radius), style = Stroke(stroke))
        }
        val textColor = if (animatedLevel > 0.52f) scheme.onPrimary else scheme.onSurface
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = soc?.let { String.format(Locale.US, "%.1f", it) } ?: "--",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
                color = textColor,
            )
            Text("percent", style = MaterialTheme.typography.labelMedium, color = textColor.copy(alpha = 0.8f))
        }
    }
}

/** Minimal line chart; NaN values are skipped. */
@Composable
fun LineChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    includeZero: Boolean = false,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val points = values.withIndex().filter { !it.value.isNaN() }
        if (points.size < 2) return@Canvas
        var lo = points.minOf { it.value }
        var hi = points.maxOf { it.value }
        if (includeZero) {
            lo = min(lo, 0f)
            hi = max(hi, 0f)
        }
        if (hi - lo < 1e-4f) {
            hi += 0.5f
            lo -= 0.5f
        }
        val pad = (hi - lo) * 0.08f
        lo -= pad
        hi += pad
        val lastIndex = (values.size - 1).coerceAtLeast(1)
        fun x(i: Int) = size.width * i / lastIndex
        fun y(v: Float) = size.height - (v - lo) / (hi - lo) * size.height

        for (k in 0..3) {
            val gy = size.height * k / 3f
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        if (includeZero && lo < 0f && hi > 0f) {
            drawLine(
                grid, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
        val line = Path()
        points.forEachIndexed { n, p ->
            if (n == 0) line.moveTo(x(p.index), y(p.value)) else line.lineTo(x(p.index), y(p.value))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(points.last().index), size.height)
            lineTo(x(points.first().index), size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = color) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
