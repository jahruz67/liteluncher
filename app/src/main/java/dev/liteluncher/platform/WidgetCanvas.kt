package dev.liteluncher.platform

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.liteluncher.data.DesktopPrefs
import dev.liteluncher.data.WidgetItem
import kotlin.math.roundToInt

@Composable
fun WidgetCanvas(
    controller: WidgetController,
    prefs: DesktopPrefs,
    widgets: List<WidgetItem>,
    onChange: (List<WidgetItem>) -> Unit,
    modifier: Modifier = Modifier
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
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }

    // Changing the desktop grid can invalidate old coordinates. Repack without overlap;
    // widgets that cannot fit remain saved and get explicit recovery/removal controls.
    LaunchedEffect(widgets, arranged) {
        if (arranged != widgets) currentChange(arranged)
    }

    Column(modifier.fillMaxSize().padding(prefs.padding.coerceIn(0, 64).dp)) {
        if (layout.overflow.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
                Text("Some widgets do not fit this grid. Increase the grid or free space.",
                    style = MaterialTheme.typography.bodySmall)
                layout.overflow.forEach { item ->
                    TextButton(onClick = { editing = item.id }) { Text("Manage widget ${item.id}") }
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
                    val width = (cellWidth * item.columns - gutter * 2).coerceAtLeast(1.dp)
                    val height = (cellHeight * item.rows - gutter * 2).coerceAtLeast(1.dp)
                    val onEdit = { editing = item.id }
                    val radius = prefs.cornerRadius.coerceIn(0, 96).dp
                    AndroidView(
                        factory = { context -> HostedWidgetFrame(context, controller, item.id, onEdit) },
                        modifier = Modifier
                            .offset(cellWidth * item.column + gutter, cellHeight * item.row + gutter)
                            .width(width).height(height)
                            .clip(RoundedCornerShape(radius))
                            .semantics {
                                customActions = listOf(CustomAccessibilityAction("Edit widget") {
                                    editing = item.id
                                    true
                                })
                            },
                        onRelease = { it.release() },
                        onReset = null,
                        update = { frame ->
                            frame.updateSize(width.value.roundToInt().coerceAtLeast(1),
                                height.value.roundToInt().coerceAtLeast(1), radius.value, onEdit)
                        }
                    )
                }
            }
        }
    }

    val selected = arranged.firstOrNull { it.id == editing }
    if (selected != null) {
        WidgetEditor(controller, selected, layout.placed, columns, rows,
            onDismiss = { editing = null },
            onReplace = { updated -> currentChange(arranged.map { if (it.id == updated.id) updated else it }) },
            onRemove = {
                currentChange(arranged.filterNot { it.id == selected.id })
                controller.remove(selected.id)
                editing = null
            })
    }
}

@Composable
private fun WidgetEditor(
    controller: WidgetController,
    item: WidgetItem,
    placed: List<WidgetItem>,
    columns: Int,
    rows: Int,
    onDismiss: () -> Unit,
    onReplace: (WidgetItem) -> Unit,
    onRemove: () -> Unit
) {
    val provider = controller.provider(item.id)
    val minimum = provider?.let(controller::minimumSpan) ?: (1 to 1)
    val maximum = provider?.let(controller::maximumSpan) ?: (columns to rows)
    val horizontal = provider != null && provider.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
    val vertical = provider != null && provider.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
    fun fits(candidate: WidgetItem) = WidgetGrid.fits(candidate, placed, columns, rows)
    val left = item.copy(column = item.column - 1)
    val right = item.copy(column = item.column + 1)
    val up = item.copy(row = item.row - 1)
    val down = item.copy(row = item.row + 1)
    val narrower = item.copy(columns = item.columns - 1)
    val wider = item.copy(columns = item.columns + 1)
    val shorter = item.copy(rows = item.rows - 1)
    val taller = item.copy(rows = item.rows + 1)
    val recovery = WidgetGrid.place(item, placed, columns, rows)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit widget") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${item.columns} x ${item.rows} - Column ${item.column + 1}, row ${item.row + 1}")
                Text("Move", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { onReplace(left) }, enabled = fits(left)) { Text("Left") }
                    TextButton(onClick = { onReplace(right) }, enabled = fits(right)) { Text("Right") }
                }
                Row {
                    TextButton(onClick = { onReplace(up) }, enabled = fits(up)) { Text("Up") }
                    TextButton(onClick = { onReplace(down) }, enabled = fits(down)) { Text("Down") }
                }
                if (placed.none { it.id == item.id } && recovery != null) {
                    TextButton(onClick = { onReplace(recovery) }) { Text("Place in free space") }
                }
                Text("Resize", style = MaterialTheme.typography.labelLarge)
                Row {
                    TextButton(onClick = { onReplace(narrower) }, enabled = horizontal &&
                        narrower.columns >= minimum.first && fits(narrower)) { Text("Narrower") }
                    TextButton(onClick = { onReplace(wider) }, enabled = horizontal &&
                        wider.columns <= maximum.first && fits(wider)) { Text("Wider") }
                }
                Row {
                    TextButton(onClick = { onReplace(shorter) }, enabled = vertical &&
                        shorter.rows >= minimum.second && fits(shorter)) { Text("Shorter") }
                    TextButton(onClick = { onReplace(taller) }, enabled = vertical &&
                        taller.rows <= maximum.second && fits(taller)) { Text("Taller") }
                }
                if (!horizontal && !vertical) Text("The provider uses a fixed widget size.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { controller.refresh(item.id) }, enabled = provider != null) { Text("Refresh") }
                TextButton(onClick = onRemove) { Text("Remove widget", color = MaterialTheme.colorScheme.error) }
                if (provider == null) Text("The provider is unavailable. Unlock its profile or remove this widget.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
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
