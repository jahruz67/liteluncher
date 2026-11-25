@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.liteluncher.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liteluncher.ui.Cerulean
import dev.liteluncher.ui.CompactAlertDialog as AlertDialog
import kotlin.math.roundToInt

internal val SettingsGreen: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val SettingsBackground: Color @Composable get() = MaterialTheme.colorScheme.background
internal val SettingsPanel: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
internal fun SettingsTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
}

@Composable
internal fun SettingsFrame(
    title: String,
    onBack: () -> Unit,
    pageKey: String = title,
    background: Color = SettingsBackground,
    content: LazyListScope.() -> Unit
) {
    // Each page owns its scroll position; returning to a long page restores it.
    androidx.compose.runtime.key(pageKey) {
        val scroll = rememberLazyListState()
        Scaffold(
            containerColor = background,
            topBar = {
                Surface(color = if (background == Color.Transparent) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(16.dp))
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                state = scroll,
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                content = content
            )
        }
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) { content() }
        }
    }
}

@Composable
internal fun SettingsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
internal fun SettingsLink(title: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 40.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ToggleSetting(title: String, checked: Boolean, description: String = "", onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 40.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotBlank()) SettingsNote(description)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.height(32.dp).scale(.75f))
    }
}

@Composable
internal fun FloatSetting(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String = { "${(it * 100).roundToInt()}%" },
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    val safeValue = if (value.isFinite()) value.coerceIn(range) else range.start
    val fraction = if (range.endInclusive > range.start) (safeValue - range.start) / (range.endInclusive - range.start) else 0f
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(.42f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(display(safeValue), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Slider(value = safeValue, onValueChange = onChange,
            modifier = Modifier.weight(.58f).height(32.dp).semantics { contentDescription = title },
            valueRange = range, steps = steps,
            thumb = { Box(Modifier.width(4.dp).height(20.dp).background(active, CircleShape)) },
            track = {
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    val centerY = size.height / 2
                    val inset = 4.dp.toPx()
                    val end = (size.width - inset).coerceAtLeast(inset)
                    drawLine(inactive, Offset(inset, centerY), Offset(end, centerY), 8.dp.toPx(), StrokeCap.Round)
                    if (fraction > 0f) drawLine(active, Offset(inset, centerY), Offset(inset + (end - inset) * fraction, centerY), 8.dp.toPx(), StrokeCap.Round)
                }
            })
    }
}

@Composable
internal fun IntSetting(title: String, value: Int, range: IntRange, suffix: String = "", onChange: (Int) -> Unit) {
    FloatSetting(title, value.toFloat(), range.first.toFloat()..range.last.toFloat(),
        display = { "${it.roundToInt()}$suffix" }, steps = (range.last - range.first - 1).coerceAtLeast(0)) {
        val next = it.roundToInt().coerceIn(range)
        if (next != value) onChange(next)
    }
}

@Composable
internal fun <T> ChoiceSetting(
    title: String,
    value: T,
    choices: List<Pair<T, String>>,
    description: String = "",
    onChange: (T) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = choices.firstOrNull { it.first == value }?.second ?: value.toString()
    Row(Modifier.fillMaxWidth().clickable { open = true }.heightIn(min = 40.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotBlank()) SettingsNote(description)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp))
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(choices.size) { index ->
                        val choice = choices[index]
                        Row(
                            Modifier.fillMaxWidth().clickable { onChange(choice.first); open = false }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = value == choice.first,
                                onClick = { onChange(choice.first); open = false })
                            Text(choice.second, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } }
        )
    }
}

@Composable
internal fun ValidatedTextSetting(
    title: String,
    value: String,
    description: String,
    validate: (String) -> String?,
    normalize: (String) -> String = { it.trim() },
    onSave: (String) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val error = validate(draft.trim())
    SettingsLink(title, value) { draft = value; open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
        OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(title) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, isError = error != null,
            supportingText = { Text(error ?: description) })
    }, confirmButton = {
        TextButton(onClick = { if (error == null) { onSave(normalize(draft)); open = false } },
            enabled = error == null && draft != value) { Text("Save") }
    }, dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } })
}

internal fun colorError(value: String): String? =
    if (Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(value)) null
    else "Use #RRGGBB or #AARRGGBB, for example #3B82F6."

internal fun settingsColor(value: String, fallback: Color = Cerulean): Color =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback)

internal fun String.settingLabel(): String = lowercase().split('_').joinToString(" ") {
    it.replaceFirstChar { character -> character.uppercase() }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
}
