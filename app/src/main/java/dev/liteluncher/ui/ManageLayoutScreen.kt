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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liteluncher.data.*
import dev.liteluncher.platform.AppEntry
import dev.liteluncher.platform.AppsRepository
import java.util.UUID

@Composable
fun ManageLayoutScreen(state: LauncherState, apps: List<AppEntry>, repository: AppsRepository, update: ((LauncherState) -> LauncherState) -> Unit, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("") }
    var editingFolder by remember { mutableStateOf<FolderItem?>(null) }
    BackHandler(onBack = onBack)
    val tabs = listOf("Home", "Dock", "Favorites", "Folders")
    val currentTab = tab
    fun selected(source: LauncherState): List<String> = when (currentTab) { 0 -> source.homeApps; 1 -> source.dockApps; else -> source.favoriteApps }
    fun replace(source: LauncherState, keys: List<String>): LauncherState = when (currentTab) {
        0 -> source.copy(homeApps = keys); 1 -> source.copy(dockApps = keys); else -> source.copy(favoriteApps = keys)
    }
    val keys = selected(state)
    val capacity = when (tab) {
        0 -> (state.prefs.home.columns * state.prefs.home.rows - state.homeFolders.size - state.shortcuts.size).coerceAtLeast(0)
        1 -> (state.prefs.dock.maxItems - state.dockFolders.size).coerceAtLeast(0)
        else -> state.prefs.favorites.maxItems
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text("Manage layout", fontSize = 22.sp, modifier = Modifier.weight(1f))
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) { tabs.forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index; filter = "" }, text = { Text(title, fontSize = 13.sp) }) } }
        if (tab == 3) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                item { SectionLabel("App collections", "New folder", { editingFolder = FolderItem(UUID.randomUUID().toString(), "New folder") }) }
                if (state.folders.isEmpty()) item { EmptyCard(Icons.Rounded.FolderOpen, "A place for related apps", "Create a folder, choose its applications, then add it to your home or dock.") }
                items(state.folders, key = { it.id }) { folder ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FolderTile(folder, apps, repository, state, GridPrefs(labels = false), { editingFolder = folder }, modifier = Modifier.width(58.dp))
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(folder.name, fontSize = 17.sp)
                                    Text("${folder.apps.size} applications", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                }
                                IconButton(onClick = { editingFolder = folder }) { Icon(Icons.Rounded.Edit, "Edit folder", modifier = Modifier.size(18.dp)) }
                                IconButton(onClick = { update { it.copy(folders = it.folders.filterNot { item -> item.id == folder.id }, homeFolders = it.homeFolders - folder.id, dockFolders = it.dockFolders - folder.id) } }) { Icon(Icons.Rounded.DeleteOutline, "Delete folder", modifier = Modifier.size(18.dp)) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(folder.id in state.homeFolders, { checked -> update { it.copy(homeFolders = if (checked) (it.homeFolders + folder.id).distinct() else it.homeFolders - folder.id) } })
                                Text("Home", fontSize = 12.sp)
                                Spacer(Modifier.width(12.dp))
                                Checkbox(folder.id in state.dockFolders, { checked -> update { it.copy(dockFolders = if (checked) (it.dockFolders + folder.id).distinct() else it.dockFolders - folder.id) } },
                                    enabled = folder.id in state.dockFolders || state.dockApps.size + state.dockFolders.size < state.prefs.dock.maxItems)
                                Text("Dock", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SectionLabel("${keys.size} selected · $capacity visible slots")
                Text("Use the arrows to arrange your apps. ${if (tab == 2) "Pinned apps appear before frequently used apps." else "Folders can be added from the Folders tab."}", fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (keys.size > capacity) Text("${keys.size - capacity} apps exceed the current layout. Increase its capacity or remove entries.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(12.dp))
            }
            items(keys, key = { it }) { key ->
                val app = apps.find { it.key == key }
                val index = keys.indexOf(key)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (app != null) AppIcon(app, repository, state, 35) else Icon(Icons.Rounded.BrokenImage, null)
                    Text(if (app != null) state.overrides[key]?.label?.ifBlank { app.label } ?: app.label else "Unavailable application", fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    IconButton(onClick = { update { current ->
                        val list = selected(current).toMutableList(); val i = list.indexOf(key)
                        if (i > 0) { list.removeAt(i); list.add(i - 1, key) }
                        replace(current, list)
                    } }, enabled = index > 0, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Move earlier", modifier = Modifier.size(19.dp)) }
                    IconButton(onClick = { update { current ->
                        val list = selected(current).toMutableList(); val i = list.indexOf(key)
                        if (i >= 0 && i < list.lastIndex) { list.removeAt(i); list.add(i + 1, key) }
                        replace(current, list)
                    } }, enabled = index < keys.lastIndex, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Move later", modifier = Modifier.size(19.dp)) }
                    IconButton(onClick = { update { replace(it, selected(it) - key) } }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove application", modifier = Modifier.size(19.dp)) }
                }
            }
            item {
                SectionLabel("Add applications")
                LauncherSearchField(filter, { filter = it }, "Filter applications", {}, null, state.prefs.appearance.search)
                Spacer(Modifier.height(12.dp))
            }
            items(apps.filter { it.key !in keys && it.label.contains(filter, true) }, key = { "add:${it.key}" }) { app ->
                Row(Modifier.fillMaxWidth().clickable(enabled = keys.size < capacity) { update { replace(it, (selected(it) + app.key).distinct()) } }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, repository, state, 35)
                    Text(app.label, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    Icon(Icons.Rounded.AddCircleOutline, "Add application", tint = if (keys.size < capacity) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .3f), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    if (editingFolder != null) {
        val edited = editingFolder!!
        FolderEditor(edited, apps, { saved ->
            update { it.copy(folders = it.folders.filterNot { item -> item.id == saved.id } + saved) }
            editingFolder = null
        }, { editingFolder = null })
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
