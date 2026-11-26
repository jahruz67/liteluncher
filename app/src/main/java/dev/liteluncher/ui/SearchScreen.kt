@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.liteluncher.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liteluncher.data.*
import dev.liteluncher.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private data class LocalSearchResults(
    val query: String, val apps: List<AppEntry>, val hiddenApps: List<AppEntry>,
    val shortcuts: List<NativeShortcut>, val calculation: String?
)

@Composable
fun LauncherSearchField(
    query: String, onQuery: (String) -> Unit, placeholder: String, onSubmit: () -> Unit,
    onVoice: (() -> Unit)?, theme: SurfaceTheme, focusRequester: FocusRequester? = null,
    onActivate: (() -> Unit)? = null, onBack: (() -> Unit)? = null
) {
    val foreground = surfaceContent(theme)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(MaterialTheme.shapes.extraLarge)
            .background(surfaceColor(theme, MaterialTheme.colorScheme.primary))
            .then(if (onActivate != null) Modifier.clickable(onClick = onActivate) else Modifier)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to home", tint = foreground.copy(alpha = .75f), modifier = Modifier.size(21.dp))
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
    val appRowPrefs = prefs.grid.copy(labels = true, iconScale = prefs.grid.iconScale * 1.2f)
    val context = LocalContext.current
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val search = query.trim()
    val latestQuery = remember { mutableStateOf(search) }
    SideEffect { latestQuery.value = search }

    val resultLimit = prefs.grid.rows * prefs.grid.columns
    val contactResults by produceState<Pair<String, List<ContactEntry>>?>(null, search, prefs.contacts, permissionSignal) {
        value = null
        if (prefs.contacts && search.isNotBlank()) {
            delay(150)
            value = search to withContext(Dispatchers.IO) { repository.contacts(search, limit = 15) }
        }
    }
    val contacts = contactResults?.takeIf { it.first == search && prefs.contacts }?.second.orEmpty()
    val shortcutRevision by repository.shortcutRevision.collectAsStateWithLifecycle()
    val allShortcuts by produceState<List<NativeShortcut>>(emptyList(), apps, prefs.shortcuts, permissionSignal, shortcutRevision) {
        value = if (prefs.shortcuts) withContext(Dispatchers.IO) {
            repository.shortcuts(apps)
        } else emptyList()
    }
    val localResults = remember(apps, allShortcuts, prefs.apps, prefs.hiddenApps, prefs.shortcuts,
        prefs.math, state.hiddenApps, state.overrides, resultLimit) {
        mutableStateOf<LocalSearchResults?>(null)
    }
    LaunchedEffect(localResults, search) {
        localResults.value = withContext(Dispatchers.Default) {
            val workContext = currentCoroutineContext()
            // Keep prefix matches first without sorting or retaining every match.
            val visiblePrefixes = ArrayList<AppEntry>()
            val visibleOthers = ArrayList<AppEntry>()
            val hiddenPrefixes = ArrayList<AppEntry>()
            val hiddenOthers = ArrayList<AppEntry>()
            if (search.isNotBlank() && (prefs.apps || prefs.hiddenApps)) for (app in apps) {
                workContext.ensureActive()
                val hidden = app.key in state.hiddenApps
                if (if (hidden) !prefs.hiddenApps else !prefs.apps) continue
                val prefixes = if (hidden) hiddenPrefixes else visiblePrefixes
                if (prefixes.size >= resultLimit) continue
                val label = state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label
                val target = when {
                    label.startsWith(search, true) -> prefixes
                    label.contains(search, true) -> if (hidden) hiddenOthers else visibleOthers
                    else -> continue
                }
                if (target.size < resultLimit) target.add(app)
            }
            val hiddenShortcutApps = if (prefs.shortcuts && !prefs.hiddenApps) apps.asSequence().filter {
                workContext.ensureActive()
                it.key in state.hiddenApps
            }.map { it.packageName to it.user }.toSet() else emptySet()
            val shortcuts = if (!prefs.shortcuts || search.isBlank()) emptyList() else allShortcuts.asSequence().filter { shortcut ->
                workContext.ensureActive()
                shortcut.label.contains(search, true) &&
                    (prefs.hiddenApps || (shortcut.packageName to shortcut.user) !in hiddenShortcutApps)
            }.take(12).toList()
            workContext.ensureActive()
            val calculation = if (prefs.math && search.isNotBlank()) SearchEngine.calculate(search) else null
            LocalSearchResults(search, (visiblePrefixes + visibleOthers).take(resultLimit),
                (hiddenPrefixes + hiddenOthers).take(resultLimit), shortcuts, calculation)
        }
    }
    val results = localResults.value
    val matches = results?.apps.orEmpty()
    val hiddenMatches = results?.hiddenApps.orEmpty()
    val shortcuts = results?.shortcuts.orEmpty()
    val calculation = results?.takeIf { it.query == search }?.calculation
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

    fun openWeb() {
        if (search.isNotBlank()) runCatching { SearchEngine.webUrl(search, prefs) }.onSuccess { url ->
            keyboard?.hide(); SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { android.widget.Toast.makeText(context, "Check your custom search URL in Settings", android.widget.Toast.LENGTH_SHORT).show() }
    }

    val searchField = @Composable {
        LauncherSearchField(
            query = query,
            onQuery = { latestQuery.value = it.trim(); onQuery(it) },
            placeholder = if (prefs.barText.isNotBlank()) prefs.barText else "Apps, people, calculations…",
            onSubmit = submit@{
                if (results?.query != latestQuery.value) return@submit
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
                if (target != null) keyboard?.hide()
                when (target) {
                    SearchTarget.APPS -> actions.launch(matches.first())
                    SearchTarget.HIDDEN_APPS -> actions.launch(hiddenMatches.first())
                    SearchTarget.CONTACTS -> SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(contacts.first().lookupUri)))
                    SearchTarget.SHORTCUTS -> actions.shortcut(shortcuts.first())
                    SearchTarget.SETTINGS -> SystemActions.open(context, Intent(settings.first().action))
                    SearchTarget.MATH -> clipboard?.setPrimaryClip(ClipData.newPlainText("Calculation", calculation!!))
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
                val recentApps = if (prefs.history) state.history.asSequence().mapNotNull { key ->
                    apps.firstOrNull { it.key == key }
                }.filter { prefs.hiddenApps || it.key !in state.hiddenApps }.take(12).toList() else emptyList()
                if (recentApps.isNotEmpty()) {
                    item { SectionLabel("Recent apps", "Clear", { actions.update { it.copy(history = emptyList()) } }) }
                    items(recentApps, key = { it.key }) { app ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp)
                                .clickable { actions.launch(app) }
                                .clip(RoundedCornerShape(12.dp)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app, repository, state, 40)
                            Text(
                                text = state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 12.dp).weight(1f)
                            )
                        }
                    }
                }
            } else {
                val hasResults = matches.isNotEmpty() || hiddenMatches.isNotEmpty() || shortcuts.isNotEmpty() || contacts.isNotEmpty() || settings.isNotEmpty() || calculation != null || websiteUrl != null

                if (!hasResults && !prefs.web && !prefs.playStore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
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
                                    Surface(onClick = { clipboard?.setPrimaryClip(ClipData.newPlainText("Calculation", calculation)) }, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.Calculate, null, tint = MaterialTheme.colorScheme.primary)
                                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                                Text(search, style = MaterialTheme.typography.bodySmall)
                                                Text("= $calculation", style = MaterialTheme.typography.headlineSmall)
                                            }
                                            Icon(Icons.Rounded.ContentCopy, "Copy calculation", modifier = Modifier.size(17.dp))
                                        }
                                    }
                                }
                            }
                            SearchTarget.APPS -> if (matches.isNotEmpty()) {
                                item { SectionLabel("Applications · ${matches.size}") }
                                if (prefs.grid.listMode) items(matches, key = { it.key }) { app ->
                                    AppListRow(app, repository, state, { keyboard?.hide(); actions.launch(app) }, { actions.menu(app) },
                                        appRowPrefs, draggable = true, modifier = Modifier.heightIn(min = 64.dp))
                                }
                                else items(matches.chunked(prefs.grid.columns)) { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((prefs.grid.spacing / 2).dp)) {
                                        row.forEach { app ->
                                            AppTile(app, repository, state, prefs.grid, badges[app.badgeKey] ?: 0,
                                                { keyboard?.hide(); actions.launch(app) }, { actions.menu(app) }, Modifier.weight(1f), draggable = true)
                                        }
                                        repeat(prefs.grid.columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                            SearchTarget.HIDDEN_APPS -> if (hiddenMatches.isNotEmpty()) {
                                item { SectionLabel("Hidden applications · ${hiddenMatches.size}") }
                                if (prefs.grid.listMode) items(hiddenMatches, key = { "hidden:${it.key}" }) { app ->
                                    AppListRow(app, repository, state, { keyboard?.hide(); actions.launch(app) }, { actions.menu(app) },
                                        appRowPrefs, modifier = Modifier.heightIn(min = 64.dp))
                                } else items(hiddenMatches.chunked(prefs.grid.columns)) { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((prefs.grid.spacing / 2).dp)) {
                                        row.forEach { app ->
                                            AppTile(app, repository, state, prefs.grid, badges[app.badgeKey] ?: 0,
                                                { keyboard?.hide(); actions.launch(app) }, { actions.menu(app) }, Modifier.weight(1f))
                                        }
                                        repeat(prefs.grid.columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                            SearchTarget.SHORTCUTS -> if (shortcuts.isNotEmpty()) {
                                item { SectionLabel("App shortcuts") }
                                items(shortcuts, key = { "${it.packageName}:${it.id}:${it.user}" }) { shortcut ->
                                    ActionRow(shortcut.label, Icons.AutoMirrored.Rounded.Shortcut, {
                                        actions.shortcut(shortcut); keyboard?.hide()
                                    }, apps.firstOrNull { it.packageName == shortcut.packageName }?.label)
                                }
                            }
                            SearchTarget.CONTACTS -> if (contacts.isNotEmpty()) {
                                item { SectionLabel("People") }
                                items(contacts, key = { it.id }) { person ->
                                    ActionRow(person.name, Icons.Rounded.PersonOutline, {
                                        SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(person.lookupUri)))
                                    })
                                }
                            }
                            SearchTarget.SETTINGS -> if (settings.isNotEmpty()) {
                                item { SectionLabel("Device settings") }
                                items(settings, key = { it.action }) { t ->
                                    ActionRow(t.title, Icons.Rounded.Settings, {
                                        SystemActions.open(context, Intent(t.action))
                                    })
                                }
                            }
                            SearchTarget.WEBSITE -> if (websiteUrl != null) {
                                item {
                                    SectionLabel("Open Website")
                                    ActionRow(websiteUrl, Icons.Rounded.Language, {
                                        keyboard?.hide(); SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl)))
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
                                        SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(SearchEngine.playStoreUrl(search))))
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
