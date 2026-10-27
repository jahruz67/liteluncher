package dev.liteluncher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.liteluncher.data.AppearancePrefs
import dev.liteluncher.data.SurfaceTheme
import dev.liteluncher.data.ThemeMode

val Ink = Color(0xFF131A16)
val Panel = Color(0xFF202923)
val Lime = Color(0xFFC1F17C)
val Mist = Color(0xFFF1F4EC)
val Muted = Color(0xFFA7B4A7)

fun parseColor(value: String, fallback: Color = Lime): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(fallback)

@Composable
fun LauncherTheme(prefs: AppearancePrefs, content: @Composable () -> Unit) {
    val dark = when (prefs.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        else -> true
    }
    val accent = parseColor(prefs.accent)
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = accent, onPrimary = Ink, background = Ink, surface = Panel,
            onBackground = Mist, onSurface = Mist, surfaceVariant = Color(0xFF2B362D), onSurfaceVariant = Muted)
        else lightColorScheme(primary = Color(0xFF47672E), onPrimary = Color.White, background = Color(0xFFEBF0E5),
            surface = Color(0xFFF4F7F0), onSurface = Ink, surfaceVariant = Color(0xFFDCE5D5)),
        content = content
    )
}

fun surfaceColor(theme: SurfaceTheme, accent: Color): Color = when (theme) {
    SurfaceTheme.GLASS -> Ink.copy(alpha = .72f)
    SurfaceTheme.DARK -> Color(0xFF1B231E).copy(alpha = .97f)
    SurfaceTheme.LIGHT -> Color(0xFFEAF0E4).copy(alpha = .96f)
    SurfaceTheme.ACCENT -> accent.copy(alpha = .92f)
}

fun surfaceContent(theme: SurfaceTheme): Color = if (theme == SurfaceTheme.LIGHT || theme == SurfaceTheme.ACCENT) Ink else Mist
