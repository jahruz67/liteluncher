@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import android.content.Intent
import android.os.UserManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.liteluncher.data.*
import dev.liteluncher.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App Icon Context Card: Dark card (#282828, 12dp radius) with top launcher actions (Edit, App-Info, Remove)
 * separated by hairline dividers from bottom Android deep app shortcuts */
@Composable
fun AppContextDialog(
    app: AppEntry,
    state: LauncherState,
    repository: AppsRepository,
    actions: WorkspaceActions,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val shortcuts by produceState<List<NativeShortcut>>(emptyList(), app.key) {
        value = withContext(Dispatchers.IO) { repository.shortcuts(app) }
    }
    fun update(block: (LauncherState) -> LauncherState) { actions.update(block); onDismiss() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF282828),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            modifier = Modifier.width(280.dp)
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                // Header: App Icon and Title
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(app, repository, state, 32)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
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
                        Icon(Icons.Rounded.Edit, null, tint = Lime, modifier = Modifier.size(20.dp))
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

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            update { current ->
                                current.copy(
                                    homeApps = current.homeApps - app.key,
                                    dockApps = current.dockApps - app.key,
                                    favoriteApps = current.favoriteApps - app.key
                                )
                            }
                        }.padding(8.dp)
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("Remove", fontSize = 11.sp, color = Color(0xFFFF5252))
                    }
                }

                // Separated by hairline dividers from bottom Android deep app shortcuts
                if (shortcuts.isNotEmpty()) {
                    HorizontalDivider(color = Color(0x22FFFFFF), thickness = 0.5.dp)
                    Text(
                        "Shortcuts",
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(shortcuts, key = { it.id }) { shortcut ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { repository.launchShortcut(shortcut); onDismiss() }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Shortcut, null, tint = Lime, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(shortcut.label, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        val serial = requireNotNull(context.getSystemService(UserManager::class.java)).getSerialNumberForUser(shortcut.user)
                                        update { current ->
                                            current.copy(shortcuts = (current.shortcuts + PinnedShortcut(shortcut.id, shortcut.packageName, shortcut.label, serial)).distinctBy { Triple(it.packageName, it.id, it.userSerial) })
                                        }
                                    },
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
                    color = Lime,
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
private fun ActionRowItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Lime, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 14.sp, color = Color.White)
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

/** Widget Picker: Categorized alphabetically with available counts per app (e.g., Amazon Alexa: 4 widgets) */
@Composable
fun WidgetPicker(controller: WidgetController, onSelect: (android.appwidget.AppWidgetProviderInfo) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val providers by produceState<List<android.appwidget.AppWidgetProviderInfo>>(emptyList()) {
        value = withContext(Dispatchers.IO) { controller.providers() }
    }
    val grouped = remember(providers) {
        providers.groupBy { provider ->
            runCatching {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(provider.provider.packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            }.getOrDefault(provider.provider.packageName)
        }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    var expandedApp by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF282828),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Add a widget", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Lime)
                Spacer(Modifier.height(10.dp))
                if (providers.isEmpty()) {
                    Text("No widget providers available.", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        grouped.forEach { (appName, appWidgets) ->
                            item(key = "group:$appName") {
                                val isExpanded = expandedApp == appName
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { expandedApp = if (isExpanded) null else appName }
                                            .padding(vertical = 10.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Widgets, null, tint = Lime, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "$appName: ${appWidgets.size} widget${if (appWidgets.size == 1) "" else "s"}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Color.Gray)
                                    }
                                    if (isExpanded) {
                                        Column(Modifier.fillMaxWidth().padding(start = 28.dp, bottom = 6.dp)) {
                                            appWidgets.forEach { widget ->
                                                val widgetLabel = runCatching { widget.loadLabel(context.packageManager) }.getOrDefault(widget.provider.shortClassName)
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .clickable { onSelect(widget) }
                                                        .padding(vertical = 8.dp, horizontal = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(widgetLabel, fontSize = 13.sp)
                                                        Text("${widget.minWidth} × ${widget.minHeight} dp", fontSize = 10.sp, color = Color.Gray)
                                                    }
                                                    Icon(Icons.Rounded.Add, null, tint = Lime, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = Color(0x15FFFFFF), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
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
    val members = folder.apps.mapNotNull { key -> apps.find { it.key == key } }
    val stack = fromDock && state.prefs.dock.folderStack
    val folderColor = parseColor(state.prefs.folders.color)
    val background = folderColor.copy(alpha = folderColor.alpha * state.prefs.folders.opacity)
    val folderContent = if (folderColor.luminance() > .5f) Ink else Mist

    Box(Modifier.fillMaxSize().background(ColorForOverlay).clickable(onClick = onDismiss)) {
        if (stack) {
            val left = state.prefs.dock.side == "Left"
            Column(
                Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
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
                        onClick = { actions.launch(app); onDismiss() },
                        color = background,
                        contentColor = folderContent,
                        shape = RoundedCornerShape(22.dp),
                        shadowElevation = 10.dp,
                        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                        modifier = Modifier.offset(x = (if (left) fan else -fan).dp).widthIn(min = 160.dp, max = 240.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 380.dp).clickable {},
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
                                    AppTile(app, repository, state, state.prefs.home.copy(columns = 3), 0, { actions.launch(app); onDismiss() }, { actions.menu(app) }, Modifier.weight(1f))
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
