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
    var actionName by rememberSaveable(gesture.name) { mutableStateOf(binding.action.name) }
    var target by rememberSaveable(gesture.name) { mutableStateOf(binding.target) }
    var shortcutId by rememberSaveable(gesture.name) { mutableStateOf(binding.shortcutId) }
    var pickingApp by rememberSaveable { mutableStateOf(false) }
    val action = Action.valueOf(actionName)
    val selectedApp = apps.firstOrNull { it.key == target }
    val needsApp = action == Action.APP || action == Action.SHORTCUT
    val context = LocalContext.current
    val launcher = remember(context) { context.getSystemService(LauncherApps::class.java) }
    val shortcuts by produceState<List<ShortcutInfo>>(emptyList(), selectedApp?.key, action) {
        value = if (action == Action.SHORTCUT && selectedApp != null) withContext(Dispatchers.IO) {
            runCatching {
                if (launcher?.hasShortcutHostPermission() != true) emptyList() else launcher.getShortcuts(
                    LauncherApps.ShortcutQuery().apply {
                        setPackage(selectedApp.packageName)
                        setActivity(selectedApp.component)
                        setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                    }, selectedApp.user
                ).orEmpty().filter { it.isEnabled }
            }.getOrDefault(emptyList())
        } else emptyList()
    }
    val valid = (!needsApp || selectedApp != null) && (action != Action.SHORTCUT || shortcuts.any { it.id == shortcutId })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(gesture.gestureLabel()) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val directional = gesture in setOf(Gesture.SWIPE_UP, Gesture.SWIPE_DOWN, Gesture.SWIPE_LEFT, Gesture.SWIPE_RIGHT, Gesture.TWO_UP, Gesture.TWO_DOWN, Gesture.TWO_LEFT, Gesture.TWO_RIGHT)
                ChoiceSetting("Action", action, Action.entries.filter { directional || it != Action.NAVIGATE }.map { it to it.actionLabel() }) {
                    actionName = it.name
                }
                if (needsApp) {
                    SettingsLink("App", selectedApp?.label
                        ?: "Choose an installed app") { pickingApp = true }
                    if (apps.isEmpty()) SettingsNote("No launchable apps are currently available. Unlock the app's profile and try again.")
                }
                if (action == Action.SHORTCUT) {
                    if (shortcuts.isNotEmpty()) ChoiceSetting("App shortcut", shortcutId,
                        shortcuts.map { it.id to (it.shortLabel?.toString() ?: it.id) }) { shortcutId = it }
                    else SettingsNote("This app has no available shortcuts. Set Lite Luncher as your default home app to allow shortcut access, then choose an app that publishes shortcuts.")
                }
                if (action == Action.NONE) SettingsNote("This gesture is disabled. Choose directional navigation to move between screens.")
            }
        },
        confirmButton = {
            Button(onClick = {
                val pinned = if (action == Action.SHORTCUT && selectedApp != null) runCatching {
                    val previous = launcher?.getShortcuts(LauncherApps.ShortcutQuery().apply {
                        setPackage(selectedApp.packageName)
                        setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                    }, selectedApp.user).orEmpty().map { it.id }
                    requireNotNull(launcher).pinShortcuts(selectedApp.packageName, (previous + shortcutId).distinct(), selectedApp.user)
                    true
                }.getOrDefault(false) else true
                if (!pinned) android.widget.Toast.makeText(context, "This shortcut could not be pinned. Set Lite Luncher as your default home app.", android.widget.Toast.LENGTH_LONG).show()
                if (valid && pinned) onSave(GestureBinding(
                    action = action,
                    target = if (needsApp) target else "",
                    shortcutId = if (action == Action.SHORTCUT) shortcutId.trim() else ""
                ))
            }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (pickingApp) {
        AppChoiceDialog(apps, target, onDismiss = { pickingApp = false }) {
            target = it.key
            pickingApp = false
        }
    }
}

@Composable
private fun AppChoiceDialog(
    apps: List<AppEntry>,
    selectedKey: String,
    onDismiss: () -> Unit,
    onSelect: (AppEntry) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) {
        apps.distinctBy { it.key }.filter {
            query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
        }.sortedBy { it.label.lowercase(Locale.ROOT) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = SettingsPanel,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.85f)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose app", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                    label = { Text("Filter apps") }, singleLine = true)
                LazyColumn(Modifier.weight(1f)) {
                    if (filtered.isEmpty()) item { SettingsNote("No matching apps.") }
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

private fun Gesture.gestureLabel(): String = when (this) {
    Gesture.HOME -> "Home button"
    Gesture.DOUBLE_TAP -> "Double tap"
    Gesture.SWIPE_UP -> "One finger · swipe up"
    Gesture.SWIPE_DOWN -> "One finger · swipe down"
    Gesture.SWIPE_LEFT -> "One finger · swipe left"
    Gesture.SWIPE_RIGHT -> "One finger · swipe right"
    Gesture.TWO_UP -> "Two fingers · swipe up"
    Gesture.TWO_DOWN -> "Two fingers · swipe down"
    Gesture.TWO_LEFT -> "Two fingers · swipe left"
    Gesture.TWO_RIGHT -> "Two fingers · swipe right"
    Gesture.PINCH_IN -> "Pinch in"
    Gesture.PINCH_OUT -> "Pinch out"
}

private fun Action.actionLabel(): String = when (this) {
    Action.NONE -> "No assigned action"
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
