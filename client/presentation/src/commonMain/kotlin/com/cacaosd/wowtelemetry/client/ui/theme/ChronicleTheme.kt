package com.cacaosd.wowtelemetry.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val ChronicleDarkBackground = Color(0xFF0F1117)
val ChronicleSurface = Color(0xFF161922)
val ChronicleSurfaceVariant = Color(0xFF1E2230)
val ChronicleSurfaceElevated = Color(0xFF262C3D)
val ChronicleBorder = Color(0xFF2D3449)
val ChronicleBorderSubtle = Color(0xFF222737)
val ChronicleGold = Color(0xFFE5B358)
val ChronicleGoldContainer = Color(0xFF332712)
val ChronicleOnGoldContainer = Color(0xFFFFE0A0)
val ChronicleArcane = Color(0xFF4FC3F7)
val ChronicleArcaneContainer = Color(0xFF132B3B)
val ChronicleSuccess = Color(0xFF4CAF50)
val ChronicleWarning = Color(0xFFFFA726)
val ChronicleError = Color(0xFFEF5350)
val ChronicleErrorContainer = Color(0xFF3B1818)
val ChronicleTextPrimary = Color(0xFFECEFF4)
val ChronicleTextSecondary = Color(0xFFA0A7BD)
val ChronicleTextMuted = Color(0xFF6B7288)

val DarkChronicleColorScheme = darkColorScheme(
    primary = ChronicleGold,
    onPrimary = Color(0xFF1E1602),
    primaryContainer = ChronicleGoldContainer,
    onPrimaryContainer = ChronicleOnGoldContainer,
    secondary = ChronicleArcane,
    onSecondary = Color(0xFF031E2C),
    secondaryContainer = ChronicleArcaneContainer,
    onSecondaryContainer = Color(0xFFBCE7FD),
    surface = ChronicleSurface,
    onSurface = ChronicleTextPrimary,
    surfaceVariant = ChronicleSurfaceVariant,
    onSurfaceVariant = ChronicleTextSecondary,
    background = ChronicleDarkBackground,
    onBackground = ChronicleTextPrimary,
    error = ChronicleError,
    errorContainer = ChronicleErrorContainer,
    onErrorContainer = Color(0xFFFFB4AB),
    outline = ChronicleBorder,
    outlineVariant = ChronicleBorderSubtle,
)

fun wowClassColor(className: String?): Color {
    return when (className?.trim()?.lowercase()) {
        "death knight", "deathknight", "dk" -> Color(0xFFC41E3A)
        "demon hunter", "demonhunter", "dh" -> Color(0xFFA330C9)
        "druid" -> Color(0xFFFF7C0A)
        "evoker" -> Color(0xFF33937F)
        "hunter" -> Color(0xFFAAD372)
        "mage" -> Color(0xFF3FC7EB)
        "monk" -> Color(0xFF00FF98)
        "paladin", "pala" -> Color(0xFFF48CBA)
        "priest" -> Color(0xFFE8E8E8)
        "rogue" -> Color(0xFFFFF468)
        "shaman" -> Color(0xFF0070DD)
        "warlock", "lock" -> Color(0xFF8788EE)
        "warrior", "warr" -> Color(0xFFC69B6D)
        else -> ChronicleGold
    }
}
