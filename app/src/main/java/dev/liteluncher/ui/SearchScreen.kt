@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.liteluncher.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liteluncher.data.*
import dev.liteluncher.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LauncherSearchField(
    query: String, onQuery: (String) -> Unit, placeholder: String, onSubmit: () -> Unit,
    onVoice: (() -> Unit)?, theme: SurfaceTheme, focusRequester: FocusRequester? = null
) {
    val foreground = surfaceContent(theme)
    OutlinedTextField(value = query, onValueChange = { onQuery(it.take(512)) }, modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        singleLine = true, shape = RoundedCornerShape(19.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
        placeholder = { Text(placeholder, fontSize = 13.sp, color = foreground.copy(alpha = .5f)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = foreground.copy(alpha = .65f), modifier = Modifier.size(21.dp)) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear search", tint = foreground, modifier = Modifier.size(19.dp)) }
                if (onVoice != null) IconButton(onClick = onVoice) { Icon(Icons.Rounded.MicNone, "Voice search", tint = foreground, modifier = Modifier.size(21.dp)) }
            }
        }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = foreground, unfocusedTextColor = foreground,
            focusedContainerColor = surfaceColor(theme, MaterialTheme.colorScheme.primary), unfocusedContainerColor = surfaceColor(theme, MaterialTheme.colorScheme.primary),
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .5f), unfocusedBorderColor = foreground.copy(alpha = .09f)),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }))
}

@Composable
fun SearchScreen(state: LauncherState, apps: List<AppEntry>, repository: AppsRepository, badges: Map<String, Int>, permissionSignal: Int,
    query: String, onQuery: (String) -> Unit, onVoice: () -> Unit, actions: WorkspaceActions) {
    val prefs = state.prefs.search
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var engines by remember { mutableStateOf(false) }
    val search = query.trim()
    val matches = remember(apps, search, prefs.apps, prefs.hiddenApps, prefs.grid.rows, prefs.grid.columns, state.hiddenApps, state.overrides) {
        if (prefs.apps && search.isNotBlank()) apps.filter { (prefs.hiddenApps || it.key !in state.hiddenApps) &&
            (state.overrides[it.key]?.label?.ifBlank { it.label } ?: it.label).contains(search, true) }
            .sortedBy { if (it.label.startsWith(search, true)) 0 else 1 }.take(prefs.grid.rows * prefs.grid.columns) else emptyList()
    }
    val contacts by produceState<List<ContactEntry>>(emptyList(), search, prefs.contacts, permissionSignal) {
        value = if (prefs.contacts && search.isNotBlank()) withContext(Dispatchers.IO) { repository.contacts(search).take(15) } else emptyList()
    }
    val allShortcuts by produceState<List<NativeShortcut>>(emptyList(), apps, prefs.shortcuts, permissionSignal) {
        value = if (prefs.shortcuts) withContext(Dispatchers.IO) { apps.flatMap { app -> repository.shortcuts(app) }.take(300) } else emptyList()
    }
    val shortcuts = remember(allShortcuts, search, state.hiddenApps, prefs.hiddenApps, apps) {
        if (search.isBlank()) emptyList() else allShortcuts.filter { shortcut -> shortcut.label.contains(search, true) &&
            (prefs.hiddenApps || apps.none { it.packageName == shortcut.packageName && it.user == shortcut.user && it.key in state.hiddenApps }) }.take(12)
    }
    val calculation = remember(search, prefs.math) { if (prefs.math && search.isNotBlank()) SearchEngine.calculate(search) else null }
    val settings = if (prefs.settings && search.isNotBlank()) SearchEngine.settingTargets.filter { it.title.contains(search, true) } else emptyList()
    fun rememberQuery() { if (prefs.history && search.isNotBlank()) actions.update { it.copy(history = (listOf(search) + it.history.filterNot { entry -> entry == search }).take(24)) } }
    fun openWeb() {
        if (search.isNotBlank()) runCatching { SearchEngine.webUrl(search, prefs) }.onSuccess { url ->
            rememberQuery(); keyboard?.hide(); SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { android.widget.Toast.makeText(context, "Check your custom search URL in Settings", android.widget.Toast.LENGTH_SHORT).show() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = prefs.grid.padding.dp).imePadding()) {
        PageHeader("Find your next thing", "Search", Icons.Rounded.Tune, actions.settings)
        LauncherSearchField(query, onQuery, "Apps, people, calculations…", {
            when { matches.isNotEmpty() -> { rememberQuery(); actions.launch(matches.first()) }; prefs.web -> openWeb() }
        }, onVoice, state.prefs.appearance.search, focus)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { engines = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Icon(Icons.Rounded.Public, null, modifier = Modifier.size(13.dp))
                    Text("  ${engineName(prefs.engine)}", fontSize = 11.sp)
                    Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(15.dp))
                }
                DropdownMenu(expanded = engines, onDismissRequest = { engines = false }) {
                    WebEngine.entries.forEach { engine -> DropdownMenuItem(text = { Text(engineName(engine)) }, onClick = {
                        actions.update { it.copy(prefs = it.prefs.copy(search = it.prefs.search.copy(engine = engine))) }; engines = false
                    }) }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("ON YOUR DEVICE", fontSize = 8.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (search.isBlank()) {
                if (prefs.history && state.history.isNotEmpty()) item {
                    SectionLabel("Recent searches", "Clear", { actions.update { it.copy(history = emptyList()) } })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.history.take(12).forEach { recent -> SuggestionChip(onClick = { onQuery(recent) }, label = { Text(recent, fontSize = 12.sp, maxLines = 1) },
                            icon = { Icon(Icons.Rounded.History, null, modifier = Modifier.size(14.dp)) }) }
                    }
                }
                item {
                    Spacer(Modifier.height(36.dp))
                    EmptyCard(Icons.Rounded.TravelExplore, "One search. A little of everything.", "Find an app, do a quick calculation, look up a contact, or head to the web.")
                }
            } else {
                if (calculation != null) item {
                    SectionLabel("Calculator")
                    Surface(onClick = { clipboard.setText(AnnotatedString(calculation)) }, color = MaterialTheme.colorScheme.primary.copy(alpha = .11f), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Calculate, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.padding(start = 17.dp).weight(1f)) {
                                Text(search, fontSize = 12.sp, color = Muted)
                                Text("= $calculation", color = MaterialTheme.colorScheme.primary, fontSize = 27.sp)
                            }
                            Icon(Icons.Rounded.ContentCopy, "Copy calculation", modifier = Modifier.size(17.dp), tint = Muted)
                        }
                    }
                }
                if (matches.isNotEmpty()) {
                    item { SectionLabel("Applications · ${matches.size}") }
                    if (prefs.grid.listMode) items(matches, key = { it.key }) { app -> AppListRow(app, repository, state, { rememberQuery(); actions.launch(app) }, { actions.menu(app) }, prefs.grid) }
                    else items(matches.chunked(prefs.grid.columns)) { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((prefs.grid.spacing / 2).dp)) {
                        row.forEach { app -> AppTile(app, repository, state, prefs.grid, badges[app.badgeKey] ?: 0,
                            { rememberQuery(); keyboard?.hide(); actions.launch(app) }, { actions.menu(app) }, Modifier.weight(1f)) }
                        repeat(prefs.grid.columns - row.size) { Spacer(Modifier.weight(1f)) }
                    } }
                }
                if (shortcuts.isNotEmpty()) {
                    item { SectionLabel("App shortcuts") }
                    items(shortcuts, key = { "${it.packageName}:${it.id}:${it.user}" }) { shortcut -> ActionRow(shortcut.label, Icons.Rounded.Shortcut, {
                        rememberQuery(); repository.launchShortcut(shortcut); keyboard?.hide()
                    }, apps.firstOrNull { it.packageName == shortcut.packageName }?.label) }
                }
                if (contacts.isNotEmpty()) {
                    item { SectionLabel("People") }
                    items(contacts, key = { it.id }) { person -> ActionRow(person.name, Icons.Rounded.PersonOutline, {
                        rememberQuery(); SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(person.lookupUri)))
                    }) }
                }
                if (settings.isNotEmpty()) {
                    item { SectionLabel("Device settings") }
                    items(settings, key = { it.action }) { target -> ActionRow(target.title, Icons.Rounded.Settings, {
                        rememberQuery(); SystemActions.open(context, Intent(target.action))
                    }) }
                }
                if (matches.isEmpty() && shortcuts.isEmpty() && contacts.isEmpty() && settings.isEmpty() && calculation == null) item {
                    EmptyCard(Icons.Rounded.SearchOff, "Nothing on this device", "Try a different search${if (prefs.web) " or continue on the web." else "."}")
                }
                if (prefs.web || prefs.playStore) item { SectionLabel("Keep exploring") }
                if (prefs.web) item { ActionRow("Search ${engineName(prefs.engine)}", Icons.Rounded.Public, { openWeb() }, search) }
                if (prefs.playStore) item { ActionRow("Search Google Play", Icons.Rounded.Shop, {
                    rememberQuery(); SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(SearchEngine.playStoreUrl(search))))
                }, search) }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

private fun engineName(engine: WebEngine): String = when (engine) {
    WebEngine.GOOGLE -> "Google"; WebEngine.BING -> "Bing"; WebEngine.DUCKDUCKGO -> "DuckDuckGo"
    WebEngine.YAHOO -> "Yahoo"; WebEngine.WIKIPEDIA -> "Wikipedia"; WebEngine.BAIDU -> "Baidu"
    WebEngine.ECOSIA -> "Ecosia"; WebEngine.STARTPAGE -> "Startpage"; WebEngine.CUSTOM -> "Custom"
}
