package dev.liteluncher.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liteluncher.data.ClockPrefs
import dev.liteluncher.data.GridPrefs
import dev.liteluncher.data.IconShape
import dev.liteluncher.data.LauncherPrefs
import dev.liteluncher.data.LauncherState
import dev.liteluncher.data.SurfaceTheme
import dev.liteluncher.data.ThemeMode
import dev.liteluncher.data.WebEngine
import dev.liteluncher.platform.AppEntry
import java.net.URI
import java.util.Locale

@Composable
fun SettingsScreen(
    state: LauncherState,
    apps: List<AppEntry>,
    iconPacks: List<Pair<String, String>>,
    onUpdate: ((LauncherState) -> LauncherState) -> Unit,
    onBack: () -> Unit,
    onManageScreens: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDefaultHome: () -> Unit,
    onNotificationAccess: () -> Unit,
    onUsageAccess: () -> Unit,
    onRequestContacts: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf("Settings") }
    var hiddenQuery by rememberSaveable { mutableStateOf("") }
    val savedPages = rememberSaveableStateHolder()
    val back: () -> Unit = { if (page == "Settings") onBack() else page = "Settings" }
    val update: (((LauncherPrefs) -> LauncherPrefs) -> Unit) = { transform ->
        onUpdate { current -> current.copy(prefs = transform(current.prefs)) }
    }
    BackHandler(onBack = back)
    SettingsTheme {
        savedPages.SaveableStateProvider(page) {
            SettingsFrame(title = when (page) {
                "Home" -> "Home Screen"
                "App drawer" -> "Application List"
                "Behavior" -> "Behavior & Animations"
                else -> page
            }, onBack = back) {
                when (page) {
                    "Settings" -> settingsIndex(state, { page = it }, onManageScreens, onDefaultHome)
                    "Home" -> homePreferences(state.prefs, apps, update)
                    "Dock" -> dockPreferences(state.prefs, update)
                    "App drawer" -> item {
                        SettingsSection("App drawer layout") {
                            IntSetting("Columns", state.prefs.drawer.columns, 1..10) { next -> update { it.copy(drawer = it.drawer.copy(columns = next)) } }
                            FloatSetting("Icon Size", state.prefs.drawer.iconScale, .5f..1.5f) { next -> update { it.copy(drawer = it.drawer.copy(iconScale = next)) } }
                            ToggleSetting("Show Text", state.prefs.drawer.labels) { next -> update { it.copy(drawer = it.drawer.copy(labels = next)) } }
                            FloatSetting("Text Size", state.prefs.drawer.textScale, .5f..1.5f) { next -> update { it.copy(drawer = it.drawer.copy(textScale = next)) } }
                            ToggleSetting("Multiline Labels", state.prefs.drawer.multiline) { next -> update { it.copy(drawer = it.drawer.copy(multiline = next)) } }
                            ToggleSetting("List layout", state.prefs.drawer.listMode) { next -> update { it.copy(drawer = it.drawer.copy(listMode = next)) } }
                        }
                    }
                    "Favorites" -> favoritePreferences(state, apps, update, onUpdate, onUsageAccess, onRequestContacts)
                    "Search" -> searchPreferences(state, update, onUpdate)
                    "Desktop" -> desktopPreferences(state.prefs, update)
                    "Appearance" -> appearanceSettings(state.prefs, iconPacks, update)
                    "Notification badges" -> badgeSettings(state.prefs, update, onNotificationAccess)
                    "Folders" -> folderSettings(state.prefs, update)
                    "Gestures" -> gestureSettings(state.prefs, apps, update)
                    "Behavior" -> behaviorSettings(state.prefs, update)
                    "Hidden apps" -> hiddenAppSettings(state, apps, hiddenQuery, { hiddenQuery = it }, onUpdate)
                    "Backup & restore" -> item {
                        SettingsSection("Launcher backup") {
                            SettingsNote("Export writes your launcher settings and saved layout to a file you choose. Import accepts Lite Luncher JSON backups and Lynx Launcher ZIP backups.")
                            SettingsNote("A restore replaces saved launcher data. Installed apps, widget provider data, and Android permission grants are managed by Android.")
                            SettingsNote("Lynx imports preserve grid cells, gaps, folder order, folder shortcuts, and included icon images. Tap each saved widget placeholder to reconnect it at its original size and position. Android may require widget setup or shortcut re-pinning; icon packs must be installed. Settings without an equivalent are retained in backups but may not affect this launcher.")
                            SettingsLink("Export backup", "Choose a destination using Android's document picker", onExport)
                            SettingsDivider()
                            SettingsLink("Import backup", "Choose a JSON or Lynx ZIP backup", onImport)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.settingsIndex(
    state: LauncherState,
    navigate: (String) -> Unit,
    onManageScreens: () -> Unit,
    onDefaultHome: () -> Unit
) {
    item {
        SettingsSection("Screens") {
            SettingsIndexLink("Home Screen", Icons.Rounded.Home) { navigate("Home") }
            SettingsIndexLink("Dock", Icons.Rounded.Dock) { navigate("Dock") }
            SettingsIndexLink("Application List", Icons.Rounded.Apps) { navigate("App drawer") }
            SettingsIndexLink("Favorites", Icons.Rounded.Favorite) { navigate("Favorites") }
            SettingsIndexLink("Search", Icons.Rounded.Search) { navigate("Search") }
            SettingsIndexLink("Desktop", Icons.Rounded.Widgets) { navigate("Desktop") }
            SettingsIndexLink("Screen Manager", Icons.Rounded.Dashboard, onManageScreens)
        }
    }
    item {
        SettingsSection("Customization") {
            SettingsIndexLink("Appearance", Icons.Rounded.Palette) { navigate("Appearance") }
            SettingsIndexLink("Notifications", Icons.Rounded.Notifications) { navigate("Notification badges") }
            SettingsIndexLink("Folder", Icons.Rounded.Folder) { navigate("Folders") }
            SettingsIndexLink("Gestures", Icons.Rounded.Gesture) { navigate("Gestures") }
            SettingsIndexLink("Behavior & Animations", Icons.Rounded.Tune) { navigate("Behavior") }
        }
    }
    item {
        SettingsSection("General") {
            SettingsIndexLink("Hidden Apps (${state.hiddenApps.size})", Icons.Rounded.VisibilityOff) { navigate("Hidden apps") }
            SettingsIndexLink("Backup & Restore", Icons.Rounded.Save) { navigate("Backup & restore") }
            SettingsIndexLink("Change Launcher App", Icons.Rounded.Home, onDefaultHome)
        }
    }
}

@Composable
private fun SettingsIndexLink(title: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 14.sp)
    }
}

private fun LazyListScope.appearanceSettings(
    prefs: LauncherPrefs,
    iconPacks: List<Pair<String, String>>,
    update: ((LauncherPrefs) -> LauncherPrefs) -> Unit
) {
    item {
        val appearance = prefs.appearance
        SettingsSection("Theme") {
            val themes = SurfaceTheme.entries.map { it to it.name.settingLabel() }
            ChoiceSetting("Launcher theme", appearance.launcher, themes) { next -> update { it.copy(appearance = it.appearance.copy(launcher = next)) } }
            ChoiceSetting("Dock theme", appearance.dock, themes) { next -> update { it.copy(appearance = it.appearance.copy(dock = next)) } }
            ChoiceSetting("Search bar theme", appearance.search, themes) { next -> update { it.copy(appearance = it.appearance.copy(search = next)) } }
        }
    }
    item {
        val appearance = prefs.appearance
        SettingsSection("Dark Mode") {
            ChoiceSetting("Launcher mode", appearance.mode, ThemeMode.entries.map { it to it.name.settingLabel() }) { next ->
                update { it.copy(appearance = it.appearance.copy(mode = next)) }
            }
            ToggleSetting("Dark texts on screens", appearance.darkTexts) { next ->
                update { it.copy(appearance = it.appearance.copy(darkTexts = next)) }
            }
        }
    }
    item {
        val appearance = prefs.appearance
        SettingsSection("Background") {
            ChoiceSetting("Dim Background", appearance.dimMode, listOf("Follow Theme", "Custom", "Off").map { it to it }) { next ->
                update { it.copy(appearance = it.appearance.copy(dimMode = next)) }
            }
            FloatSetting("Wallpaper dim", appearance.dim, 0f..1f) { next -> update { it.copy(appearance = it.appearance.copy(dim = next)) } }
            ColorSetting("Background Color", appearance.backgroundColor) { next ->
                update { it.copy(appearance = it.appearance.copy(backgroundColor = next)) }
            }
            ValidatedTextSetting("Accent color", appearance.accent, "#RRGGBB or #AARRGGBB", ::colorError,
                normalize = { it.trim().uppercase(Locale.ROOT) }) { next ->
                update { it.copy(appearance = it.appearance.copy(accent = next)) }
            }
        }
    }
    item {
        val appearance = prefs.appearance
        SettingsSection("Icons") {
            val packs = listOf("" to "System Theme") + iconPacks.distinctBy { it.first }
            val choices = if (appearance.iconPack.isNotBlank() && packs.none { it.first == appearance.iconPack }) {
                packs + (appearance.iconPack to "Unavailable pack (${appearance.iconPack})")
            } else packs
            ChoiceSetting("Theme selector", appearance.iconPack, choices) { next -> update { it.copy(appearance = it.appearance.copy(iconPack = next)) } }
            ToggleSetting("Force adaptive icons", appearance.forceAdaptive) { next -> update { it.copy(appearance = it.appearance.copy(forceAdaptive = next)) } }
            ChoiceSetting("Shape selector", appearance.iconShape, listOf(IconShape.SQUIRCLE, IconShape.CIRCLE, IconShape.ROUNDED_SQUARE, IconShape.TEARDROP).map { it to it.name.settingLabel() }) { next ->
                update { it.copy(appearance = it.appearance.copy(iconShape = next)) }
            }
        }
    }
    item {
        val appearance = prefs.appearance
        SettingsSection("System Bars") {
            ChoiceSetting("Dark status bar icons", appearance.statusIcons, listOf("Follow Theme", "Always dark", "Always light").map { it to it }) { next ->
                update { it.copy(appearance = it.appearance.copy(statusIcons = next, lightStatusIcons = next != "Always dark")) }
            }
            ChoiceSetting("Dark navigation bar icons", appearance.navigationIcons, listOf("Follow Theme", "Always dark", "Always light").map { it to it }) { next ->
                update { it.copy(appearance = it.appearance.copy(navigationIcons = next, lightNavigationIcons = next != "Always dark")) }
            }
            ToggleSetting("Custom Color", appearance.customBarColor) { next ->
                update { it.copy(appearance = it.appearance.copy(customBarColor = next)) }
            }
            ColorSetting("Status Bar Color", appearance.statusColor) { next ->
                update { it.copy(appearance = it.appearance.copy(statusColor = next)) }
            }
            ColorSetting("Navigation Bar Color", appearance.navigationColor) { next ->
                update { it.copy(appearance = it.appearance.copy(navigationColor = next)) }
            }
        }
    }
}

private fun LazyListScope.badgeSettings(
    prefs: LauncherPrefs,
    update: ((LauncherPrefs) -> LauncherPrefs) -> Unit,
    onNotificationAccess: () -> Unit
) {
    item {
        val badge = prefs.badges
        val context = androidx.compose.ui.platform.LocalContext.current
        SettingsSection("Notifications") {
            ToggleSetting("Show Notifications", badge.enabled) { next -> update { it.copy(badges = it.badges.copy(enabled = next)) } }
            SettingsLink("Notification access", "Allow Lite Luncher to read active notifications for badges", onNotificationAccess)
            ChoiceSetting("Badge Style", badge.style, listOf("Follow Theme", "Dot", "Count").map { it to it }) { next ->
                update { it.copy(badges = it.badges.copy(style = next, counts = next == "Count")) }
            }
            ColorSetting("Badge Color", badge.color) { next -> update { it.copy(badges = it.badges.copy(color = next)) } }
            FloatSetting("Badge Size", badge.scale, .5f..1.5f) { next -> update { it.copy(badges = it.badges.copy(scale = next)) } }
            ToggleSetting("Show notification count", badge.counts, "Show a number instead of a dot.") { next -> update { it.copy(badges = it.badges.copy(counts = next, style = if (next) "Count" else "Dot")) } }
            TextButton(onClick = { dev.liteluncher.platform.LauncherNotificationService.refresh(context) }) { Text("Refresh badges") }
        }
    }
}

private fun LazyListScope.folderSettings(prefs: LauncherPrefs, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit) {
    item {
        val folder = prefs.folders
        SettingsSection("Folders") {
            ChoiceSetting("Folder Background", folder.background, listOf("Follow Theme", "Custom").map { it to it }) { next ->
                update { it.copy(folders = it.folders.copy(background = next)) }
            }
            ColorSetting("Background Color", folder.color) { next -> update { it.copy(folders = it.folders.copy(color = next)) } }
            ChoiceSetting("Folder preview", folder.stacked, listOf(true to "Stacked", false to "Grid")) { next ->
                update { it.copy(folders = it.folders.copy(stacked = next)) }
            }
            ChoiceSetting("Shape selector", folder.shape, listOf(IconShape.SQUIRCLE, IconShape.CIRCLE, IconShape.ROUNDED_SQUARE, IconShape.TEARDROP).map { it to it.name.settingLabel() }) { next ->
                update { it.copy(folders = it.folders.copy(shape = next)) }
            }
            ColorSetting("Icon color", folder.iconColor) { next -> update { it.copy(folders = it.folders.copy(iconColor = next)) } }
            FloatSetting("Icon Size", folder.iconScale, .5f..1.5f) { next -> update { it.copy(folders = it.folders.copy(iconScale = next)) } }
            ToggleSetting("Show Text", folder.labels) { next -> update { it.copy(folders = it.folders.copy(labels = next)) } }
            FloatSetting("Text Size", folder.textScale, .5f..1.5f) { next -> update { it.copy(folders = it.folders.copy(textScale = next)) } }
            ToggleSetting("Multiline Labels", folder.multiline) { next -> update { it.copy(folders = it.folders.copy(multiline = next)) } }
        }
    }
}

internal fun LazyListScope.behaviorSettings(prefs: LauncherPrefs, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit) {
    item {
        val behavior = prefs.behavior
        SettingsSection("Behavior & Animations") {
            ToggleSetting("Screen rotation (Tablet-only restriction)", behavior.lockRotation, "Lock rotation to portrait on phones; tablets allow rotation.") { next ->
                update { it.copy(behavior = it.behavior.copy(lockRotation = next)) }
            }
            ToggleSetting("Hide Status Bar", !behavior.showStatusBar) { next ->
                update { it.copy(behavior = it.behavior.copy(showStatusBar = !next)) }
            }
            ToggleSetting("Return to home screen on exit", behavior.returnHome) { next ->
                update { it.copy(behavior = it.behavior.copy(returnHome = next)) }
            }
            ToggleSetting("Open settings via context menu", behavior.contextSettings) { next ->
                update { it.copy(behavior = it.behavior.copy(contextSettings = next)) }
            }
            ChoiceSetting("Scroll Wallpaper", behavior.wallpaperScroll, listOf("Off" to "Off", "Parallax" to "Parallax", "Horizontal" to "Horizontal")) { next ->
                update { it.copy(behavior = it.behavior.copy(wallpaperScroll = next)) }
            }
            ChoiceSetting("Alignment", behavior.wallpaperAlignment, listOf("Center" to "Center", "Left" to "Left", "Right" to "Right")) { next ->
                update { it.copy(behavior = it.behavior.copy(wallpaperAlignment = next)) }
            }
            ChoiceSetting("Horizontal screen transitions", behavior.horizontalTransition, listOf("Slide", "Fade", "None").map { it to it }) { next ->
                update { it.copy(behavior = it.behavior.copy(horizontalTransition = next)) }
            }
            ChoiceSetting("Vertical screen transitions", behavior.verticalTransition, listOf("Fade", "Slide", "None").map { it to it }) { next ->
                update { it.copy(behavior = it.behavior.copy(verticalTransition = next)) }
            }
            ToggleSetting("Show screen borders when scrolling", behavior.borders) { next ->
                update { it.copy(behavior = it.behavior.copy(borders = next)) }
            }
            ToggleSetting("Show page indicators", behavior.pageIndicators) { next ->
                update { it.copy(behavior = it.behavior.copy(pageIndicators = next)) }
            }
        }
    }
}

private fun LazyListScope.hiddenAppSettings(
    state: LauncherState,
    apps: List<AppEntry>,
    query: String,
    onQuery: (String) -> Unit,
    onUpdate: ((LauncherState) -> LauncherState) -> Unit
) {
    item {
        SettingsSection("Visible") {
            SettingsNote("Unchecked apps below are excluded (hidden) from the launcher. Checked apps remain visible.")
            Text("${apps.size - state.hiddenApps.size} visible · ${state.hiddenApps.size} hidden", color = SettingsGreen)
            TextButton(onClick = { onUpdate { it.copy(hiddenApps = emptySet()) } }, enabled = state.hiddenApps.isNotEmpty()) {
                Text("Make all visible")
            }
        }
    }
    item {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(),
            label = { Text("Filter apps") }, singleLine = true)
    }
    val installed = apps.distinctBy { it.key }.sortedBy { it.label.lowercase(Locale.ROOT) }
    val availableKeys = installed.map { it.key }.toSet()
    val entries = (installed.map {
        HiddenAppChoice(it.key, it.label, "${it.packageName} · ${if (it.user == android.os.Process.myUserHandle()) "Personal" else "Another profile"}")
    } + (state.hiddenApps - availableKeys).sorted().map { HiddenAppChoice(it, "Unavailable app", it) })
        .filter { query.isBlank() || it.label.contains(query.trim(), true) || it.detail.contains(query.trim(), true) }
    if (entries.isEmpty()) item { SettingsNote("No matching apps.") }
    items(entries, key = { "hidden:${it.key}" }) { app ->
        val visible = app.key !in state.hiddenApps
        val toggle: () -> Unit = {
            onUpdate { current ->
                current.copy(hiddenApps = if (app.key in current.hiddenApps) current.hiddenApps - app.key else current.hiddenApps + app.key)
            }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(visible, onCheckedChange = { toggle() })
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(app.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class HiddenAppChoice(val key: String, val label: String, val detail: String)
