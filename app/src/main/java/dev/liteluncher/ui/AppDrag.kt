@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.sp
import dev.liteluncher.data.*
import dev.liteluncher.platform.AppEntry
import dev.liteluncher.platform.AppsRepository
import kotlin.math.roundToInt

internal data class DraggedApp(val app: AppEntry, val source: LayoutArea?, val token: Any, val folderId: String?)
internal data class AppDropRegion(val bounds: Rect, val target: AppDropTarget, val priority: Int)

internal class AppDragController(private val onDrop: (DraggedApp, AppDropTarget) -> Unit) {
    var active by mutableStateOf<DraggedApp?>(null)
        private set
    var position by mutableStateOf(Offset.Zero)
        private set
    private var start = Offset.Zero
    private var slop = 0f
    private var moved = false
    val regions = mutableStateMapOf<Any, AppDropRegion>()
    val hovered by derivedStateOf {
        if (active == null) null else regions.values.filter { it.bounds.contains(position) }.maxByOrNull { it.priority }
    }

    fun begin(app: AppEntry, source: LayoutArea?, token: Any, point: Offset, touchSlop: Float, folderId: String?) {
        start = point
        position = point
        slop = touchSlop
        moved = false
        active = DraggedApp(app, source, token, folderId)
    }

    fun move(token: Any, amount: Offset) {
        if (active?.token !== token) return
        position += amount
        moved = moved || (position - start).getDistance() > slop
    }

    fun finish(token: Any, onMenu: (() -> Unit)?) {
        val item = active?.takeIf { it.token === token } ?: return
        val target = hovered?.target
        val wasMoved = moved
        cancel(token)
        if (!wasMoved) onMenu?.invoke()
        else if (target != null) onDrop(item, target)
    }

    fun cancel(token: Any? = null) {
        if (token == null || active?.token === token) active = null
    }
}

internal val LocalAppDrag = staticCompositionLocalOf<AppDragController?> { null }

@Composable
internal fun Modifier.appDropTarget(target: AppDropTarget, priority: Int = 0): Modifier {
    val drag = LocalAppDrag.current ?: return this
    val token = remember { Any() }
    DisposableEffect(drag, token) { onDispose { drag.regions.remove(token) } }
    return onGloballyPositioned { drag.regions[token] = AppDropRegion(it.boundsInRoot(), target, priority) }
}

@Composable
internal fun Modifier.appIconGestures(
    app: AppEntry, area: LayoutArea?, enabled: Boolean,
    onClick: (() -> Unit)?, onMenu: (() -> Unit)?, folderId: String? = null
): Modifier {
    val drag = LocalAppDrag.current
    if (!enabled || drag == null) return if (onClick == null && onMenu == null) this
        else combinedClickable(onClick = onClick ?: {}, onLongClick = onMenu)
    val token = remember(app.key, area, folderId) { Any() }
    val bounds = remember { mutableStateOf(Rect.Zero) }
    val menu by rememberUpdatedState(onMenu)
    val click by rememberUpdatedState(onClick)
    val feedback = LocalHapticFeedback.current
    DisposableEffect(drag, token) { onDispose { drag.cancel(token) } }
    val target: AppDropTarget? = if (folderId != null) AppDropTarget.Folder(folderId, app.key) else area?.let { AppDropTarget.Area(it, app.key) }
    return then(if (target != null) Modifier.appDropTarget(target, if (folderId != null) 5 else 2) else Modifier)
        .onGloballyPositioned { bounds.value = it.boundsInRoot() }
        .alpha(if (drag.active?.token === token) .3f else 1f)
        .then(if (target != null && drag.active != null && drag.hovered?.target == target)
            Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .2f), RoundedCornerShape(16.dp)) else Modifier)
        .then(if (onClick != null) Modifier.clickable { click?.invoke() } else Modifier)
        .semantics { if (onMenu != null) onLongClick(label = "App actions") { menu?.invoke(); true } }
        .pointerInput(drag, token) {
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    drag.begin(app, area, token, bounds.value.topLeft + point, viewConfiguration.touchSlop, folderId)
                },
                onDragEnd = { drag.finish(token, menu) },
                onDragCancel = { drag.cancel(token) },
                onDrag = { change, amount -> change.consume(); drag.move(token, amount) }
            )
        }
}

@Composable
internal fun AppDragOverlay(drag: AppDragController, state: LauncherState, repository: AppsRepository) {
    val active = drag.active ?: return
    val targets: List<Pair<String, AppDropTarget>> = LayoutArea.entries.map { it.title to AppDropTarget.Area(it) } +
        if (active.source != null) listOf("Remove" to AppDropTarget.Remove) else emptyList()
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            targets.forEach { (title, target) ->
                Surface(Modifier.weight(1f).height(44.dp).appDropTarget(target, 6), shape = RoundedCornerShape(20.dp),
                    color = if (drag.hovered?.target == target) MaterialTheme.colorScheme.primary else Panel,
                    contentColor = if (drag.hovered?.target == target) Ink else Mist) {
                    Box(contentAlignment = Alignment.Center) { Text(title, fontSize = 12.sp) }
                }
            }
        }
        Text(when (val target = drag.hovered?.target) {
            is AppDropTarget.Area -> "Drop on ${target.area.title}"
            is AppDropTarget.Folder -> "Drop in ${state.folders.find { it.id == target.id }?.name ?: "folder"}"
            AppDropTarget.Remove -> "Remove this pinned entry"
            null -> "Drag to a position, folder, or destination above"
        }, color = Mist, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).background(Ink.copy(alpha = .85f), RoundedCornerShape(12.dp)).padding(8.dp))
    }
    val halfIcon = with(LocalDensity.current) { 28.dp.roundToPx() }
    AppIcon(active.app, repository, state, 56, Modifier.offset {
        IntOffset(drag.position.x.roundToInt() - halfIcon, drag.position.y.roundToInt() - halfIcon)
    }.clearAndSetSemantics {})
}
