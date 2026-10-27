package dev.liteluncher.platform

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import dev.liteluncher.data.DesktopPrefs
import dev.liteluncher.data.WidgetItem
import kotlin.math.roundToInt

private val CyanHandle = Color(0xFF00E5FF)

@Composable
fun WidgetCanvas(
    controller: WidgetController,
    prefs: DesktopPrefs,
    widgets: List<WidgetItem>,
    onChange: (List<WidgetItem>) -> Unit,
    modifier: Modifier = Modifier,
    onWidgetDeleted: ((WidgetItem) -> Unit)? = null
) {
    val columns = prefs.columns.coerceIn(1, 24)
    val rows = prefs.rows.coerceIn(1, 32)
    val layout = remember(widgets, columns, rows) { WidgetGrid.arrange(widgets, columns, rows) }
    val arranged = remember(widgets, layout) {
        val byId = layout.placed.associateBy { it.id }
        widgets.distinctBy { it.id }.map { byId[it.id] ?: it }
    }
    val currentChange by rememberUpdatedState(onChange)
    val refreshes by controller.refreshes.collectAsState()
    var contextMenuWidget by rememberSaveable { mutableStateOf<Int?>(null) }
    var resizingWidgetId by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(widgets, arranged) {
        if (arranged != widgets) currentChange(arranged)
    }

    Column(modifier.fillMaxSize().padding(prefs.padding.coerceIn(0, 64).dp)) {
        if (layout.overflow.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
                Text("Some widgets do not fit this grid. Increase the grid or free space.",
                    style = MaterialTheme.typography.bodySmall)
                layout.overflow.forEach { item ->
                    TextButton(onClick = { contextMenuWidget = item.id }) { Text("Manage widget ${item.id}") }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val canvasHeight = if (constraints.hasBoundedHeight) maxHeight else 640.dp
            val canvasWidth = if (constraints.hasBoundedWidth) maxWidth else 360.dp
            val cellWidth = canvasWidth / columns
            val cellHeight = canvasHeight / rows
            val gutter = 2.dp

            SideEffect {
                controller.syncLayout(prefs, arranged, canvasWidth.value, canvasHeight.value)
            }

            if (widgets.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Add widgets from the widget picker.", style = MaterialTheme.typography.bodyMedium)
                }
            }

            layout.placed.forEach { item ->
                key(item.id, refreshes[item.id] ?: 0) {
                    val isResizing = resizingWidgetId == item.id
                    val itemWidth = (cellWidth * item.columns - gutter * 2).coerceAtLeast(1.dp)
                    val itemHeight = (cellHeight * item.rows - gutter * 2).coerceAtLeast(1.dp)
                    val onEdit = { contextMenuWidget = item.id }
                    val radius = (if (prefs.rounded) prefs.cornerRadius.coerceIn(0, 96) else 0).dp

                    Box(
                        modifier = Modifier
                            .offset(cellWidth * item.column + gutter, cellHeight * item.row + gutter)
                            .width(itemWidth).height(itemHeight)
                    ) {
                        AndroidView(
                            factory = { context -> HostedWidgetFrame(context, controller, item.id, onEdit) },
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(radius))
                                .then(if (isResizing) Modifier.border(BorderStroke(2.dp, CyanHandle), RoundedCornerShape(radius)) else Modifier)
                                .semantics {
                                    customActions = listOf(CustomAccessibilityAction("Edit widget") {
                                        contextMenuWidget = item.id
                                        true
                                    })
                                },
                            onRelease = { it.release() },
                            onReset = null,
                            update = { frame ->
                                frame.updateSize(itemWidth.value.roundToInt().coerceAtLeast(1),
                                    itemHeight.value.roundToInt().coerceAtLeast(1), radius.value, onEdit)
                            }
                        )

                        // 4 Cyan border pill handles when resizing with snap-to-grid internal reflow
                        if (isResizing) {
                            val provider = controller.provider(item.id)
                            val minSpan = provider?.let(controller::minimumSpan) ?: (1 to 1)
                            val maxSpan = provider?.let(controller::maximumSpan) ?: (columns to rows)

                            // Top Handle (pill width 36dp, height 6dp)
                            Box(
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = (-4).dp)
                                    .size(36.dp, 8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CyanHandle)
                                    .pointerInput(item.id) {
                                        var accY = 0f
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            accY += dragAmount.y
                                            val cellPx = cellHeight.toPx()
                                            if (kotlin.math.abs(accY) >= cellPx * 0.5f) {
                                                val step = if (accY < 0) 1 else -1
                                                val newRow = (item.row - step).coerceIn(0, rows - 1)
                                                val newRows = (item.rows + step).coerceIn(minSpan.second, maxSpan.second)
                                                if (newRow >= 0 && newRow + newRows <= rows) {
                                                    val candidate = item.copy(row = newRow, rows = newRows)
                                                    if (WidgetGrid.fits(candidate, layout.placed, columns, rows)) {
                                                        currentChange(arranged.map { if (it.id == item.id) candidate else it })
                                                    }
                                                }
                                                accY = 0f
                                            }
                                        }
                                    }
                            )

                            // Bottom Handle
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .offset(y = 4.dp)
                                    .size(36.dp, 8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CyanHandle)
                                    .pointerInput(item.id) {
                                        var accY = 0f
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            accY += dragAmount.y
                                            val cellPx = cellHeight.toPx()
                                            if (kotlin.math.abs(accY) >= cellPx * 0.5f) {
                                                val step = if (accY > 0) 1 else -1
                                                val newRows = (item.rows + step).coerceIn(minSpan.second, maxSpan.second)
                                                if (item.row + newRows <= rows) {
                                                    val candidate = item.copy(rows = newRows)
                                                    if (WidgetGrid.fits(candidate, layout.placed, columns, rows)) {
                                                        currentChange(arranged.map { if (it.id == item.id) candidate else it })
                                                    }
                                                }
                                                accY = 0f
                                            }
                                        }
                                    }
                            )

                            // Left Handle
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = (-4).dp)
                                    .size(8.dp, 36.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CyanHandle)
                                    .pointerInput(item.id) {
                                        var accX = 0f
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            accX += dragAmount.x
                                            val cellPx = cellWidth.toPx()
                                            if (kotlin.math.abs(accX) >= cellPx * 0.5f) {
                                                val step = if (accX < 0) 1 else -1
                                                val newCol = (item.column - step).coerceIn(0, columns - 1)
                                                val newCols = (item.columns + step).coerceIn(minSpan.first, maxSpan.first)
                                                if (newCol >= 0 && newCol + newCols <= columns) {
                                                    val candidate = item.copy(column = newCol, columns = newCols)
                                                    if (WidgetGrid.fits(candidate, layout.placed, columns, rows)) {
                                                        currentChange(arranged.map { if (it.id == item.id) candidate else it })
                                                    }
                                                }
                                                accX = 0f
                                            }
                                        }
                                    }
                            )

                            // Right Handle
                            Box(
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .offset(x = 4.dp)
                                    .size(8.dp, 36.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CyanHandle)
                                    .pointerInput(item.id) {
                                        var accX = 0f
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            accX += dragAmount.x
                                            val cellPx = cellWidth.toPx()
                                            if (kotlin.math.abs(accX) >= cellPx * 0.5f) {
                                                val step = if (accX > 0) 1 else -1
                                                val newCols = (item.columns + step).coerceIn(minSpan.first, maxSpan.first)
                                                if (item.column + newCols <= columns) {
                                                    val candidate = item.copy(columns = newCols)
                                                    if (WidgetGrid.fits(candidate, layout.placed, columns, rows)) {
                                                        currentChange(arranged.map { if (it.id == item.id) candidate else it })
                                                    }
                                                }
                                                accX = 0f
                                            }
                                        }
                                    }
                            )

                            // Done button for resize
                            IconButton(
                                onClick = { resizingWidgetId = null },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(4.dp, (-14).dp)
                                    .size(24.dp)
                                    .background(CyanHandle, CircleShape)
                            ) {
                                Icon(Icons.Rounded.Check, "Done resizing", tint = Color.Black, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Long-Press Context Card: Resize, Refresh, Remove
    val selectedItem = arranged.firstOrNull { it.id == contextMenuWidget }
    if (selectedItem != null) {
        WidgetContextMenuDialog(
            item = selectedItem,
            onResize = {
                resizingWidgetId = selectedItem.id
                contextMenuWidget = null
            },
            onRefresh = {
                controller.refresh(selectedItem.id)
                contextMenuWidget = null
            },
            onRemove = {
                currentChange(arranged.filterNot { it.id == selectedItem.id })
                controller.remove(selectedItem.id)
                onWidgetDeleted?.invoke(selectedItem)
                contextMenuWidget = null
            },
            onDismiss = { contextMenuWidget = null }
        )
    }
}

@Composable
private fun WidgetContextMenuDialog(
    item: WidgetItem,
    onResize: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF282828),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            modifier = Modifier.width(260.dp)
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "Widget options",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color(0xFFC1F17C)
                )
                HorizontalDivider(color = Color(0x22FFFFFF), thickness = 0.5.dp)

                // Resize
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onResize)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AspectRatio, null, tint = CyanHandle, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Resize", fontSize = 14.sp)
                }

                HorizontalDivider(color = Color(0x15FFFFFF), thickness = 0.5.dp)

                // Refresh
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRefresh)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Refresh, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Refresh", fontSize = 14.sp)
                }

                HorizontalDivider(color = Color(0x15FFFFFF), thickness = 0.5.dp)

                // Remove
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Remove", fontSize = 14.sp, color = Color(0xFFFF5252))
                }
            }
        }
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
