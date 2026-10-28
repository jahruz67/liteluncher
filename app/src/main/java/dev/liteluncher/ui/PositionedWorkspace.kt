@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.UserManager
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.liteluncher.data.*
import dev.liteluncher.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberBackupBitmap(icon: BackupIcon?): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, icon) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (!icon?.bitmap.isNullOrEmpty()) {
                    val bytes = Base64.decode(icon!!.bitmap, Base64.DEFAULT)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                    var sample = 1
                    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } else if (icon != null && icon.packageName.isNotBlank() && icon.resourceName.isNotBlank()) {
                    val resources = context.packageManager.getResourcesForApplication(icon.packageName)
                    val type = icon.resourceName.substringBeforeLast('/', "drawable").substringAfter(':')
                    val id = resources.getIdentifier(icon.resourceName.substringAfterLast('/'), type, icon.packageName)
                    if (id == 0) null else resources.getDrawable(id, null).toBitmap(168, 168)
                } else null
            }.getOrNull()
        }
    }
    return bitmap
}

@Composable
internal fun ShortcutTile(shortcut: PinnedShortcut, state: LauncherState, apps: List<AppEntry>, repository: AppsRepository,
    prefs: GridPrefs, actions: WorkspaceActions, modifier: Modifier = Modifier, onLaunch: () -> Unit = {}) {
    val context = LocalContext.current
    val bitmap = rememberBackupBitmap(shortcut.backupIcon)
    val app = apps.firstOrNull { it.packageName == shortcut.packageName && it.key.substringAfterLast('|').toLongOrNull() == shortcut.userSerial }
    Column(modifier.semantics { contentDescription = shortcut.label }.combinedClickable(onClick = {
        val user = context.getSystemService(UserManager::class.java)?.getUserForSerialNumber(shortcut.userSerial)
        if (user == null) SystemActions.error(context, "This shortcut's profile is unavailable")
        else if (actions.shortcut(NativeShortcut(shortcut.id, shortcut.packageName, shortcut.label, user))) onLaunch()
    }, onLongClick = {
        val locked = when {
            shortcut.container == "dock" -> state.prefs.dock.preventChanges
            shortcut.container.startsWith("folder:") -> state.folderLocked(shortcut.container.substringAfter(':'))
            shortcut.container.startsWith("desktop:") -> state.prefs.desktop.preventChanges
            else -> state.prefs.home.preventChanges
        }
        if (!locked) actions.update { current ->
            val stillLocked = when {
                shortcut.container == "dock" -> current.prefs.dock.preventChanges
                shortcut.container.startsWith("desktop:") -> current.prefs.desktop.preventChanges
                shortcut.container.startsWith("folder:") -> current.folderLocked(shortcut.container.substringAfter(':'))
                else -> current.prefs.home.preventChanges
            }
            if (stillLocked) current else current.copy(shortcuts = current.shortcuts.filterNot { it.layoutKey == shortcut.layoutKey && it.container == shortcut.container })
        }
    }).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val size = (56 * prefs.iconScale).toInt()
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), shortcut.label, Modifier.size(size.dp), contentScale = ContentScale.Fit)
            app != null -> AppIcon(app, repository, state, size)
            else -> Icon(Icons.Rounded.Shortcut, shortcut.label, Modifier.size(size.dp))
        }
        if (prefs.labels) Text(shortcut.label, modifier = Modifier.padding(top = 5.dp), fontSize = (12 * prefs.textScale).sp,
            maxLines = if (prefs.multiline) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun WorkspaceEntry(key: String, container: String, state: LauncherState, apps: List<AppEntry>, repository: AppsRepository,
    prefs: GridPrefs, badges: Map<String, Int>, actions: WorkspaceActions, modifier: Modifier = Modifier, onLaunch: () -> Unit = {}) {
    val folder = if (key.startsWith("folder:")) state.folders.find { "folder:${it.id}" == key } else null
    val shortcut = state.shortcuts.find { it.layoutKey == key && it.container == container }
    val app = apps.find { it.key == key }
    val area = when (container) { "home" -> LayoutArea.HOME; "dock" -> LayoutArea.DOCK; else -> null }
    val locked = when {
        container.startsWith("desktop:") -> state.prefs.desktop.preventChanges
        container.startsWith("folder:") -> state.folderLocked(container.substringAfter(':'))
        area != null -> state.layoutLocked(area)
        else -> true
    }
    var removal by remember(key, container) { mutableStateOf(false) }
    fun remove() { actions.update { current ->
        val currentLocked = when {
            container.startsWith("desktop:") -> current.prefs.desktop.preventChanges
            container.startsWith("folder:") -> current.folderLocked(container.substringAfter(':'))
            area != null -> current.layoutLocked(area)
            else -> true
        }
        if (currentLocked) current else when {
            container.startsWith("desktop:") -> current.copy(desktopApps = current.desktopApps.mapValues { (page, entries) -> if (container == "desktop:$page") entries - key else entries })
            container.startsWith("folder:") -> current.copy(folders = current.folders.map { if (container == "folder:${it.id}") it.copy(apps = it.apps - key) else it })
            area != null -> removeLayoutApp(current, area, key)
            else -> current
        }
    }; removal = false }
    if (removal) AlertDialog(onDismissRequest = { removal = false }, title = { Text("Remove entry?") }, text = { Text(key) },
        confirmButton = { TextButton(onClick = { remove() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removal = false }) { Text("Cancel") } })
    when {
        folder != null -> FolderTile(folder, apps, repository, state, prefs, { actions.folder(folder, container == "dock") },
            { actions.folderActions(folder) }, modifier, container)
        shortcut != null -> ShortcutTile(shortcut, state, apps, repository, prefs, actions, modifier, onLaunch)
        app != null -> AppTile(app, repository, state, prefs, badges[app.badgeKey] ?: 0, { actions.launch(app); onLaunch() },
            { if (container == "dock") actions.dockMenu(app) else actions.menu(app) }, modifier,
            draggable = !locked,
            dragArea = area, dragFolderId = container.takeIf { it.startsWith("folder:") }?.substringAfter(':'),
            desktopPage = container.takeIf { it.startsWith("desktop:") }?.substringAfter(':')?.toIntOrNull())
        else -> Column(modifier.combinedClickable(onClick = {}, onLongClick = { removal = !locked }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Apps, "App not installed", Modifier.size(40.dp), tint = Muted)
            if (prefs.labels) Text(key.substringBefore('/'), fontSize = 10.sp, maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun PositionedWorkspace(container: String, state: LauncherState, apps: List<AppEntry>, repository: AppsRepository,
    prefs: GridPrefs, badges: Map<String, Int>, actions: WorkspaceActions, modifier: Modifier = Modifier) {
    if (prefs.listMode && !container.startsWith("desktop:")) {
        Column(modifier.verticalScroll(rememberScrollState())) {
            state.workspaceKeys(container).forEach { key ->
                val app = apps.find { it.key == key }
                if (app != null) AppListRow(app, repository, state, { actions.launch(app) }, { actions.menu(app) }, prefs,
                    draggable = container == "home" && !state.prefs.home.preventChanges,
                    dragArea = if (container == "home") LayoutArea.HOME else null)
                else WorkspaceEntry(key, container, state, apps, repository, prefs, badges, actions, Modifier.fillMaxWidth().height(88.dp))
            }
        }
        return
    }
    val positions = remember(state, container, prefs.columns, prefs.rows) { state.workspacePositions(container, prefs.columns, prefs.rows) }
    val overflow = state.workspaceKeys(container).filterNot { key -> positions.any { it.key == key } }
    Column(modifier) {
    if (overflow.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
        Text("Entries outside the grid", style = MaterialTheme.typography.labelSmall)
        overflow.forEach { key -> WorkspaceEntry(key, container, state, apps, repository, prefs.copy(iconScale = .5f, labels = true), badges, actions, Modifier.fillMaxWidth().height(76.dp)) }
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val cellWidth = maxWidth / prefs.columns
        val cellHeight = maxHeight / prefs.rows
        val page = container.takeIf { it.startsWith("desktop:") }?.substringAfter(':')?.toIntOrNull()
        if (page != null && !state.prefs.desktop.preventChanges && LocalAppDrag.current?.active != null) {
            repeat(prefs.rows) { row -> repeat(prefs.columns) { column ->
                Box(Modifier.offset(cellWidth * column, cellHeight * row).width(cellWidth).height(cellHeight)
                    .appDropTarget(AppDropTarget.DesktopCell(page, column, row), 1))
            } }
        }
        positions.forEach { position ->
            key(position.key) {
                WorkspaceEntry(position.key, container, state, apps, repository, prefs, badges, actions,
                    Modifier.offset(x = cellWidth * position.column, y = cellHeight * position.row)
                        .width(cellWidth * position.columns).height(cellHeight * position.rows))
            }
        }
    }
    }
}

@Composable
internal fun PendingWidgetTile(item: PortableWidget, controller: WidgetController, modifier: Modifier = Modifier, onRemove: () -> Unit) {
    val context = LocalContext.current
    Column(modifier.background(Panel).clickable {
        val provider = controller.providers().find { it.provider.flattenToString() == item.provider }
        if (provider == null) SystemActions.error(context, "Install this widget's app before restoring it")
        else controller.add(provider, item.location())
    }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Widgets, null, tint = Cerulean)
        Text("Restore widget", fontSize = 12.sp)
        Text(item.provider.substringBefore('/'), fontSize = 10.sp, maxLines = 2, textAlign = TextAlign.Center)
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}
