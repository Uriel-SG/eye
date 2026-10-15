package app.eye.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eye.engine.Level

private val Light = lightColorScheme(
    background = Color(0xFFFBFBFD), onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFF1EFF4), onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    primary = Color(0xFF1D4ED8), onPrimary = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
)
private val Dark = darkColorScheme(
    background = Color(0xFF121316), onBackground = Color(0xFFE4E2E6),
    surface = Color(0xFF1E1F23), onSurface = Color(0xFFE4E2E6),
    surfaceVariant = Color(0xFF2A2B30), onSurfaceVariant = Color(0xFFC9C5CF),
    outline = Color(0xFF938F99),
    primary = Color(0xFFAEC6FF), onPrimary = Color(0xFF002A78),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

/** Scuro OLED: sfondo nero puro (pixel spenti); superfici appena più chiare per distinguere le card. */
private val Oled = Dark.copy(
    background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceBright = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0A), surfaceContainer = Color(0xFF0F0F0F),
    surfaceContainerHigh = Color(0xFF141414), surfaceContainerHighest = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF161616), outlineVariant = Color(0xFF2C2C2C),
)

@Immutable
class VerdictColors(
    val container: Color, val text: Color, val solid: Color, val onSolid: Color,
    val icon: Color = solid, val bar: Color = solid,
)

private fun verdictColors(dark: Boolean): Map<Level, VerdictColors> = if (!dark) mapOf(
    Level.PROBABILE_TRUFFA to VerdictColors(Color(0xFFFDECEA), Color(0xFF8C1D18), Color(0xFFB3261E), Color(0xFFFFFFFF)),
    Level.SOSPETTO to VerdictColors(Color(0xFFFFF1D6), Color(0xFF6B4500), Color(0xFFF5B700), Color(0xFF000000)),
    Level.NESSUN_SEGNALE to VerdictColors(Color(0xFFDDF1F4), Color(0xFF0B4F5C), Color(0xFF0B6B7A), Color(0xFFFFFFFF)),
) else mapOf(
    Level.PROBABILE_TRUFFA to VerdictColors(Color(0xFF5C1410), Color(0xFFFFDAD6), Color(0xFFFFB4AB), Color(0xFF000000)),
    Level.SOSPETTO to VerdictColors(Color(0xFF4A3500), Color(0xFFFFE08A), Color(0xFFFFE08A), Color(0xFF000000)),
    Level.NESSUN_SEGNALE to VerdictColors(Color(0xFF0E3A43), Color(0xFFBFEAF1), Color(0xFF7FD0DE), Color(0xFF000000)),
)

/** Modalità Famiglia: colori pieni, testo nero/bianco, contrasto >= 7:1. */
private fun familyVerdictColors(dark: Boolean): Map<Level, VerdictColors> {
    val ink = if (dark) Color.White else Color.Black
    fun c(container: Long, text: Long) = VerdictColors(Color(container), Color(text), Color(container), Color(text), icon = Color(text), bar = ink)
    return if (!dark) mapOf(
        Level.PROBABILE_TRUFFA to c(0xFFB3261E, 0xFFFFFFFF),
        Level.SOSPETTO to c(0xFFF5B700, 0xFF000000),
        Level.NESSUN_SEGNALE to c(0xFF0B6B7A, 0xFFFFFFFF),
    ) else mapOf(
        Level.PROBABILE_TRUFFA to c(0xFFFFB4AB, 0xFF000000),
        Level.SOSPETTO to c(0xFFFFE08A, 0xFF000000),
        Level.NESSUN_SEGNALE to c(0xFF7FD0DE, 0xFF000000),
    )
}

private val LightHC = lightColorScheme(
    background = Color.White, onBackground = Color.Black, surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFF1B1B1B), outline = Color.Black,
    primary = Color(0xFF0A2E9E), onPrimary = Color.White, error = Color(0xFF8C0009), onError = Color.White,
)
private val DarkHC = darkColorScheme(
    background = Color.Black, onBackground = Color.White, surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF1F1F1F), onSurfaceVariant = Color(0xFFEDEDED), outline = Color.White,
    primary = Color(0xFFC9D8FF), onPrimary = Color.Black, error = Color(0xFFFFB4AB), onError = Color.Black,
)

@Immutable
class EyeExtras(val verdict: Map<Level, VerdictColors>, val familyMode: Boolean) {
    fun of(level: Level): VerdictColors = verdict.getValue(level)
}

val LocalExtras = staticCompositionLocalOf { EyeExtras(verdictColors(false), false) }

val MaterialTheme.extras: EyeExtras
    @Composable get() = LocalExtras.current

private fun typography(family: Boolean): Typography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = if (family) 20.sp else 16.sp, lineHeight = if (family) 28.sp else 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = if (family) 20.sp else 16.sp, lineHeight = if (family) 30.sp else 24.sp),
    bodyMedium = TextStyle(fontSize = if (family) 20.sp else 14.sp, lineHeight = if (family) 28.sp else 20.sp),
    labelLarge = TextStyle(fontSize = if (family) 18.sp else 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

private val EyeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun EyeTheme(familyMode: Boolean = false, dark: Boolean = isSystemInDarkTheme(), oled: Boolean = false, content: @Composable () -> Unit) {
    val scheme: ColorScheme = when {
        familyMode -> if (dark) DarkHC else LightHC // DarkHC è già nero puro
        !dark -> Light
        oled -> Oled
        else -> Dark
    }
    val vc = if (familyMode) familyVerdictColors(dark) else verdictColors(dark)
    CompositionLocalProvider(LocalExtras provides EyeExtras(vc, familyMode)) {
        MaterialTheme(colorScheme = scheme, typography = typography(familyMode), shapes = EyeShapes, content = content)
    }
}
