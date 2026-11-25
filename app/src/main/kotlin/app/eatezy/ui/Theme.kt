package app.eatezy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Near-black and greys; color only where it helps to find or read something.
 * Text is off-white, never pure white on black: full contrast glares on OLED.
 * Accent is for two things only: the main action, and a limit that was passed ([warn]).
 * Selection is shown with a lighter surface ([selected]), not a white fill.
 */
object C {
    val bg = Color(0xFF000000)
    val surface = Color(0xFF121212)
    val surface2 = Color(0xFF1C1C1C)
    /** Selected chip, day or tab: one step lighter than [surface2]. */
    val selected = Color(0xFF2C2C2C)
    val line = Color(0xFF262626)
    val text = Color(0xFFE8E8E8)
    val text2 = Color(0xFF9E9E9E)
    val text3 = Color(0xFF6B6B6B)
    val accent = Color(0xFFFF3B5C)
    /** A limit or goal passed, an error. Same hue as [accent], named for what it means. */
    val warn = accent
    val accentDim = Color(0x33FF3B5C)
    /** 60 % black and greys, 30 % text, 10 % accent: macros are told apart by labels, not colors. */
    val protein = text
    val carbs = text
    val fat = text
    val good = text

    @Suppress("UNUSED_PARAMETER")
    fun of(key: String): Color = text
}

/** Sizes on the 12 px grid: 6, 12, 18, 24, 36, 48, 60, 72… */
object S {
    val s6 = 6.dp
    val s12 = 12.dp
    val s18 = 18.dp
    val s24 = 24.dp
    val s36 = 36.dp
    val s48 = 48.dp
    val s60 = 60.dp
    val s72 = 72.dp
    val s96 = 96.dp
    val radius = 18.dp
    val radiusSmall = 12.dp
}

object T {
    val huge = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.SemiBold, color = C.text, lineHeight = 48.sp)
    val h1 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.text, lineHeight = 24.sp)
    val h2 = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.text, lineHeight = 24.sp)
    val body = TextStyle(fontSize = 14.sp, color = C.text, lineHeight = 18.sp)
    val bodyBold = body.copy(fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontSize = 12.sp, color = C.text2, lineHeight = 18.sp)
    val smallBold = small.copy(fontWeight = FontWeight.SemiBold)
    /**
     * Section and group titles. Sentence case and one shade of grey: a title is either
     * quiet (grey) or loud (caps, bold), never both at once.
     */
    val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.text2, lineHeight = 18.sp)
}

@Composable
fun EatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = C.text, background = C.bg, onBackground = C.text,
            surface = C.surface, onSurface = C.text, surfaceVariant = C.surface2, onSurfaceVariant = C.text2,
            surfaceContainer = C.surface, surfaceContainerHigh = C.surface2, surfaceContainerLow = C.surface,
            surfaceContainerHighest = C.surface2, outline = C.line, outlineVariant = C.line, secondary = C.text,
        ),
        typography = Typography(bodyLarge = T.body, bodyMedium = T.body, labelLarge = T.bodyBold),
        content = content,
    )
}
