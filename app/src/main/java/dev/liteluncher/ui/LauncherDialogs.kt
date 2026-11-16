@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import android.content.Intent
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liteluncher.data.*
import dev.liteluncher.platform.*

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
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    source: Screen = Screen.HOME,
    fromDock: Boolean = false,
    folderId: String? = null,
    onRemove: () -> Unit = {}
) {
    val removable = when {
        folderId != null -> state.folders.any { it.id == folderId && app.key in it.apps } && !state.folderLocked(folderId)
        fromDock -> app.key in state.dockApps && !state.prefs.dock.preventChanges
        source == Screen.DESKTOP -> state.desktopApps.values.any { app.key in it } && !state.prefs.desktop.preventChanges
        source == Screen.HOME -> app.key in state.homeApps && !state.prefs.home.preventChanges
        source == Screen.FAVORITES -> true
        else -> false
    }

    AnchoredIconMenu(anchor, onDismiss) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MenuBackground,
            contentColor = MenuText,
            shadowElevation = 8.dp,
            modifier = Modifier.width(208.dp).heightIn(max = 360.dp)
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ActionRowItem("Edit", Icons.Default.Edit, onEdit)
                ActionRowItem("App Info", Icons.Outlined.Info, { repository.appInfo(app); onDismiss() })
                when {
                    folderId != null || fromDock -> ActionRowItem("Remove", Icons.Default.Close, onRemove, enabled = removable)
                    source == Screen.APPS -> ActionRowItem("Uninstall", Icons.Rounded.DeleteOutline, { repository.uninstall(app); onDismiss() })
                    source != Screen.SEARCH -> ActionRowItem("Remove", Icons.Default.Close, onRemove, enabled = removable)
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
            modifier = Modifier.width(248.dp)
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
private fun ActionRowItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .alpha(if (enabled) 1f else .38f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MenuIcon, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Normal, color = MenuText)
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
    Box(Modifier.fillMaxSize().alpha(if (draggingOutside) 0f else 1f).background(ColorForOverlay).imePadding().clickable(onClick = onDismiss)) {
        if (!stack) {
            val keys = state.workspaceKeys("folder:${folder.id}")
            Surface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 360.dp)
                .onGloballyPositioned { bounds = it.boundsInRoot() }.appDropTarget(AppDropTarget.Folder(folder.id), 4).clickable {},
                color = background, contentColor = folderContent, shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(14.dp)) {
                    EditableFolderName(folder, state, actions)
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
                    EditableFolderName(folder, state, actions, Modifier.padding(horizontal = 12.dp), 12.sp, Mist)
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
                Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 360.dp)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }.appDropTarget(AppDropTarget.Folder(folder.id), 4).clickable {},
                color = background,
                contentColor = folderContent,
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color(0x22FFFFFF))
            ) {
                Column(Modifier.padding(14.dp)) {
                    EditableFolderName(folder, state, actions, color = folderContent)
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
private fun EditableFolderName(
    folder: FolderItem, state: LauncherState, actions: WorkspaceActions,
    modifier: Modifier = Modifier, fontSize: TextUnit = 20.sp, color: Color = LocalContentColor.current
) {
    var name by rememberSaveable(folder.id) { mutableStateOf(folder.name) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = name,
        onValueChange = { value ->
            name = value.take(128)
            val savedName = name.trim()
            actions.update { current ->
                if (current.folderLocked(folder.id)) current else current.copy(folders = current.folders.map {
                    if (it.id == folder.id) it.copy(name = savedName) else it
                })
            }
        },
        enabled = !state.folderLocked(folder.id),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = fontSize, color = color),
        cursorBrush = SolidColor(color),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
        modifier = modifier.fillMaxWidth().onFocusChanged {
            if (!it.isFocused) name = name.trim()
        }.padding(vertical = 8.dp).semantics { contentDescription = "Folder name" }
    )
}

@Composable
internal fun FolderContextMenu(folder: FolderItem, anchor: Rect, locked: Boolean, onEdit: () -> Unit, onDismiss: () -> Unit, onRemove: () -> Unit = {}) {
    AnchoredIconMenu(anchor, onDismiss) {
        Surface(shape = RoundedCornerShape(10.dp), color = MenuBackground, shadowElevation = 8.dp, modifier = Modifier.width(208.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text(folder.name, color = Color.White)
                ActionRowItem("Edit", Icons.Rounded.Edit, onEdit, enabled = !locked)
                ActionRowItem("Remove", Icons.Default.Close, onRemove, enabled = !locked)
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
    }, confirmButton = { TextButton(onClick = { onSave(folder.copy(name = name.trim(), apps = selected.distinct())) }) { Text("Save folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
