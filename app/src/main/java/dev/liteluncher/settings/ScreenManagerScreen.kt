package dev.liteluncher.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liteluncher.data.LauncherState
import dev.liteluncher.data.NavigationPrefs
import dev.liteluncher.data.Screen

private val FrostedGlass = Color(0x80222222)
private val CardBorder = Color(0x33FFFFFF)
private val RedBadge = Color(0xFFE53935)

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
    val desktop = state.prefs.desktop
    var showMoreMenu by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

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
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(
                        "5-Screen Cross Topology",
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap the red badges to remove a screen or tap an empty slot to enable it. Desktop supports multiple pages.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SymmetricalCrossLayout(
                    enabled = navigation.enabledScreens + Screen.HOME,
                    desktopPages = desktop.pageCount,
                    onSetEnabled = setEnabled,
                    onAddDesktopPage = {
                        onUpdate { current ->
                            current.copy(prefs = current.prefs.copy(desktop = current.prefs.desktop.copy(
                                pageCount = current.prefs.desktop.pageCount + 1
                            )))
                        }
                    },
                    onRemoveDesktopPage = {
                        onUpdate { current ->
                            current.copy(prefs = current.prefs.copy(desktop = current.prefs.desktop.copy(
                                pageCount = (current.prefs.desktop.pageCount - 1).coerceAtLeast(1)
                            )))
                        }
                    }
                )
            }

            if (navigation.advanced) {
                item {
                    SettingsSection("Advanced mode controls") {
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

            item {
                Spacer(Modifier.height(16.dp))
                BottomControlsBar(
                    advanced = navigation.advanced,
                    onToggleAdvanced = {
                        onUpdate { current -> current.copy(prefs = current.prefs.copy(
                            navigation = current.prefs.navigation.copy(advanced = !navigation.advanced)
                        )) }
                    },
                    onReset = {
                        onUpdate { current ->
                            current.copy(prefs = current.prefs.copy(
                                navigation = NavigationPrefs(),
                                desktop = current.prefs.desktop.copy(pageCount = 1)
                            ))
                        }
                    },
                    showMoreMenu = showMoreMenu,
                    onShowMoreMenu = { showMoreMenu = it },
                    navigation = navigation,
                    onUpdateNavigation = { nav ->
                        onUpdate { current -> current.copy(prefs = current.prefs.copy(navigation = nav)) }
                    }
                )
            }
        }
    }
}

@Composable
private fun SymmetricalCrossLayout(
    enabled: Set<Screen>,
    desktopPages: Int,
    onSetEnabled: (Screen, Boolean) -> Unit,
    onAddDesktopPage: () -> Unit,
    onRemoveDesktopPage: () -> Unit
) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val lineColor = Color(0x663B82F6)
            drawLine(lineColor, Offset(center.x, 24.dp.toPx()), Offset(center.x, size.height - 24.dp.toPx()), strokeWidth = 2.dp.toPx())
            drawLine(lineColor, Offset(24.dp.toPx(), center.y), Offset(size.width - 24.dp.toPx(), center.y), strokeWidth = 2.dp.toPx())
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Row 1: Top (Search)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                CrossCard(
                    screen = Screen.SEARCH,
                    directionLabel = "Top",
                    enabled = Screen.SEARCH in enabled,
                    onSetEnabled = onSetEnabled,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.weight(1f))
            }

            // Row 2: Desktop (Left), Home (Center), Application List (Right)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CrossCard(
                    screen = Screen.DESKTOP,
                    directionLabel = "Left",
                    enabled = Screen.DESKTOP in enabled,
                    onSetEnabled = onSetEnabled,
                    desktopPages = desktopPages,
                    onAddDesktopPage = onAddDesktopPage,
                    onRemoveDesktopPage = onRemoveDesktopPage,
                    modifier = Modifier.weight(1f)
                )

                // Center Card: Home Screen
                Surface(
                    modifier = Modifier.weight(1f).height(156.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = FrostedGlass,
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Home, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Home Screen", fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Center", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }

                CrossCard(
                    screen = Screen.APPS,
                    directionLabel = "Right",
                    enabled = Screen.APPS in enabled,
                    onSetEnabled = onSetEnabled,
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 3: Bottom (Favorites)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                CrossCard(
                    screen = Screen.FAVORITES,
                    directionLabel = "Bottom",
                    enabled = Screen.FAVORITES in enabled,
                    onSetEnabled = onSetEnabled,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CrossCard(
    screen: Screen,
    directionLabel: String,
    enabled: Boolean,
    onSetEnabled: (Screen, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    desktopPages: Int = 1,
    onAddDesktopPage: (() -> Unit)? = null,
    onRemoveDesktopPage: (() -> Unit)? = null
) {
    val icon = when (screen) {
        Screen.DESKTOP -> Icons.Rounded.Widgets
        Screen.SEARCH -> Icons.Rounded.Search
        Screen.APPS -> Icons.Rounded.Apps
        Screen.FAVORITES -> Icons.Rounded.StarOutline
        Screen.HOME -> Icons.Rounded.Home
    }
    val title = when (screen) {
        Screen.APPS -> "Application List"
        else -> screen.title
    }

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(156.dp)
                .clickable { if (!enabled) onSetEnabled(screen, true) },
            shape = RoundedCornerShape(16.dp),
            color = if (enabled) FrostedGlass else FrostedGlass.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, if (enabled) CardBorder else Color(0x15FFFFFF))
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(directionLabel.uppercase(), fontSize = 9.sp, letterSpacing = 1.sp, color = Color.Gray)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        title,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        color = if (enabled) Color.White else Color.Gray
                    )
                }

                if (screen == Screen.DESKTOP && enabled && onAddDesktopPage != null && onRemoveDesktopPage != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = onRemoveDesktopPage, enabled = desktopPages > 1, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Rounded.Remove, "Remove desktop page", modifier = Modifier.size(14.dp))
                        }
                        Text("$desktopPages pg", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = onAddDesktopPage, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Rounded.Add, "Add desktop page", modifier = Modifier.size(14.dp))
                        }
                    }
                } else {
                    Text(
                        if (enabled) "Active" else "Tap to add",
                        fontSize = 10.sp,
                        color = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else Color.DarkGray
                    )
                }
            }
        }

        // Circular red deletion badge with a white × on top-right corner
        if (enabled) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(RedBadge)
                    .clickable { onSetEnabled(screen, false) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove $title",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun BottomControlsBar(
    advanced: Boolean,
    onToggleAdvanced: () -> Unit,
    onReset: () -> Unit,
    showMoreMenu: Boolean,
    onShowMoreMenu: (Boolean) -> Unit,
    navigation: NavigationPrefs,
    onUpdateNavigation: (NavigationPrefs) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = FrostedGlass,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: ... More
            Box {
                TextButton(onClick = { onShowMoreMenu(true) }) {
                    Icon(Icons.Rounded.MoreHoriz, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("More", fontSize = 12.sp)
                }

                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { onShowMoreMenu(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text("Fingers: ${if (navigation.fingers == 2) "Two fingers" else "One finger"}") },
                        onClick = {
                            onUpdateNavigation(navigation.copy(fingers = if (navigation.fingers == 2) 1 else 2))
                            onShowMoreMenu(false)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Infinite navigation: ${if (navigation.infinite) "ON" else "OFF"}") },
                        onClick = {
                            onUpdateNavigation(navigation.copy(infinite = !navigation.infinite))
                            onShowMoreMenu(false)
                        }
                    )
                    HorizontalDivider()
                    navigationPresets.forEach { (name, screens) ->
                        DropdownMenuItem(
                            text = { Text("Preset: $name") },
                            onClick = {
                                onUpdateNavigation(navigation.copy(preset = name, enabledScreens = screens + Screen.HOME))
                                onShowMoreMenu(false)
                            }
                        )
                    }
                }
            }

            // Center: Toggle button switching between Advanced Mode and Normal Mode
            FilledTonalButton(
                onClick = onToggleAdvanced,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(if (advanced) "Advanced Mode" else "Normal Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            // Right: Counter-clockwise circular arrow labeled Reset
            TextButton(onClick = onReset) {
                Icon(Icons.Rounded.RotateLeft, "Reset layout", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset", fontSize = 12.sp)
            }
        }
    }
}
