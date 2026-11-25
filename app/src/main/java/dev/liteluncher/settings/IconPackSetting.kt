package dev.liteluncher.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.liteluncher.ui.CompactAlertDialog as AlertDialog

@Composable
internal fun IconPackSetting(value: String, packs: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val installed = packs.distinctBy { it.first }
    val current = installed.firstOrNull { it.first == value }?.second
    SettingsLink("Icon pack", if (value.isBlank()) "System icons" else current ?: "Unavailable pack") {
        query = ""
        open = true
    }
    if (!open) return
    val matching = installed.filter { (id, label) ->
        label.contains(query.trim(), ignoreCase = true) || id.contains(query.trim(), ignoreCase = true)
    }
    AlertDialog(onDismissRequest = { open = false }, title = { Text("Icon pack") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsNote("Install an Android icon pack to use it here. Apps without a matching icon use the pack's fallback or their original icon. Custom app icons keep their overrides.")
            if (value.isNotBlank() && current == null) {
                Text("The selected pack ($value) is unavailable. System icons are used until it is installed again.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(query, { query = it }, label = { Text("Search installed packs") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 320.dp).selectableGroup()) {
                item(key = "") {
                    IconPackOption("System icons", "Original app icons", value.isBlank()) {
                        onChange(""); open = false
                    }
                }
                items(matching, key = { it.first }) { (id, label) ->
                    IconPackOption(label, id, value == id) { onChange(id); open = false }
                }
                if (matching.isEmpty()) item {
                    SettingsNote(if (installed.isEmpty()) "No icon packs installed. Install a compatible pack, then return to Appearance."
                        else "No icon packs match your search.")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

@Composable
private fun IconPackOption(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 44.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
