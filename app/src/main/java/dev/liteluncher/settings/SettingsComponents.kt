package dev.liteluncher.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal val SettingsGreen = Color(0xFF82B1D5)
internal val SettingsBackground = Color(0xFF454545)
internal val SettingsPanel = Color(0xFF505050)
private val SettingsToolbar = Color(0xFF45657D)

@Composable
internal fun SettingsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SettingsGreen,
            onPrimary = Color(0xFF202020),
            primaryContainer = Color(0xFF45657D),
            onPrimaryContainer = Color.White,
            secondary = SettingsGreen,
            background = SettingsBackground,
            onBackground = Color(0xFFEEEEEE),
            surface = SettingsBackground,
            onSurface = Color(0xFFEEEEEE),
            surfaceVariant = SettingsPanel,
            onSurfaceVariant = Color(0xFFBDBDBD),
            outline = Color(0xFF888888)
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
    }
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
                Surface(color = if (background == Color.Transparent) Color.Transparent else SettingsToolbar) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(title, fontSize = 16.sp,
                            fontWeight = FontWeight.Normal, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(16.dp))
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                state = scroll,
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
                content = content
            )
        }
    }
}

@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = SettingsGreen, fontSize = 12.sp, fontStyle = FontStyle.Italic,
            modifier = Modifier.fillMaxWidth().background(SettingsPanel).padding(horizontal = 12.dp, vertical = 5.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { content() }
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 14.sp)
            if (description.isNotBlank()) SettingsNote(description)
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
internal fun ToggleSetting(title: String, checked: Boolean, description: String = "", onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp)
            if (description.isNotBlank()) SettingsNote(description)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
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
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 14.sp)
            Text(display(safeValue), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Slider(value = safeValue, onValueChange = onChange,
            modifier = Modifier.semantics { contentDescription = title },
            valueRange = range, steps = steps)
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
    SettingsLink(title, listOf(label, description).filter { it.isNotBlank() }.joinToString(" · ")) { open = true }
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
                                .padding(vertical = 6.dp),
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
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val error = validate(draft.trim())
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(title) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = draft != value && error != null,
            supportingText = { Text(if (draft != value && error != null) error else description) }
        )
        Button(onClick = { if (error == null) onSave(normalize(draft)) },
            enabled = draft != value && error == null,
            modifier = Modifier.align(Alignment.End)) { Text("Save") }
    }
}

internal fun colorError(value: String): String? =
    if (Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(value)) null
    else "Use #RRGGBB or #AARRGGBB, for example #3B82F6."

internal fun settingsColor(value: String, fallback: Color = SettingsGreen): Color =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback)

internal fun String.settingLabel(): String = lowercase().split('_').joinToString(" ") {
    it.replaceFirstChar { character -> character.uppercase() }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
}
