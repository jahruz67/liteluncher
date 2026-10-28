@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
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
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HomeScreen(
    state: LauncherState,
    apps: List<AppEntry>,
    repository: AppsRepository,
    badges: Map<String, Int>,
    actions: WorkspaceActions,
    onEmpty: () -> Unit,
    onDoubleTap: () -> Unit,
    onVoice: (() -> Unit)? = null
) {
    val prefs = state.prefs.home
    val appsByKey = remember(apps) { apps.associateBy { it.key } }
    val selected = remember(state.homeApps, appsByKey) { state.homeApps.mapNotNull(appsByKey::get) }
    val foldersById = remember(state.folders) { state.folders.associateBy { it.id } }
    val folders = remember(state.homeFolders, foldersById) { state.homeFolders.mapNotNull(foldersById::get) }
    val context = LocalContext.current
    val showSearchBar = Screen.HOME in state.prefs.search.barScreens
    val barAtBottom = state.prefs.search.barAtBottom

    Column(
        Modifier.fillMaxSize()
            .appDropTarget(AppDropTarget.Area(LayoutArea.HOME))
            .combinedClickable(onClick = {}, onLongClick = onEmpty, onDoubleClick = onDoubleTap)
            .padding(horizontal = prefs.padding.dp)
    ) {
        if (showSearchBar && !barAtBottom) {
            Spacer(Modifier.height(8.dp))
            LauncherSearchField(
                query = "",
                onQuery = { actions.navigate(Screen.SEARCH) },
                placeholder = state.prefs.search.barText,
                onSubmit = { actions.navigate(Screen.SEARCH) },
                onVoice = onVoice.takeIf { state.prefs.search.voice },
                theme = state.prefs.appearance.search,
                onActivate = { actions.navigate(Screen.SEARCH) }
            )
            Spacer(Modifier.height(8.dp))
        }

        if (state.prefs.clock.enabled) {
            ClockHeader(state.prefs.clock)
        } else {
            Spacer(Modifier.height(32.dp))
        }

        if (selected.isEmpty() && folders.isEmpty() && state.shortcuts.isEmpty()) {
            Box(Modifier.weight(1f)) {
                EmptyCard(Icons.Rounded.Home, "A home of your own", "Hold an app in the app drawer to add it to Home.", "Add apps", { actions.navigate(Screen.APPS) })
            }
        } else {
            val slotCount = prefs.rows * prefs.columns
            val filledSlots = selected.size + folders.size + state.shortcuts.size
            val dockInset = if (state.prefs.dock.enabled) (56 * state.prefs.dock.iconScale + 12).dp else 0.dp
            BoxWithConstraints(Modifier.weight(1f).padding(
                start = if (state.prefs.dock.side == "Left") dockInset else 0.dp,
                end = if (state.prefs.dock.side == "Right") dockInset else 0.dp
            )) {
            val visibleRows = if (prefs.listMode) filledSlots.coerceIn(1, slotCount) else prefs.rows
            val spacing = if (prefs.listMode) (prefs.spacing / 2).dp else 0.dp
            val rowHeight = ((maxHeight - spacing * (visibleRows - 1)) / visibleRows).coerceAtLeast(0.dp)
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (prefs.listMode) 1 else prefs.columns),
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy((prefs.spacing / 4).dp),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                items(selected.take(slotCount), key = { it.key }) { app ->
                    if (prefs.listMode) Box(Modifier.height(rowHeight)) {
                        AppListRow(app, repository, state, { actions.launch(app) }, { actions.menu(app) }, prefs, !prefs.preventChanges, LayoutArea.HOME)
                    }
                    else AppTile(app, repository, state, prefs, badges[app.badgeKey] ?: 0, { actions.launch(app) }, { actions.menu(app) }, Modifier.fillMaxWidth().height(rowHeight),
                        draggable = !prefs.preventChanges, dragArea = LayoutArea.HOME)
                }
                items(folders.take((slotCount - selected.size).coerceAtLeast(0)), key = { "folder:${it.id}" }) { folder ->
                    FolderTile(folder, apps, repository, state, prefs, { actions.folder(folder, false) }, { actions.folderActions(folder) }, Modifier.fillMaxWidth().height(rowHeight))
                }
                items(state.shortcuts.take((slotCount - selected.size - folders.size).coerceAtLeast(0)), key = { "shortcut:${it.packageName}:${it.id}:${it.userSerial}" }) { shortcut ->
                    val app = remember(apps, shortcut.packageName, shortcut.userSerial) {
                        apps.firstOrNull { it.packageName == shortcut.packageName && it.key.substringAfterLast('|').toLongOrNull() == shortcut.userSerial }
                    }
                    Column(
                        Modifier.height(rowHeight).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = {
                            val user = app?.user ?: context.getSystemService(android.os.UserManager::class.java)
                                ?.getUserForSerialNumber(shortcut.userSerial)
                            if (user != null) repository.launchShortcut(NativeShortcut(shortcut.id, shortcut.packageName, shortcut.label, user))
                            else android.widget.Toast.makeText(context, "This shortcut's profile is unavailable", android.widget.Toast.LENGTH_SHORT).show()
                        }, onLongClick = { actions.update { current ->
                            if (current.prefs.home.preventChanges) current else current.copy(shortcuts = current.shortcuts - shortcut)
                        } }).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box {
                            if (app != null) AppIcon(app, repository, state, (56 * prefs.iconScale).toInt())
                            else Icon(Icons.Rounded.Shortcut, null, modifier = Modifier.size(48.dp))
                            Icon(Icons.Rounded.Shortcut, null, tint = Cerulean, modifier = Modifier.align(Alignment.BottomEnd).size(16.dp).background(Ink, CircleShape))
                        }
                        if (prefs.labels) Text(shortcut.label, fontSize = 11.sp, maxLines = if (prefs.multiline) 2 else 1, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                repeat((slotCount - filledSlots.coerceAtLeast(0)).coerceAtLeast(0)) {
                    item { Box(Modifier.fillMaxWidth().height(if (prefs.listMode) 0.dp else rowHeight)) }
                }
            }
            }
        }

        if (showSearchBar && barAtBottom) {
            Spacer(Modifier.height(8.dp))
            LauncherSearchField(
                query = "",
                onQuery = { actions.navigate(Screen.SEARCH) },
                placeholder = state.prefs.search.barText,
                onSubmit = { actions.navigate(Screen.SEARCH) },
                onVoice = onVoice.takeIf { state.prefs.search.voice },
                theme = state.prefs.appearance.search,
                onActivate = { actions.navigate(Screen.SEARCH) }
            )
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun ClockHeader(prefs: ClockPrefs, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, prefs.analog) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val interval = if (prefs.analog) 1_000L else 60_000L
            while (true) {
                now = ZonedDateTime.now()
                delay(interval - System.currentTimeMillis() % interval)
            }
        }
    }
    val context = LocalContext.current
    val color = parseColor(prefs.color, Mist)
    val font = when (prefs.font) {
        "Serif" -> FontFamily.Serif
        "Monospace" -> FontFamily.Monospace
        else -> FontFamily.SansSerif
    }
    val clockApp = prefs.clockApp
    val textScale = prefs.textScale.coerceIn(0.5f, 1.5f)
    val clockShadow = Shadow(color = Color(0x40000000), offset = Offset(0f, 4f), blurRadius = 8f)

    val onClockClick: () -> Unit = {
        val launched = when {
            '|' in clockApp -> SystemActions.openLauncherApp(context, clockApp)
            clockApp.isNotBlank() && clockApp != "Clock" -> context.packageManager.getLaunchIntentForPackage(clockApp)
                ?.let { SystemActions.open(context, it) } ?: false
            else -> false
        }
        if (!launched) SystemActions.open(context, Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
    }

    Column(modifier.fillMaxWidth().padding(start = 12.dp, top = 16.dp, bottom = 28.dp)) {
        if (prefs.analog) {
            // Analog clock rendering
            Box(Modifier.size(110.dp).clickable(onClick = onClockClick).align(Alignment.Start), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f - 4f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    drawCircle(color = color.copy(alpha = 0.15f), radius = radius)
                    drawCircle(color = color, radius = radius, style = Stroke(width = 2.dp.toPx()))

                    val hour = now.hour % 12 + now.minute / 60f
                    val minute = now.minute + now.second / 60f
                    val hourAngle = Math.toRadians((hour * 30 - 90).toDouble())
                    val minuteAngle = Math.toRadians((minute * 6 - 90).toDouble())

                    drawLine(
                        color = color,
                        start = center,
                        end = Offset((center.x + radius * 0.5f * cos(hourAngle)).toFloat(), (center.y + radius * 0.5f * sin(hourAngle)).toFloat()),
                        strokeWidth = 3.dp.toPx()
                    )
                    drawLine(
                        color = color,
                        start = center,
                        end = Offset((center.x + radius * 0.75f * cos(minuteAngle)).toFloat(), (center.y + radius * 0.75f * sin(minuteAngle)).toFloat()),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawCircle(color = Cerulean, radius = 3.dp.toPx(), center = center)
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.clickable(onClick = onClockClick)
            ) {
                val timeStr = now.format(DateTimeFormatter.ofPattern(if (prefs.use24Hour) "HH:mm" else "h:mm"))
                Text(
                    text = timeStr,
                    color = color,
                    fontSize = (prefs.size * textScale).sp,
                    fontFamily = font,
                    fontWeight = if (prefs.font == "Bold") FontWeight.Bold else FontWeight.Light,
                    letterSpacing = (-1).sp,
                    lineHeight = (prefs.size * textScale + 2).sp,
                    style = TextStyle(shadow = clockShadow)
                )
                if (!prefs.use24Hour) {
                    Text(
                        text = now.format(DateTimeFormatter.ofPattern("a")),
                        color = color.copy(alpha = .7f),
                        fontSize = (prefs.size * 0.43f * textScale).sp,
                        fontWeight = FontWeight.Normal,
                        style = TextStyle(shadow = clockShadow),
                        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                    )
                }
            }

            if (prefs.showDate) {
                Text(
                    text = now.format(DateTimeFormatter.ofPattern(
                        (if (prefs.showDay) "EEEE, " else "") + "MMM d" + (if (prefs.showYear) ", yyyy" else ""), Locale.getDefault())),
                    color = color,
                    fontSize = (16 * textScale).sp,
                    fontFamily = font,
                    style = TextStyle(shadow = clockShadow),
                    modifier = Modifier.clickable { onClockClick() }
                )
            }
        }
    }
}

private sealed interface DrawerRow {
    data class Header(val letter: String) : DrawerRow
    data class App(val app: AppEntry) : DrawerRow
}

@Composable
fun AppDrawer(
    state: LauncherState,
    apps: List<AppEntry>,
    repository: AppsRepository,
    badges: Map<String, Int>,
    actions: WorkspaceActions,
    onVoice: (() -> Unit)? = null
) {
    var filter by rememberSaveable { mutableStateOf("") }
    val prefs = state.prefs.drawer
    val locale = LocalConfiguration.current.locales[0]
    val collator = remember(locale) { Collator.getInstance(locale) }
    val sortedApps = remember(apps, state.hiddenApps, state.overrides, collator) {
        apps.filter { it.key !in state.hiddenApps }
            .sortedWith { a, b -> collator.compare(state.overrides[a.key]?.label?.ifBlank { a.label } ?: a.label, state.overrides[b.key]?.label?.ifBlank { b.label } ?: b.label) }
    }
    val rows = remember(sortedApps, state.overrides, filter) {
        val grouped = sortedApps.filter {
            (state.overrides[it.key]?.label?.ifBlank { it.label } ?: it.label).contains(filter, true)
        }.groupBy { app ->
            val first = (state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label).firstOrNull()?.uppercaseChar()
            when {
                first == null -> "#"
                first in 'A'..'Z' -> first.toString()
                first.isDigit() -> "1"
                else -> "#"
            }
        }.toSortedMap(compareBy { if (it == "#") "00" else if (it == "1") "01" else it })
        grouped.flatMap { (letter, group) -> listOf(DrawerRow.Header(letter)) + group.map { DrawerRow.App(it) } }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val letters = remember { listOf("#", "1") + ('A'..'Z').map { it.toString() } }
    val positions = remember(rows) { rows.mapIndexedNotNull { i, row -> (row as? DrawerRow.Header)?.let { it.letter to i } }.toMap() }
    var scrubLetter by remember { mutableStateOf<String?>(null) }
    var scrubY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val visibleLetter by remember(positions) { derivedStateOf {
        positions.entries.lastOrNull { it.value <= grid.firstVisibleItemIndex }?.key
    } }
    fun scrollTo(letter: String) {
        val index = letters.indexOf(letter)
        val nearest = letters.drop(index.coerceAtLeast(0)).firstOrNull { it in positions } ?: positions.keys.lastOrNull() ?: return
        positions[nearest]?.let { target -> scope.launch { grid.scrollToItem(target) } }
    }
    val showSearchBar = Screen.APPS in state.prefs.search.barScreens
    val searchField = @Composable {
        Box(Modifier.padding(end = 10.dp, top = 8.dp, bottom = 8.dp)) {
            LauncherSearchField(filter, { filter = it }, state.prefs.search.barText, {},
                onVoice.takeIf { state.prefs.search.voice }, state.prefs.appearance.search)
        }
    }

    Column(Modifier.fillMaxSize().padding(start = prefs.padding.dp, end = 2.dp)) {
        if (showSearchBar && !state.prefs.search.barAtBottom) searchField()
        if (rows.isEmpty()) EmptyCard(Icons.Rounded.SearchOff, "No applications found", "Try another name or review your hidden apps in Settings.")
        else Row(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (prefs.listMode) 1 else prefs.columns),
                state = grid,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy((prefs.spacing / 2).dp),
                verticalArrangement = Arrangement.spacedBy((prefs.spacing / 2).dp)
            ) {
                items(rows.size, key = { index -> when (val row = rows[index]) { is DrawerRow.Header -> "header:${row.letter}"; is DrawerRow.App -> row.app.key } },
                    span = { index -> if (rows[index] is DrawerRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { index ->
                    when (val row = rows[index]) {
                        is DrawerRow.Header -> Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(row.letter, color = LocalContentColor.current, fontSize = 14.sp, modifier = Modifier.width(22.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f), thickness = 0.5.dp)
                        }
                        is DrawerRow.App -> if (prefs.listMode) AppListRow(row.app, repository, state, { actions.launch(row.app) }, { actions.menu(row.app) }, prefs, draggable = true)
                            else AppTile(row.app, repository, state, prefs, badges[row.app.badgeKey] ?: 0, { actions.launch(row.app) }, { actions.menu(row.app) }, Modifier.fillMaxWidth(), draggable = true)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(20.dp)) }
            }

            // Condensed vertical A-Z scrub rail (~9sp font) along far right edge
            Box(Modifier.width(28.dp).fillMaxHeight()) {
            Column(
                Modifier.width(24.dp).fillMaxHeight().padding(vertical = 4.dp).pointerInput(positions) {
                    detectDragGestures(onDragStart = { point ->
                        val idx = (point.y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                        scrubY = point.y.coerceIn(0f, size.height.toFloat())
                        scrubLetter = letters[idx]
                        scrollTo(letters[idx])
                    }, onDragEnd = { scrubLetter = null }, onDragCancel = { scrubLetter = null }, onDrag = { change, _ ->
                        change.consume()
                        val idx = (change.position.y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                        scrubY = change.position.y.coerceIn(0f, size.height.toFloat())
                        val letter = letters[idx]
                        if (scrubLetter != letter) {
                            scrubLetter = letter
                            scrollTo(letter)
                        }
                    })
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                letters.forEach { letter ->
                    Text(
                        text = letter,
                        fontSize = 9.sp,
                        fontWeight = if (letter in positions) FontWeight.Bold else FontWeight.Normal,
                        color = if (letter in positions) LocalContentColor.current.copy(alpha = .7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .25f),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .background(if (letter == (scrubLetter ?: visibleLetter)) Color.White.copy(alpha = .3f) else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable(enabled = letter in positions) { scrollTo(letter) }.wrapContentSize()
                    )
                }
            }
            scrubLetter?.let { letter ->
                Surface(Modifier.offset(x = (-54).dp, y = with(density) { scrubY.toDp() } - 24.dp).size(48.dp),
                    shape = CircleShape, color = Color.White.copy(alpha = .7f), contentColor = Ink) {
                    Box(contentAlignment = Alignment.Center) { Text(letter, fontSize = 24.sp) }
                }
            }
            }
        }
        if (showSearchBar && state.prefs.search.barAtBottom) searchField()
    }
}

@Composable
fun AppListRow(app: AppEntry, repository: AppsRepository, state: LauncherState, onClick: () -> Unit, onLongClick: () -> Unit,
    prefs: GridPrefs = state.prefs.drawer, draggable: Boolean = false, dragArea: LayoutArea? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).appIconGestures(app, dragArea, draggable, onClick, onLongClick)
        .padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app, repository, state, (40 * prefs.iconScale).toInt())
        if (prefs.labels) Text(state.overrides[app.key]?.label?.ifBlank { app.label } ?: app.label, modifier = Modifier.padding(start = 15.dp).weight(1f), fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        else Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.NorthEast, null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FavoritesScreen(
    state: LauncherState,
    apps: List<AppEntry>,
    repository: AppsRepository,
    badges: Map<String, Int>,
    usage: Map<String, Long>,
    permissionSignal: Int,
    actions: WorkspaceActions,
    onVoice: (() -> Unit)? = null
) {
    val prefs = state.prefs.favorites
    val excluded = buildSet {
        addAll(state.hiddenApps)
        addAll(prefs.excluded)
        if (prefs.excludeDock) addAll(state.dockApps.filterNot { it in state.favoriteApps })
        if (prefs.excludeFolders) state.folders.forEach { folder -> addAll(folder.apps.filterNot { it in state.favoriteApps }) }
    }
    val favorites = remember(state.favoriteApps, state.launches, apps, prefs, usage, excluded) {
        val baseList = if (prefs.byUsage) {
            apps.filter { (state.launches[it.key] ?: 0) > 0 || (usage[it.key] ?: 0) > 0 }
                .sortedByDescending { (usage[it.key] ?: 0L) + (state.launches[it.key] ?: 0) * 60_000L }
                .map { it.key }
        } else {
            apps.sortedBy { it.label.lowercase(Locale.ROOT) }.map { it.key }
        }
        val available = apps.associateBy { it.key }
        val combined = (state.favoriteApps + baseList).distinct().filter { it !in excluded && it in available }.take(prefs.maxItems)
        val ordered = if (prefs.reverse) combined.reversed() else combined
        ordered.mapNotNull(available::get)
    }

    val context = LocalContext.current
    val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    val contacts by produceState<List<ContactEntry>>(emptyList(), permissionSignal, prefs.showContacts) {
        value = if (permitted && prefs.showContacts) withContext(Dispatchers.IO) { repository.contacts() } else emptyList()
    }
    val pinned by produceState<List<Pair<String, ContactEntry>>>(emptyList(), state.contacts, contacts, permissionSignal, prefs.showContacts) {
        val references = state.contacts
        value = if (permitted && prefs.showContacts) withContext(Dispatchers.IO) {
            references.mapNotNull { reference ->
                val resolved = runCatching {
                    android.provider.ContactsContract.Contacts.lookupContact(context.contentResolver, Uri.parse(reference))
                }.getOrNull()
                contacts.firstOrNull { it.id == resolved?.lastPathSegment }?.let { reference to it }
            }
        } else emptyList()
    }
    var picker by remember { mutableStateOf(false) }
    val columns = if (prefs.itemsPerRow > 0) prefs.itemsPerRow else 4
    val showSearchBar = Screen.FAVORITES in state.prefs.search.barScreens

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = prefs.grid.padding.dp)) {
        if (showSearchBar && !state.prefs.search.barAtBottom) {
            item {
                Spacer(Modifier.height(8.dp))
                LauncherSearchField(
                    query = "",
                    onQuery = { actions.navigate(Screen.SEARCH) },
                    placeholder = state.prefs.search.barText,
                    onSubmit = { actions.navigate(Screen.SEARCH) },
                    onVoice = onVoice.takeIf { state.prefs.search.voice },
                    theme = state.prefs.appearance.search,
                    onActivate = { actions.navigate(Screen.SEARCH) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        item { SectionLabel(if (prefs.byUsage) "Most used & pinned" else "Alphabetical favorites") }
        if (favorites.isEmpty()) item { EmptyCard(Icons.Rounded.StarOutline, "Your favorites find you", "Open apps to see your most used here, or long-press any app to pin it.", "Browse applications", { actions.navigate(Screen.APPS) }) }
        else items(favorites.chunked(columns)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((prefs.grid.spacing / 2).dp)) {
                row.forEach { app -> AppTile(app, repository, state, prefs.grid.copy(multiline = false), badges[app.badgeKey] ?: 0, { actions.launch(app) }, { actions.menu(app) }, Modifier.weight(1f),
                    draggable = true, dragArea = LayoutArea.FAVORITES) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (prefs.showContacts) {
            item { SectionLabel("People", "Pin contact", { if (permitted) picker = true else actions.requestContacts() }) }

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
        if (showSearchBar && state.prefs.search.barAtBottom) {
            item {
                Spacer(Modifier.height(16.dp))
                LauncherSearchField(
                    query = "",
                    onQuery = { actions.navigate(Screen.SEARCH) },
                    placeholder = state.prefs.search.barText,
                    onSubmit = { actions.navigate(Screen.SEARCH) },
                    onVoice = onVoice.takeIf { state.prefs.search.voice },
                    theme = state.prefs.appearance.search,
                    onActivate = { actions.navigate(Screen.SEARCH) }
                )
            }
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
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { picker = false }) { Text("Done") } })
}
