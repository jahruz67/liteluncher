package dev.liteluncher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.liteluncher.data.AppearancePrefs
import dev.liteluncher.data.SurfaceTheme
import dev.liteluncher.data.ThemeMode

val Ink = Color(0xFF282828)
val Panel = Color(0xFF3D3D3D)
val Cerulean = Color(0xFF3B82F6)
val Mist = Color(0xFFF0F0F0)
val Muted = Color(0xFFB8B8B8)

fun parseColor(value: String, fallback: Color = Cerulean): Color = runCatching {
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(fallback)

@Composable
fun LauncherTheme(prefs: AppearancePrefs, content: @Composable () -> Unit) {
    val accent = parseColor(prefs.accent)
    val colorScheme = when (prefs.mode) {
        ThemeMode.LIGHT -> lightColorScheme(
            primary = accent, onPrimary = contrastContent(accent), background = Color(0xFFF2F2F2),
            surface = Color(0xFFFAFAFA), onSurface = Ink, surfaceVariant = Color(0xFFE0E0E0)
        )
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) {
            darkColorScheme(
                primary = accent, onPrimary = contrastContent(accent), background = Ink, surface = Panel,
                onBackground = Mist, onSurface = Mist, surfaceVariant = Color(0xFF484848), onSurfaceVariant = Muted
            )
        } else {
            lightColorScheme(
                primary = accent, onPrimary = contrastContent(accent), background = Color(0xFFF2F2F2),
                surface = Color(0xFFFAFAFA), onSurface = Ink, surfaceVariant = Color(0xFFE0E0E0)
            )
        }
        ThemeMode.ADVANCED -> darkColorScheme(
            primary = accent, onPrimary = contrastContent(accent), background = Color.Black, surface = Color(0xFF141414),
            onBackground = Color.White, onSurface = Color.White, surfaceVariant = Color(0xFF222222), onSurfaceVariant = Color(0xFFCCCCCC)
        )
        ThemeMode.DARK -> darkColorScheme(
            primary = accent, onPrimary = contrastContent(accent), background = Ink, surface = Panel,
            onBackground = Mist, onSurface = Mist, surfaceVariant = Color(0xFF484848), onSurfaceVariant = Muted
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

fun surfaceColor(theme: SurfaceTheme, accent: Color): Color = when (theme) {
    SurfaceTheme.GLASS -> Color(0xFF555555).copy(alpha = .58f)
    SurfaceTheme.DARK -> Color(0xFF303030).copy(alpha = .97f)
    SurfaceTheme.LIGHT -> Color(0xFFF0F0F0).copy(alpha = .96f)
    SurfaceTheme.ACCENT -> accent.copy(alpha = .92f)
}

fun contrastContent(color: Color): Color = if (color.luminance() > .179f) Color.Black else Color.White

@Composable
fun surfaceContent(theme: SurfaceTheme): Color = when (theme) {
    SurfaceTheme.LIGHT -> Ink
    SurfaceTheme.ACCENT -> contrastContent(MaterialTheme.colorScheme.primary)
    else -> Mist
}
