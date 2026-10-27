@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import android.content.Intent
import android.os.UserManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.liteluncher.data.*
import dev.liteluncher.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppContextDialog(app: AppEntry, state: LauncherState, repository: AppsRepository, actions: WorkspaceActions, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val shortcuts by produceState<List<NativeShortcut>>(emptyList(), app.key) { value = withContext(Dispatchers.IO) { repository.shortcuts(app) } }
    fun update(block: (LauncherState) -> LauncherState) { actions.update(block); onDismiss() }
    AlertDialog(onDismissRequest = onDismiss, title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, repository, state, 38)
            Text(state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label, modifier = Modifier.padding(start = 13.dp), fontSize = 20.sp)
        }
    }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            item { ActionRow("Open application", Icons.Rounded.OpenInNew, { actions.launch(app); onDismiss() }) }
            item { ActionRow("App info", Icons.Rounded.Info, { repository.appInfo(app); onDismiss() }) }
            item { ActionRow("Edit label & icon", Icons.Rounded.Edit, onEdit) }
            item { ActionRow(if (app.key in state.homeApps) "Remove from home" else "Add to home", Icons.Rounded.Home, {
                update { it.copy(homeApps = if (app.key in it.homeApps) it.homeApps - app.key else (it.homeApps + app.key).distinct()) }
            }) }
            item { ActionRow(if (app.key in state.dockApps) "Remove from dock" else "Pin to dock", Icons.Rounded.Dock, {
                if (app.key !in state.dockApps && state.dockApps.size + state.dockFolders.size >= state.prefs.dock.maxItems) {
                    android.widget.Toast.makeText(context, "The dock is full. Increase its item limit in Settings.", android.widget.Toast.LENGTH_SHORT).show()
                } else update { it.copy(dockApps = if (app.key in it.dockApps) it.dockApps - app.key else (it.dockApps + app.key).distinct()) }
            }) }
            item { ActionRow(if (app.key in state.favoriteApps) "Unpin favorite" else "Pin favorite", Icons.Rounded.StarOutline, {
                update { it.copy(favoriteApps = if (app.key in it.favoriteApps) it.favoriteApps - app.key else (it.favoriteApps + app.key).distinct()) }
            }) }
            item { ActionRow(if (app.key in state.hiddenApps) "Unhide application" else "Hide application", Icons.Rounded.VisibilityOff, {
                update { it.copy(hiddenApps = if (app.key in it.hiddenApps) it.hiddenApps - app.key else it.hiddenApps + app.key) }
            }) }
            if (shortcuts.isNotEmpty()) {
                item { SectionLabel("App shortcuts") }
                items(shortcuts, key = { it.id }) { shortcut ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { repository.launchShortcut(shortcut); onDismiss() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(shortcut.label, modifier = Modifier.fillMaxWidth(), fontSize = 13.sp) }
                        IconButton(onClick = {
                            val serial = requireNotNull(context.getSystemService(UserManager::class.java)).getSerialNumberForUser(shortcut.user)
                            val pinned = runCatching {
                                val launcher = requireNotNull(context.getSystemService(android.content.pm.LauncherApps::class.java))
                                val previous = launcher.getShortcuts(android.content.pm.LauncherApps.ShortcutQuery().apply {
                                    setPackage(shortcut.packageName)
                                    setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                                }, shortcut.user).orEmpty().map { it.id }
                                launcher.pinShortcuts(shortcut.packageName, (previous + shortcut.id).distinct(), shortcut.user)
                                true
                            }.getOrDefault(false)
                            if (pinned) update { current -> current.copy(shortcuts = (current.shortcuts + PinnedShortcut(shortcut.id, shortcut.packageName, shortcut.label, serial)).distinctBy { Triple(it.packageName, it.id, it.userSerial) }) }
                            else android.widget.Toast.makeText(context, "Set Lite Luncher as your default home app to pin shortcuts.", android.widget.Toast.LENGTH_LONG).show()
                        }) { Icon(Icons.Rounded.Add, "Add shortcut to home", modifier = Modifier.size(18.dp)) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
fun EditAppDialog(app: AppEntry, state: LauncherState, update: ((LauncherState) -> LauncherState) -> Unit, onDismiss: () -> Unit) {
    var label by remember(app.key) { mutableStateOf(state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label) }
    var iconUri by remember(app.key) { mutableStateOf(state.overrides[app.key]?.iconUri.orEmpty()) }
    val context = LocalContext.current
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            iconUri = uri.toString()
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit application") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(label, { label = it.take(80) }, label = { Text("Display name") }, singleLine = true)
            Text("Choose an image for this icon, or use the active icon pack.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { pickIcon.launch(arrayOf("image/*")) }) { Icon(Icons.Rounded.Image, null); Text("  Choose icon image") }
            if (iconUri.isNotBlank()) TextButton(onClick = { iconUri = "" }) { Text("Use default icon") }
        }
    }, confirmButton = { TextButton(onClick = {
        update { it.copy(overrides = it.overrides + (app.key to AppOverride(label.trim().takeIf { name -> name != app.label }.orEmpty(), iconUri))) }
        onDismiss()
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun WidgetPicker(controller: WidgetController, onSelect: (android.appwidget.AppWidgetProviderInfo) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val providers by produceState<List<android.appwidget.AppWidgetProviderInfo>>(emptyList()) { value = withContext(Dispatchers.IO) { controller.providers() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add a widget") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
            if (providers.isEmpty()) item { Text("No widget providers are available. Install an app with home screen widgets to add one.", fontSize = 13.sp) }
            items(providers, key = { "${it.provider.flattenToString()}:${it.profile}" }) { provider ->
                ActionRow(runCatching { provider.loadLabel(context.packageManager) }.getOrDefault(provider.provider.shortClassName), Icons.Rounded.Widgets, { onSelect(provider) },
                    "${provider.minWidth} × ${provider.minHeight} dp")
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun FolderOverlay(folder: FolderItem, fromDock: Boolean, state: LauncherState, apps: List<AppEntry>, repository: AppsRepository, actions: WorkspaceActions, onDismiss: () -> Unit) {
    val members = folder.apps.mapNotNull { key -> apps.find { it.key == key } }
    val stack = fromDock && state.prefs.dock.folderStack
    val folderColor = parseColor(state.prefs.folders.color)
    val background = folderColor.copy(alpha = folderColor.alpha * state.prefs.folders.opacity)
    val folderContent = if (folderColor.luminance() > .5f) Ink else Mist
    Box(Modifier.fillMaxSize().background(ColorForOverlay).clickable(onClick = onDismiss)) {
        if (stack) {
            val left = state.prefs.dock.side == "Left"
            Column(Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).padding(start = if (left) 88.dp else 16.dp, end = if (left) 16.dp else 88.dp)
                .widthIn(max = 250.dp), horizontalAlignment = if (left) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(folder.name, fontSize = 12.sp, color = Mist, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                members.take(8).forEachIndexed { index, app ->
                    val fan = (kotlin.math.sin((index.toFloat() / (members.size - 1).coerceAtLeast(1)) * Math.PI) * 22).toInt()
                    Surface(onClick = { actions.launch(app); onDismiss() }, color = background, contentColor = folderContent, shape = RoundedCornerShape(22.dp), shadowElevation = 10.dp,
                        modifier = Modifier.offset(x = (if (left) fan else -fan).dp).widthIn(min = 160.dp, max = 235.dp)) {
                        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app, repository, state, 39)
                            Text(state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label, modifier = Modifier.padding(start = 12.dp), fontSize = 13.sp, color = folderContent, maxLines = 1)
                        }
                    }
                }
                if (members.size > 8) TextButton(onClick = { onDismiss(); actions.manageLayout() }) { Text("Manage all ${members.size} apps") }
                if (members.isEmpty()) TextButton(onClick = { onDismiss(); actions.manageLayout() }) { Text("Add applications") }
            }
        } else Surface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 380.dp).clickable {}, color = background, contentColor = folderContent, shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(folder.name, modifier = Modifier.weight(1f), fontSize = 22.sp, color = folderContent)
                    IconButton(onClick = { onDismiss(); actions.manageLayout() }) { Icon(Icons.Rounded.Edit, "Edit folder", tint = Muted, modifier = Modifier.size(18.dp)) }
                }
                if (members.isEmpty()) Text("Add applications in Manage layout.", color = Muted, fontSize = 13.sp)
                LazyColumn(Modifier.heightIn(max = 410.dp)) {
                    items(members.chunked(3)) { row -> Row(Modifier.fillMaxWidth()) {
                        row.forEach { app -> AppTile(app, repository, state, state.prefs.home.copy(columns = 3), 0, { actions.launch(app); onDismiss() }, { actions.menu(app) }, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    } }
                }
            }
        }
    }
}

private val ColorForOverlay = androidx.compose.ui.graphics.Color.Black.copy(alpha = .5f)
