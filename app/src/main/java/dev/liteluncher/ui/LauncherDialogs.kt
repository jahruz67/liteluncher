@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import android.content.Intent
import android.os.UserManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liteluncher.data.*
import dev.liteluncher.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MenuBackground = Color(0xFF282E2F)
private val MenuText = Color(0xFFBFC1C1)
private val MenuIcon = Color(0xFFE3E6E5)

/** An in-window menu keeps the original icon's touch sequence alive for dragging. */
@Composable
fun AppContextMenu(
    app: AppEntry,
    anchor: Rect,
    state: LauncherState,
    repository: AppsRepository,
    actions: WorkspaceActions,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    source: Screen = Screen.HOME,
    fromDock: Boolean = false,
    onRemove: () -> Unit = {}
) {
    val context = LocalContext.current
    val shortcutRevision by repository.shortcutRevision.collectAsStateWithLifecycle()
    val shortcuts by produceState<List<NativeShortcut>>(emptyList(), app.key, shortcutRevision) {
        value = withContext(Dispatchers.IO) { repository.shortcuts(app) }
    }
    fun update(block: (LauncherState) -> LauncherState) { actions.update(block); onDismiss() }
    val removable = when {
        fromDock -> app.key in state.dockApps && !state.prefs.dock.preventChanges
        source == Screen.DESKTOP -> state.desktopApps.values.any { app.key in it } && !state.prefs.desktop.preventChanges
        source == Screen.HOME -> app.key in state.homeApps && !state.prefs.home.preventChanges
        source == Screen.FAVORITES -> app.key in state.favoriteApps
        else -> false
    }

    AnchoredIconMenu(anchor, onDismiss) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MenuBackground,
            contentColor = MenuText,
            shadowElevation = 8.dp,
            modifier = Modifier.width(224.dp).heightIn(max = 360.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ActionRowItem("Edit", Icons.Default.Edit, onEdit)
                ActionRowItem("App-Info", Icons.Outlined.Info, { repository.appInfo(app); onDismiss() })
                if (removable) ActionRowItem("Remove", Icons.Default.Close, onRemove)

                if (app.key !in state.homeApps && !state.prefs.home.preventChanges) {
                    ActionRowItem("Add to Home", Icons.Rounded.AddToHomeScreen, {
                        val full = state.layoutSpace(LayoutArea.HOME) == 0
                        if (full) android.widget.Toast.makeText(context, "Home is full. Remove an entry or increase the grid size.", android.widget.Toast.LENGTH_SHORT).show()
                        else actions.update { current ->
                            if (current.prefs.home.preventChanges || current.layoutSpace(LayoutArea.HOME) == 0) current
                            else current.copy(homeApps = (current.homeApps + app.key).distinct())
                        }
                        onDismiss()
                    }, compact = true)
                }
                if (app.key !in state.dockApps && !state.prefs.dock.preventChanges) {
                    ActionRowItem("Pin to Dock", Icons.Rounded.Dock, {
                        val full = state.layoutSpace(LayoutArea.DOCK) == 0
                        if (full) android.widget.Toast.makeText(context, "The dock has reached its item limit.", android.widget.Toast.LENGTH_SHORT).show()
                        else actions.update { current ->
                            if (current.prefs.dock.preventChanges || current.layoutSpace(LayoutArea.DOCK) == 0) current
                            else current.copy(dockApps = (current.dockApps + app.key).distinct())
                        }
                        onDismiss()
                    }, compact = true)
                }
                if (app.key !in state.favoriteApps) {
                    ActionRowItem("Pin to Favorites", Icons.Rounded.Favorite, {
                        if (state.favoriteApps.size >= state.prefs.favorites.maxItems) {
                            android.widget.Toast.makeText(context, "Favorites is full. Increase its item limit in Settings.", android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } else update { current ->
                            if (current.favoriteApps.size >= current.prefs.favorites.maxItems) current
                            else current.copy(favoriteApps = (current.favoriteApps + app.key).distinct(),
                                prefs = current.prefs.copy(favorites = current.prefs.favorites.copy(excluded = current.prefs.favorites.excluded - app.key)))
                        }
                    }, compact = true)
                }

                // Separated by hairline dividers from bottom Android deep app shortcuts
                if (shortcuts.isNotEmpty()) {
                    HorizontalDivider(color = Color(0x22FFFFFF), thickness = 0.5.dp)
                    Text(
                        "Shortcuts",
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                    Column {
                        shortcuts.forEach { shortcut ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { actions.shortcut(shortcut); onDismiss() }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Shortcut, null, tint = MenuIcon, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(shortcut.label, fontSize = 16.sp, color = MenuText, maxLines = 1, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        val serial = requireNotNull(context.getSystemService(UserManager::class.java)).getSerialNumberForUser(shortcut.user)
                                        val alreadyPinned = state.shortcuts.any {
                                            it.id == shortcut.id && it.packageName == shortcut.packageName && it.userSerial == serial && it.container == "home"
                                        }
                                        if (!alreadyPinned && state.layoutSpace(LayoutArea.HOME) <= 0) {
                                            android.widget.Toast.makeText(context, "Home is full. Remove an entry or increase the grid size.", android.widget.Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } else if (!repository.syncShortcutPins(state.shortcuts + PinnedShortcut(shortcut.id, shortcut.packageName, shortcut.label, serial))) {
                                            SystemActions.error(context, "Android could not pin this shortcut")
                                        } else update { current ->
                                            if (current.prefs.home.preventChanges || current.layoutSpace(LayoutArea.HOME) <= 0) current
                                            else current.copy(shortcuts = (current.shortcuts + PinnedShortcut(shortcut.id, shortcut.packageName, shortcut.label, serial)).distinctBy { it.layoutKey to it.container })
                                        }
                                    },
                                    enabled = !state.prefs.home.preventChanges,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Rounded.Add, "Pin shortcut", modifier = Modifier.size(16.dp), tint = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnchoredIconMenu(anchor: Rect, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss
        ))
        Layout(content = {
            content()
            Canvas(Modifier.size(width = 24.dp, height = 12.dp)) {
                drawPath(Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2, size.height)
                    close()
                }, MenuBackground)
            }
        }, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp)
            .onGloballyPositioned { origin = it.positionInRoot() }) { measurables, constraints ->
            val pointer = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val menu = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0,
                maxHeight = (constraints.maxHeight - pointer.height).coerceAtLeast(0)))
            val height = menu.height + pointer.height
            val gap = 2.dp.roundToPx()
            val localAnchor = anchor.translate(-origin)
            val x = (localAnchor.center.x.toInt() - menu.width / 2).coerceIn(0, constraints.maxWidth - menu.width)
            val above = localAnchor.top.toInt() - gap - height
            val below = localAnchor.bottom.toInt() + gap
            val placeAbove = above >= 0 || (below + height > constraints.maxHeight &&
                localAnchor.top > constraints.maxHeight - localAnchor.bottom)
            val y = (if (placeAbove) above else below).coerceIn(0, constraints.maxHeight - height)
            val pointerInset = minOf(16.dp.roundToPx(), (menu.width - pointer.width).coerceAtLeast(0) / 2)
            val pointerX = (localAnchor.center.x.toInt() - x - pointer.width / 2)
                .coerceIn(pointerInset, (menu.width - pointer.width - pointerInset).coerceAtLeast(pointerInset))
            layout(constraints.maxWidth, constraints.maxHeight) {
                menu.place(x, if (placeAbove) y else y + pointer.height)
                pointer.placeWithLayer(x + pointerX, if (placeAbove) y + menu.height else y) {
                    rotationZ = if (placeAbove) 0f else 180f
                }
            }
        }
    }
}

/** Empty Space Context Menu: Dark card with Change Wallpaper, Add Widget, Settings, and Manage Layout */
@Composable
fun EmptySpaceMenuDialog(
    onWallpaper: () -> Unit,
    onAddWidget: () -> Unit,
    onSettings: () -> Unit,
    onManageLayout: () -> Unit,
    onDismiss: () -> Unit,
    showSettings: Boolean = true
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MenuBackground,
            contentColor = MenuText,
            shadowElevation = 8.dp,
            modifier = Modifier.width(280.dp)
        ) {
            Column {
                ActionRowItem("Change Wallpaper", Icons.Default.Wallpaper, onWallpaper)
                ActionRowItem("Add Widget", Icons.Default.Widgets, onAddWidget)
                if (showSettings) ActionRowItem("Settings", Icons.Default.Settings, onSettings)
                ActionRowItem("Manage Layout", Icons.Default.ViewCarousel, onManageLayout)
            }
        }
    }
}

@Composable
private fun ActionRowItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, compact: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 40.dp else 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MenuIcon, modifier = Modifier.size(if (compact) 24.dp else 28.dp))
        Spacer(Modifier.width(22.dp))
        Text(label, fontSize = if (compact) 16.sp else 20.sp, fontWeight = FontWeight.Normal, color = MenuText)
    }
}

@Composable
fun EditAppDialog(app: AppEntry, state: LauncherState, update: ((LauncherState) -> LauncherState) -> Unit, onDismiss: () -> Unit) {
    var label by rememberSaveable(app.key) { mutableStateOf(state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label) }
    var iconUri by rememberSaveable(app.key) { mutableStateOf(state.overrides[app.key]?.iconUri.orEmpty()) }
    var useBackup by rememberSaveable(app.key) { mutableStateOf(true) }
    val backupIcon = state.overrides[app.key]?.backupIcon.takeIf { useBackup }
    var shape by rememberSaveable(app.key) { mutableStateOf(state.overrides[app.key]?.shape) }
    val context = LocalContext.current
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            iconUri = uri.toString()
            useBackup = false
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit application") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(label, { label = it.take(80) }, label = { Text("Display name") }, singleLine = true)
            Text("Custom App Icon & Masking Shape", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { pickIcon.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Image, null)
                Text("  Choose icon image")
            }
            if (iconUri.isNotBlank() || backupIcon != null) TextButton(onClick = { iconUri = ""; useBackup = false }) { Text("Use default icon") }

            Text("Icon Shape", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                IconShape.entries.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { s ->
                    FilterChip(
                        selected = shape == s,
                        onClick = { shape = s },
                        label = { Text(s.name.take(4)) }
                    )
                } } }
            }
            FilterChip(selected = shape == null, onClick = { shape = null }, label = { Text("Follow global shape") })
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (iconUri.startsWith("content://") && runCatching {
                context.contentResolver.takePersistableUriPermission(android.net.Uri.parse(iconUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isFailure) {
                SystemActions.error(context, "Cannot retain access to this image. Choose another image.")
                return@TextButton
            }
            update { it.copy(overrides = it.overrides + (app.key to AppOverride(label.trim().takeIf { name -> name != app.label }.orEmpty(), iconUri, shape, backupIcon))) }
            onDismiss()
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}


/** Folder Overlay: Docked folders expand outward as a vertical floating fan-out stack */
@Composable
fun FolderOverlay(
    folder: FolderItem,
    fromDock: Boolean,
    state: LauncherState,
    apps: List<AppEntry>,
    repository: AppsRepository,
    actions: WorkspaceActions,
    onDismiss: () -> Unit
) {
    val folderBadges by LauncherNotificationService.counts.collectAsStateWithLifecycle()
    val members = remember(folder.apps, apps) {
        val byKey = apps.associateBy { it.key }
        folder.apps.mapNotNull(byKey::get)
    }
    val stack = fromDock && state.prefs.dock.folderStack
    val drag = LocalAppDrag.current
    var bounds by remember(folder.id) { mutableStateOf(Rect.Zero) }
    var draggingOutside by remember(folder.id) { mutableStateOf(false) }
    LaunchedEffect(drag?.active, drag?.position) {
        if (drag != null && drag.active?.folderId == folder.id && bounds != Rect.Zero && !bounds.contains(drag.position)) {
            draggingOutside = true
            if (folder.id !in drag.dismissedFolders) drag.dismissedFolders.add(folder.id)
        }
        if (draggingOutside && drag?.active == null) onDismiss()
    }
    val folderPrefs = state.prefs.folders
    val folderColor = if (folderPrefs.background == "Custom") parseColor(folderPrefs.color)
        else surfaceColor(state.prefs.appearance.launcher, MaterialTheme.colorScheme.primary)
    val background = folderColor.copy(alpha = folderColor.alpha * folderPrefs.opacity)
    val folderContent = contrastContent(folderColor)

    // Keep the source composed until release so its pointer handler continues the drag.
    Box(Modifier.fillMaxSize().alpha(if (draggingOutside) 0f else 1f).background(ColorForOverlay).clickable(onClick = onDismiss)) {
        if (!stack) {
            val keys = state.workspaceKeys("folder:${folder.id}")
            Surface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 380.dp)
                .onGloballyPositioned { bounds = it.boundsInRoot() }.appDropTarget(AppDropTarget.Folder(folder.id), 4).clickable {},
                color = background, contentColor = folderContent, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.name, Modifier.weight(1f), fontSize = 22.sp)
                        IconButton(onClick = { onDismiss(); actions.editFolder(folder) }, enabled = !state.folderLocked(folder.id)) {
                            Icon(Icons.Rounded.Edit, "Edit folder")
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 410.dp)) {
                        items(keys.chunked(3)) { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { key ->
                                    WorkspaceEntry(key, "folder:${folder.id}", state, apps, repository,
                                        state.prefs.home.copy(columns = 3), folderBadges, actions, Modifier.weight(1f).height(100.dp), onDismiss)
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        } else if (stack) {
            val left = state.prefs.dock.side == "Left"
            Column(
                Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(start = if (left) 88.dp else 16.dp, end = if (left) 16.dp else 88.dp)
                    .widthIn(max = 260.dp).heightIn(max = 460.dp)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .appDropTarget(AppDropTarget.Folder(folder.id), 4).verticalScroll(rememberScrollState()),
                horizontalAlignment = if (left) Alignment.Start else Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xCC000000),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Text(folder.name, fontSize = 12.sp, color = Mist, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                val keys = state.workspaceKeys("folder:${folder.id}")
                keys.forEachIndexed { index, entryKey ->
                    val fan = (kotlin.math.sin((index.toFloat() / (keys.size - 1).coerceAtLeast(1)) * Math.PI) * 24).toInt()
                    Surface(
                        color = background,
                        contentColor = folderContent,
                        shape = RoundedCornerShape(22.dp),
                        shadowElevation = 10.dp,
                        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                        modifier = Modifier.offset(x = (if (left) fan else -fan).dp).widthIn(min = 160.dp, max = 240.dp)
                    ) {
                        WorkspaceEntry(entryKey, "folder:${folder.id}", state, apps, repository,
                            state.prefs.home.copy(iconScale = .7f), folderBadges, actions, Modifier.fillMaxWidth(), onDismiss)
                    }
                }
            }
        } else {
            Surface(
                Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 380.dp)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }.appDropTarget(AppDropTarget.Folder(folder.id), 4).clickable {},
                color = background,
                contentColor = folderContent,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color(0x22FFFFFF))
            ) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.name, modifier = Modifier.weight(1f), fontSize = 22.sp, color = folderContent)
                        IconButton(onClick = { onDismiss(); actions.editFolder(folder) }, enabled = !state.folderLocked(folder.id)) {
                            Icon(Icons.Rounded.Edit, "Edit folder", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 410.dp)) {
                        items(members.chunked(3)) { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { app ->
                                    AppTile(app, repository, state, state.prefs.home.copy(columns = 3), folderBadges[app.badgeKey] ?: 0, { actions.launch(app); onDismiss() }, { actions.menu(app) }, Modifier.weight(1f),
                                        draggable = !state.folderLocked(folder.id), dragFolderId = folder.id)
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FolderContextMenu(folder: FolderItem, anchor: Rect, locked: Boolean, onEdit: () -> Unit, onDismiss: () -> Unit, onRemove: () -> Unit = {}) {
    AnchoredIconMenu(anchor, onDismiss) {
        Surface(shape = RoundedCornerShape(10.dp), color = MenuBackground, shadowElevation = 8.dp, modifier = Modifier.width(224.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(folder.name, color = Color.White)
                if (!locked) ActionRowItem("Edit folder", Icons.Rounded.Edit, onEdit)
                if (!locked) ActionRowItem("Delete folder", Icons.Rounded.DeleteOutline, onRemove)
            }
        }
    }
}

private val ColorForOverlay = Color.Black.copy(alpha = .5f)


@Composable
internal fun FolderEditor(folder: FolderItem, apps: List<AppEntry>, onSave: (FolderItem) -> Unit, onDismiss: () -> Unit, overrides: Map<String, AppOverride> = emptyMap()) {
    var name by rememberSaveable(folder.id) { mutableStateOf(folder.name) }
    var selected by rememberSaveable(folder.id) { mutableStateOf(folder.apps) }
    var filter by rememberSaveable(folder.id) { mutableStateOf("") }
    val appKeys = remember(apps) { apps.mapTo(hashSetOf()) { it.key } }
    val selectedKeys = remember(selected) { selected.toHashSet() }
    val unavailable = remember(selected, appKeys) { selected.filter { it !in appKeys } }
    val matching = remember(apps, filter, overrides) { apps.filter { (overrides[it.key]?.label?.ifBlank { it.label } ?: it.label).contains(filter, true) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit folder") }, text = {
        Column {
            OutlinedTextField(name, { name = it.take(128) }, label = { Text("Folder name") }, singleLine = true)
            OutlinedTextField(filter, { filter = it.take(80) }, label = { Text("Filter applications") }, singleLine = true, modifier = Modifier.padding(top = 10.dp))
            Text("${selected.size} selected", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 10.dp))
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(unavailable, key = { "unavailable:$it" }) { key ->
                    Row(Modifier.fillMaxWidth().clickable { selected = selected - key }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(true, { selected = selected - key })
                        Text("Unavailable: $key", fontSize = 13.sp)
                    }
                }
                items(matching, key = { it.key }) { app ->
                    val checked = app.key in selectedKeys
                    val enabled = checked || selected.size < MAX_LAYOUT_APPS
                    val toggle = { selected = if (checked) selected - app.key else selected + app.key }
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, { toggle() }, enabled = enabled)
                        Text(overrides[app.key]?.label?.ifBlank { app.label } ?: app.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(folder.copy(name = name.trim(), apps = selected.distinct())) }, enabled = name.isNotBlank()) { Text("Save folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
