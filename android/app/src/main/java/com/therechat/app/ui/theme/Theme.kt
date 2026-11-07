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
    secondaryContainer = Color(0xFFDDE3FA),
    onSecondaryContainer = ThereColors.Ink,
    // Neutral blue-grey surfaces instead of Material's default lavender tint.
    background = Color.White,
    onBackground = ThereColors.Ink,
    surface = Color.White,
    surfaceVariant = Color(0xFFE4E7F1),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8FC),
    surfaceContainer = ThereColors.SurfaceMuted,
    surfaceContainerHigh = Color(0xFFECEEF6),
    surfaceContainerHighest = Color(0xFFE4E7F1),
    outline = Color(0xFF8D93A8),
    outlineVariant = Color(0xFFDADDE8),
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
    secondaryContainer = Color(0xFF263770),
    onSecondaryContainer = Color(0xFFE6E9F5),
    background = Color(0xFF0E1120),
    onBackground = Color(0xFFE6E9F5),
    surface = Color(0xFF151929),
    surfaceVariant = Color(0xFF2A2F44),
    surfaceContainerLowest = Color(0xFF0B0E1A),
    surfaceContainerLow = Color(0xFF151929),
    surfaceContainer = Color(0xFF1C2033),
    surfaceContainerHigh = Color(0xFF23283C),
    surfaceContainerHighest = Color(0xFF2A2F44),
    outline = Color(0xFF6B7189),
    outlineVariant = Color(0xFF33384D),
    error = Color(0xFFFF5A5A),
)

/** Material 3 with the There palette. Typography stays the default Roboto. */
@Composable
fun ThereTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
