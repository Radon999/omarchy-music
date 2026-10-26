package org.omarchy.music.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The look: Omarchy's (Tokyo Night) colours in a Poweramp-like layout, a sans-serif font,
// rounded corners, the accent for what's playing and what's on. Settings › Look swaps the
// colours: Dark, Light, or a neon one (black, one colour glowing).
object C {
    var accent by mutableStateOf(Color(0xFF7AA2F7))
    var selection by mutableStateOf(Color(0xFF414868))
    var muted by mutableStateOf(Color(0xFF787C99))
    var bg by mutableStateOf(Color(0xFF1A1B26))
    var dark by mutableStateOf(Color(0xFF16161E))
    var darker by mutableStateOf(Color(0xFF111118))
    var light by mutableStateOf(Color(0xFF1F2335))
    var fg by mutableStateOf(Color(0xFFC0CAF5))
    var red by mutableStateOf(Color(0xFFF7768E))
    var green by mutableStateOf(Color(0xFF9ECE6A))
    var isLight by mutableStateOf(false)
    var glow by mutableStateOf(false)   // a neon look: what's on glows
    val line get() = selection.copy(alpha = 0.45f)
    /** A soft glow under accent text, in a neon look. */
    val shadow get() = if (glow) Shadow(accent.copy(alpha = 0.8f), blurRadius = 14f) else null
}

/** The looks: Default, Dark, Light, and a neon one per colour. */
object Looks {
    val NEON = linkedMapOf("red" to ("Red" to Color(0xFFFF1F3D)), "blue" to ("Blue" to Color(0xFF2F7BFF)),
        "cyan" to ("Cyan" to Color(0xFF14E4FF)), "green" to ("Green" to Color(0xFF1FFF6E)), "lime" to ("Lime" to Color(0xFFB6FF1A)),
        "gold" to ("Gold" to Color(0xFFFFC21A)), "orange" to ("Orange" to Color(0xFFFF7A1A)), "pink" to ("Pink" to Color(0xFFFF2FA8)),
        "purple" to ("Purple" to Color(0xFFA24DFF)), "white" to ("White" to Color(0xFFF4F4FF)))
    val ALL: List<Pair<String, String>> = listOf("default" to "Default", "dark" to "Dark", "light" to "Light") +
        NEON.map { (k, v) -> k to "Neon ${v.first.lowercase()}" }

    fun apply(look: String) {
        val neon = NEON[look]?.second
        C.isLight = look == "light"
        C.glow = neon != null
        when {
            neon != null -> {
                fun tint(t: Float) = lerp(Color.Black, neon, t)
                C.accent = neon; C.selection = tint(0.22f); C.muted = lerp(Color(0xFF8A8A8A), neon, 0.25f)
                C.bg = tint(0.02f); C.dark = tint(0.01f); C.darker = Color.Black; C.light = tint(0.06f)
                C.fg = lerp(Color(0xFFF4F4F4), neon, 0.08f); C.red = Color(0xFFFF3B4D); C.green = lerp(neon, Color.White, 0.2f)
            }
            look == "light" -> {
                C.accent = Color(0xFF2E5BD8); C.selection = Color(0xFFC6CBE0); C.muted = Color(0xFF6A6F85)
                C.bg = Color(0xFFF4F5F9); C.dark = Color(0xFFE9EBF2); C.darker = Color(0xFFDFE2EB); C.light = Color(0xFFFFFFFF)
                C.fg = Color(0xFF1F2335); C.red = Color(0xFFC6334F); C.green = Color(0xFF3F7D1A)
            }
            look == "dark" -> {
                C.accent = Color(0xFF7AA2F7); C.selection = Color(0xFF2A2D3E); C.muted = Color(0xFF6B7089)
                C.bg = Color(0xFF0B0B10); C.dark = Color(0xFF07070A); C.darker = Color.Black; C.light = Color(0xFF14151D)
                C.fg = Color(0xFFD4D8F0); C.red = Color(0xFFF7768E); C.green = Color(0xFF9ECE6A)
            }
            else -> {
                C.accent = Color(0xFF7AA2F7); C.selection = Color(0xFF414868); C.muted = Color(0xFF787C99)
                C.bg = Color(0xFF1A1B26); C.dark = Color(0xFF16161E); C.darker = Color(0xFF111118); C.light = Color(0xFF1F2335)
                C.fg = Color(0xFFC0CAF5); C.red = Color(0xFFF7768E); C.green = Color(0xFF9ECE6A)
            }
        }
    }
}

val Mono = FontFamily.Monospace
/** The type: a clean sans-serif, like Poweramp's (the looks' colours are the same as ever). */
val Sans = FontFamily.SansSerif

object T {
    val title get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = C.fg)
    val sub get() = TextStyle(fontFamily = Sans, fontSize = 13.sp, color = C.muted)
    val body get() = TextStyle(fontFamily = Sans, fontSize = 14.sp, color = C.fg)
    val section get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp, color = C.muted)
    val brand get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = C.fg)
    val tab get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    val time get() = TextStyle(fontFamily = Sans, fontSize = 12.sp, color = C.muted)
    /** The player's song title. */
    val big get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = C.fg)
    /** A screen's name in its header. */
    val head get() = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.fg)
}

/** Corners: rounded, Poweramp-like. */
object R {
    val small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    val card = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    val big = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(50)
}

@Composable
fun OmarchyTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val dark = darkColorScheme(
        primary = C.accent, onPrimary = C.darker, background = C.bg, onBackground = C.fg, surface = C.light,
        onSurface = C.fg, surfaceVariant = C.light, onSurfaceVariant = C.muted, secondary = C.accent,
        surfaceContainer = C.light, surfaceContainerHigh = C.light, surfaceContainerHighest = C.light,
        surfaceContainerLow = C.dark, outline = C.line, error = C.red,
    )
    val light = lightColorScheme(
        primary = C.accent, onPrimary = C.light, background = C.bg, onBackground = C.fg, surface = C.light,
        onSurface = C.fg, surfaceVariant = C.light, onSurfaceVariant = C.muted, secondary = C.accent,
        surfaceContainer = C.light, surfaceContainerHigh = C.light, surfaceContainerHighest = C.light,
        surfaceContainerLow = C.dark, outline = C.line, error = C.red,
    )
    MaterialTheme(
        colorScheme = if (C.isLight) light else dark,
        typography = Typography(
            bodyLarge = base.bodyLarge.copy(fontFamily = Sans), bodyMedium = base.bodyMedium.copy(fontFamily = Sans),
            bodySmall = base.bodySmall.copy(fontFamily = Sans), titleLarge = base.titleLarge.copy(fontFamily = Sans),
            titleMedium = base.titleMedium.copy(fontFamily = Sans), titleSmall = base.titleSmall.copy(fontFamily = Sans),
            labelLarge = base.labelLarge.copy(fontFamily = Sans), labelMedium = base.labelMedium.copy(fontFamily = Sans),
            labelSmall = base.labelSmall.copy(fontFamily = Sans), headlineSmall = base.headlineSmall.copy(fontFamily = Sans),
        ),
        shapes = androidx.compose.material3.Shapes(extraSmall = R.small, small = R.small, medium = R.card, large = R.big, extraLarge = R.big),
        content = content,
    )
}
