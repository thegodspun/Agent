package io.github.thegodspun.sentinel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Sentinel's palette: blue first, with green, white and black accents. */
object SentinelColors {
    val Blue = Color(0xFF1565C0)
    val BlueDeep = Color(0xFF0D3B7A)
    val BlueLight = Color(0xFF8AB8FF)
    val Green = Color(0xFF2E7D32)
    val GreenBright = Color(0xFF5BD47A)
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
}

private val Light = lightColorScheme(
    primary = SentinelColors.Blue,
    onPrimary = SentinelColors.White,
    primaryContainer = Color(0xFFD7E6FF),
    onPrimaryContainer = Color(0xFF001A40),
    secondary = SentinelColors.Green,
    onSecondary = SentinelColors.White,
    secondaryContainer = Color(0xFFC9F2CF),
    onSecondaryContainer = Color(0xFF002109),
    tertiary = SentinelColors.Black,
    onTertiary = SentinelColors.White,
    background = SentinelColors.White,
    onBackground = SentinelColors.Black,
    surface = SentinelColors.White,
    onSurface = SentinelColors.Black,
    surfaceVariant = Color(0xFFE4ECF8),
    onSurfaceVariant = Color(0xFF26303F),
    surfaceContainer = Color(0xFFEFF4FC),
    surfaceContainerHigh = Color(0xFFE8EFFA),
    surfaceContainerHighest = Color(0xFFE1E9F6),
    outline = Color(0xFF6A7487),
)

private val Dark = darkColorScheme(
    primary = SentinelColors.BlueLight,
    onPrimary = Color(0xFF002D68),
    primaryContainer = SentinelColors.BlueDeep,
    onPrimaryContainer = SentinelColors.White,
    secondary = SentinelColors.GreenBright,
    onSecondary = Color(0xFF00390F),
    secondaryContainer = Color(0xFF1B5E20),
    onSecondaryContainer = Color(0xFFC9F2CF),
    tertiary = SentinelColors.White,
    onTertiary = SentinelColors.Black,
    background = SentinelColors.Black,
    onBackground = SentinelColors.White,
    surface = SentinelColors.Black,
    onSurface = SentinelColors.White,
    surfaceVariant = Color(0xFF14202F),
    onSurfaceVariant = Color(0xFFC4CDDB),
    surfaceContainer = Color(0xFF0B1422),
    surfaceContainerHigh = Color(0xFF111C2C),
    surfaceContainerHighest = Color(0xFF172437),
    outline = Color(0xFF7D879A),
)

@Composable
fun SentinelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** The deep-blue header bar, the same in light and dark mode. */
val HeaderBlue: Color @Composable get() = if (isSystemInDarkTheme()) SentinelColors.BlueDeep else SentinelColors.Blue
