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

/** All writes are transformations of the latest state supplied by the host. */
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
            SettingsFrame(title = page, onBack = back) {
                when (page) {
                    "Settings" -> settingsIndex(state, { page = it }, onManageScreens, onDefaultHome)
                    "Home" -> homeSettings(state.prefs, update)
                    "Dock" -> dockSettings(state.prefs, update)
                    "App drawer" -> item {
                        GridSettings("App drawer layout", state.prefs.drawer) { change ->
                            update { it.copy(drawer = change(it.drawer)) }
                        }
                    }
                    "Favorites" -> favoritesSettings(state.prefs, update, onUsageAccess, onRequestContacts)
                    "Search" -> searchSettings(state, update, onUpdate, onRequestContacts)
                    "Desktop" -> desktopSettings(state.prefs, update)
                    "Appearance" -> appearanceSettings(state.prefs, iconPacks, update)
                    "Notification badges" -> badgeSettings(state.prefs, update, onNotificationAccess)
                    "Folders" -> folderSettings(state.prefs, update)
                    "Gestures" -> gestureSettings(state.prefs, apps, update)
                    "Behavior" -> behaviorSettings(state.prefs, update)
                    "Hidden apps" -> hiddenAppSettings(state, apps, hiddenQuery, { hiddenQuery = it }, onUpdate)
                    "Backup & restore" -> item {
                        SettingsSection("Launcher backup") {
                            SettingsNote("Export writes your launcher settings and saved layout to a file you choose. Import opens the system file picker to restore a backup.")
                            SettingsNote("A restore replaces saved launcher data. Installed apps, widget provider data, and Android permission grants are managed by Android.")
                            SettingsLink("Export backup", "Choose a destination using Android's document picker", onExport)
                            SettingsDivider()
                            SettingsLink("Import backup", "Choose a saved launcher backup", onImport)
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
        SettingsSection("Make Lynx your home") {
            SettingsLink("Default home app", "Open Android's default launcher settings", onDefaultHome)
        }
    }
    item {
        ExpandableCategory("Screens", "Layouts, clock, dock and screen navigation") {
            SettingsLink("Home", "Grid, labels and clock") { navigate("Home") }
            SettingsLink("Dock", "Placement, size and folders") { navigate("Dock") }
            SettingsLink("App drawer", "Grid and list layout") { navigate("App drawer") }
            SettingsLink("Favorites", "Usage, contacts and layout") { navigate("Favorites") }
            SettingsLink("Search", "Sources, search engine and results") { navigate("Search") }
            SettingsLink("Desktop", "Widget area and grid") { navigate("Desktop") }
            SettingsLink("Manage screens", "${state.prefs.navigation.enabledScreens.size} screens enabled · directional layout", onManageScreens)
        }
    }
    item {
        ExpandableCategory("Customization", "Themes, icons, badges and folders") {
            SettingsLink("Appearance", "Independent surface themes and icon pack") { navigate("Appearance") }
            SettingsLink("Notification badges", "Notification access, counts and color") { navigate("Notification badges") }
            SettingsLink("Folders", "Live preview and folder appearance") { navigate("Folders") }
        }
    }
    item {
        ExpandableCategory("Controls", "Gestures and launcher behavior") {
            SettingsLink("Gestures", "Actions, apps and native app shortcuts") { navigate("Gestures") }
            SettingsLink("Behavior", "System bars, wallpaper and transitions") { navigate("Behavior") }
        }
    }
    item {
        ExpandableCategory("Apps & data", "Privacy and backup") {
            SettingsLink("Hidden apps", "${state.hiddenApps.size} apps hidden") { navigate("Hidden apps") }
            SettingsLink("Backup & restore", "Export or import with Android's document picker") { navigate("Backup & restore") }
        }
    }
}

@Composable
private fun ExpandableCategory(title: String, description: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    SettingsSection(title) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(description, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title")
        }
        if (expanded) content()
    }
}

@Composable
internal fun GridSettings(title: String, value: GridPrefs, onChange: ((GridPrefs) -> GridPrefs) -> Unit) {
    SettingsSection(title) {
        IntSetting("Columns", value.columns, 1..10) { next -> onChange { it.copy(columns = next) } }
        IntSetting("Rows", value.rows, 1..16) { next -> onChange { it.copy(rows = next) } }
        FloatSetting("Icon size", value.iconScale, .5f..1.5f) { next -> onChange { it.copy(iconScale = next) } }
        ToggleSetting("App labels", value.labels) { next -> onChange { it.copy(labels = next) } }
        ToggleSetting("Multiline labels", value.multiline) { next -> onChange { it.copy(multiline = next) } }
        ToggleSetting("List layout", value.listMode, "Display apps in a vertical list.") { next ->
            onChange { it.copy(listMode = next) }
        }
        IntSetting("Outer padding", value.padding, 0..64, " dp") { next -> onChange { it.copy(padding = next) } }
        IntSetting("Item spacing", value.spacing, 0..48, " dp") { next -> onChange { it.copy(spacing = next) } }
    }
}

private fun LazyListScope.homeSettings(prefs: LauncherPrefs, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit) {
    item {
        GridSettings("Home layout", prefs.home) { change -> update { it.copy(home = change(it.home)) } }
    }
    item {
        val clock = prefs.clock
        SettingsSection("Clock & date") {
            ClockPreview(clock)
            ToggleSetting("Show clock", clock.enabled) { next -> update { it.copy(clock = it.clock.copy(enabled = next)) } }
            ToggleSetting("24-hour time", clock.use24Hour) { next -> update { it.copy(clock = it.clock.copy(use24Hour = next)) } }
            ToggleSetting("Show year", clock.showYear) { next -> update { it.copy(clock = it.clock.copy(showYear = next)) } }
            IntSetting("Clock text size", clock.size, 24..140, " sp") { next -> update { it.copy(clock = it.clock.copy(size = next)) } }
            ChoiceSetting("Clock font", clock.font,
                listOf("Light" to "Light sans serif", "Bold" to "Bold sans serif", "Serif" to "Serif", "Mono" to "Monospace")) { next ->
                update { it.copy(clock = it.clock.copy(font = next)) }
            }
            ValidatedTextSetting("Clock color", clock.color, "#RRGGBB or #AARRGGBB", ::colorError,
                normalize = { it.trim().uppercase(Locale.ROOT) }) { next ->
                update { it.copy(clock = it.clock.copy(color = next)) }
            }
        }
    }
}

@Composable
private fun ClockPreview(clock: ClockPrefs) {
    val family = when (clock.font) {
        "Serif" -> FontFamily.Serif
        "Mono" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    val weight = when (clock.font) {
        "Bold" -> FontWeight.Bold
        else -> FontWeight.ExtraLight
    }
    Surface(shape = RoundedCornerShape(16.dp), color = SettingsBackground,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (clock.use24Hour) "21:41" else "9:41", color = settingsColor(clock.color),
                fontFamily = family, fontWeight = weight, fontSize = clock.size.coerceIn(24, 64).sp,
                maxLines = 1)
            Text(if (clock.showYear) "Tuesday, October 27, 2026" else "Tuesday, October 27",
                color = settingsColor(clock.color), style = MaterialTheme.typography.bodySmall)
            SettingsNote("Typography preview · size capped here to fit")
        }
    }
}

private fun LazyListScope.dockSettings(prefs: LauncherPrefs, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit) {
    item {
        val dock = prefs.dock
        SettingsSection("Dock placement") {
            ToggleSetting("Show dock", dock.enabled) { next -> update { it.copy(dock = it.dock.copy(enabled = next)) } }
            ChoiceSetting("Side", dock.side, listOf("Left", "Right").map { it to it }) { next ->
                update { it.copy(dock = it.dock.copy(side = next)) }
            }
            FloatSetting("Alignment", dock.alignment, 0f..1f) { next -> update { it.copy(dock = it.dock.copy(alignment = next)) } }
            SettingsNote("0% places the dock at the start of its edge; 100% places it at the end.")
            ToggleSetting("Full width", dock.fullWidth) { next -> update { it.copy(dock = it.dock.copy(fullWidth = next)) } }
            SettingsNote("Extends the vertical dock along the full height of its edge.")
            IntSetting("Maximum items", dock.maxItems, 1..12) { next -> update { it.copy(dock = it.dock.copy(maxItems = next)) } }
            FloatSetting("Icon size", dock.iconScale, .5f..1.5f) { next -> update { it.copy(dock = it.dock.copy(iconScale = next)) } }
            IntSetting("Padding", dock.padding, 0..48, " dp") { next -> update { it.copy(dock = it.dock.copy(padding = next)) } }
            ToggleSetting("App labels", dock.labels) { next -> update { it.copy(dock = it.dock.copy(labels = next)) } }
            ToggleSetting("Stack folder icons", dock.folderStack) { next -> update { it.copy(dock = it.dock.copy(folderStack = next)) } }
        }
    }
    item {
        SettingsSection("Dock visibility") {
            ToggleSetting("Auto-hide dock", prefs.dock.autoHide) { next -> update { it.copy(dock = it.dock.copy(autoHide = next)) } }
            ToggleSetting("Hide while scrolling", prefs.dock.hideOnScroll) { next -> update { it.copy(dock = it.dock.copy(hideOnScroll = next)) } }
        }
    }
}

private fun LazyListScope.favoritesSettings(
    prefs: LauncherPrefs,
    update: ((LauncherPrefs) -> LauncherPrefs) -> Unit,
    onUsageAccess: () -> Unit,
    onRequestContacts: () -> Unit
) {
    item {
        val favorites = prefs.favorites
        SettingsSection("Favorite content") {
            ToggleSetting("Rank by usage", favorites.byUsage, "Use app usage to order favorites.") { next ->
                update { it.copy(favorites = it.favorites.copy(byUsage = next)) }
            }
            SettingsLink("App usage access", "Allow Android usage statistics for usage ranking", onUsageAccess)
            ToggleSetting("Exclude dock apps", favorites.excludeDock) { next -> update { it.copy(favorites = it.favorites.copy(excludeDock = next)) } }
            ToggleSetting("Exclude apps in folders", favorites.excludeFolders) { next -> update { it.copy(favorites = it.favorites.copy(excludeFolders = next)) } }
            ToggleSetting("Show contacts", favorites.showContacts) { next -> update { it.copy(favorites = it.favorites.copy(showContacts = next)) } }
            SettingsLink("Allow contacts", "Request access to contacts used by the launcher", onRequestContacts)
            IntSetting("Maximum favorites", favorites.maxItems, 1..60) { next -> update { it.copy(favorites = it.favorites.copy(maxItems = next)) } }
        }
    }
    item {
        GridSettings("Favorites layout", prefs.favorites.grid) { change ->
            update { it.copy(favorites = it.favorites.copy(grid = change(it.favorites.grid))) }
        }
    }
}

private fun LazyListScope.searchSettings(
    state: LauncherState,
    update: ((LauncherPrefs) -> LauncherPrefs) -> Unit,
    onUpdate: ((LauncherState) -> LauncherState) -> Unit,
    onRequestContacts: () -> Unit
) {
    item {
        val search = state.prefs.search
        SettingsSection("Search sources") {
            ToggleSetting("Installed apps", search.apps) { next -> update { it.copy(search = it.search.copy(apps = next)) } }
            ToggleSetting("Include hidden apps", search.hiddenApps, "Hidden apps can appear in search when enabled.") { next -> update { it.copy(search = it.search.copy(hiddenApps = next)) } }
            ToggleSetting("Contacts", search.contacts) { next -> update { it.copy(search = it.search.copy(contacts = next)) } }
            SettingsLink("Allow contacts", "Request Android contacts permission", onRequestContacts)
            ToggleSetting("App shortcuts", search.shortcuts) { next -> update { it.copy(search = it.search.copy(shortcuts = next)) } }
            ToggleSetting("Settings results", search.settings) { next -> update { it.copy(search = it.search.copy(settings = next)) } }
            ToggleSetting("Calculator", search.math) { next -> update { it.copy(search = it.search.copy(math = next)) } }
            ToggleSetting("Web search", search.web) { next -> update { it.copy(search = it.search.copy(web = next)) } }
            ToggleSetting("Play Store search", search.playStore) { next -> update { it.copy(search = it.search.copy(playStore = next)) } }
            ToggleSetting("Search history", search.history) { next -> update { it.copy(search = it.search.copy(history = next)) } }
            TextButton(onClick = { onUpdate { it.copy(history = emptyList()) } }, enabled = state.history.isNotEmpty()) {
                Text("Clear search history (${state.history.size})")
            }
        }
    }
    item {
        val search = state.prefs.search
        SettingsSection("Web search engine") {
            ChoiceSetting("Engine", search.engine, WebEngine.entries.map { it to it.name.settingLabel() }) { next ->
                update { it.copy(search = it.search.copy(engine = next)) }
            }
            if (search.engine == WebEngine.CUSTOM) {
                ValidatedTextSetting("Custom search URL", search.customUrl,
                    "Use an https:// URL containing {query}; the launcher inserts the search text.", ::searchUrlError) { next ->
                    update { it.copy(search = it.search.copy(customUrl = next)) }
                }
            }
        }
    }
    item {
        GridSettings("Search results layout", state.prefs.search.grid) { change ->
            update { it.copy(search = it.search.copy(grid = change(it.search.grid))) }
        }
    }
}

private fun searchUrlError(value: String): String? {
    if (value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() }) return "Use a URL without spaces, up to 2,048 characters."
    if (!value.contains("{query}")) return "Include {query} where the search text belongs."
    val uri = runCatching { URI(value.replace("{query}", "example")) }.getOrNull()
    return if (uri != null && uri.scheme?.lowercase(Locale.ROOT) in listOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in -1..65535) null
    else "Enter a valid http:// or https:// search URL."
}

private fun LazyListScope.desktopSettings(prefs: LauncherPrefs, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit) {
    item {
        SettingsSection("Widget area") {
            IntSetting("Widget columns", prefs.desktop.columns, 1..10) { next -> update { it.copy(desktop = it.desktop.copy(columns = next)) } }
            IntSetting("Widget rows", prefs.desktop.rows, 1..16) { next -> update { it.copy(desktop = it.desktop.copy(rows = next)) } }
            IntSetting("Widget padding", prefs.desktop.padding, 0..48, " dp") { next -> update { it.copy(desktop = it.desktop.copy(padding = next)) } }
            IntSetting("Widget corner radius", prefs.desktop.cornerRadius, 0..48, " dp") { next -> update { it.copy(desktop = it.desktop.copy(cornerRadius = next)) } }
        }
    }
}

private fun LazyListScope.appearanceSettings(
    prefs: LauncherPrefs,
    iconPacks: List<Pair<String, String>>,
    update: ((LauncherPrefs) -> LauncherPrefs) -> Unit
) {
    item {
        val appearance = prefs.appearance
        SettingsSection("Appearance mode") {
            ChoiceSetting("Mode", appearance.mode, ThemeMode.entries.map { it to it.name.settingLabel() }) { next ->
                update { it.copy(appearance = it.appearance.copy(mode = next)) }
            }
            SettingsNote("Advanced mode uses the independent launcher, dock and search themes below.")
            val themes = SurfaceTheme.entries.map { it to it.name.settingLabel() }
            ChoiceSetting("Launcher theme", appearance.launcher, themes) { next -> update { it.copy(appearance = it.appearance.copy(launcher = next)) } }
            ChoiceSetting("Dock theme", appearance.dock, themes) { next -> update { it.copy(appearance = it.appearance.copy(dock = next)) } }
            ChoiceSetting("Search theme", appearance.search, themes) { next -> update { it.copy(appearance = it.appearance.copy(search = next)) } }
            FloatSetting("Wallpaper dim", appearance.dim, 0f..1f) { next -> update { it.copy(appearance = it.appearance.copy(dim = next)) } }
            ValidatedTextSetting("Accent color", appearance.accent, "#RRGGBB or #AARRGGBB", ::colorError,
                normalize = { it.trim().uppercase(Locale.ROOT) }) { next ->
                update { it.copy(appearance = it.appearance.copy(accent = next)) }
            }
        }
    }
    item {
        val appearance = prefs.appearance
        SettingsSection("Icons") {
            val packs = listOf("" to "System icons") + iconPacks.distinctBy { it.first }
            val choices = if (appearance.iconPack.isNotBlank() && packs.none { it.first == appearance.iconPack }) {
                packs + (appearance.iconPack to "Unavailable pack (${appearance.iconPack})")
            } else packs
            ChoiceSetting("Icon pack", appearance.iconPack, choices) { next -> update { it.copy(appearance = it.appearance.copy(iconPack = next)) } }
            if (iconPacks.isEmpty()) SettingsNote("Install an Android icon pack to choose it here.")
            ChoiceSetting("Icon shape", appearance.iconShape, IconShape.entries.map { it to it.name.settingLabel() }) { next ->
                update { it.copy(appearance = it.appearance.copy(iconShape = next)) }
            }
            ToggleSetting("Light status bar icons", appearance.lightStatusIcons, "White icons for dark wallpapers.") { next ->
                update { it.copy(appearance = it.appearance.copy(lightStatusIcons = next)) }
            }
            ToggleSetting("Light navigation bar icons", appearance.lightNavigationIcons) { next ->
                update { it.copy(appearance = it.appearance.copy(lightNavigationIcons = next)) }
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
        SettingsSection("Notification badges") {
            ToggleSetting("Show badges", badge.enabled) { next -> update { it.copy(badges = it.badges.copy(enabled = next)) } }
            SettingsLink("Notification access", "Allow Lynx to read active notifications for badges", onNotificationAccess)
            ToggleSetting("Show notification counts", badge.counts, "Show a number instead of a dot.") { next -> update { it.copy(badges = it.badges.copy(counts = next)) } }
            FloatSetting("Badge size", badge.scale, .5f..1.5f) { next -> update { it.copy(badges = it.badges.copy(scale = next)) } }
            ValidatedTextSetting("Badge color", badge.color, "#RRGGBB or #AARRGGBB", ::colorError,
                normalize = { it.trim().uppercase(Locale.ROOT) }) { next -> update { it.copy(badges = it.badges.copy(color = next)) } }
        }
    }
}

private fun LazyListScope.folderSettings(prefs: LauncherPrefs, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit) {
    item {
        val folder = prefs.folders
        SettingsSection("Folder preview") {
            val shape = RoundedCornerShape(22.dp)
            val background = settingsColor(folder.color, SettingsPanel)
            val opacity = folder.opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .95f
            val iconScale = folder.iconScale.takeIf { it.isFinite() }?.coerceIn(.5f, 1.5f) ?: 1f
            Surface(shape = shape, color = background.copy(alpha = background.alpha * opacity),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (folder.stacked) {
                        Box(Modifier.size(100.dp, 80.dp), contentAlignment = Alignment.Center) {
                            repeat(3) { index ->
                                FolderSampleIcon(index, iconScale,
                                    Modifier.offset((index * 12 - 12).dp, (index * 6 - 6).dp))
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(2) { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    repeat(2) { column -> FolderSampleIcon(row * 2 + column, iconScale) }
                                }
                            }
                        }
                    }
                    Text("Folder preview", style = MaterialTheme.typography.labelLarge)
                }
            }
            ToggleSetting("Stacked preview", folder.stacked) { next -> update { it.copy(folders = it.folders.copy(stacked = next)) } }
            FloatSetting("Folder icon size", folder.iconScale, .5f..1.5f) { next -> update { it.copy(folders = it.folders.copy(iconScale = next)) } }
            FloatSetting("Folder opacity", folder.opacity, 0f..1f) { next -> update { it.copy(folders = it.folders.copy(opacity = next)) } }
            ValidatedTextSetting("Folder background", folder.color, "#RRGGBB or #AARRGGBB", ::colorError,
                normalize = { it.trim().uppercase(Locale.ROOT) }) { next -> update { it.copy(folders = it.folders.copy(color = next)) } }
        }
    }
}

@Composable
private fun FolderSampleIcon(index: Int, scale: Float, modifier: Modifier = Modifier) {
    val colors = listOf(SettingsGreen, Color(0xFFAFD3FF), Color(0xFFF2CEA2), Color(0xFFD4B7F7))
    Box(modifier.size((36 * scale.coerceIn(.5f, 1.5f)).dp)
        .background(colors[index], RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Text(('A'.code + index).toChar().toString(), color = SettingsBackground, fontWeight = FontWeight.Bold)
    }
}

internal fun LazyListScope.behaviorSettings(prefs: LauncherPrefs, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit) {
    item {
        val behavior = prefs.behavior
        SettingsSection("System & wallpaper") {
            ToggleSetting("Lock rotation", behavior.lockRotation) { next -> update { it.copy(behavior = it.behavior.copy(lockRotation = next)) } }
            ToggleSetting("Show status bar", behavior.showStatusBar) { next -> update { it.copy(behavior = it.behavior.copy(showStatusBar = next)) } }
            ChoiceSetting("Wallpaper scrolling", behavior.wallpaperScroll,
                listOf("Parallax" to "Parallax", "Off" to "Fixed", "Horizontal" to "Horizontal")) { next ->
                update { it.copy(behavior = it.behavior.copy(wallpaperScroll = next)) }
            }
        }
    }
    item {
        val behavior = prefs.behavior
        SettingsSection("Transitions") {
            ToggleSetting("Animations", behavior.animations) { next -> update { it.copy(behavior = it.behavior.copy(animations = next)) } }
            ToggleSetting("Screen borders", behavior.borders) { next -> update { it.copy(behavior = it.behavior.copy(borders = next)) } }
            val transitions = listOf("Slide", "Fade", "None").map { it to it }
            ChoiceSetting("Horizontal transition", behavior.horizontalTransition, transitions) { next -> update { it.copy(behavior = it.behavior.copy(horizontalTransition = next)) } }
            ChoiceSetting("Vertical transition", behavior.verticalTransition, transitions) { next -> update { it.copy(behavior = it.behavior.copy(verticalTransition = next)) } }
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
        SettingsSection("Hidden app checklist") {
            SettingsNote("Checked apps are hidden from the launcher. Search can include them when ‘Include hidden apps’ is enabled in Search settings.")
            Text("${state.hiddenApps.size} hidden", color = SettingsGreen)
            TextButton(onClick = { onUpdate { it.copy(hiddenApps = emptySet()) } }, enabled = state.hiddenApps.isNotEmpty()) {
                Text("Unhide all")
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
        val checked = app.key in state.hiddenApps
        val toggle: () -> Unit = {
            onUpdate { current ->
                current.copy(hiddenApps = if (app.key in current.hiddenApps) current.hiddenApps - app.key else current.hiddenApps + app.key)
            }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onCheckedChange = { toggle() })
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
