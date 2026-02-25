package com.orbit.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppLightColors = lightColorScheme(
    primary = Color(0xFF123F5A),
    onPrimary = Color.White,
    secondary = Color(0xFF2D8BA5),
    onSecondary = Color.White,
    tertiary = Color(0xFFE7A86A),
    onTertiary = Color.White,
    background = Color(0xFFF7F7FC),
    onBackground = Color(0xFF10212E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12212D),
    surfaceVariant = Color(0xFFE5EDF4),
    onSurfaceVariant = Color(0xFF2A3E50)
)

private val AppDarkColors = darkColorScheme(
    primary = Color(0xFF8BC8E8),
    onPrimary = Color(0xFF00283D),
    secondary = Color(0xFF6DC4B5),
    onSecondary = Color(0xFF002A23),
    tertiary = Color(0xFFF2BC81),
    onTertiary = Color(0xFF3A2100),
    background = Color(0xFF10151D),
    onBackground = Color(0xFFEAF2F8),
    surface = Color(0xFF171E29),
    onSurface = Color(0xFFEAF2F8),
    surfaceVariant = Color(0xFF232D3A),
    onSurfaceVariant = Color(0xFFCAD8E7)
)

@Composable
fun OrbitTheme(
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) AppDarkColors else AppLightColors,
        typography = OrbitTypography,
        content = content
    )
}
