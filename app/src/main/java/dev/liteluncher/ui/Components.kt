@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liteluncher.data.*
import dev.liteluncher.platform.AppEntry
import dev.liteluncher.platform.AppsRepository
import kotlinx.coroutines.withContext

fun iconShape(shape: IconShape): Shape = when (shape) {
    IconShape.CIRCLE -> CircleShape
    IconShape.SQUIRCLE -> RoundedCornerShape(32)
    IconShape.ROUNDED_SQUARE -> RoundedCornerShape(22)
    IconShape.TEARDROP -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 12, bottomStartPercent = 50)
    IconShape.ORIGINAL -> RoundedCornerShape(0)
}

@Composable
internal fun badgeCounts(prefs: LauncherPrefs): Boolean = when (prefs.badges.style) {
    "Count" -> true
    "Dot" -> false
    else -> when (prefs.appearance.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        else -> true
    }
}

@Composable
fun AppIcon(app: AppEntry, repository: AppsRepository, state: LauncherState, size: Int = 48, modifier: Modifier = Modifier) {
    val override = state.overrides[app.key]
    val pack = state.prefs.appearance.iconPack
    val forceAdaptive = state.prefs.appearance.forceAdaptive
    val iconRevision by repository.iconRevision.collectAsStateWithLifecycle()
    val uri = override?.iconUri.orEmpty()
    val backup = override?.backupIcon.takeIf { uri.isBlank() }
    val cachedBackup = backup?.let { repository.imageLoader.cached(it) }
    val cached = cachedBackup ?: if (uri.isBlank()) repository.cachedIconBitmap(app, pack, forceAdaptive) else repository.imageLoader.cachedCustom(uri)
    val bitmap by produceState<android.graphics.Bitmap?>(cached, app.key, uri, backup, pack, forceAdaptive, iconRevision) {
        value = cached
        if (cachedBackup != null || (backup == null && cached != null)) return@produceState
        value = withContext(repository.imageLoader.dispatcher) {
            backup?.let { repository.imageLoader.backup(it) } ?: cached ?:
                repository.imageLoader.custom(uri) ?: repository.iconBitmap(app, pack, forceAdaptive)
        }
    }
    val selectedShape = override?.shape ?: state.prefs.appearance.iconShape
    val shape = iconShape(if (forceAdaptive && selectedShape == IconShape.ORIGINAL) IconShape.SQUIRCLE else selectedShape)
    Box(modifier.size(size.dp).clip(shape), contentAlignment = Alignment.Center) {
        val displayed = bitmap
        if (displayed != null) Image(displayed.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(app.label.take(1).uppercase(), color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = (size / 2).sp)
        }
    }
}

@Composable
fun AppTile(
    app: AppEntry, repository: AppsRepository, state: LauncherState, prefs: GridPrefs,
    badgeCount: Int, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier,
    draggable: Boolean = false, dragArea: LayoutArea? = null, dragFolderId: String? = null, desktopPage: Int? = null
) {
    val label = state.overrides[app.key]?.label?.takeIf { it.isNotBlank() } ?: app.label
    val iconSize = (56 * prefs.iconScale).toInt()
    val textScale = prefs.textScale.coerceIn(0.5f, 1.5f)
    val showCount = badgeCounts(state.prefs)
    var iconBounds by remember { mutableStateOf(Rect.Zero) }
    Column(modifier.clip(RoundedCornerShape(16.dp)).appIconGestures(app, dragArea, draggable, onClick, onLongClick, dragFolderId,
        desktopPage = desktopPage, menuBounds = { iconBounds })
        .padding(vertical = 4.dp, horizontal = 2.dp).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box {
            AppIcon(app, repository, state, iconSize, Modifier.onGloballyPositioned { iconBounds = it.boundsInRoot() })
            if (state.prefs.badges.enabled && badgeCount > 0) {
                val badgeSize = ((if (showCount) 19 else 10) * state.prefs.badges.scale).dp
                Box(Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp).size(badgeSize)
                    .background(parseColor(state.prefs.badges.color), CircleShape).border(2.dp, Ink, CircleShape), contentAlignment = Alignment.Center) {
                    if (showCount) Text(if (badgeCount > 99) "99+" else "$badgeCount", color = contrastContent(parseColor(state.prefs.badges.color)), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (prefs.labels) {
            Spacer(Modifier.height(3.dp))
            Text(label, color = LocalContentColor.current, fontSize = (12 * textScale).sp, lineHeight = (15 * textScale).sp,
                textAlign = TextAlign.Center, maxLines = if (prefs.multiline) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun FolderTile(folder: FolderItem, apps: List<AppEntry>, repository: AppsRepository, state: LauncherState, prefs: GridPrefs,
    onClick: () -> Unit, onLongClick: () -> Unit = {}, modifier: Modifier = Modifier, container: String = "home") {
    val context = LocalContext.current
    val iconRevision by repository.iconRevision.collectAsStateWithLifecycle()
    val cached = repository.imageLoader.cachedCustom(folder.iconUri)
    val customBitmap by produceState<android.graphics.Bitmap?>(cached, folder.iconUri, iconRevision) {
        value = cached
        if (cached != null || folder.iconUri.isBlank()) return@produceState
        value = withContext(repository.imageLoader.dispatcher) { repository.imageLoader.custom(folder.iconUri) }
    }
    val backupBitmap = customBitmap ?: rememberBackupBitmap(folder.backupIcon, repository)
    val representative = apps.firstOrNull { it.key in folder.apps } ?: AppEntry("folder:${folder.id}", context.packageName,
        folder.name, android.content.ComponentName(context, dev.liteluncher.MainActivity::class.java), android.os.Process.myUserHandle())
    val area = when (container) { "dock" -> LayoutArea.DOCK; "home" -> LayoutArea.HOME; else -> null }
    val folderPrefs = state.prefs.folders
    var iconBounds by remember { mutableStateOf(Rect.Zero) }
    Column(modifier.semantics { contentDescription = folder.name }.clip(RoundedCornerShape(16.dp)).appDropTarget(AppDropTarget.Folder(folder.id), 3)
        .then(Modifier.appIconGestures(representative, area,
            !state.folderLocked(folder.id) && (container == "home" && folder.id in state.homeFolders || container == "dock" && folder.id in state.dockFolders || container.startsWith("desktop:") && folder.id in state.desktopApps.values.flatten()),
            onClick, onLongClick, movingFolderId = folder.id,
            desktopPage = container.takeIf { it.startsWith("desktop:") }?.substringAfter(':')?.toIntOrNull(),
            menuBounds = { iconBounds })
        ).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val size = (56 * prefs.iconScale).dp
        val folderColor = if (folderPrefs.background == "Custom") parseColor(folderPrefs.color)
            else surfaceColor(state.prefs.appearance.launcher, MaterialTheme.colorScheme.primary)
        val shape = iconShape(folder.shape ?: folderPrefs.shape)
        Box(Modifier.size(size).onGloballyPositioned { iconBounds = it.boundsInRoot() }.clip(shape)
            .background(folderColor.copy(alpha = folderColor.alpha * folderPrefs.opacity)), contentAlignment = Alignment.Center) {
            val members = remember(folder.apps, apps) {
                folder.apps.asSequence().mapNotNull { key -> apps.find { it.key == key } }.take(4).toList()
            }
            if (backupBitmap != null) Image(backupBitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (members.isEmpty()) Icon(Icons.Rounded.Folder, null, tint = parseColor(folderPrefs.iconColor, Cerulean))
            else if (folderPrefs.stacked) {
                members.take(3).reversed().forEachIndexed { index, app ->
                    AppIcon(app, repository, state, (28 * folderPrefs.iconScale).toInt(), Modifier.offset((index * 7 - 7).dp, (index * 5 - 5).dp))
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                members.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { AppIcon(it, repository, state, (18 * folderPrefs.iconScale).toInt()) }
                } }
            }
        }
        if (folderPrefs.labels) {
            Spacer(Modifier.height(3.dp))
            Text(folder.name, fontSize = (12 * folderPrefs.textScale).sp,
                maxLines = if (folderPrefs.multiline) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SearchEngineLogo(engine: WebEngine, modifier: Modifier = Modifier) {
    val (bg, label, textColor) = when (engine) {
        WebEngine.GOOGLE -> Triple(Color(0xFF4285F4), "G", Color.White)
        WebEngine.BING -> Triple(Color(0xFF008373), "b", Color.White)
        WebEngine.DUCKDUCKGO -> Triple(Color(0xFFDE5833), "D", Color.White)
        WebEngine.YAHOO -> Triple(Color(0xFF6001D2), "Y!", Color.White)
        WebEngine.WIKIPEDIA -> Triple(Color(0xFF333333), "W", Color.White)
        WebEngine.BAIDU -> Triple(Color(0xFF2319DC), "度", Color.White)
        WebEngine.ECOSIA -> Triple(Color(0xFF28A745), "E", Color.White)
        WebEngine.NAVER -> Triple(Color(0xFF03C75A), "N", Color.White)
        WebEngine.QWANT -> Triple(Color(0xFF5C24E5), "Q", Color.White)
        WebEngine.STARTPAGE -> Triple(Color(0xFF1B3D8A), "SP", Color.White)
        WebEngine.BRAVE -> Triple(Color(0xFFFF3300), "B", Color.White)
        WebEngine.CUSTOM -> Triple(Color(0xFF4A4A4A), "URL", Color.White)
    }
    Surface(
        modifier = modifier.size(26.dp),
        shape = RoundedCornerShape(7.dp),
        color = bg
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = textColor,
                fontSize = if (label.length > 2) 8.sp else 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun DeletionSnackbar(
    visible: Boolean,
    message: String = "Entry deleted",
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onUndo) { Text("Undo", color = MaterialTheme.colorScheme.inversePrimary) }
            }
        }
    }
}

@Composable
fun PageIndicators(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    onSelectPage: ((Int) -> Unit)? = null
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isActive = index == currentPage
            val width = if (isActive) 16.dp else 5.dp
            val color = if (isActive) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = .35f)
            Box(
                Modifier
                    .width(width)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
                    .semantics {
                        contentDescription = "Page ${index + 1}"
                        selected = isActive
                    }
                    .then(if (onSelectPage != null) Modifier.clickable(role = Role.Button) { onSelectPage(index) } else Modifier)
            )
        }
    }
}

@Composable
fun DestinationCapsule(destination: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(destination, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null, onTrailing: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (trailing != null) TextButton(onClick = onTrailing, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(trailing, fontSize = 11.sp) }
    }
}

@Composable
fun PageHeader(eyebrow: String, title: String, icon: ImageVector, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        FilledTonalIconButton(onClick = onAction, modifier = Modifier.size(40.dp)) {
            Icon(icon, contentDescription = "Page options")
        }
    }
}

@Composable
fun EmptyCard(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(MaterialTheme.shapes.large)
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun ActionRow(label: String, icon: ImageVector, onClick: () -> Unit, detail: String? = null) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp)
                if (detail != null) Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}
