package com.gameroulette.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

fun normalizedAppearance(value: String?): String = if (value == "casino") "casino" else "clean"

fun cartwheelColors(theme: String): ColorScheme = if (theme == "casino") CasinoColors else CalmBlueColors

private val CalmBlueColors = darkColorScheme(
    primary = Color(0xFF9CC7FF), onPrimary = Color(0xFF071C34),
    primaryContainer = Color(0xFF1A3B61), onPrimaryContainer = Color(0xFFD7E8FF),
    secondary = Color(0xFF88D0DF), onSecondary = Color(0xFF062A33),
    secondaryContainer = Color(0xFF193C48), onSecondaryContainer = Color(0xFFD2F0F7),
    background = Color(0xFF0B1220), onBackground = Color(0xFFEDF3FC),
    surface = Color(0xFF142034), onSurface = Color(0xFFEDF3FC),
    surfaceVariant = Color(0xFF1C2C43), onSurfaceVariant = Color(0xFFA8B8CE),
    outline = Color(0xFF526680), outlineVariant = Color(0xFF2C405A)
)

private val CasinoColors = darkColorScheme(
    primary = Color(0xFFE6C481), onPrimary = Color(0xFF271B06),
    primaryContainer = Color(0xFF42351E), onPrimaryContainer = Color(0xFFFFE8B8),
    secondary = Color(0xFFF0A0A6), onSecondary = Color(0xFF43141D),
    secondaryContainer = Color(0xFF57232E), onSecondaryContainer = Color(0xFFFFDADF),
    background = Color(0xFF08130F), onBackground = Color(0xFFF6F0E2),
    surface = Color(0xFF132A21), onSurface = Color(0xFFF6F0E2),
    surfaceVariant = Color(0xFF203B2E), onSurfaceVariant = Color(0xFFB9C8B8),
    outline = Color(0xFF8F8060), outlineVariant = Color(0xFF405344)
)
