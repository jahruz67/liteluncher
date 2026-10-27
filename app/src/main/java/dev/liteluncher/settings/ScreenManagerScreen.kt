package dev.liteluncher.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.liteluncher.data.LauncherState
import dev.liteluncher.data.Screen

private val navigationPresets = listOf(
    "Cross" to Screen.entries.toSet(),
    "Horizontal" to setOf(Screen.HOME, Screen.DESKTOP, Screen.APPS),
    "Vertical" to setOf(Screen.HOME, Screen.SEARCH, Screen.FAVORITES),
    "Home only" to setOf(Screen.HOME)
)

@Composable
fun ScreenManagerScreen(
    state: LauncherState,
    onUpdate: ((LauncherState) -> LauncherState) -> Unit,
    onBack: () -> Unit
) {
    val navigation = state.prefs.navigation
    BackHandler(onBack = onBack)
    // Imported backups must obey the same invariant as edits made in this screen.
    LaunchedEffect(navigation.enabledScreens) {
        if (Screen.HOME !in navigation.enabledScreens) {
            onUpdate { current ->
                current.copy(prefs = current.prefs.copy(navigation = current.prefs.navigation.copy(
                    enabledScreens = current.prefs.navigation.enabledScreens + Screen.HOME
                )))
            }
        }
    }
    val setEnabled: (Screen, Boolean) -> Unit = { screen, enabled ->
        if (screen != Screen.HOME) {
            onUpdate { current ->
                val previous = current.prefs.navigation
                val screens = (if (enabled) previous.enabledScreens + screen else previous.enabledScreens - screen) + Screen.HOME
                current.copy(prefs = current.prefs.copy(navigation = previous.copy(
                    enabledScreens = screens, preset = "Custom"
                )))
            }
        }
    }
    SettingsTheme {
        SettingsFrame("Manage screens", onBack) {
            item {
                SettingsSection("Your screen layout") {
                    SettingsNote("Home stays in the center. Toggle any direction to enable its screen, or remove it with the trash button. Removing a screen keeps its saved apps, widgets and settings.")
                    NavigationCross(navigation.enabledScreens + Screen.HOME, setEnabled)
                }
            }
            item {
                SettingsSection("Layout presets") {
                    val choices = navigationPresets.map { it.first to it.first }
                    ChoiceSetting("Preset", navigation.preset, choices,
                        description = "Applies the enabled screen set immediately") { selected ->
                        navigationPresets.firstOrNull { it.first == selected }?.let { preset ->
                            onUpdate { current ->
                                current.copy(prefs = current.prefs.copy(navigation = current.prefs.navigation.copy(
                                    preset = preset.first, enabledScreens = preset.second + Screen.HOME
                                )))
                            }
                        }
                    }
                    SettingsNote("Cross: all five screens. Horizontal: Desktop, Home and Apps. Vertical: Search, Home and Favorites. Home only: the center screen.")
                }
            }
            item {
                SettingsSection("Swipe navigation") {
                    ChoiceSetting("Fingers", navigation.fingers,
                        listOf(1 to "One finger", 2 to "Two fingers")) { fingers ->
                        onUpdate { current -> current.copy(prefs = current.prefs.copy(
                            navigation = current.prefs.navigation.copy(fingers = fingers)
                        )) }
                    }
                    ToggleSetting("Infinite navigation", navigation.infinite,
                        "Wrap to the other end when swiping past the last enabled screen.") { enabled ->
                        onUpdate { current -> current.copy(prefs = current.prefs.copy(
                            navigation = current.prefs.navigation.copy(infinite = enabled)
                        )) }
                    }
                    SettingsNote("Use Gestures settings to assign separate actions to one-finger and two-finger swipes.")
                }
            }
            item {
                SettingsSection("Advanced navigation") {
                    ToggleSetting("Advanced controls", navigation.advanced,
                        "Show transition and screen boundary controls.") { enabled ->
                        onUpdate { current -> current.copy(prefs = current.prefs.copy(
                            navigation = current.prefs.navigation.copy(advanced = enabled)
                        )) }
                    }
                    if (navigation.advanced) {
                        ToggleSetting("Screen borders", state.prefs.behavior.borders) { enabled ->
                            onUpdate { current -> current.copy(prefs = current.prefs.copy(
                                behavior = current.prefs.behavior.copy(borders = enabled)
                            )) }
                        }
                        ToggleSetting("Animations", state.prefs.behavior.animations) { enabled ->
                            onUpdate { current -> current.copy(prefs = current.prefs.copy(
                                behavior = current.prefs.behavior.copy(animations = enabled)
                            )) }
                        }
                        val transitions = listOf("Slide", "Fade", "None").map { it to it }
                        ChoiceSetting("Horizontal transition", state.prefs.behavior.horizontalTransition, transitions) { transition ->
                            onUpdate { current -> current.copy(prefs = current.prefs.copy(
                                behavior = current.prefs.behavior.copy(horizontalTransition = transition)
                            )) }
                        }
                        ChoiceSetting("Vertical transition", state.prefs.behavior.verticalTransition, transitions) { transition ->
                            onUpdate { current -> current.copy(prefs = current.prefs.copy(
                                behavior = current.prefs.behavior.copy(verticalTransition = transition)
                            )) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationCross(enabled: Set<Screen>, onSetEnabled: (Screen, Boolean) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val color = SettingsGreen.copy(alpha = .35f)
            drawLine(color, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 2.dp.toPx())
            drawLine(color, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 2.dp.toPx())
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f))
                DirectionScreen(Screen.SEARCH, "Above", Screen.SEARCH in enabled,
                    Modifier.weight(1f), onSetEnabled)
                Box(Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                DirectionScreen(Screen.DESKTOP, "Left", Screen.DESKTOP in enabled,
                    Modifier.weight(1f), onSetEnabled)
                Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.Home, contentDescription = null, tint = SettingsGreen)
                        Text("Home", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp))
                        Icon(Icons.Default.Lock, contentDescription = "Home is always enabled")
                        Text("Required", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
                DirectionScreen(Screen.APPS, "Right", Screen.APPS in enabled,
                    Modifier.weight(1f), onSetEnabled)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f))
                DirectionScreen(Screen.FAVORITES, "Below", Screen.FAVORITES in enabled,
                    Modifier.weight(1f), onSetEnabled)
                Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DirectionScreen(
    screen: Screen,
    direction: String,
    enabled: Boolean,
    modifier: Modifier,
    onSetEnabled: (Screen, Boolean) -> Unit
) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (enabled) SettingsBackground else SettingsBackground.copy(alpha = .65f))) {
        Column(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(direction, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (screen == Screen.APPS) "Apps" else screen.title,
                style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 5.dp))
            Switch(checked = enabled, onCheckedChange = { onSetEnabled(screen, it) },
                modifier = Modifier.semantics { contentDescription = "Enable ${screen.title} screen" })
            IconButton(onClick = { onSetEnabled(screen, false) }, enabled = enabled,
                modifier = Modifier.height(40.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove ${screen.title} screen")
            }
        }
    }
}
