package dev.liteluncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liteluncher.data.*
import dev.liteluncher.platform.AppEntry
import dev.liteluncher.platform.AppsRepository

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
fun ManageLayoutScreen(state: LauncherState, apps: List<AppEntry>, repository: AppsRepository, update: ((LauncherState) -> LauncherState) -> Unit, onBack: () -> Unit) {
    val navigation = state.prefs.navigation
    var advanced by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    val setEnabled: (Screen, Boolean) -> Unit = { screen, enabled ->
        if (screen != Screen.HOME) {
            update { current ->
                val previous = current.prefs.navigation
                val screens = (if (enabled) previous.enabledScreens + screen else previous.enabledScreens - screen) + Screen.HOME
                current.copy(prefs = current.prefs.copy(navigation = previous.copy(
                    enabledScreens = screens, preset = "Custom"
                )))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text("Manage layout", fontSize = 22.sp, modifier = Modifier.weight(1f))
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ScreenCard(
                    screen = Screen.HOME, title = "Home", icon = Icons.Rounded.Home,
                    enabled = true, onToggle = {}, isCenter = true
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Spacer(Modifier.width(110.dp))
                ScreenCard(
                    screen = Screen.SEARCH, title = "Search", icon = Icons.Rounded.Search,
                    enabled = Screen.SEARCH in navigation.enabledScreens,
                    onToggle = { setEnabled(Screen.SEARCH, it) },
                    isCenter = false
                )
                Spacer(Modifier.width(110.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ScreenCard(
                    screen = Screen.DESKTOP, title = "Desktop", icon = Icons.Rounded.Widgets,
                    enabled = Screen.DESKTOP in navigation.enabledScreens,
                    onToggle = { setEnabled(Screen.DESKTOP, it) },
                    isCenter = false
                )
                Spacer(Modifier.weight(1f))
                ScreenCard(
                    screen = Screen.APPS, title = "App List", icon = Icons.Rounded.Apps,
                    enabled = Screen.APPS in navigation.enabledScreens,
                    onToggle = { setEnabled(Screen.APPS, it) },
                    isCenter = false
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Spacer(Modifier.width(110.dp))
                ScreenCard(
                    screen = Screen.FAVORITES, title = "Favorites", icon = Icons.Rounded.StarOutline,
                    enabled = Screen.FAVORITES in navigation.enabledScreens,
                    onToggle = { setEnabled(Screen.FAVORITES, it) },
                    isCenter = false
                )
                Spacer(Modifier.width(110.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = FrostedGlass,
            border = BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    TextButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Rounded.MoreHoriz, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("More", fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Fingers: ${if (navigation.fingers == 2) "Two fingers" else "One finger"}") },
                            onClick = {
                                update { current ->
                                    current.copy(prefs = current.prefs.copy(navigation = current.prefs.navigation.copy(
                                        fingers = if (navigation.fingers == 2) 1 else 2
                                    )))
                                }
                                showMoreMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Infinite navigation: ${if (navigation.infinite) "ON" else "OFF"}") },
                            onClick = {
                                update { current ->
                                    current.copy(prefs = current.prefs.copy(navigation = current.prefs.navigation.copy(
                                        infinite = !navigation.infinite
                                    )))
                                }
                                showMoreMenu = false
                            }
                        )
                        HorizontalDivider()
                        navigationPresets.forEach { (name, screens) ->
                            DropdownMenuItem(
                                text = { Text("Preset: $name") },
                                onClick = {
                                    update { current ->
                                        current.copy(prefs = current.prefs.copy(navigation = current.prefs.navigation.copy(
                                            preset = name, enabledScreens = screens + Screen.HOME
                                        )))
                                    }
                                    showMoreMenu = false
                                }
                            )
                        }
                    }
        }
                FilledTonalButton(
                    onClick = { advanced = !advanced },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(if (advanced) "Advanced Mode" else "Normal Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                TextButton(onClick = {
                    update { current ->
                        current.copy(prefs = current.prefs.copy(navigation = NavigationPrefs()))
                    }
                }) {
                    Icon(Icons.Rounded.RotateLeft, "Reset layout", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset", fontSize = 12.sp)
                }
    }
}
}
}

@Composable
private fun ScreenCard(
    screen: Screen,
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    isCenter: Boolean = false
) {
    Column(
        modifier = Modifier.size(110.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Surface(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(BorderStroke(1.dp, CardBorder)),
                color = if (enabled) FrostedGlass else Color(0x40222222),
                contentColor = Color.White
            ) {
                Icon(icon, null, modifier = Modifier.size(40.dp), tint = if (enabled) Color.White else Color.White.copy(alpha = .4f))
            }
            if (!isCenter) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = { onToggle(!enabled) },
                        modifier = Modifier.size(24.dp).background(RedBadge, CircleShape)
                    ) {
                        Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FolderEditor(folder: FolderItem, apps: List<AppEntry>, onSave: (FolderItem) -> Unit, onDismiss: () -> Unit) {
    var name by remember(folder.id) { mutableStateOf(folder.name) }
    var selected by remember(folder.id) { mutableStateOf(folder.apps) }
    var filter by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit folder") }, text = {
        Column {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Folder name") }, singleLine = true)
            OutlinedTextField(filter, { filter = it.take(80) }, label = { Text("Filter applications") }, singleLine = true, modifier = Modifier.padding(top = 10.dp))
            Text("${selected.size} selected", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(apps.filter { it.label.contains(filter, true) }, key = { it.key }) { app ->
                    Row(Modifier.fillMaxWidth().clickable { selected = if (app.key in selected) selected - app.key else selected + app.key }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(app.key in selected, { checked -> selected = if (checked) (selected + app.key).distinct() else selected - app.key })
                        Text(app.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(folder.copy(name = name.trim(), apps = selected.distinct())) }, enabled = name.isNotBlank()) { Text("Save folder") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
