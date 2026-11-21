package com.xera.bms.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Palette taken from the subsea world XERA works in: deep petrol water, a bioluminescent
 * teal for "healthy", kelp green for charging, amber for discharging and coral for alarms.
 */
object XeraPalette {
    val Petrol = Color(0xFF0C2230)
    val PetrolRaised = Color(0xFF12303F)
    val PetrolHigh = Color(0xFF1A3D50)
    val PetrolHighest = Color(0xFF224A5F)
    val Foam = Color(0xFFE4F1F3)
    val Mist = Color(0xFF9FB8C1)
    val Biolume = Color(0xFF45D6CB)
    val Kelp = Color(0xFF7BDC8B)
    val Amber = Color(0xFFF2B148)
    val Coral = Color(0xFFFF6F61)
    val Paper = Color(0xFFEEF4F6)
    val DeepTeal = Color(0xFF0E6E78)
}

private val DarkScheme = darkColorScheme(
    primary = XeraPalette.Biolume,
    onPrimary = XeraPalette.Petrol,
    primaryContainer = Color(0xFF0F4C55),
    onPrimaryContainer = XeraPalette.Foam,
    secondary = XeraPalette.Amber,
    onSecondary = XeraPalette.Petrol,
    secondaryContainer = Color(0xFF4A3510),
    onSecondaryContainer = Color(0xFFFFDDB0),
    tertiary = XeraPalette.Kelp,
    onTertiary = XeraPalette.Petrol,
    error = XeraPalette.Coral,
    onError = XeraPalette.Petrol,
    errorContainer = Color(0xFF5A1F1A),
    onErrorContainer = Color(0xFFFFDAD5),
    background = XeraPalette.Petrol,
    onBackground = XeraPalette.Foam,
    surface = XeraPalette.Petrol,
    onSurface = XeraPalette.Foam,
    surfaceVariant = XeraPalette.PetrolHigh,
    onSurfaceVariant = XeraPalette.Mist,
    surfaceContainerLowest = Color(0xFF081A25),
    surfaceContainerLow = Color(0xFF0F2836),
    surfaceContainer = XeraPalette.PetrolRaised,
    surfaceContainerHigh = XeraPalette.PetrolHigh,
    surfaceContainerHighest = XeraPalette.PetrolHighest,
    outline = Color(0xFF3E6273),
    outlineVariant = Color(0xFF26475A),
)

private val LightScheme = lightColorScheme(
    primary = XeraPalette.DeepTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4EEF0),
    onPrimaryContainer = Color(0xFF00363C),
    secondary = Color(0xFF9A5F08),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB0),
    onSecondaryContainer = Color(0xFF2C1700),
    tertiary = Color(0xFF2E7D3E),
    onTertiary = Color.White,
    error = Color(0xFFC0392B),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410002),
    background = XeraPalette.Paper,
    onBackground = XeraPalette.Petrol,
    surface = XeraPalette.Paper,
    onSurface = XeraPalette.Petrol,
    surfaceVariant = Color(0xFFDCE7EB),
    onSurfaceVariant = Color(0xFF41575F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F9FA),
    surfaceContainer = Color(0xFFE4EEF1),
    surfaceContainerHigh = Color(0xFFDAE6EA),
    surfaceContainerHighest = Color(0xFFD0DFE4),
    outline = Color(0xFF71878F),
    outlineVariant = Color(0xFFC2D2D8),
)

private val base = Typography()

val XeraTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge.copy(fontFeatureSettings = "tnum"),
    labelMedium = base.labelMedium,
    labelSmall = base.labelSmall,
)

@Composable
fun XeraTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = XeraTypography,
        content = content,
    )
}

// ------------------------------------------------------------------ formatting

fun Double?.fmt(decimals: Int, fallback: String = "--"): String =
    if (this == null || this.isNaN()) fallback else String.format(Locale.US, "%.${decimals}f", this)

fun Float.fmt(decimals: Int): String =
    if (isNaN()) "--" else String.format(Locale.US, "%.${decimals}f", this)

fun formatTemp(celsius: Double?, fahrenheit: Boolean): String = when {
    celsius == null -> "--"
    fahrenheit -> String.format(Locale.US, "%.1f °F", celsius * 9 / 5 + 32)
    else -> String.format(Locale.US, "%.1f °C", celsius)
}

fun formatHours(hours: Double): String {
    val minutes = (hours * 60).roundToInt()
    return when {
        minutes >= 100 * 60 -> "over 99 h"
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "$minutes min"
    }
}
