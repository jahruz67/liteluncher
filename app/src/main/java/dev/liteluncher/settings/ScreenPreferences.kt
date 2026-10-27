package dev.liteluncher.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.liteluncher.data.*
import dev.liteluncher.platform.AppEntry
import dev.liteluncher.ui.ClockHeader

@Composable
internal fun LayoutPreferences(value: GridPrefs, onChange: ((GridPrefs) -> GridPrefs) -> Unit) {
    val dotColor = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            IntSetting("Columns", value.columns, 1..10) { next -> onChange { it.copy(columns = next) } }
            IntSetting("Rows", value.rows, 1..16) { next -> onChange { it.copy(rows = next) } }
        }
        Canvas(Modifier.padding(start = 16.dp).width(52.dp).height(104.dp)) {
            val stepX = size.width / (value.columns + 1)
            val stepY = size.height / (value.rows + 1)
            repeat(value.rows) { row -> repeat(value.columns) { col ->
                drawCircle(dotColor, 1.5.dp.toPx(), Offset((col + 1) * stepX, (row + 1) * stepY))
            } }
        }
    }
    IntSetting("Intermediate steps", value.intermediateSteps, 0..4) { next -> onChange { it.copy(intermediateSteps = next) } }
    ChoiceSetting("Border padding", value.borderPadding, listOf("Default", "None", "Small", "Large").map { it to it }) { next -> onChange { it.copy(borderPadding = next) } }
    ToggleSetting("Prevent changes", value.preventChanges) { next -> onChange { it.copy(preventChanges = next) } }
}

@Composable
internal fun IconPreferences(value: GridPrefs, onChange: ((GridPrefs) -> GridPrefs) -> Unit) {
    FloatSetting("Icon Size", value.iconScale, .5f..1.5f) { next -> onChange { it.copy(iconScale = next) } }
    ToggleSetting("Show Text", value.labels) { next -> onChange { it.copy(labels = next) } }
    FloatSetting("Text Size", value.textScale, .5f..1.5f) { next -> onChange { it.copy(textScale = next) } }
    ToggleSetting("Multiline Labels", value.multiline) { next -> onChange { it.copy(multiline = next) } }
}

internal fun LazyListScope.homePreferences(p: LauncherPrefs, apps: List<AppEntry>, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit) {
    item { SettingsSection("Layout") { LayoutPreferences(p.home) { change -> update { it.copy(home = change(it.home)) } } } }
    item { SettingsSection("Widgets") {
        ToggleSetting("Rounded widget corners", p.desktop.rounded) { v -> update { it.copy(desktop = it.desktop.copy(rounded = v)) } }
        IntSetting("Corner radius", p.desktop.cornerRadius, 0..48) { v -> update { it.copy(desktop = it.desktop.copy(cornerRadius = v)) } }
    } }
    item { SettingsSection("Icons") { IconPreferences(p.home) { change -> update { it.copy(home = change(it.home)) } } } }
    item { SettingsSection("Date & Time") {
        ClockHeader(p.clock)
        ToggleSetting("Show time", p.clock.enabled) { v -> update { it.copy(clock = it.clock.copy(enabled = v)) } }
        ToggleSetting("Analog clock", p.clock.analog) { v -> update { it.copy(clock = it.clock.copy(analog = v)) } }
        ChoiceSetting("Clock App", p.clock.clockApp, listOf("" to "Clock") + apps.map { it.key to it.label }) { v -> update { it.copy(clock = it.clock.copy(clockApp = v)) } }
        ToggleSetting("24-hour time", p.clock.use24Hour) { v -> update { it.copy(clock = it.clock.copy(use24Hour = v)) } }
        ToggleSetting("Show date", p.clock.showDate) { v -> update { it.copy(clock = it.clock.copy(showDate = v)) } }
        ToggleSetting("Show day of week", p.clock.showDay) { v -> update { it.copy(clock = it.clock.copy(showDay = v)) } }
        ToggleSetting("Show year", p.clock.showYear) { v -> update { it.copy(clock = it.clock.copy(showYear = v)) } }
        ColorSetting("Date and time color", p.clock.color) { v -> update { it.copy(clock = it.clock.copy(color = v)) } }
        FloatSetting("Text Size", p.clock.textScale, .5f..1.5f) { v -> update { it.copy(clock = it.clock.copy(textScale = v)) } }
    } }
}

internal fun LazyListScope.dockPreferences(p: LauncherPrefs, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit) {
    item { SettingsSection("Dock") {
        val d = p.dock
        ToggleSetting("Show Dock", d.enabled) { v -> update { it.copy(dock = it.dock.copy(enabled = v)) } }
        ToggleSetting("All Apps Button", d.allApps) { v -> update { it.copy(dock = it.dock.copy(allApps = v)) } }
        ToggleSetting("All Apps button on left side", d.allAppsLeft) { v -> update { it.copy(dock = it.dock.copy(allAppsLeft = v)) } }
        ToggleSetting("Open folder as stack", d.folderStack) { v -> update { it.copy(dock = it.dock.copy(folderStack = v)) } }
        ChoiceSetting("Hide automatically", d.autoHide, listOf(false to "Never", true to "After inactivity")) { v -> update { it.copy(dock = it.dock.copy(autoHide = v)) } }
        ToggleSetting("Show when scrolling", d.showWhenScrolling) { v -> update { it.copy(dock = it.dock.copy(showWhenScrolling = v, hideOnScroll = false)) } }
        ChoiceSetting("Position", d.side, listOf("Right", "Left").map { it to it }) { v -> update { it.copy(dock = it.dock.copy(side = v)) } }
        ToggleSetting("Use full width", d.fullWidth) { v -> update { it.copy(dock = it.dock.copy(fullWidth = v)) } }
        FloatSetting("Alignment", d.alignment, 0f..1f) { v -> update { it.copy(dock = it.dock.copy(alignment = v)) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Top"); Text("Bottom") }
        ToggleSetting("Prevent changes", d.preventChanges) { v -> update { it.copy(dock = it.dock.copy(preventChanges = v)) } }
        FloatSetting("Icon Size", d.iconScale, .5f..1.5f) { v -> update { it.copy(dock = it.dock.copy(iconScale = v)) } }
        IntSetting("Maximum items", d.maxItems, 1..12) { v -> update { it.copy(dock = it.dock.copy(maxItems = v)) } }
    } }
}

internal fun LazyListScope.desktopPreferences(p: LauncherPrefs, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit) {
    item { SettingsSection("Layout") {
        val d = p.desktop
        LayoutPreferences(d.grid.copy(columns = d.columns, rows = d.rows, preventChanges = d.preventChanges)) { change -> update {
            val grid = change(it.desktop.grid.copy(columns = it.desktop.columns, rows = it.desktop.rows, preventChanges = it.desktop.preventChanges))
            it.copy(desktop = it.desktop.copy(grid = grid, columns = grid.columns, rows = grid.rows, preventChanges = grid.preventChanges))
        } }
        ToggleSetting("Hint on empty screen", d.hint) { v -> update { it.copy(desktop = it.desktop.copy(hint = v)) } }
        ToggleSetting("Widget padding", d.widgetPadding) { v -> update { it.copy(desktop = it.desktop.copy(widgetPadding = v)) } }
    } }
}

internal fun LazyListScope.favoritePreferences(state: LauncherState, apps: List<AppEntry>, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit,
    write: ((LauncherState) -> LauncherState) -> Unit, usage: () -> Unit, contacts: () -> Unit) {
    val f = state.prefs.favorites
    item { SettingsSection("General") {
        ToggleSetting("Exclude Dock Entries", f.excludeDock) { v -> update { it.copy(favorites = it.favorites.copy(excludeDock = v)) } }
        ToggleSetting("Exclude Folder Content", f.excludeFolders, "Exclude entries from folders in the dock") { v -> update { it.copy(favorites = it.favorites.copy(excludeFolders = v)) } }
        ToggleSetting("Show Contacts", f.showContacts) { v -> update { it.copy(favorites = it.favorites.copy(showContacts = v)) } }
        IntSetting("Number of Favorites", f.maxItems, 1..60) { v -> update { it.copy(favorites = it.favorites.copy(maxItems = v)) } }
        ChoiceSetting("Items per row", f.itemsPerRow, listOf(0 to "Automatic") + (1..10).map { it to "$it" }) { v -> update { it.copy(favorites = it.favorites.copy(itemsPerRow = v)) } }
        ChoiceSetting("Sorting", f.byUsage, listOf(true to "By usage", false to "Alphabetical")) { v -> update { it.copy(favorites = it.favorites.copy(byUsage = v)) } }
        ToggleSetting("Reverse Sorting", f.reverse) { v -> update { it.copy(favorites = it.favorites.copy(reverse = v)) } }
        AppBlacklist("Excluded Entries", apps, f.excluded) { key -> update { it.copy(favorites = it.favorites.copy(excluded = if (key in it.favorites.excluded) it.favorites.excluded - key else it.favorites.excluded + key)) } }
        TextButton(onClick = { write { it.copy(favoriteApps = emptyList(), launches = emptyMap(), prefs = it.prefs.copy(favorites = FavoritesPrefs())) } }) { Text("Reset Favorites") }
        SettingsLink("App usage access", "Use Android usage statistics", usage)
        SettingsLink("Allow contacts", "Grant contact access", contacts)
    } }
    item { SettingsSection("Icons") { IconPreferences(f.grid) { change -> update { it.copy(favorites = it.favorites.copy(grid = change(it.favorites.grid))) } } } }
}

@Composable
private fun AppBlacklist(title: String, apps: List<AppEntry>, selected: Set<String>, toggle: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsLink(title, "${selected.size} excluded") { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) { items(apps, key = { it.key }) { app ->
            Row(Modifier.fillMaxWidth().clickable { toggle(app.key) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(app.key in selected, { toggle(app.key) }); Text(app.label)
            }
        } }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } })
}

@Composable
internal fun ColorSetting(title: String, value: String, save: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = settingsColor(value), modifier = Modifier.size(24.dp), shape = androidx.compose.foundation.shape.CircleShape) {}
        Text(value, Modifier.padding(start = 12.dp))
    }
    ValidatedTextSetting(title, value, "#RRGGBB or #AARRGGBB", ::colorError, onSave = save)
}

internal fun LazyListScope.searchPreferences(state: LauncherState, update: ((LauncherPrefs) -> LauncherPrefs) -> Unit, write: ((LauncherState) -> LauncherState) -> Unit) {
    val s = state.prefs.search
    item { SettingsSection("Search Bar") {
        var screens by remember { mutableStateOf(false) }
        SettingsLink("Show search bar", s.barScreens.joinToString { it.title }) { screens = true }
        if (screens) AlertDialog(onDismissRequest = { screens = false }, title = { Text("Show search bar") }, text = {
            Column { listOf(Screen.HOME, Screen.APPS, Screen.FAVORITES).forEach { screen ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(screen in s.barScreens, { v -> update { it.copy(search = it.search.copy(barScreens = if (v) it.search.barScreens + screen else it.search.barScreens - screen)) } }); Text(screen.title)
                }
            } }
        }, confirmButton = { TextButton(onClick = { screens = false }) { Text("Done") } })
        ToggleSetting("Search bar at bottom", s.barAtBottom) { v -> update { it.copy(search = it.search.copy(barAtBottom = v)) } }
        SearchTargetsSetting(s) { change -> update { it.copy(search = change(it.search)) } }
        SearchEngineSetting(s) { v -> update { it.copy(search = it.search.copy(engine = v)) } }
        if (s.engine == WebEngine.CUSTOM) ValidatedTextSetting("Custom URL", s.customUrl, "Use {query} for the query", { if (SearchEngine.validCustomUrl(it) == null) "Enter an http(s) URL with {query}" else null }) { v -> update { it.copy(search = it.search.copy(customUrl = v)) } }
    } }
    item { SettingsSection("General") {
        ValidatedTextSetting("Custom Search bar text", s.barText, "Search", { if (it.length > 128) "Maximum 128 characters" else null }) { v -> update { it.copy(search = it.search.copy(barText = v)) } }
        ToggleSetting("Hint on empty screen", s.hint) { v -> update { it.copy(search = it.search.copy(hint = v)) } }
        ToggleSetting("Voice Search", s.voice) { v -> update { it.copy(search = it.search.copy(voice = v)) } }
        ToggleSetting("Show search history", s.history) { v -> update { it.copy(search = it.search.copy(history = v)) } }
        TextButton(onClick = { write { it.copy(history = emptyList()) } }) { Text("Clear search history") }
    } }
}

private fun SearchPrefs.checked(target: SearchTarget) = when (target) {
    SearchTarget.APPS -> apps; SearchTarget.HIDDEN_APPS -> hiddenApps; SearchTarget.CONTACTS -> contacts
    SearchTarget.SHORTCUTS -> shortcuts; SearchTarget.SETTINGS -> settings; SearchTarget.MATH -> math
    SearchTarget.WEB -> web; SearchTarget.PLAY_STORE -> playStore; SearchTarget.WEBSITE -> website
}
private fun SearchPrefs.check(target: SearchTarget, v: Boolean) = when (target) {
    SearchTarget.APPS -> copy(apps = v); SearchTarget.HIDDEN_APPS -> copy(hiddenApps = v); SearchTarget.CONTACTS -> copy(contacts = v)
    SearchTarget.SHORTCUTS -> copy(shortcuts = v); SearchTarget.SETTINGS -> copy(settings = v); SearchTarget.MATH -> copy(math = v)
    SearchTarget.WEB -> copy(web = v); SearchTarget.PLAY_STORE -> copy(playStore = v); SearchTarget.WEBSITE -> copy(website = v)
}

@Composable
private fun SearchTargetsSetting(prefs: SearchPrefs, update: ((SearchPrefs) -> SearchPrefs) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsLink("Search Targets", "Choose and reorder search sources") { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Search Targets") }, text = {
        val rowPixels = with(LocalDensity.current) { 48.dp.toPx() }
        val currentOrder by rememberUpdatedState(prefs.targetOrder)
        LazyColumn(Modifier.heightIn(max = 440.dp)) { items(prefs.targetOrder, key = { it.name }) { target ->
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(prefs.checked(target), { v -> update { it.check(target, v) } })
                Text(target.title, Modifier.weight(1f))
                Icon(Icons.Rounded.DragHandle, "Drag to reorder ${target.title}", Modifier.size(40.dp).pointerInput(target) {
                    var distance = 0f
                    detectDragGesturesAfterLongPress(onDragStart = { distance = 0f }, onDrag = { change, delta ->
                        change.consume(); distance += delta.y
                        if (kotlin.math.abs(distance) >= rowPixels) {
                            val step = if (distance > 0) 1 else -1
                            update { current ->
                                val list = current.targetOrder.toMutableList(); val from = list.indexOf(target); val to = (from + step).coerceIn(0, list.lastIndex)
                                if (from >= 0) { list.removeAt(from); list.add(to, target) }
                                current.copy(targetOrder = list)
                            }
                            distance -= step * rowPixels
                        }
                    })
                })
            }
        } }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } })
}

@Composable
internal fun SearchEngineSetting(prefs: SearchPrefs, select: (WebEngine) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsLink("Search Engines", prefs.engine.displayName()) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Search Engines") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) { items(WebEngine.entries) { engine ->
            Row(Modifier.fillMaxWidth().clickable { select(engine); open = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(prefs.engine == engine, { select(engine); open = false })
                dev.liteluncher.ui.SearchEngineLogo(engine)
                Text(engine.displayName(), Modifier.padding(start = 12.dp))
            }
        } }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}
