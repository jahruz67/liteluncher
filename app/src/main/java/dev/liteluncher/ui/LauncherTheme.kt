package dev.liteluncher.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import dev.liteluncher.data.AppearancePrefs
import dev.liteluncher.data.SurfaceTheme
import dev.liteluncher.data.ThemeMode

val Ink = Color(0xFF282828)
val Panel = Color(0xFF3D3D3D)
val Cerulean = Color(0xFF3B82F6)
val Mist = Color(0xFFF0F0F0)
val Muted = Color(0xFFB8B8B8)

private val CompactShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp)
)

private val CompactTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
)

fun parseColor(value: String, fallback: Color = Cerulean): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(fallback)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherTheme(prefs: AppearancePrefs, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (prefs.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        else -> true
    }
    val accent = parseColor(prefs.accent)
    val palette = if (prefs.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else remember(accent, dark) { accentColorScheme(accent, dark) }
    val colorScheme = if (prefs.mode == ThemeMode.ADVANCED) palette.copy(
        background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black
    ) else palette
    // Compact visual controls; rows retain their own click/selection semantics.
    CompositionLocalProvider(
        LocalRippleConfiguration provides null,
        LocalMinimumInteractiveComponentSize provides 40.dp
    ) {
        MaterialTheme(colorScheme = colorScheme, shapes = CompactShapes, typography = CompactTypography, content = content)
    }
}

private fun accentColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(seed.toArgb(), it) }
    fun tone(lightness: Float, saturation: Float = hsl[1].coerceIn(.35f, .8f), hue: Float = hsl[0]): Color =
        Color(ColorUtils.HSLToColor(floatArrayOf(hue % 360f, saturation, lightness)))
    fun neutral(lightness: Float): Color = tone(lightness, .08f)
    val primary = tone(if (dark) .76f else .36f)
    val secondary = tone(if (dark) .76f else .36f, .25f)
    val tertiary = tone(if (dark) .78f else .36f, .45f, hsl[0] + 60f)
    val background = neutral(if (dark) .055f else .98f)
    val foreground = neutral(if (dark) .93f else .10f)
    val containerContent = neutral(if (dark) .92f else .12f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = contrastContent(primary),
        primaryContainer = tone(if (dark) .23f else .90f), onPrimaryContainer = containerContent,
        inversePrimary = tone(if (dark) .36f else .76f),
        secondary = secondary, onSecondary = contrastContent(secondary),
        secondaryContainer = tone(if (dark) .22f else .90f, .20f), onSecondaryContainer = containerContent,
        tertiary = tertiary, onTertiary = contrastContent(tertiary),
        tertiaryContainer = tone(if (dark) .22f else .90f, .30f, hsl[0] + 60f), onTertiaryContainer = containerContent,
        background = background, onBackground = foreground, surface = background, onSurface = foreground,
        surfaceVariant = neutral(if (dark) .20f else .90f),
        onSurfaceVariant = neutral(if (dark) .73f else .36f),
        surfaceTint = primary, outline = neutral(.48f),
        outlineVariant = neutral(if (dark) .26f else .82f),
        inverseSurface = neutral(if (dark) .92f else .18f),
        inverseOnSurface = neutral(if (dark) .12f else .95f),
        surfaceDim = neutral(if (dark) .055f else .87f),
        surfaceBright = neutral(if (dark) .22f else .98f),
        surfaceContainerLowest = neutral(if (dark) .035f else 1f),
        surfaceContainerLow = neutral(if (dark) .09f else .96f),
        surfaceContainer = neutral(if (dark) .12f else .94f),
        surfaceContainerHigh = neutral(if (dark) .16f else .92f),
        surfaceContainerHighest = neutral(if (dark) .20f else .90f)
    )
}

@Composable
fun surfaceColor(theme: SurfaceTheme, accent: Color): Color = when (theme) {
    SurfaceTheme.GLASS -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .80f)
    SurfaceTheme.DARK -> Color(0xFF191C20).copy(alpha = .97f)
    SurfaceTheme.LIGHT -> Color(0xFFF2F4F8).copy(alpha = .97f)
    SurfaceTheme.ACCENT -> accent.copy(alpha = .94f)
}

fun contrastContent(color: Color): Color = if (color.luminance() > .179f) Color.Black else Color.White

@Composable
fun surfaceContent(theme: SurfaceTheme): Color = when (theme) {
    SurfaceTheme.LIGHT -> Ink
    SurfaceTheme.DARK -> Mist
    SurfaceTheme.ACCENT -> contrastContent(MaterialTheme.colorScheme.primary)
    SurfaceTheme.GLASS -> MaterialTheme.colorScheme.onSurface
}
