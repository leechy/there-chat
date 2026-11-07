package com.therechat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Tokens from the original There design (docs/UI-PLATFORMS.md).
object ThereColors {
    val Accent = Color(0xFF264BCA)
    val AccentDark = Color(0xFF6E8CFF)
    val Ink = Color(0xFF121E49)
    val Secondary = Color(0xFF949BB4)
    val SurfaceMuted = Color(0xFFF2F4FA)
    val Reaction = Color(0xFFFFF9E4)
    val ReactionBorder = Color(0xFFEBD9A6)
    val Danger = Color(0xFFC41111)
}

private val Light = lightColorScheme(
    primary = ThereColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FA),
    onPrimaryContainer = ThereColors.Ink,
    secondary = ThereColors.Secondary,
    onSurface = ThereColors.Ink,
    onSurfaceVariant = ThereColors.Secondary,
    surfaceContainer = ThereColors.SurfaceMuted,
    error = ThereColors.Danger,
)

private val Dark = darkColorScheme(
    primary = ThereColors.AccentDark,
    onPrimary = Color(0xFF0B1640),
    primaryContainer = Color(0xFF263770),
    onPrimaryContainer = Color(0xFFE6E9F5),
    secondary = Color(0xFF8A91AA),
    onSurface = Color(0xFFE6E9F5),
    onSurfaceVariant = Color(0xFF8A91AA),
    surfaceContainer = Color(0xFF1C2033),
    error = Color(0xFFFF5A5A),
)

/** Material 3 with the There palette. Typography stays the default Roboto. */
@Composable
fun ThereTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
