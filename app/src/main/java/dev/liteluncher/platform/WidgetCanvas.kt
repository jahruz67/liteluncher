package dev.liteluncher.platform

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.liteluncher.data.DesktopPrefs
import dev.liteluncher.data.WidgetItem
import dev.liteluncher.data.borderPaddingDp
import kotlin.math.roundToInt

private val ResizeColor = Color.White

@Composable
fun WidgetCanvas(
    controller: WidgetController,
    prefs: DesktopPrefs,
    widgets: List<WidgetItem>,
    onChange: (List<WidgetItem>) -> Unit,
    modifier: Modifier = Modifier,
    onWidgetDeleted: ((WidgetItem) -> Unit)? = null,
    onCanvasBounds: (Rect) -> Unit = {},
    blocked: List<WidgetItem> = emptyList(),
    isActive: Boolean = true,
    overlay: @Composable BoxWithConstraintsScope.() -> Unit = {}
) {
    val columns = prefs.columns.coerceIn(1, 24)
    val rows = prefs.rows.coerceIn(1, 32)
    val layout = remember(widgets, columns, rows, blocked) { WidgetGrid.arrange(widgets, columns, rows, blocked) }
    val arranged = remember(widgets, layout) {
        val byId = layout.placed.associateBy { it.id }
        widgets.distinctBy { it.id }.map { byId[it.id] ?: it }
    }
    val currentItems by rememberUpdatedState(arranged)
    val currentChange by rememberUpdatedState(onChange)
    val refreshes by controller.refreshes.collectAsState()
    val providerChanges by controller.providerChanges.collectAsState()
    val configuration = LocalConfiguration.current
    var contextMenuWidget by remember(isActive) { mutableStateOf<Int?>(null) }
    var resizingWidgetId by remember(isActive) { mutableStateOf<Int?>(null) }
    var draft by remember(isActive) { mutableStateOf<WidgetItem?>(null) }

    LaunchedEffect(widgets, arranged) {
        if (arranged != widgets) currentChange(arranged)
        if (widgets.none { it.id == resizingWidgetId }) { resizingWidgetId = null; draft = null }
    }
    LaunchedEffect(prefs.preventChanges) {
        if (prefs.preventChanges) { resizingWidgetId = null; draft = null; contextMenuWidget = null }
    }
    BackHandler(isActive && resizingWidgetId != null) { resizingWidgetId = null; draft = null }

    fun remove(item: WidgetItem) {
        currentChange(currentItems.filterNot { it.id == item.id })
        // Keep the Android binding alive for the caller's Undo window.
        if (onWidgetDeleted != null) onWidgetDeleted(item) else controller.remove(item.id)
        contextMenuWidget = null
        resizingWidgetId = null
        draft = null
    }

    val padding = if (prefs.grid.borderPadding == "Default") prefs.padding else prefs.grid.borderPaddingDp
    Column(modifier.fillMaxSize().padding(padding.coerceIn(0, 64).dp)) {
        if (layout.overflow.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
                Text("Some widgets do not fit this grid. Increase the grid or free space.", style = MaterialTheme.typography.bodySmall)
                layout.overflow.forEach { item ->
                    Box {
                        TextButton(onClick = { contextMenuWidget = item.id }) { Text("Manage widget ${item.id}") }
                        WidgetContextMenu(contextMenuWidget == item.id, false, !prefs.preventChanges, {},
                            { controller.refresh(item.id); contextMenuWidget = null }, { remove(item) }, { contextMenuWidget = null })
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { onCanvasBounds(it.boundsInRoot()) }) {
            val canvasHeight = if (constraints.hasBoundedHeight) maxHeight else 640.dp
            val canvasWidth = if (constraints.hasBoundedWidth) maxWidth else 360.dp
            val cellWidth = canvasWidth / columns
            val cellHeight = canvasHeight / rows
            val gutter = if (prefs.widgetPadding) 8.dp else 0.dp
            SideEffect { controller.syncGeometry(prefs, canvasWidth.value, canvasHeight.value) }
            overlay()

            // Behind the widgets, so dismissing the selection cannot swallow the handles.
            if (resizingWidgetId != null) {
                Box(Modifier.fillMaxSize().clickable { resizingWidgetId = null; draft = null })
            }
            layout.placed.forEach { saved ->
                key(saved.id, refreshes[saved.id] ?: 0, providerChanges) {
                    val item = draft?.takeIf { it.id == saved.id } ?: saved
                    val isResizing = resizingWidgetId == item.id
                    val itemWidth = (cellWidth * item.columns - gutter * 2).coerceAtLeast(1.dp)
                    val itemHeight = (cellHeight * item.rows - gutter * 2).coerceAtLeast(1.dp)
                    val onEdit = { if (isActive) contextMenuWidget = saved.id }
                    val radius = (if (prefs.rounded) prefs.cornerRadius.coerceIn(0, 96) else 0).dp

                    Box(Modifier.offset(cellWidth * item.column + gutter, cellHeight * item.row + gutter)
                        .width(itemWidth).height(itemHeight)) {
                        AndroidView(
                            factory = { context -> HostedWidgetFrame(context, controller, item.id, onEdit) },
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(radius))
                                .then(if (isResizing) Modifier.border(BorderStroke(2.dp, ResizeColor), RoundedCornerShape(radius)) else Modifier)
                                .semantics {
                                    customActions = listOf(CustomAccessibilityAction("Edit widget") { onEdit(); true }) +
                                        if (prefs.preventChanges) emptyList() else listOf(
                                            "Move left" to item.copy(column = item.column - 1),
                                            "Move right" to item.copy(column = item.column + 1),
                                            "Move up" to item.copy(row = item.row - 1),
                                            "Move down" to item.copy(row = item.row + 1),
                                            "Widen" to item.copy(columns = item.columns + 1),
                                            "Narrow" to item.copy(columns = item.columns - 1),
                                            "Make taller" to item.copy(rows = item.rows + 1),
                                            "Make shorter" to item.copy(rows = item.rows - 1)
                                        ).map { (label, candidate) -> CustomAccessibilityAction(label) {
                                            val info = controller.provider(item.id)
                                            val min = info?.let { controller.minimumSpan(it) } ?: (1 to 1)
                                            val max = info?.let { controller.maximumSpan(it) } ?: (columns to rows)
                                            val horizontal = candidate.columns != item.columns
                                            val vertical = candidate.rows != item.rows
                                            val mode = info?.resizeMode ?: AppWidgetProviderInfo.RESIZE_NONE
                                            val valid = candidate.columns in min.first..max.first && candidate.rows in min.second..max.second &&
                                                (!horizontal || mode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0) &&
                                                (!vertical || mode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0) &&
                                                WidgetGrid.fits(candidate, layout.placed + blocked, columns, rows)
                                            if (valid) currentChange(currentItems.map { if (it.id == item.id) candidate else it })
                                            valid
                                        } }
                                },
                            onRelease = { it.release() }, onReset = null,
                            update = { frame -> frame.updateSize(itemWidth.value.roundToInt().coerceAtLeast(1),
                                itemHeight.value.roundToInt().coerceAtLeast(1), radius.value, onEdit) }
                        )
                        WidgetContextMenu(contextMenuWidget == item.id,
                            !prefs.preventChanges, !prefs.preventChanges,
                            { resizingWidgetId = item.id; contextMenuWidget = null },
                            { controller.refresh(item.id); contextMenuWidget = null }, { remove(item) }, { contextMenuWidget = null })

                        if (isResizing) {
                            val provider = remember(controller, item.id, providerChanges, configuration) { controller.provider(item.id) }
                            val resizeMode = provider?.resizeMode ?: AppWidgetProviderInfo.RESIZE_NONE
                            val minSpan = remember(controller, provider, cellWidth, cellHeight) {
                                provider?.let { controller.minimumSpan(it, cellWidth.value, cellHeight.value) } ?: (1 to 1)
                            }
                            val maxSpan = remember(controller, provider, columns, rows, cellWidth, cellHeight) {
                                provider?.let { controller.maximumSpan(it, cellWidth.value, cellHeight.value, columns, rows) }
                                    ?: (columns to rows)
                            }
                            // Capture the drag on top of the native widget; ordinary widget touches stay native outside edit mode.
                            Box(Modifier.fillMaxSize().widgetGridDrag(item, cellWidth.value, cellHeight.value,
                                onPreview = { origin, delta ->
                                    val candidate = origin.copy(
                                        column = (origin.column + delta.first).coerceIn(0, columns - origin.columns),
                                        row = (origin.row + delta.second).coerceIn(0, rows - origin.rows))
                                    if (WidgetGrid.fits(candidate, layout.placed + blocked, columns, rows)) draft = candidate
                                }, onCommit = {
                                    draft?.let { changed -> currentChange(currentItems.map { if (it.id == changed.id) changed else it }) }
                                    draft = null
                                }, onCancel = { draft = null }))
                            ResizeEdge.entries.filter { edge ->
                                val axis = if (edge == ResizeEdge.LEFT || edge == ResizeEdge.RIGHT)
                                    AppWidgetProviderInfo.RESIZE_HORIZONTAL else AppWidgetProviderInfo.RESIZE_VERTICAL
                                resizeMode and axis != 0
                            }.forEach { edge ->
                                val horizontal = edge == ResizeEdge.TOP || edge == ResizeEdge.BOTTOM
                                val alignment = when (edge) {
                                    ResizeEdge.TOP -> Alignment.TopCenter
                                    ResizeEdge.BOTTOM -> Alignment.BottomCenter
                                    ResizeEdge.LEFT -> Alignment.CenterStart
                                    ResizeEdge.RIGHT -> Alignment.CenterEnd
                                }
                                Box(Modifier.align(alignment)
                                    .offset(x = when (edge) { ResizeEdge.LEFT -> (-16).dp; ResizeEdge.RIGHT -> 16.dp; else -> 0.dp },
                                        y = when (edge) { ResizeEdge.TOP -> (-16).dp; ResizeEdge.BOTTOM -> 16.dp; else -> 0.dp })
                                    .size(32.dp)
                                    .widgetGridDrag(item, cellWidth.value, cellHeight.value,
                                        onPreview = { origin, delta ->
                                            val candidate = resizeWidget(origin, edge, delta, minSpan, maxSpan, columns, rows)
                                            if (candidate != null && WidgetGrid.fits(candidate, layout.placed + blocked, columns, rows)) draft = candidate
                                        }, onCommit = {
                                            draft?.let { changed -> currentChange(currentItems.map { if (it.id == changed.id) changed else it }) }
                                            draft = null
                                        }, onCancel = { draft = null }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(Modifier.size(if (horizontal) 18.dp else 6.dp, if (horizontal) 6.dp else 18.dp)
                                        .background(ResizeColor, RoundedCornerShape(4.dp)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class ResizeEdge { TOP, BOTTOM, LEFT, RIGHT }

private fun resizeWidget(
    origin: WidgetItem, edge: ResizeEdge, delta: Pair<Int, Int>,
    minimum: Pair<Int, Int>, maximum: Pair<Int, Int>, columns: Int, rows: Int
): WidgetItem? {
    val left = if (edge == ResizeEdge.LEFT) origin.column + delta.first else origin.column
    val top = if (edge == ResizeEdge.TOP) origin.row + delta.second else origin.row
    val right = origin.column + origin.columns + if (edge == ResizeEdge.RIGHT) delta.first else 0
    val bottom = origin.row + origin.rows + if (edge == ResizeEdge.BOTTOM) delta.second else 0
    if (left < 0 || top < 0 || right > columns || bottom > rows ||
        right - left !in minimum.first..maximum.first || bottom - top !in minimum.second..maximum.second) return null
    return origin.copy(column = left, row = top, columns = right - left, rows = bottom - top)
}

@Composable
private fun Modifier.widgetGridDrag(
    item: WidgetItem, cellWidthDp: Float, cellHeightDp: Float,
    onPreview: (WidgetItem, Pair<Int, Int>) -> Unit, onCommit: () -> Unit, onCancel: () -> Unit
): Modifier {
    val latestItem by rememberUpdatedState(item)
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    return pointerInput(item.id, cellWidthDp, cellHeightDp) {
        var origin = latestItem
        var distance = Offset.Zero
        detectDragGestures(
            onDragStart = { origin = latestItem; distance = Offset.Zero },
            onDragEnd = { commit() }, onDragCancel = { cancel() },
            onDrag = { change, amount ->
                change.consume()
                distance += amount
                preview(origin, (distance.x / (cellWidthDp * density)).roundToInt() to
                    (distance.y / (cellHeightDp * density)).roundToInt())
            }
        )
    }
}

@Composable
private fun WidgetContextMenu(
    expanded: Boolean, canEdit: Boolean, canRemove: Boolean,
    onResize: () -> Unit, onRefresh: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit
) {
    DropdownMenu(expanded, onDismiss, containerColor = Color(0xFF253238)) {
        DropdownMenuItem(text = { Text("Move / resize", color = Color.White) }, onClick = onResize, enabled = canEdit,
            leadingIcon = { Icon(Icons.Rounded.AspectRatio, null, tint = Color.White) })
        DropdownMenuItem(text = { Text("Refresh", color = Color.White) }, onClick = onRefresh,
            leadingIcon = { Icon(Icons.Rounded.Refresh, null, tint = Color.White) })
        DropdownMenuItem(text = { Text("Remove", color = Color.White) }, onClick = onRemove, enabled = canRemove,
            leadingIcon = { Icon(Icons.Rounded.Close, null, tint = Color.White) })
    }
}

private class HostedWidgetFrame(
    context: Context,
    controller: WidgetController,
    id: Int,
    onEdit: () -> Unit
) : FrameLayout(context) {
    private val widget = controller.createView(context, id, onEdit)
    private var lastWidth = 0
    private var lastHeight = 0
    private var cornerRadius = 0f

    init {
        clipChildren = true
        clipToPadding = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        if (widget != null) {
            addView(widget, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            addView(TextView(context).apply {
                text = "Widget unavailable\nHold to manage"
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0x66202020)
                setPadding(8, 8, 8, 8)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            setOnLongClickListener { onEdit(); true }
        }
    }

    fun updateSize(widthDp: Int, heightDp: Int, radiusDp: Float, onEdit: () -> Unit) {
        (widget as? LauncherWidgetHostView)?.edit = onEdit
        if (widget == null) setOnLongClickListener { onEdit(); true }
        val radiusPx = radiusDp * resources.displayMetrics.density
        if (radiusPx != cornerRadius) {
            cornerRadius = radiusPx
            invalidateOutline()
        }
        if (lastWidth != widthDp || lastHeight != heightDp) {
            lastWidth = widthDp
            lastHeight = heightDp
            @Suppress("DEPRECATION")
            runCatching { widget?.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp) }
        }
    }

    fun release() {
        (widget as? LauncherWidgetHostView)?.edit = null
        setOnLongClickListener(null)
        removeAllViews()
    }
}
