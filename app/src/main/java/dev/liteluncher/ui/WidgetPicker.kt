package dev.liteluncher.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.liteluncher.data.DesktopPrefs
import dev.liteluncher.data.SurfaceTheme
import dev.liteluncher.data.WidgetItem
import dev.liteluncher.platform.WidgetController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Kept in the workspace's root so a held preview can continue onto the visible desktop. */
@Composable
fun WidgetPicker(
    controller: WidgetController, prefs: DesktopPrefs, page: Int, canvasBounds: Rect,
    onSelect: (AppWidgetProviderInfo, WidgetItem?) -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val providers by produceState<List<AppWidgetProviderInfo>?>(null, controller) {
        value = withContext(Dispatchers.IO) { controller.providers() }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember(providers, query) {
        providers.orEmpty().groupBy { it.provider.packageName }.map { (key, widgets) ->
            val label = runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(key, 0)).toString()
            }.getOrDefault(key)
            Triple(key, label, widgets)
        }.filter { (_, label, widgets) -> label.contains(query, true) || widgets.any {
            runCatching { it.loadLabel(context.packageManager) }.getOrDefault("").contains(query, true)
        } }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
    }
    var dragging by remember { mutableStateOf<AppWidgetProviderInfo?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var pickerBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val feedback = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val active = dragging
    val candidate = active?.let { provider ->
        if (canvasBounds.width <= 0 || canvasBounds.height <= 0 || !canvasBounds.contains(pointer)) null else {
            val span = controller.placement(AppWidgetManager.INVALID_APPWIDGET_ID, provider)
            span?.let {
                val column = (((pointer.x - canvasBounds.left) / canvasBounds.width * prefs.columns) - it.columns / 2f)
                    .roundToInt().coerceIn(0, prefs.columns - it.columns)
                val row = (((pointer.y - canvasBounds.top) / canvasBounds.height * prefs.rows) - it.rows / 2f)
                    .roundToInt().coerceIn(0, prefs.rows - it.rows)
                controller.placement(AppWidgetManager.INVALID_APPWIDGET_ID, provider, it.copy(column = column, row = row, page = page))
            }
        }
    }
    val latestCandidate by rememberUpdatedState(candidate)
    val latestSelect by rememberUpdatedState(onSelect)
    BackHandler { if (dragging != null) dragging = null else onDismiss() }

    Box(Modifier.fillMaxSize().onGloballyPositioned { pickerBounds = it.boundsInRoot() }) {
        // Keep the list composed while dragging so its pointer handler keeps the held finger.
        Column(Modifier.fillMaxSize().alpha(if (active == null) 1f else 0f)
            .background(Color.Black.copy(alpha = .35f)).clickable {}
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.ArrowBack, "Close widgets", tint = Color.White) }
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    LauncherSearchField(query, { query = it }, "Search widgets", {}, null, SurfaceTheme.GLASS)
                }
            }
            if (providers == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            } else if (groups.isEmpty()) Text("No widgets found", color = Color.White, modifier = Modifier.padding(20.dp))
            else LazyColumn(Modifier.weight(1f)) {
                items(groups, key = { it.first }) { (key, label, widgets) ->
                    val open = expanded == key || query.isNotBlank()
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { expanded = if (open) null else key }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            WidgetPreview(widgets.first(), true, Modifier.size(36.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(label, color = Color.White, fontSize = 15.sp)
                                Text("${widgets.size} widget${if (widgets.size == 1) "" else "s"}", color = Color.LightGray, fontSize = 12.sp)
                            }
                            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Color.LightGray)
                        }
                        if (open) widgets.forEach { provider ->
                            var bounds by remember(provider.provider) { mutableStateOf(Rect.Zero) }
                            val name = runCatching { provider.loadLabel(context.packageManager) }.getOrDefault(label)
                            val span = controller.placement(AppWidgetManager.INVALID_APPWIDGET_ID, provider)
                            Surface(Modifier.fillMaxWidth().padding(start = 26.dp, bottom = 10.dp)
                                .onGloballyPositioned { bounds = it.boundsInRoot() }
                                .pointerInput(provider.provider) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { point ->
                                            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            keyboard?.hide()
                                            pointer = bounds.topLeft + point
                                            dragging = provider
                                        },
                                        onDrag = { change, _ -> change.consume(); pointer = bounds.topLeft + change.position },
                                        onDragEnd = {
                                            latestCandidate?.let { latestSelect(provider, it) }
                                            dragging = null
                                        },
                                        onDragCancel = { dragging = null }
                                    )
                                }.clickable { keyboard?.hide(); onSelect(provider, null) },
                                color = Color.White.copy(alpha = .16f), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    WidgetPreview(provider, false, Modifier.fillMaxWidth().height(110.dp))
                                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(name, color = Color.White, fontSize = 12.sp)
                                        Text(span?.let { "${it.columns} × ${it.rows}" } ?: "No space", color = Color.LightGray, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (active != null) {
            if (candidate != null) {
                val width = with(density) { (canvasBounds.width / prefs.columns * candidate.columns).toDp() }
                val height = with(density) { (canvasBounds.height / prefs.rows * candidate.rows).toDp() }
                Surface(Modifier.offset { IntOffset(
                    (canvasBounds.left - pickerBounds.left + canvasBounds.width / prefs.columns * candidate.column).roundToInt(),
                    (canvasBounds.top - pickerBounds.top + canvasBounds.height / prefs.rows * candidate.row).roundToInt()) }
                    .size(width, height), color = Color.White.copy(alpha = .2f), shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White)) {
                    WidgetPreview(active, false, Modifier.fillMaxSize().padding(8.dp))
                }
            }
            Surface(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                color = Color(0xCC202020), shape = RoundedCornerShape(22.dp)) {
                Text(if (candidate != null) "Release to add widget" else "Drag onto free desktop space", color = Color.White,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun WidgetPreview(provider: AppWidgetProviderInfo, iconOnly: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, provider.provider, iconOnly) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = if (iconOnly) provider.loadIcon(context, 0)
                    else provider.loadPreviewImage(context, 0) ?: provider.loadIcon(context, 0)
                drawable?.let {
                    val width = it.intrinsicWidth.coerceIn(1, 600)
                    val height = (width.toFloat() * it.intrinsicHeight.coerceAtLeast(1) / it.intrinsicWidth.coerceAtLeast(1))
                        .roundToInt().coerceIn(1, 600)
                    it.toBitmap(width, height).asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Icon(Icons.Rounded.Widgets, null, tint = Color.White, modifier = Modifier.size(32.dp))
    }
}
