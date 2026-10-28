@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.liteluncher.data.*
import dev.liteluncher.platform.AppEntry
import dev.liteluncher.platform.AppsRepository
import kotlin.math.roundToInt

internal data class DraggedApp(val app: AppEntry, val source: LayoutArea?, val token: Any, val folderId: String?, val movingFolderId: String? = null)
internal data class AppDropRegion(val bounds: Rect, val target: AppDropTarget, val priority: Int)

internal class AppDragController(private val onDragStart: () -> Unit, private val onDrop: (DraggedApp, AppDropTarget) -> Unit) {
    var active by mutableStateOf<DraggedApp?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
        private set
    var menuAnchor by mutableStateOf(Rect.Zero)
    private var held: DraggedApp? = null
    private var start = Offset.Zero
    private var slop = 0f
    val regions = mutableStateMapOf<Any, AppDropRegion>()
    val dismissedFolders = mutableStateListOf<String>()
    val hovered by derivedStateOf {
        if (active == null) null else regions.values.filter {
            val folder = it.target as? AppDropTarget.Folder
            it.bounds.contains(position) && (folder == null || folder.id !in dismissedFolders)
        }.maxByOrNull { it.priority }
    }

    fun begin(app: AppEntry, source: LayoutArea?, token: Any, point: Offset, touchSlop: Float, folderId: String?, movingFolderId: String? = null) {
        start = point
        position = point
        slop = touchSlop
        dismissedFolders.clear()
        held = DraggedApp(app, source, token, folderId, movingFolderId)
        active = null
    }

    fun move(token: Any, amount: Offset) {
        val item = held?.takeIf { it.token === token } ?: return
        position += amount
        if (active == null && (position - start).getDistance() > slop) {
            active = item
            onDragStart()
        }
        val sourceFolder = item.folderId
        if (active != null && sourceFolder != null && sourceFolder !in dismissedFolders) {
            val container = regions.values.firstOrNull {
                it.priority == 4 && (it.target as? AppDropTarget.Folder)?.id == sourceFolder
            }
            if (container != null && !container.bounds.contains(position)) dismissedFolders.add(sourceFolder)
        }
    }

    fun finish(token: Any) {
        val item = held?.takeIf { it.token === token } ?: return
        val target = if (active != null) hovered?.target else null
        cancel(token)
        if (target != null) onDrop(item, target)
    }

    fun cancel(token: Any? = null) {
        if (token == null || held?.token === token) {
            held = null
            active = null
        }
    }
}

internal val LocalAppDrag = staticCompositionLocalOf<AppDragController?> { null }

@Composable
internal fun Modifier.appDropTarget(target: AppDropTarget, priority: Int = 0, centerOnly: Boolean = false): Modifier {
    val drag = LocalAppDrag.current ?: return this
    val token = remember { Any() }
    DisposableEffect(drag, token) { onDispose { drag.regions.remove(token) } }
    return onGloballyPositioned {
        val bounds = it.boundsInRoot()
        val hitBounds = if (centerOnly) Rect(
            bounds.left + bounds.width * .25f, bounds.top + bounds.height * .25f,
            bounds.right - bounds.width * .25f, bounds.bottom - bounds.height * .25f
        ) else bounds
        drag.regions[token] = AppDropRegion(hitBounds, target, priority)
    }
}

@Composable
internal fun Modifier.appIconGestures(
    app: AppEntry, area: LayoutArea?, enabled: Boolean,
    onClick: (() -> Unit)?, onMenu: (() -> Unit)?, folderId: String? = null, movingFolderId: String? = null
): Modifier {
    val drag = LocalAppDrag.current
    if (drag == null) return if (onClick == null && onMenu == null) this
        else combinedClickable(onClick = onClick ?: {}, onLongClick = onMenu)
    val token = remember(app.key, area, folderId) { Any() }
    val bounds = remember { mutableStateOf(Rect.Zero) }
    val menu by rememberUpdatedState(onMenu)
    val click by rememberUpdatedState(onClick)
    val feedback = LocalHapticFeedback.current
    val showMenu: () -> Unit = { drag.menuAnchor = bounds.value; menu?.invoke() }
    if (!enabled) return onGloballyPositioned { bounds.value = it.boundsInRoot() }
        .combinedClickable(onClick = onClick ?: {}, onLongClick = if (onMenu != null) showMenu else null)
    DisposableEffect(drag, token) { onDispose { drag.cancel(token) } }
    val target: AppDropTarget? = if (movingFolderId != null) AppDropTarget.Folder(movingFolderId)
        else if (folderId != null) AppDropTarget.Folder(folderId, app.key) else area?.takeIf { it != LayoutArea.DOCK }?.let { AppDropTarget.Area(it, app.key) }
    val folderTarget = if (movingFolderId == null && folderId == null && area != null && area != LayoutArea.FAVORITES && area != LayoutArea.DOCK)
        AppDropTarget.CreateFolder(area, app.key) else null
    return then(if (target != null) Modifier.appDropTarget(target, if (folderId != null) 5 else 2) else Modifier)
        .then(if (folderTarget != null) Modifier.appDropTarget(folderTarget, 3, centerOnly = true) else Modifier)
        .onGloballyPositioned { bounds.value = it.boundsInRoot() }
        .alpha(if (drag.active?.token === token) .3f else 1f)
        .then(if (drag.active?.app?.key != app.key && drag.active != null &&
            (target != null && drag.hovered?.target == target || folderTarget != null && drag.hovered?.target == folderTarget))
            Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .2f), RoundedCornerShape(16.dp)) else Modifier)
        .then(if (onClick != null) Modifier.clickable { click?.invoke() } else Modifier)
        .semantics { if (onMenu != null) onLongClick(label = "App actions") { showMenu(); true } }
        .pointerInput(drag, token) {
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    drag.begin(app, area, token, bounds.value.topLeft + point, viewConfiguration.touchSlop, folderId, movingFolderId)
                    showMenu()
                },
                onDragEnd = { drag.finish(token) },
                onDragCancel = { drag.cancel(token) },
                onDrag = { change, amount -> change.consume(); drag.move(token, amount) }
            )
        }
}

@Composable
internal fun AppDragOverlay(drag: AppDragController, state: LauncherState, repository: AppsRepository) {
    val active = drag.active ?: return
    val halfIcon = with(LocalDensity.current) { 28.dp.roundToPx() }
    val folder = state.folders.find { it.id == active.movingFolderId }
    if (folder != null) {
        Icon(androidx.compose.material.icons.Icons.Rounded.Folder, folder.name, Modifier.offset {
            IntOffset(drag.position.x.roundToInt() - halfIcon, drag.position.y.roundToInt() - halfIcon)
        }.size(56.dp))
        return
    }
    AppIcon(active.app, repository, state, 56, Modifier.offset {
        IntOffset(drag.position.x.roundToInt() - halfIcon, drag.position.y.roundToInt() - halfIcon)
    }.clearAndSetSemantics {})
}
