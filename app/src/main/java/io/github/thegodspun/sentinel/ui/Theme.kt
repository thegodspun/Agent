package io.github.thegodspun.sentinel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sentinel's palette: blue first, with green, white and black accents. */
object SentinelColors {
    val Navy = Color(0xFF071A33)
    val BlueDeep = Color(0xFF0B3D91)
    val Blue = Color(0xFF1565C0)
    val BlueBright = Color(0xFF3D8BFF)
    val BlueLight = Color(0xFF9CC3FF)
    val Green = Color(0xFF1E8E3E)
    val GreenBright = Color(0xFF34C759)
    val GreenLight = Color(0xFF7BE495)
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
}

private val Light = lightColorScheme(
    primary = SentinelColors.Blue,
    onPrimary = SentinelColors.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF001A40),
    secondary = SentinelColors.Green,
    onSecondary = SentinelColors.White,
    secondaryContainer = Color(0xFFD3F5DC),
    onSecondaryContainer = Color(0xFF00210B),
    tertiary = SentinelColors.Black,
    onTertiary = SentinelColors.White,
    background = Color(0xFFF4F7FC),
    onBackground = Color(0xFF0A0F1A),
    surface = SentinelColors.White,
    onSurface = Color(0xFF0A0F1A),
    surfaceVariant = Color(0xFFE6EDF8),
    onSurfaceVariant = Color(0xFF4A5468),
    surfaceContainerLowest = SentinelColors.White,
    surfaceContainerLow = Color(0xFFF9FBFE),
    surfaceContainer = SentinelColors.White,
    surfaceContainerHigh = Color(0xFFEDF2FA),
    surfaceContainerHighest = Color(0xFFE4EBF6),
    outline = Color(0xFF8A93A6),
    outlineVariant = Color(0xFFDDE3EE),
)

private val Dark = darkColorScheme(
    primary = SentinelColors.BlueLight,
    onPrimary = Color(0xFF002D68),
    primaryContainer = Color(0xFF12346B),
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = SentinelColors.GreenLight,
    onSecondary = Color(0xFF00391A),
    secondaryContainer = Color(0xFF0F4D25),
    onSecondaryContainer = Color(0xFFD3F5DC),
    tertiary = SentinelColors.White,
    onTertiary = SentinelColors.Black,
    background = SentinelColors.Black,
    onBackground = Color(0xFFF2F5FA),
    surface = Color(0xFF0B111C),
    onSurface = Color(0xFFF2F5FA),
    surfaceVariant = Color(0xFF172133),
    onSurfaceVariant = Color(0xFFAEB8CA),
    surfaceContainerLowest = SentinelColors.Black,
    surfaceContainerLow = Color(0xFF080D16),
    surfaceContainer = Color(0xFF0E1522),
    surfaceContainerHigh = Color(0xFF151E2E),
    surfaceContainerHighest = Color(0xFF1C273A),
    outline = Color(0xFF6E7890),
    outlineVariant = Color(0xFF243048),
)

private val Sans = FontFamily.SansSerif

private val SentinelTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

private val SentinelShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SentinelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = SentinelTypography,
        shapes = SentinelShapes,
        content = content,
    )
}

/** Gradients that give Sentinel its look. */
object SentinelBrushes {
    /** Deep navy into Sentinel blue, for headers. */
    val Header: Brush
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) {
            Brush.linearGradient(listOf(SentinelColors.Navy, SentinelColors.BlueDeep))
        } else {
            Brush.linearGradient(listOf(SentinelColors.BlueDeep, SentinelColors.Blue, SentinelColors.BlueBright))
        }

    /** The user's own chat bubbles. */
    val Outgoing = Brush.linearGradient(listOf(SentinelColors.BlueBright, SentinelColors.Blue))

    /** Voice and "go" actions. */
    val Go = Brush.linearGradient(listOf(SentinelColors.GreenBright, SentinelColors.Green))

    /** Progress bars: blue running into green. */
    val Progress = Brush.horizontalGradient(listOf(SentinelColors.Blue, SentinelColors.GreenBright))
}
