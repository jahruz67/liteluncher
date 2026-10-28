@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liteluncher.data.*
import dev.liteluncher.platform.AppEntry
import dev.liteluncher.platform.AppsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun iconShape(shape: IconShape): Shape = when (shape) {
    IconShape.CIRCLE -> CircleShape
    IconShape.SQUIRCLE -> RoundedCornerShape(32)
    IconShape.ROUNDED_SQUARE -> RoundedCornerShape(22)
    IconShape.TEARDROP -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 12, bottomStartPercent = 50)
    IconShape.ORIGINAL -> RoundedCornerShape(0)
}

@Composable
fun AppIcon(app: AppEntry, repository: AppsRepository, state: LauncherState, size: Int = 48, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val override = state.overrides[app.key]
    val backupBitmap = rememberBackupBitmap(override?.backupIcon.takeIf { override?.iconUri.isNullOrBlank() })
    val pack = state.prefs.appearance.iconPack
    val iconRevision by repository.iconRevision.collectAsStateWithLifecycle()
    val cached = if (override?.iconUri.isNullOrBlank()) repository.cachedIconBitmap(app, pack) else null
    val bitmap by produceState<android.graphics.Bitmap?>(cached, app.key, override?.iconUri, pack, iconRevision) {
        value = withContext(Dispatchers.IO) {
            val custom = override?.iconUri?.takeIf { it.startsWith("content://") }?.let { uri ->
                runCatching {
                    val parsed = android.net.Uri.parse(uri)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 256 && bounds.outHeight / it <= 256 }
                    context.contentResolver.openInputStream(parsed)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }.getOrNull()
            }
            custom ?: repository.iconBitmap(app, pack)
        }
    }
    val shape = iconShape(override?.shape ?: state.prefs.appearance.iconShape)
    Box(modifier.size(size.dp).clip(shape), contentAlignment = Alignment.Center) {
        val displayed = backupBitmap ?: bitmap
        if (displayed != null) Image(displayed.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Box(Modifier.fillMaxSize().background(Panel), contentAlignment = Alignment.Center) {
            Text(app.label.take(1).uppercase(), color = Cerulean, fontSize = (size / 2).sp)
        }
    }
}

@Composable
fun AppTile(
    app: AppEntry, repository: AppsRepository, state: LauncherState, prefs: GridPrefs,
    badgeCount: Int, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier,
    draggable: Boolean = false, dragArea: LayoutArea? = null, dragFolderId: String? = null
) {
    val label = state.overrides[app.key]?.label?.takeIf { it.isNotBlank() } ?: app.label
    val iconSize = (56 * prefs.iconScale).toInt()
    val textScale = prefs.textScale.coerceIn(0.5f, 1.5f)
    Column(modifier.clip(RoundedCornerShape(16.dp)).appIconGestures(app, dragArea, draggable, onClick, onLongClick, dragFolderId)
        .padding(vertical = 8.dp, horizontal = 2.dp).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box {
            AppIcon(app, repository, state, iconSize)
            if (state.prefs.badges.enabled && badgeCount > 0) {
                val badgeSize = ((if (state.prefs.badges.counts) 19 else 10) * state.prefs.badges.scale).dp
                Box(Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp).size(badgeSize)
                    .background(parseColor(state.prefs.badges.color), CircleShape).border(2.dp, Ink, CircleShape), contentAlignment = Alignment.Center) {
                    if (state.prefs.badges.counts) Text(if (badgeCount > 99) "99+" else "$badgeCount", color = Ink, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (prefs.labels) {
            Spacer(Modifier.height(5.dp))
            Text(label, color = LocalContentColor.current, fontSize = (12 * textScale).sp, lineHeight = (15 * textScale).sp,
                textAlign = TextAlign.Center, maxLines = if (prefs.multiline) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun FolderTile(folder: FolderItem, apps: List<AppEntry>, repository: AppsRepository, state: LauncherState, prefs: GridPrefs,
    onClick: () -> Unit, onLongClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    val backupBitmap = rememberBackupBitmap(folder.backupIcon)
    val representative = apps.firstOrNull { it.key in folder.apps }
    val area = if (folder.id in state.dockFolders) LayoutArea.DOCK else LayoutArea.HOME
    Column(modifier.clip(RoundedCornerShape(16.dp)).appDropTarget(AppDropTarget.Folder(folder.id), 3)
        .then(if (representative != null) Modifier.appIconGestures(representative, area,
            !state.folderLocked(folder.id) && (folder.id in state.homeFolders || folder.id in state.dockFolders), onClick, onLongClick, movingFolderId = folder.id)
        else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val size = (56 * prefs.iconScale).dp
        val folderColor = parseColor(state.prefs.folders.color)
        val textScale = prefs.textScale.coerceIn(0.5f, 1.5f)
        val shape = iconShape(folder.shape ?: state.prefs.folders.shape)
        Box(Modifier.size(size).clip(shape)
            .background(folderColor.copy(alpha = folderColor.alpha * state.prefs.folders.opacity)), contentAlignment = Alignment.Center) {
            val members = remember(folder.apps, apps) {
                folder.apps.asSequence().mapNotNull { key -> apps.find { it.key == key } }.take(4).toList()
            }
            if (backupBitmap != null) Image(backupBitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else if (members.isEmpty()) Icon(Icons.Rounded.Folder, null, tint = Cerulean)
            else if (state.prefs.folders.stacked) {
                members.take(3).reversed().forEachIndexed { index, app ->
                    AppIcon(app, repository, state, (28 * state.prefs.folders.iconScale).toInt(), Modifier.offset((index * 7 - 7).dp, (index * 5 - 5).dp))
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                members.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { AppIcon(it, repository, state, (18 * state.prefs.folders.iconScale).toInt()) }
                } }
            }
        }
        if (prefs.labels) {
            Spacer(Modifier.height(5.dp))
            Text(folder.name, fontSize = (12 * textScale).sp, maxLines = if (prefs.multiline) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1E1E1E),
            contentColor = Color.White,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, Color(0x22FFFFFF))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(message, fontSize = 13.sp, color = Color.White)
                Spacer(Modifier.width(16.dp))
                Text(
                    "Undo",
                    color = Cerulean,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onUndo)
                )
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
        modifier = modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isActive = index == currentPage
            val width = if (isActive) 18.dp else 6.dp
            val color = if (isActive) Color.White else Color(0x60FFFFFF)
            Box(
                Modifier
                    .width(width)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
                    .then(if (onSelectPage != null) Modifier.clickable { onSelectPage(index) } else Modifier)
            )
        }
    }
}

@Composable
fun DestinationCapsule(destination: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xCC000000),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.DriveFileMove, null, tint = Cerulean, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(destination, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null, onTrailing: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) TextButton(onClick = onTrailing, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(trailing, fontSize = 11.sp) }
    }
}

@Composable
fun PageHeader(eyebrow: String, title: String, icon: ImageVector, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(7.dp))
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
        }
        IconButton(onClick = onAction, modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .7f), CircleShape)) {
            Icon(icon, contentDescription = "Page options")
        }
    }
}

@Composable
fun EmptyCard(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp).clip(RoundedCornerShape(24.dp))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = .75f)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(15.dp))
        Text(title, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun ActionRow(label: String, icon: ImageVector, onClick: () -> Unit, detail: String? = null) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp)
                if (detail != null) Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}
