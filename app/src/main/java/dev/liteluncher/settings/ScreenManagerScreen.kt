package dev.liteluncher.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liteluncher.data.LauncherState
import dev.liteluncher.data.NavigationPrefs
import dev.liteluncher.data.Screen
import dev.liteluncher.data.minimumDesktopPages

private val FrostedGlass = Color(0xBFFFFFFF)
private val CardInk = Color(0xFF222222)
private val RedBadge = Color(0xFFD46264)
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
    var showMore by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val setEnabled: (Screen, Boolean) -> Unit = { screen, enabled ->
        if (screen != Screen.HOME) onUpdate { current ->
            val previous = current.prefs.navigation
            val screens = (if (enabled) previous.enabledScreens + screen else previous.enabledScreens - screen) + Screen.HOME
            current.copy(prefs = current.prefs.copy(navigation = previous.copy(enabledScreens = screens, preset = "Custom")))
        }
    }

    SettingsTheme {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Screen Manager", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (navigation.fingers == 1) "One finger" else "Two fingers",
                    color = Color(0xFFAAAAAA), fontSize = 15.sp, modifier = Modifier.padding(top = 3.dp))
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                // Match the portrait cards in the reference while fitting short and landscape displays.
                val cardWidth = minOf((maxWidth - 88.dp) / 3, (maxHeight - 28.dp) / 5.4f).coerceAtLeast(0.dp)
                val cardHeight = cardWidth * 1.7f
                val gap = 8.dp
                Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
                    ScreenCard(Screen.SEARCH, Screen.SEARCH in navigation.enabledScreens, setEnabled, Modifier.size(cardWidth, cardHeight))
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        listOf(Screen.DESKTOP, Screen.HOME, Screen.APPS).forEach { screen ->
                            ScreenCard(screen, screen == Screen.HOME || screen in navigation.enabledScreens,
                                setEnabled, Modifier.size(cardWidth, cardHeight))
                        }
                    }
                    ScreenCard(Screen.FAVORITES, Screen.FAVORITES in navigation.enabledScreens, setEnabled, Modifier.size(cardWidth, cardHeight))
                }
            }
            Surface(color = Color(0xFF242829), shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ManagerControl("More", Icons.Rounded.MoreHoriz, { showMore = true }, Modifier.fillMaxWidth())
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(text = { Text(if (navigation.fingers == 1) "Use two fingers" else "Use one finger") }, onClick = {
                                onUpdate { current -> current.copy(prefs = current.prefs.copy(navigation =
                                    current.prefs.navigation.copy(fingers = if (current.prefs.navigation.fingers == 1) 2 else 1))) }
                                showMore = false
                            })
                            DropdownMenuItem(text = { Text("Infinite navigation: ${if (navigation.infinite) "On" else "Off"}") }, onClick = {
                                onUpdate { current -> current.copy(prefs = current.prefs.copy(navigation =
                                    current.prefs.navigation.copy(infinite = !current.prefs.navigation.infinite))) }
                                showMore = false
                            })
                            HorizontalDivider()
                            navigationPresets.forEach { (name, screens) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    onUpdate { current -> current.copy(prefs = current.prefs.copy(navigation =
                                        current.prefs.navigation.copy(preset = name, enabledScreens = screens + Screen.HOME))) }
                                    showMore = false
                                })
                            }
                        }
                    }
                    ManagerControl(if (navigation.advanced) "Normal Mode" else "Advanced Mode", Icons.Rounded.AddToPhotos, {
                        val enabling = !navigation.advanced
                        onUpdate { current -> current.copy(prefs = current.prefs.copy(navigation =
                            current.prefs.navigation.copy(advanced = enabling))) }
                        showAdvanced = enabling
                    }, Modifier.weight(2f))
                    ManagerControl("Reset", Icons.Rounded.RotateLeft, {
                        onUpdate { current -> current.copy(prefs = current.prefs.copy(navigation = NavigationPrefs(),
                            desktop = current.prefs.desktop.copy(pageCount = current.minimumDesktopPages))) }
                    }, Modifier.weight(1f), enabled = navigation != NavigationPrefs() || state.prefs.desktop.pageCount != 1)
                }
            }
        }
        if (showAdvanced) AdvancedNavigationDialog(state, onUpdate) { showAdvanced = false }
    }
}

@Composable
private fun ScreenCard(
    screen: Screen, enabled: Boolean, onSetEnabled: (Screen, Boolean) -> Unit, modifier: Modifier
) {
    val icon = when (screen) {
        Screen.DESKTOP -> Icons.Rounded.Widgets
        Screen.HOME -> Icons.Rounded.Home
        Screen.SEARCH -> Icons.Rounded.Search
        Screen.APPS -> Icons.Rounded.Apps
        Screen.FAVORITES -> Icons.Rounded.Favorite
    }
    Box(modifier) {
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
            .background(if (enabled) FrostedGlass else FrostedGlass.copy(alpha = .25f))
            .clickable(enabled = !enabled) { onSetEnabled(screen, true) }.padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(if (enabled) icon else Icons.Rounded.Add, null,
                tint = if (enabled) CardInk else Color.White, modifier = Modifier.fillMaxWidth(.4f).aspectRatio(1f))
            Spacer(Modifier.height(8.dp))
            Text(screen.title, color = if (enabled) CardInk else Color.White, fontSize = 15.sp,
                lineHeight = 18.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
        if (enabled && screen != Screen.HOME) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp)
                .size(30.dp).clip(CircleShape).background(RedBadge)
                .clickable { onSetEnabled(screen, false) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Remove ${screen.title}", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ManagerControl(
    label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true
) {
    val color = if (enabled) Color(0xFFBBBBBB) else Color(0xFF686C6D)
    Column(modifier.heightIn(min = 56.dp).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(25.dp))
        Spacer(Modifier.height(10.dp))
        Text(label, color = color, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AdvancedNavigationDialog(
    state: LauncherState, onUpdate: ((LauncherState) -> LauncherState) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Advanced mode") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ToggleSetting("Screen borders", state.prefs.behavior.borders) { enabled ->
                onUpdate { current -> current.copy(prefs = current.prefs.copy(behavior = current.prefs.behavior.copy(borders = enabled))) }
            }
            ToggleSetting("Animations", state.prefs.behavior.animations) { enabled ->
                onUpdate { current -> current.copy(prefs = current.prefs.copy(behavior = current.prefs.behavior.copy(animations = enabled))) }
            }
            val transitions = listOf("Slide", "Fade", "None").map { it to it }
            ChoiceSetting("Horizontal transition", state.prefs.behavior.horizontalTransition, transitions) { transition ->
                onUpdate { current -> current.copy(prefs = current.prefs.copy(behavior = current.prefs.behavior.copy(horizontalTransition = transition))) }
            }
            ChoiceSetting("Vertical transition", state.prefs.behavior.verticalTransition, transitions) { transition ->
                onUpdate { current -> current.copy(prefs = current.prefs.copy(behavior = current.prefs.behavior.copy(verticalTransition = transition))) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Desktop pages", modifier = Modifier.weight(1f))
                IconButton(enabled = state.prefs.desktop.pageCount > state.minimumDesktopPages, onClick = {
                    onUpdate { current -> current.copy(prefs = current.prefs.copy(desktop =
                        current.prefs.desktop.copy(pageCount = (current.prefs.desktop.pageCount - 1).coerceAtLeast(1)))) }
                }) { Icon(Icons.Rounded.Remove, "Remove desktop page") }
                Text("${state.prefs.desktop.pageCount}")
                IconButton(enabled = state.prefs.desktop.pageCount < 16, onClick = {
                    onUpdate { current -> current.copy(prefs = current.prefs.copy(desktop =
                        current.prefs.desktop.copy(pageCount = (current.prefs.desktop.pageCount + 1).coerceAtMost(16)))) }
                }) { Icon(Icons.Rounded.Add, "Add desktop page") }
            }
            if (state.minimumDesktopPages > 1) Text("Move or remove entries from the last desktop page before removing that page.",
                style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
