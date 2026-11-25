package com.zapfig.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF25D366)
val GreenDark = Color(0xFF128C4A)
val Bg = Color(0xFF0B1210)
val Surface1 = Color(0xFF131D19)
val Surface2 = Color(0xFF1A2722)
val Muted = Color(0xFF8FA39A)
val Danger = Color(0xFFFF6B6B)
val Amber = Color(0xFFFFC857)

private val scheme = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF00210D),
    primaryContainer = GreenDark,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFB9F5CF),
    background = Bg,
    onBackground = Color(0xFFE8F2EC),
    surface = Bg,
    onSurface = Color(0xFFE8F2EC),
    surfaceVariant = Surface1,
    onSurfaceVariant = Muted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    outline = Color(0xFF3E5249),
    error = Danger,
)

@Composable
fun ZapFigTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
