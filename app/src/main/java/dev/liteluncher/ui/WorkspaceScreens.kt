@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.liteluncher.data.*
import dev.liteluncher.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(state: LauncherState, apps: List<AppEntry>, repository: AppsRepository, badges: Map<String, Int>,
    actions: WorkspaceActions, onEmpty: () -> Unit, onDoubleTap: () -> Unit) {
    val prefs = state.prefs.home
    val selected = state.homeApps.mapNotNull { key -> apps.find { it.key == key } }
    val folders = state.homeFolders.mapNotNull { id -> state.folders.find { it.id == id } }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().combinedClickable(onClick = {}, onLongClick = onEmpty, onDoubleClick = onDoubleTap)
        .padding(horizontal = prefs.padding.dp)) {
        if (state.prefs.clock.enabled) ClockHeader(state.prefs.clock)
        else Spacer(Modifier.height(32.dp))
        Row(Modifier.padding(top = 14.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AT YOUR FINGERTIPS", fontSize = 9.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = actions.manageLayout, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Add, "Customize home layout", modifier = Modifier.size(18.dp)) }
        }
        if (selected.isEmpty() && folders.isEmpty() && state.shortcuts.isEmpty()) {
            EmptyCard(Icons.Rounded.Home, "A home of your own", "Choose your everyday apps and arrange them just how you like.", "Add apps", actions.manageLayout)
        } else LazyVerticalGrid(columns = GridCells.Fixed(if (prefs.listMode) 1 else prefs.columns), modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(prefs.spacing.dp.coerceAtMost(24.dp)),
            verticalArrangement = Arrangement.spacedBy((prefs.spacing / 2).dp)) {
            items(selected.take(prefs.rows * prefs.columns), key = { it.key }) { app ->
                if (prefs.listMode) AppListRow(app, repository, state, { actions.launch(app) }, { actions.menu(app) }, prefs)
                else AppTile(app, repository, state, prefs, badges[app.badgeKey] ?: 0, { actions.launch(app) }, { actions.menu(app) }, Modifier.fillMaxWidth())
            }
            items(folders.take((prefs.rows * prefs.columns - selected.size).coerceAtLeast(0)), key = { "folder:${it.id}" }) { folder ->
                FolderTile(folder, apps, repository, state, prefs, { actions.folder(folder, false) }, actions.manageLayout, Modifier.fillMaxWidth())
            }
            items(state.shortcuts.take((prefs.rows * prefs.columns - selected.size - folders.size).coerceAtLeast(0)), key = { "shortcut:${it.packageName}:${it.id}:${it.userSerial}" }) { shortcut ->
                val user = requireNotNull(context.getSystemService(android.os.UserManager::class.java)).getUserForSerialNumber(shortcut.userSerial)
                val app = apps.firstOrNull { it.packageName == shortcut.packageName && it.user == user }
                Column(Modifier.clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = {
                    if (user != null) repository.launchShortcut(NativeShortcut(shortcut.id, shortcut.packageName, shortcut.label, user))
                    else android.widget.Toast.makeText(context, "This shortcut's profile is unavailable", android.widget.Toast.LENGTH_SHORT).show()
                }, onLongClick = { actions.update { current -> current.copy(shortcuts = current.shortcuts - shortcut) } }).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        if (app != null) AppIcon(app, repository, state, (48 * prefs.iconScale).toInt())
                        else Icon(Icons.Rounded.Shortcut, null, modifier = Modifier.size(48.dp))
                        Icon(Icons.Rounded.Shortcut, null, tint = Lime, modifier = Modifier.align(Alignment.BottomEnd).size(16.dp).background(Ink, CircleShape))
                    }
                    if (prefs.labels) Text(shortcut.label, fontSize = 11.sp, maxLines = if (prefs.multiline) 2 else 1, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Spacer(Modifier.weight(.01f))
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("A LITTLE LESS. A LITTLE CLOSER.", fontSize = 8.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
            Icon(Icons.Rounded.South, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f), modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun ClockHeader(prefs: ClockPrefs) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(); delay(1000) } }
    val context = LocalContext.current
    val color = parseColor(prefs.color, Mist)
    val font = when (prefs.font) { "Serif" -> FontFamily.Serif; "Mono" -> FontFamily.Monospace; else -> FontFamily.SansSerif }
    Column(Modifier.fillMaxWidth().padding(top = 23.dp, bottom = 8.dp)) {
        Text(now.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())), color = color.copy(alpha = .7f), fontSize = 16.sp, fontFamily = font, fontWeight = FontWeight.Light,
            modifier = Modifier.clickable { SystemActions.open(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)) })
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.clickable { SystemActions.open(context, Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }) {
            Text(now.format(DateTimeFormatter.ofPattern(if (prefs.use24Hour) "HH:mm" else "h:mm")), color = color, fontSize = prefs.size.sp,
                fontFamily = font, fontWeight = if (prefs.font == "Bold") FontWeight.Bold else FontWeight.ExtraLight, letterSpacing = (-4).sp, lineHeight = (prefs.size + 4).sp)
            if (!prefs.use24Hour) Text(now.format(DateTimeFormatter.ofPattern("a")), color = color.copy(alpha = .55f), fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 9.dp, bottom = 17.dp))
        }
        Text(now.format(DateTimeFormatter.ofPattern(if (prefs.showYear) "MMMM d, yyyy" else "MMMM d", Locale.getDefault())), color = color.copy(alpha = .65f), fontSize = 12.sp, letterSpacing = 1.sp)
    }
}

private sealed interface DrawerRow {
    data class Header(val letter: String) : DrawerRow
    data class App(val app: AppEntry) : DrawerRow
}

@Composable
fun AppDrawer(state: LauncherState, apps: List<AppEntry>, repository: AppsRepository, badges: Map<String, Int>, actions: WorkspaceActions) {
    var filter by rememberSaveable { mutableStateOf("") }
    val prefs = state.prefs.drawer
    val collator = remember { Collator.getInstance() }
    val rows = remember(apps, state.hiddenApps, state.overrides, filter) {
        val sorted = apps.filter { it.key !in state.hiddenApps && (state.overrides[it.key]?.label?.ifBlank { it.label } ?: it.label).contains(filter, true) }
            .sortedWith { a, b -> collator.compare(state.overrides[a.key]?.label?.ifBlank { a.label } ?: a.label, state.overrides[b.key]?.label?.ifBlank { b.label } ?: b.label) }
        val grouped = sorted.groupBy { app ->
            (state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label).firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"
        }.toSortedMap(compareBy { if (it == "#") "0" else it })
        grouped.flatMap { (letter, group) -> listOf(DrawerRow.Header(letter)) + group.map { DrawerRow.App(it) } }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val letters = listOf("#") + ('A'..'Z').map { it.toString() }
    val positions = remember(rows) { rows.mapIndexedNotNull { i, row -> (row as? DrawerRow.Header)?.let { it.letter to i } }.toMap() }
    fun scrollTo(letter: String) { positions[letter]?.let { index -> scope.launch { grid.scrollToItem(index) } } }
    Column(Modifier.fillMaxSize().padding(start = prefs.padding.dp, end = 5.dp)) {
        Column(Modifier.padding(end = 16.dp)) {
            PageHeader("Everything, in its place", "Applications", Icons.Rounded.MoreHoriz, actions.settings)
            LauncherSearchField(filter, { filter = it }, "Find an application", {}, null, state.prefs.appearance.search)
            SectionLabel("${rows.count { it is DrawerRow.App }} installed apps")
        }
        if (rows.isEmpty()) EmptyCard(Icons.Rounded.SearchOff, "No applications found", "Try another name or review your hidden apps in Settings.")
        else Row(Modifier.weight(1f)) {
            LazyVerticalGrid(columns = GridCells.Fixed(if (prefs.listMode) 1 else prefs.columns), state = grid,
                modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy((prefs.spacing / 2).dp), verticalArrangement = Arrangement.spacedBy((prefs.spacing / 2).dp)) {
                items(rows.size, key = { index -> when (val row = rows[index]) { is DrawerRow.Header -> "header:${row.letter}"; is DrawerRow.App -> row.app.key } },
                    span = { index -> if (rows[index] is DrawerRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { index ->
                    when (val row = rows[index]) {
                        is DrawerRow.Header -> Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.letter, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, modifier = Modifier.width(30.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
                        }
                        is DrawerRow.App -> if (prefs.listMode) AppListRow(row.app, repository, state, { actions.launch(row.app) }, { actions.menu(row.app) })
                            else AppTile(row.app, repository, state, prefs, badges[row.app.badgeKey] ?: 0, { actions.launch(row.app) }, { actions.menu(row.app) }, Modifier.fillMaxWidth())
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(20.dp)) }
            }
            Column(Modifier.width(28.dp).fillMaxHeight().padding(vertical = 5.dp).pointerInput(positions) {
                detectDragGestures(onDragStart = { point -> scrollTo(letters[(point.y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)]) }, onDrag = { change, _ ->
                    change.consume(); scrollTo(letters[(change.position.y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)])
                })
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                letters.forEach { letter -> Text(letter, fontSize = 9.sp, color = if (letter in positions) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .25f),
                    modifier = Modifier.fillMaxWidth().weight(1f).clickable(enabled = letter in positions) { scrollTo(letter) }.wrapContentSize()) }
            }
        }
    }
}

@Composable
fun AppListRow(app: AppEntry, repository: AppsRepository, state: LauncherState, onClick: () -> Unit, onLongClick: () -> Unit, prefs: GridPrefs = state.prefs.drawer) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app, repository, state, (40 * prefs.iconScale).toInt())
        if (prefs.labels) Text(state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label, modifier = Modifier.padding(start = 15.dp).weight(1f), fontSize = 14.sp,
            maxLines = if (prefs.multiline) 2 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        else Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.NorthEast, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FavoritesScreen(state: LauncherState, apps: List<AppEntry>, repository: AppsRepository, badges: Map<String, Int>, usage: Map<String, Long>, permissionSignal: Int, actions: WorkspaceActions) {
    val prefs = state.prefs.favorites
    val excluded = buildSet {
        addAll(state.hiddenApps)
        if (prefs.excludeDock) addAll(state.dockApps)
        if (prefs.excludeFolders) state.folders.forEach { addAll(it.apps) }
    }
    val favorites = remember(state.favoriteApps, state.launches, apps, prefs, usage, excluded) {
        val dynamic = if (prefs.byUsage) apps.filter { (state.launches[it.key] ?: 0) > 0 || (usage[it.key] ?: 0) > 0 }
            .sortedByDescending { (usage[it.key] ?: 0L) + (state.launches[it.key] ?: 0) * 60_000L }.map { it.key } else emptyList()
        (state.favoriteApps + dynamic).distinct().filter { it !in excluded }.take(minOf(prefs.maxItems, prefs.grid.rows * prefs.grid.columns)).mapNotNull { key -> apps.find { it.key == key } }
    }
    val context = LocalContext.current
    val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val contacts by produceState<List<ContactEntry>>(emptyList(), permissionSignal, prefs.showContacts) {
        value = if (permitted && prefs.showContacts) withContext(Dispatchers.IO) { repository.contacts() } else emptyList()
    }
    val pinned by produceState<List<Pair<String, ContactEntry>>>(emptyList(), state.contacts, contacts, permissionSignal) {
        val references = state.contacts
        value = if (permitted) withContext(Dispatchers.IO) {
            references.mapNotNull { reference ->
                val resolved = runCatching {
                    android.provider.ContactsContract.Contacts.lookupContact(context.contentResolver, Uri.parse(reference))
                }.getOrNull()
                contacts.firstOrNull { it.id == resolved?.lastPathSegment }?.let { reference to it }
            }
        } else emptyList()
    }
    var picker by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = prefs.grid.padding.dp)) {
        item { PageHeader("Your everyday essentials", "Favorites", Icons.Rounded.Tune, actions.settings) }
        item { SectionLabel(if (prefs.byUsage) "Most used & pinned" else "Pinned applications", "Arrange", actions.manageLayout) }
        if (favorites.isEmpty()) item { EmptyCard(Icons.Rounded.StarOutline, "Your favorites find you", "Open apps to see your most used here, or long-press any app to pin it.", "Browse applications", { actions.navigate(Screen.APPS) }) }
        else if (prefs.grid.listMode) items(favorites, key = { it.key }) { app -> AppListRow(app, repository, state, { actions.launch(app) }, { actions.menu(app) }, prefs.grid) }
        else items(favorites.chunked(prefs.grid.columns)) { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((prefs.grid.spacing / 2).dp)) {
            row.forEach { app -> AppTile(app, repository, state, prefs.grid, badges[app.badgeKey] ?: 0, { actions.launch(app) }, { actions.menu(app) }, Modifier.weight(1f)) }
            repeat(prefs.grid.columns - row.size) { Spacer(Modifier.weight(1f)) }
        } }
        if (prefs.showContacts) {
            item { SectionLabel("People", "Pin contact", { if (permitted) picker = true else actions.requestContacts() }) }
            if (!permitted) item { EmptyCard(Icons.Rounded.PeopleOutline, "Keep your people close", "Allow contact access to pin the people you reach for most.", "Allow contacts", actions.requestContacts) }
            items(pinned.distinctBy { it.second.id }, key = { "contact:${it.second.id}" }) { (_, person) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = {
                    SystemActions.open(context, Intent(Intent.ACTION_VIEW, Uri.parse(person.lookupUri)))
                }, onLongClick = {
                    val references = pinned.filter { it.second.id == person.id }.map { it.first }.toSet()
                    actions.update { it.copy(contacts = it.contacts.filterNot { reference -> reference in references }) }
                }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .13f), CircleShape), contentAlignment = Alignment.Center) { Text(person.name.take(1), color = MaterialTheme.colorScheme.primary, fontSize = 19.sp) }
                    Text(person.name, modifier = Modifier.padding(start = 14.dp).weight(1f), fontSize = 14.sp)
                    Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.size(18.dp), tint = Muted)
                }
            }
            if (permitted && pinned.isEmpty()) item { Text("Pin a contact to add them here.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 24.dp)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("Pin contacts") }, text = {
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            if (contacts.isEmpty()) item { Text("No contacts available on this device.") }
            items(contacts, key = { it.id }) { person ->
                val references = pinned.filter { it.second.id == person.id }.map { it.first }.toSet()
                val selected = references.isNotEmpty() || person.lookupUri in state.contacts
                val toggle: () -> Unit = {
                    actions.update { it.copy(contacts = if (selected) it.contacts.filterNot { reference -> reference in references || reference == person.lookupUri } else (it.contacts + person.lookupUri).distinct()) }
                }
                Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(selected, onCheckedChange = { toggle() })
                Text(person.name, fontSize = 14.sp)
            } }
        }
    }, confirmButton = { TextButton(onClick = { picker = false }) { Text("Done") } })
}
