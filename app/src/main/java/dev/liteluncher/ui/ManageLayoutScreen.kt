package dev.liteluncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liteluncher.data.*
import dev.liteluncher.platform.AppEntry
import dev.liteluncher.platform.AppsRepository
import java.util.UUID

@Composable
fun ManageLayoutScreen(
    state: LauncherState, apps: List<AppEntry>, repository: AppsRepository,
    update: ((LauncherState) -> LauncherState) -> Unit, onBack: () -> Unit,
    onManageScreens: () -> Unit = {},
    onRemoveApp: (LayoutArea, String) -> Unit = { area, key -> update { removeLayoutApp(it, area, key) } }
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FolderItem?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    val drag = LocalAppDrag.current
    LaunchedEffect(picker, editing?.id) { if (picker || editing != null) drag?.cancel() }
    val back = { if (drag?.active != null) drag.cancel() else onBack() }
    BackHandler(onBack = back)
    val area = LayoutArea.entries.getOrNull(tab)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text("Manage layout", fontSize = 20.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onManageScreens) { Icon(Icons.Rounded.Dashboard, "Screen Manager") }
        }
        TabRow(selectedTabIndex = tab) {
            (LayoutArea.entries.map { it.title } + "Folders").forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { drag?.cancel(); tab = index; picker = false }, text = { Text(title, fontSize = 12.sp, maxLines = 1) })
            }
        }
        if (area != null) key(area) {
            LayoutAreaEditor(state, apps, repository, area, update, { picker = true }, { tab = 3 },
                { editing = it; newFolder = false }, { key -> onRemoveApp(area, key) }, Modifier.weight(1f))
        } else {
            LayoutFolders(state, update, { editing = it; newFolder = false }, {
                editing = FolderItem(UUID.randomUUID().toString(), "Folder")
                newFolder = true
            }, Modifier.weight(1f))
        }
    }
    if (picker && area != null) LayoutAppPicker(state, apps, area, { picker = false }) { keys ->
        update { current ->
            val additions = keys.filter { it !in current.layoutApps(area) }
            if (current.layoutLocked(area) || additions.size > current.layoutSpace(area)) current
            else additions.fold(current) { next, key -> applyAppDrop(next, key, null, AppDropTarget.Area(area)).state }
        }
        picker = false
    }
    editing?.let { folder ->
        FolderEditor(folder, apps, { value ->
            val creating = newFolder
            update { current -> saveLayoutFolder(current, value, creating) }
            editing = null
        }, { editing = null })
    }
}

@Composable
private fun LayoutAreaEditor(
    state: LauncherState, apps: List<AppEntry>, repository: AppsRepository, area: LayoutArea,
    update: ((LauncherState) -> LauncherState) -> Unit, onAdd: () -> Unit, onFolders: () -> Unit,
    onEditFolder: (FolderItem) -> Unit, onRemoveApp: (String) -> Unit, modifier: Modifier
) {
    val locked = state.layoutLocked(area)
    val keys = state.layoutApps(area)
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val folderIds = when (area) { LayoutArea.HOME -> state.homeFolders; LayoutArea.DOCK -> state.dockFolders; else -> emptyList() }
    LazyColumn(modifier.fillMaxSize().appDropTarget(AppDropTarget.Area(area)), contentPadding = PaddingValues(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAdd, enabled = !locked && state.layoutSpace(area) > 0) { Text("Add apps") }
                if (area != LayoutArea.FAVORITES) TextButton(onClick = onFolders) { Text("Folders") }
                Spacer(Modifier.weight(1f))
                if (area != LayoutArea.FAVORITES) IconButton(onClick = { update { toggleLayoutLock(it, area) } }) {
                    Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, if (locked) "Unlock ${area.title}" else "Lock ${area.title}")
                }
            }
            Text(if (locked) "Layout locked. Unlock to make changes." else "Hold and drag apps to reorder. Arrows also move entries.", fontSize = 12.sp, color = Muted)
            Text("${state.layoutSpace(area)} slots available", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 8.dp))
            if (area == LayoutArea.FAVORITES) Text("Pinned apps appear before usage-based favorites.", fontSize = 12.sp, color = Muted)
        }
        if (keys.isEmpty()) item { Text("No pinned apps here. Choose Add apps to get started.", fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp)) }
        items(keys, key = { "app:$it" }) { key ->
            val app = byKey[key]
            val index = keys.indexOf(key)
            val gestures = if (app != null) Modifier.appIconGestures(app, area, !locked, null, null) else Modifier
            LayoutEntryRow(state.overrides[key]?.label?.takeIf { it.isNotBlank() } ?: app?.label ?: "Unavailable app", !locked,
                index > 0, index < keys.lastIndex,
                { update { moveLayoutApp(it, area, key, -1) } }, { update { moveLayoutApp(it, area, key, 1) } },
                { onRemoveApp(key) }, gestures,
                leading = { if (app != null) AppIcon(app, repository, state, 32) else Icon(Icons.Rounded.Apps, null) })
        }
        if (folderIds.isNotEmpty()) item { SectionLabel("Folders") }
        items(folderIds, key = { "folder:$it" }) { id ->
            state.folders.find { it.id == id }?.let { folder ->
                val index = folderIds.indexOf(id)
                LayoutEntryRow(folder.name, !locked, index > 0, index < folderIds.lastIndex,
                    { update { moveLayoutFolder(it, area, id, -1) } }, { update { moveLayoutFolder(it, area, id, 1) } },
                    { update { toggleLayoutFolder(it, area, id) } }, Modifier.appDropTarget(AppDropTarget.Folder(id), 3),
                    leading = { Icon(Icons.Rounded.Folder, null) },
                    onEdit = if (!state.folderLocked(id)) { { onEditFolder(folder) } } else null)
            }
        }
        if (area == LayoutArea.HOME) layoutShortcuts(state, update)
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LayoutEntryRow(
    title: String, enabled: Boolean, canUp: Boolean, canDown: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier,
    leading: @Composable () -> Unit, onEdit: (() -> Unit)? = null
) {
    val drag = LocalAppDrag.current
    Row(modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { leading() }
        Text(title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        if (onEdit != null) IconButton(onClick = { drag?.cancel(); onEdit() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Edit, "Edit $title", modifier = Modifier.size(18.dp)) }
        IconButton(onClick = { drag?.cancel(); onUp() }, enabled = enabled && canUp, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Move $title up") }
        IconButton(onClick = { drag?.cancel(); onDown() }, enabled = enabled && canDown, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Move $title down") }
        IconButton(onClick = { drag?.cancel(); onRemove() }, enabled = enabled, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Remove $title", modifier = Modifier.size(18.dp)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.layoutShortcuts(state: LauncherState, update: ((LauncherState) -> LauncherState) -> Unit) {
    if (state.shortcuts.isEmpty()) return
    item { SectionLabel("Shortcuts") }
    items(state.shortcuts, key = { "shortcut:${it.packageName}:${it.id}:${it.userSerial}" }) { shortcut ->
        val index = state.shortcuts.indexOf(shortcut)
        fun move(step: Int) = update { current ->
            if (current.prefs.home.preventChanges) current else {
                val from = current.shortcuts.indexOf(shortcut)
                current.copy(shortcuts = current.shortcuts.moveLayoutItem(from, from + step))
            }
        }
        LayoutEntryRow(shortcut.label, !state.prefs.home.preventChanges, index > 0, index < state.shortcuts.lastIndex,
            { move(-1) }, { move(1) }, { update { if (it.prefs.home.preventChanges) it else it.copy(shortcuts = it.shortcuts - shortcut) } },
            leading = { Icon(Icons.Rounded.Shortcut, null) })
    }
}

@Composable
private fun LayoutFolders(
    state: LauncherState, update: ((LauncherState) -> LauncherState) -> Unit,
    onEdit: (FolderItem) -> Unit, onCreate: () -> Unit, modifier: Modifier
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Button(onClick = onCreate, enabled = state.folders.size < MAX_LAYOUT_FOLDERS) { Text("Create folder") }
            Text("Choose which folders appear on Home and in the Dock.", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))
        }
        if (state.folders.isEmpty()) item { Text("No folders yet.", fontSize = 14.sp) }
        items(state.folders, key = { it.id }) { folder ->
            Column(Modifier.fillMaxWidth().appDropTarget(AppDropTarget.Folder(folder.id), 3).padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Text(folder.name, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), fontWeight = FontWeight.Medium)
                    IconButton(onClick = { onEdit(folder) }, enabled = !state.folderLocked(folder.id)) { Icon(Icons.Rounded.Edit, "Edit ${folder.name}") }
                    IconButton(onClick = { update { current ->
                        if (current.folderLocked(folder.id)) current else current.copy(folders = current.folders.filterNot { it.id == folder.id },
                            homeFolders = current.homeFolders - folder.id, dockFolders = current.dockFolders - folder.id)
                    } }, enabled = !state.folderLocked(folder.id)) { Icon(Icons.Rounded.DeleteOutline, "Delete ${folder.name}") }
                }
                Text("${folder.apps.size} apps", fontSize = 12.sp, color = Muted)
                Row {
                    FolderPlacement(state, folder.id, LayoutArea.HOME, update)
                    FolderPlacement(state, folder.id, LayoutArea.DOCK, update)
                }
                HorizontalDivider(color = Muted.copy(alpha = .15f))
            }
        }
    }
}

@Composable
private fun FolderPlacement(state: LauncherState, id: String, area: LayoutArea, update: ((LauncherState) -> LauncherState) -> Unit) {
    val checked = id in (if (area == LayoutArea.HOME) state.homeFolders else state.dockFolders)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = { update { toggleLayoutFolder(it, area, id) } },
            enabled = !state.layoutLocked(area) && (checked || state.layoutSpace(area) > 0))
        Text(area.title, fontSize = 13.sp)
    }
}

@Composable
private fun LayoutAppPicker(state: LauncherState, apps: List<AppEntry>, area: LayoutArea, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val available = apps.filter { it.key !in state.layoutApps(area) && it.key !in state.hiddenApps }
    val capacity = state.layoutSpace(area)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add to ${area.title}") }, text = {
        Column {
            OutlinedTextField(query, { query = it.take(80) }, singleLine = true, label = { Text("Find apps") })
            Text("${selected.size} selected · $capacity slots available", fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(available.filter { (state.overrides[it.key]?.label?.takeIf(String::isNotBlank) ?: it.label).contains(query, true) }, key = { it.key }) { app ->
                    val checked = app.key in selected
                    val enabled = checked || selected.size < capacity
                    val toggle = { selected = if (checked) selected - app.key else selected + app.key }
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = { toggle() }, enabled = enabled)
                        Text(state.overrides[app.key]?.label?.takeIf(String::isNotBlank) ?: app.label, fontSize = 13.sp, maxLines = 2)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onAdd(selected.filter { key -> available.any { it.key == key } }) },
        enabled = selected.isNotEmpty() && selected.size <= capacity && !state.layoutLocked(area)) { Text("Add apps") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun toggleLayoutLock(state: LauncherState, area: LayoutArea): LauncherState = when (area) {
    LayoutArea.HOME -> state.copy(prefs = state.prefs.copy(home = state.prefs.home.copy(preventChanges = !state.prefs.home.preventChanges)))
    LayoutArea.DOCK -> state.copy(prefs = state.prefs.copy(dock = state.prefs.dock.copy(preventChanges = !state.prefs.dock.preventChanges)))
    LayoutArea.FAVORITES -> state
}

private fun saveLayoutFolder(state: LauncherState, folder: FolderItem, isNew: Boolean): LauncherState {
    if (folder.name.isBlank() || state.folderLocked(folder.id)) return state
    val existing = state.folders.find { it.id == folder.id }
    if (existing == null && (!isNew || state.folders.size >= MAX_LAYOUT_FOLDERS)) return state
    val next = (existing ?: folder).copy(name = folder.name.trim(), apps = folder.apps.distinct().take(MAX_LAYOUT_APPS))
    return state.copy(folders = if (existing == null) state.folders + next else state.folders.map { if (it.id == folder.id) next else it })
}

@Composable
private fun FolderEditor(folder: FolderItem, apps: List<AppEntry>, onSave: (FolderItem) -> Unit, onDismiss: () -> Unit) {
    var name by remember(folder.id) { mutableStateOf(folder.name) }
    var selected by remember(folder.id) { mutableStateOf(folder.apps) }
    var filter by rememberSaveable(folder.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit folder") }, text = {
        Column {
            OutlinedTextField(name, { name = it.take(128) }, label = { Text("Folder name") }, singleLine = true)
            OutlinedTextField(filter, { filter = it.take(80) }, label = { Text("Filter applications") }, singleLine = true, modifier = Modifier.padding(top = 10.dp))
            Text("${selected.size} selected", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 10.dp))
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(selected.filter { key -> apps.none { it.key == key } }, key = { "unavailable:$it" }) { key ->
                    Row(Modifier.fillMaxWidth().clickable { selected = selected - key }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(true, { selected = selected - key })
                        Text("Unavailable app", fontSize = 13.sp)
                    }
                }
                items(apps.filter { it.label.contains(filter, true) }, key = { it.key }) { app ->
                    val checked = app.key in selected
                    val enabled = checked || selected.size < MAX_LAYOUT_APPS
                    val toggle = { selected = if (checked) selected - app.key else selected + app.key }
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, { toggle() }, enabled = enabled)
                        Text(app.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(folder.copy(name = name.trim(), apps = selected.distinct())) }, enabled = name.isNotBlank()) { Text("Save folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
