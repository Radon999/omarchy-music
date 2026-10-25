package org.omarchy.music.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The desktop app's look: Omarchy's default (Tokyo Night) colours, a monospace font,
// square corners, the accent for what's playing and what's on.
object C {
    val accent = Color(0xFF7AA2F7)
    val selection = Color(0xFF414868)
    val muted = Color(0xFF787C99)
    val bg = Color(0xFF1A1B26)
    val dark = Color(0xFF16161E)
    val darker = Color(0xFF111118)
    val light = Color(0xFF1F2335)
    val fg = Color(0xFFC0CAF5)
    val red = Color(0xFFF7768E)
    val green = Color(0xFF9ECE6A)
    val line = selection.copy(alpha = 0.45f)
}

val Mono = FontFamily.Monospace

object T {
    val title = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = C.fg)
    val sub = TextStyle(fontFamily = Mono, fontSize = 12.sp, color = C.muted)
    val body = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = C.fg)
    val section = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.5.sp, color = C.muted)
    val brand = TextStyle(fontFamily = Mono, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, letterSpacing = 1.sp, color = C.fg)
    val tab = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    val time = TextStyle(fontFamily = Mono, fontSize = 11.sp, color = C.muted)
}

@Composable
fun OmarchyTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = C.darker, background = C.bg, onBackground = C.fg, surface = C.light,
            onSurface = C.fg, surfaceVariant = C.light, onSurfaceVariant = C.muted, secondary = C.accent,
            surfaceContainer = C.light, surfaceContainerHigh = C.light, surfaceContainerHighest = C.light,
            surfaceContainerLow = C.dark, outline = C.line, error = C.red,
        ),
        typography = Typography(
            bodyLarge = base.bodyLarge.copy(fontFamily = Mono), bodyMedium = base.bodyMedium.copy(fontFamily = Mono),
            bodySmall = base.bodySmall.copy(fontFamily = Mono), titleLarge = base.titleLarge.copy(fontFamily = Mono),
            titleMedium = base.titleMedium.copy(fontFamily = Mono), titleSmall = base.titleSmall.copy(fontFamily = Mono),
            labelLarge = base.labelLarge.copy(fontFamily = Mono), labelMedium = base.labelMedium.copy(fontFamily = Mono),
            labelSmall = base.labelSmall.copy(fontFamily = Mono), headlineSmall = base.headlineSmall.copy(fontFamily = Mono),
        ),
        content = content,
    )
}
