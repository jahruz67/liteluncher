@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import android.content.Intent
import android.os.UserManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
        source == Screen.HOME -> app.key in state.homeApps && !state.prefs.home.preventChanges
        source == Screen.FAVORITES -> app.key in state.favoriteApps
        else -> false
    }

    AnchoredIconMenu(anchor, onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF282828),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            modifier = Modifier.width(224.dp).heightIn(max = 360.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                // Header: App Icon and Title
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(app, repository, state, 24)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }

                HorizontalDivider(color = Color(0x22FFFFFF), thickness = 0.5.dp)

                // Top launcher actions: Edit, App-Info, Remove
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onEdit() }.padding(8.dp)
                    ) {
                        Icon(Icons.Rounded.Edit, null, tint = Cerulean, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("Edit", fontSize = 11.sp)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { repository.appInfo(app); onDismiss() }.padding(8.dp)
                    ) {
                        Icon(Icons.Rounded.Info, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("App-Info", fontSize = 11.sp)
                    }

                    if (removable) Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRemove).padding(8.dp)
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("Remove", fontSize = 11.sp, color = Color(0xFFFF5252))
                    }
                }

                if (app.key !in state.homeApps && !state.prefs.home.preventChanges) {
                    ActionRowItem("Add to Home", Icons.Rounded.AddToHomeScreen, {
                        val full = state.homeApps.size + state.homeFolders.size + state.shortcuts.size >= state.prefs.home.rows * state.prefs.home.columns
                        if (full) android.widget.Toast.makeText(context, "Home is full. Remove an entry or increase the grid size.", android.widget.Toast.LENGTH_SHORT).show()
                        else actions.update { current ->
                            val capacity = current.prefs.home.rows * current.prefs.home.columns
                            if (current.prefs.home.preventChanges || current.homeApps.size + current.homeFolders.size + current.shortcuts.size >= capacity) current
                            else current.copy(homeApps = (current.homeApps + app.key).distinct())
                        }
                        onDismiss()
                    }, compact = true)
                }
                if (app.key !in state.dockApps && !state.prefs.dock.preventChanges) {
                    ActionRowItem("Pin to Dock", Icons.Rounded.Dock, {
                        val full = state.dockApps.size + state.dockFolders.size >= state.prefs.dock.maxItems
                        if (full) android.widget.Toast.makeText(context, "Dock is full. Remove an entry or increase its item limit.", android.widget.Toast.LENGTH_SHORT).show()
                        else actions.update { current ->
                            if (current.prefs.dock.preventChanges || current.dockApps.size + current.dockFolders.size >= current.prefs.dock.maxItems) current
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
                                    .clickable { repository.launchShortcut(shortcut); onDismiss() }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Shortcut, null, tint = Cerulean, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(shortcut.label, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        val serial = requireNotNull(context.getSystemService(UserManager::class.java)).getSerialNumberForUser(shortcut.user)
                                        if (state.layoutSpace(LayoutArea.HOME) <= 0) {
                                            android.widget.Toast.makeText(context, "Home is full. Remove an entry or increase the grid size.", android.widget.Toast.LENGTH_SHORT).show()
                                            onDismiss()
                                        } else update { current ->
                                            if (current.prefs.home.preventChanges || current.layoutSpace(LayoutArea.HOME) <= 0) current
                                            else current.copy(shortcuts = (current.shortcuts + PinnedShortcut(shortcut.id, shortcut.packageName, shortcut.label, serial)).distinctBy { Triple(it.packageName, it.id, it.userSerial) })
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
        Layout(content = content, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .onGloballyPositioned { origin = it.positionInRoot() }) { measurables, constraints ->
            val menu = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val gap = 8.dp.roundToPx()
            val localAnchor = anchor.translate(-origin)
            val x = (localAnchor.center.x.toInt() - menu.width / 2).coerceIn(0, constraints.maxWidth - menu.width)
            val above = localAnchor.top.toInt() - gap - menu.height
            val below = localAnchor.bottom.toInt() + gap
            val y = when {
                above >= 0 -> above
                below + menu.height <= constraints.maxHeight -> below
                localAnchor.top > constraints.maxHeight - localAnchor.bottom -> above
                else -> below
            }.coerceIn(0, constraints.maxHeight - menu.height)
            layout(constraints.maxWidth, constraints.maxHeight) { menu.place(x, y) }
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
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF282828),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            modifier = Modifier.width(280.dp)
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "Lite Luncher",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Cerulean,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider(color = Color(0x22FFFFFF), thickness = 0.5.dp)

                ActionRowItem("Change Wallpaper", Icons.Rounded.Wallpaper, onWallpaper)
                HorizontalDivider(color = Color(0x15FFFFFF), thickness = 0.5.dp)

                ActionRowItem("Add Widget", Icons.Rounded.Widgets, onAddWidget)
                HorizontalDivider(color = Color(0x15FFFFFF), thickness = 0.5.dp)

                ActionRowItem("Settings", Icons.Rounded.Tune, onSettings)
                HorizontalDivider(color = Color(0x15FFFFFF), thickness = 0.5.dp)

                ActionRowItem("Manage Layout", Icons.Rounded.DashboardCustomize, onManageLayout)
            }
        }
    }
}

@Composable
private fun ActionRowItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, compact: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Cerulean, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = if (compact) 12.sp else 14.sp, color = Color.White)
    }
}

@Composable
fun EditAppDialog(app: AppEntry, state: LauncherState, update: ((LauncherState) -> LauncherState) -> Unit, onDismiss: () -> Unit) {
    var label by remember(app.key) { mutableStateOf(state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label) }
    var iconUri by remember(app.key) { mutableStateOf(state.overrides[app.key]?.iconUri.orEmpty()) }
    var shape by remember(app.key) { mutableStateOf(state.overrides[app.key]?.shape) }
    val context = LocalContext.current
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            iconUri = uri.toString()
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
            if (iconUri.isNotBlank()) TextButton(onClick = { iconUri = "" }) { Text("Use default icon") }

            Text("Icon Shape", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(IconShape.SQUIRCLE, IconShape.CIRCLE, IconShape.ROUNDED_SQUARE, IconShape.TEARDROP).forEach { s ->
                    FilterChip(
                        selected = (shape ?: state.prefs.appearance.iconShape) == s,
                        onClick = { shape = s },
                        label = { Text(s.name.take(4)) }
                    )
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            update { it.copy(overrides = it.overrides + (app.key to AppOverride(label.trim().takeIf { name -> name != app.label }.orEmpty(), iconUri, shape))) }
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
    val members = remember(folder.apps, apps) {
        val byKey = apps.associateBy { it.key }
        folder.apps.mapNotNull(byKey::get)
    }
    val stack = fromDock && state.prefs.dock.folderStack
    val folderColor = parseColor(state.prefs.folders.color)
    val background = folderColor.copy(alpha = folderColor.alpha * state.prefs.folders.opacity)
    val folderContent = if (folderColor.luminance() > .5f) Ink else Mist

    Box(Modifier.fillMaxSize().background(ColorForOverlay).clickable(onClick = onDismiss)) {
        if (stack) {
            val left = state.prefs.dock.side == "Left"
            Column(
                Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                    .appDropTarget(AppDropTarget.Folder(folder.id), 4)
                    .padding(start = if (left) 88.dp else 16.dp, end = if (left) 16.dp else 88.dp)
                    .widthIn(max = 260.dp),
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
                members.take(8).forEachIndexed { index, app ->
                    val fan = (kotlin.math.sin((index.toFloat() / (members.size - 1).coerceAtLeast(1)) * Math.PI) * 24).toInt()
                    Surface(
                        color = background,
                        contentColor = folderContent,
                        shape = RoundedCornerShape(22.dp),
                        shadowElevation = 10.dp,
                        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                        modifier = Modifier.offset(x = (if (left) fan else -fan).dp).widthIn(min = 160.dp, max = 240.dp)
                    ) {
                        Row(Modifier.appIconGestures(app, null, !state.folderLocked(folder.id), { actions.launch(app); onDismiss() }, { actions.menu(app) }, folder.id)
                            .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app, repository, state, 36)
                            Text(
                                state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label,
                                modifier = Modifier.padding(start = 12.dp),
                                fontSize = 13.sp,
                                color = folderContent,
                                maxLines = 1
                            )
                        }
                    }
                }
                if (members.size > 8) TextButton(onClick = { onDismiss(); actions.manageLayout() }) { Text("Manage all ${members.size} apps") }
                if (members.isEmpty()) TextButton(onClick = { onDismiss(); actions.manageLayout() }) { Text("Add applications") }
            }
        } else {
            Surface(
                Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 380.dp).appDropTarget(AppDropTarget.Folder(folder.id), 4).clickable {},
                color = background,
                contentColor = folderContent,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color(0x22FFFFFF))
            ) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.name, modifier = Modifier.weight(1f), fontSize = 22.sp, color = folderContent)
                        IconButton(onClick = { onDismiss(); actions.manageLayout() }) {
                            Icon(Icons.Rounded.Edit, "Edit folder", tint = Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (members.isEmpty()) Text("Add applications in Manage layout.", color = Muted, fontSize = 13.sp)
                    LazyColumn(Modifier.heightIn(max = 410.dp)) {
                        items(members.chunked(3)) { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { app ->
                                    AppTile(app, repository, state, state.prefs.home.copy(columns = 3), 0, { actions.launch(app); onDismiss() }, { actions.menu(app) }, Modifier.weight(1f),
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

private val ColorForOverlay = Color.Black.copy(alpha = .5f)
