package dev.liteluncher.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.liteluncher.data.Action
import dev.liteluncher.data.Gesture
import dev.liteluncher.data.GestureBinding
import dev.liteluncher.data.LauncherPrefs
import dev.liteluncher.data.defaultGestures
import dev.liteluncher.platform.AppEntry
import java.util.Locale
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun LazyListScope.gestureSettings(
    prefs: LauncherPrefs,
    apps: List<AppEntry>,
    update: ((LauncherPrefs) -> LauncherPrefs) -> Unit
) {
    item {
        SettingsSection("Gesture actions") {
            SettingsNote("Choose a launcher action, an installed app, or an app's native shortcut for each gesture. App targets keep their activity and Android profile identity.")
            SettingsNote("Screen navigation also follows the finger count selected in Manage screens.")
            TextButton(onClick = { update { it.copy(gestures = defaultGestures()) } }) {
                Text("Restore default gestures")
            }
        }
    }
    items(Gesture.entries, key = { it.name }) { gesture ->
        GestureSetting(gesture, prefs.gestures[gesture] ?: GestureBinding(), apps) { binding ->
            update { it.copy(gestures = it.gestures + (gesture to binding)) }
        }
    }
}

@Composable
private fun GestureSetting(
    gesture: Gesture,
    binding: GestureBinding,
    apps: List<AppEntry>,
    onSave: (GestureBinding) -> Unit
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val app = apps.firstOrNull { it.key == binding.target }
    val description = when (binding.action) {
        Action.APP -> app?.label ?: "Unavailable app · choose another app"
        Action.SHORTCUT -> "${app?.label ?: "Unavailable app"} · App shortcut"
        else -> binding.action.actionLabel()
    }
    SettingsSection(gesture.gestureLabel()) {
        SettingsLink("Assigned action", description) { editing = true }
    }
    if (editing) {
        GestureBindingDialog(gesture, binding, apps, onDismiss = { editing = false }) {
            onSave(it)
            editing = false
        }
    }
}

@Composable
private fun GestureBindingDialog(
    gesture: Gesture,
    binding: GestureBinding,
    apps: List<AppEntry>,
    onDismiss: () -> Unit,
    onSave: (GestureBinding) -> Unit
) {
    var action by rememberSaveable { mutableStateOf(binding.action) }
    var target by rememberSaveable { mutableStateOf(binding.target) }
    var shortcutId by rememberSaveable { mutableStateOf(binding.shortcutId) }
    var choosingApp by rememberSaveable { mutableStateOf(false) }
    var choosingShortcut by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = SettingsPanel,
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(gesture.gestureLabel(), style = MaterialTheme.typography.titleMedium)
                Action.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable {
                        action = option
                        if (option != Action.APP && option != Action.SHORTCUT) {
                            target = ""
                            shortcutId = ""
                        }
                    }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = action == option, onClick = {
                            action = option
                            if (option != Action.APP && option != Action.SHORTCUT) {
                                target = ""
                                shortcutId = ""
                            }
                        })
                        Text(option.actionLabel(), modifier = Modifier.weight(1f))
                    }
                }
                if (action == Action.APP) {
                    val label = apps.firstOrNull { it.key == target }?.label ?: "Choose an app"
                    Button(onClick = { choosingApp = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(label)
                    }
                }
                if (action == Action.SHORTCUT) {
                    val label = apps.firstOrNull { it.key == target }?.label ?: "Choose an app for shortcuts"
                    Button(onClick = { choosingShortcut = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (shortcutId.isBlank()) label else "$label · Shortcut selected")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        val valid = when (action) {
                            Action.APP -> target.isNotBlank()
                            Action.SHORTCUT -> target.isNotBlank() && shortcutId.isNotBlank()
                            else -> true
                        }
                        if (valid) onSave(GestureBinding(action, target, shortcutId))
                    }) { Text("Save") }
                }
            }
        }
    }

    if (choosingApp) {
        AppChooserDialog(apps, target, onDismiss = { choosingApp = false }) { selected ->
            target = selected.key
            shortcutId = ""
            choosingApp = false
        }
    }
    if (choosingShortcut) {
        AppShortcutChooserDialog(apps, target, onDismiss = { choosingShortcut = false }) { app, shortcut ->
            target = app.key
            shortcutId = shortcut
            choosingShortcut = false
        }
    }
}

@Composable
private fun AppChooserDialog(
    apps: List<AppEntry>,
    selectedKey: String,
    onDismiss: () -> Unit,
    onSelect: (AppEntry) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        .sortedBy { it.label.lowercase(Locale.ROOT) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = SettingsPanel,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.8f)) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                    label = { Text("Filter apps") }, singleLine = true)
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    items(filtered, key = { it.key }) { app ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(app) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = app.key == selectedKey, onClick = { onSelect(app) })
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text("${app.packageName} · ${if (app.user == android.os.Process.myUserHandle()) "Personal" else "Another profile"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun AppShortcutChooserDialog(
    apps: List<AppEntry>,
    selectedAppKey: String,
    onDismiss: () -> Unit,
    onSelect: (AppEntry, String) -> Unit
) {
    var pickedApp by remember { mutableStateOf(apps.firstOrNull { it.key == selectedAppKey }) }
    val context = LocalContext.current
    val selectedApp = pickedApp
    val shortcuts by key(selectedApp?.key) { produceState<List<ShortcutInfo>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            val app = selectedApp ?: return@withContext emptyList()
            val launcher = context.getSystemService(LauncherApps::class.java) ?: return@withContext emptyList()
            runCatching {
                val query = LauncherApps.ShortcutQuery().apply {
                    setPackage(app.packageName)
                    setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                }
                launcher.getShortcuts(query, app.user).orEmpty()
            }.getOrDefault(emptyList())
        }
    } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = SettingsPanel,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.8f)) {
            Column(Modifier.padding(16.dp)) {
                if (selectedApp == null) {
                    Text("Choose an app", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(Modifier.weight(1f)) {
                        items(apps.sortedBy { it.label.lowercase(Locale.ROOT) }, key = { it.key }) { app ->
                            Text(app.label, modifier = Modifier.fillMaxWidth().clickable { pickedApp = app }.padding(vertical = 10.dp))
                        }
                    }
                } else {
                    Text(selectedApp.label, style = MaterialTheme.typography.titleMedium)
                    if (shortcuts.isEmpty()) {
                        Text("No shortcuts available for this app.", modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(shortcuts, key = { it.id }) { shortcut ->
                                Text(shortcut.shortLabel?.toString() ?: shortcut.id,
                                    modifier = Modifier.fillMaxWidth().clickable { onSelect(selectedApp, shortcut.id) }.padding(vertical = 10.dp))
                            }
                        }
                    }
                    TextButton(onClick = { pickedApp = null }) { Text("Choose another app") }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

private fun Gesture.gestureLabel(): String = when (this) {
    Gesture.HOME -> "Home Button"
    Gesture.DOUBLE_TAP -> "Double Tap"
    Gesture.SWIPE_DOWN -> "Swipe down"
    Gesture.SWIPE_UP -> "Swipe up"
    Gesture.SWIPE_LEFT -> "Swipe left"
    Gesture.SWIPE_RIGHT -> "Swipe right"
    Gesture.TWO_DOWN -> "Swipe down (Two Fingers)"
    Gesture.TWO_UP -> "Swipe up (Two Fingers)"
    Gesture.TWO_LEFT -> "Swipe left (Two Fingers)"
    Gesture.TWO_RIGHT -> "Swipe right (Two Fingers)"
    Gesture.PINCH_IN -> "Zoom in"
    Gesture.PINCH_OUT -> "Zoom out"
}

private fun Action.actionLabel(): String = when (this) {
    Action.NONE -> "Nothing"
    Action.NOTIFICATIONS -> "Notifications"
    Action.NAVIGATE -> "Directional navigation (swipes)"
    Action.HOME -> "Open Home"
    Action.SEARCH -> "Open Search"
    Action.APPS -> "Open app drawer"
    Action.DESKTOP -> "Open Desktop"
    Action.FAVORITES -> "Open Favorites"
    Action.SETTINGS -> "Open settings"
    Action.SCREEN_MANAGER -> "Manage screens"
    Action.WALLPAPER -> "Choose wallpaper"
    Action.APP -> "Launch app"
    Action.SHORTCUT -> "Launch native app shortcut"
}
