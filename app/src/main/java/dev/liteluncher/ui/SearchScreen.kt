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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liteluncher.data.*
import dev.liteluncher.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun LauncherSearchField(
    query: String, onQuery: (String) -> Unit, placeholder: String, onSubmit: () -> Unit,
    onVoice: (() -> Unit)?, theme: SurfaceTheme, focusRequester: FocusRequester? = null,
    onActivate: (() -> Unit)? = null, onBack: (() -> Unit)? = null
) {
    val foreground = surfaceContent(theme)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(50))
            .background(surfaceColor(theme, MaterialTheme.colorScheme.primary))
            .then(if (onActivate != null) Modifier.clickable(onClick = onActivate) else Modifier)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.ArrowBack, "Back to home", tint = foreground.copy(alpha = .75f), modifier = Modifier.size(21.dp))
        } else Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Search, null, tint = foreground.copy(alpha = .65f), modifier = Modifier.size(21.dp))
        }
        if (onActivate != null) {
            Text(placeholder, color = foreground.copy(alpha = .65f), fontSize = 14.sp, modifier = Modifier.weight(1f))
        } else BasicTextField(
            value = query,
            onValueChange = { onQuery(it.take(512)) },
            modifier = Modifier.weight(1f).then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = foreground, fontSize = 14.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { input ->
                Box {
                    if (query.isEmpty()) Text(placeholder, color = foreground.copy(alpha = .65f), fontSize = 14.sp)
                    input()
                }
            }
        )
        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Close, "Clear search", tint = foreground, modifier = Modifier.size(19.dp))
        }
        if (onVoice != null) IconButton(onClick = onVoice, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.MicNone, "Voice search", tint = foreground.copy(alpha = .75f), modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
fun SearchScreen(
    state: LauncherState, apps: List<AppEntry>, repository: AppsRepository, badges: Map<String, Int>,
    permissionSignal: Int, query: String, onQuery: (String) -> Unit, onVoice: () -> Unit, actions: WorkspaceActions
) {
    val prefs = state.prefs.search
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val search = query.trim()

    val matchingApps = remember(apps, search, prefs.apps, prefs.hiddenApps, state.hiddenApps, state.overrides) {
        if (search.isBlank()) emptyList() else apps.filter {
            (state.overrides[it.key]?.label?.ifBlank { it.label } ?: it.label).contains(search, true)
        }.sortedBy {
            if ((state.overrides[it.key]?.label?.ifBlank { it.label } ?: it.label).startsWith(search, true)) 0 else 1
        }
    }
    val resultLimit = prefs.grid.rows * prefs.grid.columns
    val matches = if (prefs.apps) matchingApps.filterNot { it.key in state.hiddenApps }.take(resultLimit) else emptyList()
    val hiddenMatches = if (prefs.hiddenApps) matchingApps.filter { it.key in state.hiddenApps }.take(resultLimit) else emptyList()
    val contacts by produceState<List<ContactEntry>>(emptyList(), search, prefs.contacts, permissionSignal) {
        value = emptyList()
        if (prefs.contacts && search.isNotBlank()) {
            delay(150)
            value = withContext(Dispatchers.IO) { repository.contacts(search, limit = 15) }
        }
    }
    val shortcutRevision by repository.shortcutRevision.collectAsStateWithLifecycle()
    val allShortcuts by produceState<List<NativeShortcut>>(emptyList(), apps, prefs.shortcuts, permissionSignal, shortcutRevision) {
        value = if (prefs.shortcuts) withContext(Dispatchers.IO) {
            repository.shortcuts(apps)
        } else emptyList()
    }
    val hiddenShortcutApps = remember(apps, state.hiddenApps) {
        apps.asSequence().filter { it.key in state.hiddenApps }.map { it.packageName to it.user }.toSet()
    }
    val shortcuts = remember(allShortcuts, search, hiddenShortcutApps, prefs.hiddenApps) {
        if (search.isBlank()) emptyList() else allShortcuts.asSequence().filter { shortcut -> shortcut.label.contains(search, true) &&
            (prefs.hiddenApps || (shortcut.packageName to shortcut.user) !in hiddenShortcutApps) }.take(12).toList()
    }
    val calculation = remember(search, prefs.math) { if (prefs.math && search.isNotBlank()) SearchEngine.calculate(search) else null }
    val settings = if (prefs.settings && search.isNotBlank()) SearchEngine.settingTargets.filter { it.title.contains(search, true) } else emptyList()

    val candidateUrl = when {
        search.startsWith("http://", true) -> "http://${search.substring(7)}"
        search.startsWith("https://", true) -> "https://${search.substring(8)}"
        else -> "https://$search"
    }
    val websiteUrl = candidateUrl.takeIf {
        prefs.website && calculation == null && search.isNotBlank() && !search.any(Char::isWhitespace) &&
            runCatching { java.net.URI(it).let { uri -> uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.host.contains('.') && uri.userInfo == null } }.getOrDefault(false)
    }

    fun rememberQuery() { if (prefs.history && search.isNotBlank()) actions.update { it.copy(history = (listOf(search) + it.history.filterNot { entry -> entry == search }).take(24)) } }
    fun openWeb() {
        if (search.isNotBlank()) runCatching { SearchEngine.webUrl(search, prefs) }.onSuccess { url ->
            rememberQuery(); keyboard?.hide(); SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { android.widget.Toast.makeText(context, "Check your custom search URL in Settings", android.widget.Toast.LENGTH_SHORT).show() }
    }

    val searchField = @Composable {
        LauncherSearchField(
            query = query,
            onQuery = onQuery,
            placeholder = if (prefs.barText.isNotBlank()) prefs.barText else "Apps, people, calculations…",
            onSubmit = {
                val target = prefs.targetOrder.firstOrNull { when (it) {
                    SearchTarget.APPS -> matches.isNotEmpty()
                    SearchTarget.HIDDEN_APPS -> hiddenMatches.isNotEmpty()
                    SearchTarget.CONTACTS -> contacts.isNotEmpty()
                    SearchTarget.SHORTCUTS -> shortcuts.isNotEmpty()
                    SearchTarget.SETTINGS -> settings.isNotEmpty()
                    SearchTarget.MATH -> calculation != null
                    SearchTarget.WEBSITE -> websiteUrl != null
                    SearchTarget.WEB -> prefs.web && search.isNotBlank()
                    SearchTarget.PLAY_STORE -> prefs.playStore && search.isNotBlank()
                } }
                if (target != null) { rememberQuery(); keyboard?.hide() }
                when (target) {
                    SearchTarget.APPS -> actions.launch(matches.first())
                    SearchTarget.HIDDEN_APPS -> actions.launch(hiddenMatches.first())
                    SearchTarget.CONTACTS -> SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(contacts.first().lookupUri)))
                    SearchTarget.SHORTCUTS -> actions.shortcut(shortcuts.first())
                    SearchTarget.SETTINGS -> SystemActions.open(context, Intent(settings.first().action))
                    SearchTarget.MATH -> clipboard.setText(AnnotatedString(calculation!!))
                    SearchTarget.WEBSITE -> SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl!!)))
                    SearchTarget.WEB -> openWeb()
                    SearchTarget.PLAY_STORE -> SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(SearchEngine.playStoreUrl(search))))
                    null -> Unit
                }
            },
            onVoice = if (prefs.voice) onVoice else null,
            theme = state.prefs.appearance.search,
            focusRequester = focus,
            onBack = { keyboard?.hide(); actions.navigate(Screen.HOME) }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = prefs.grid.borderPaddingDp.dp).imePadding()) {

        if (!prefs.barAtBottom) {
            searchField()
            Spacer(Modifier.height(4.dp))
        }


        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (search.isBlank()) {
                if (prefs.history && state.history.isNotEmpty()) {
                    item { SectionLabel("Recent searches", "Clear", { actions.update { it.copy(history = emptyList()) } }) }
                    items(state.history.take(12), key = { it }) { recent ->
                        val app = apps.firstOrNull {
                            (prefs.hiddenApps || it.key !in state.hiddenApps) &&
                                (state.overrides[it.key]?.label?.ifBlank { it.label } ?: it.label).equals(recent, true)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)
                                .clickable { onQuery(recent) }
                                .clip(RoundedCornerShape(12.dp)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app != null) {
                                AppIcon(app, repository, state, 40)
                            } else {
                                Icon(Icons.Rounded.History, null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
                            }
                            Text(
                                text = recent,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 12.dp).weight(1f)
                            )
                        }
                    }
                }
                if (prefs.hint) item {
                    Text("Search apps, shortcuts, contacts, settings, or the web.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            } else {
                val hasResults = matches.isNotEmpty() || hiddenMatches.isNotEmpty() || shortcuts.isNotEmpty() || contacts.isNotEmpty() || settings.isNotEmpty() || calculation != null || websiteUrl != null

                if (!hasResults && !prefs.web && !prefs.playStore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            Text("No results were found", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    // Render in targetOrder
                    prefs.targetOrder.forEach { target ->
                        when (target) {
                            SearchTarget.MATH -> if (calculation != null) {
                                item {
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
                            }
                            SearchTarget.APPS -> if (matches.isNotEmpty()) {
                                item { SectionLabel("Applications · ${matches.size}") }
                                if (prefs.grid.listMode) items(matches, key = { it.key }) { app -> AppListRow(app, repository, state, { rememberQuery(); actions.launch(app) }, { actions.menu(app) }, prefs.grid, draggable = true) }
                                else items(matches.chunked(prefs.grid.columns)) { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((prefs.grid.spacing / 2).dp)) {
                                        row.forEach { app ->
                                            AppTile(app, repository, state, prefs.grid, badges[app.badgeKey] ?: 0,
                                                { rememberQuery(); keyboard?.hide(); actions.launch(app) }, { actions.menu(app) }, Modifier.weight(1f), draggable = true)
                                        }
                                        repeat(prefs.grid.columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                            SearchTarget.HIDDEN_APPS -> if (hiddenMatches.isNotEmpty()) {
                                item { SectionLabel("Hidden applications · ${hiddenMatches.size}") }
                                if (prefs.grid.listMode) items(hiddenMatches, key = { "hidden:${it.key}" }) { app ->
                                    AppListRow(app, repository, state, { rememberQuery(); actions.launch(app) }, { actions.menu(app) }, prefs.grid)
                                } else items(hiddenMatches.chunked(prefs.grid.columns)) { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((prefs.grid.spacing / 2).dp)) {
                                        row.forEach { app ->
                                            AppTile(app, repository, state, prefs.grid, badges[app.badgeKey] ?: 0,
                                                { rememberQuery(); keyboard?.hide(); actions.launch(app) }, { actions.menu(app) }, Modifier.weight(1f))
                                        }
                                        repeat(prefs.grid.columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                            SearchTarget.SHORTCUTS -> if (shortcuts.isNotEmpty()) {
                                item { SectionLabel("App shortcuts") }
                                items(shortcuts, key = { "${it.packageName}:${it.id}:${it.user}" }) { shortcut ->
                                    ActionRow(shortcut.label, Icons.Rounded.Shortcut, {
                                        rememberQuery(); actions.shortcut(shortcut); keyboard?.hide()
                                    }, apps.firstOrNull { it.packageName == shortcut.packageName }?.label)
                                }
                            }
                            SearchTarget.CONTACTS -> if (contacts.isNotEmpty()) {
                                item { SectionLabel("People") }
                                items(contacts, key = { it.id }) { person ->
                                    ActionRow(person.name, Icons.Rounded.PersonOutline, {
                                        rememberQuery(); SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(person.lookupUri)))
                                    })
                                }
                            }
                            SearchTarget.SETTINGS -> if (settings.isNotEmpty()) {
                                item { SectionLabel("Device settings") }
                                items(settings, key = { it.action }) { t ->
                                    ActionRow(t.title, Icons.Rounded.Settings, {
                                        rememberQuery(); SystemActions.open(context, Intent(t.action))
                                    })
                                }
                            }
                            SearchTarget.WEBSITE -> if (websiteUrl != null) {
                                item {
                                    SectionLabel("Open Website")
                                    ActionRow(websiteUrl, Icons.Rounded.Language, {
                                        rememberQuery(); keyboard?.hide(); SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl)))
                                    })
                                }
                            }
                            SearchTarget.WEB -> if (prefs.web) {
                                item {
                                    SectionLabel("Web Search")
                                    ActionRow("Search ${prefs.engine.displayName()}", Icons.Rounded.Public, { openWeb() }, search)
                                }
                            }
                            SearchTarget.PLAY_STORE -> if (prefs.playStore) {
                                item {
                                    SectionLabel("Google Play")
                                    ActionRow("Search on Google Play", Icons.Rounded.Shop, {
                                        rememberQuery(); SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(SearchEngine.playStoreUrl(search))))
                                    }, search)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (prefs.barAtBottom) {
            Spacer(Modifier.height(8.dp))
            searchField()
            Spacer(Modifier.height(8.dp))
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
