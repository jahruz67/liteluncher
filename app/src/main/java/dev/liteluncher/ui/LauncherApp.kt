@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.liteluncher.ui

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.UserManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liteluncher.LauncherViewModel
import dev.liteluncher.data.*
import dev.liteluncher.platform.*
import dev.liteluncher.settings.ScreenManagerScreen
import dev.liteluncher.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull

data class WorkspaceActions(
    val launch: (AppEntry) -> Unit,
    val menu: (AppEntry) -> Unit,
    val navigate: (Screen) -> Unit,
    val settings: () -> Unit,
    val manageScreens: () -> Unit,
    val manageLayout: () -> Unit,
    val widgets: () -> Unit,
    val folder: (FolderItem, Boolean) -> Unit,
    val requestContacts: () -> Unit,
    val update: ((LauncherState) -> LauncherState) -> Unit,
    val dockMenu: (AppEntry) -> Unit = menu,
    val folderMenu: (AppEntry) -> Unit = menu
)

@Composable
fun LauncherApp(
    model: LauncherViewModel, widgets: WidgetController, homeSignal: Int, permissionSignal: Int,
    voiceQuery: String, onConsumeVoice: () -> Unit, onVoice: () -> Unit, onContacts: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit, onDefaultHome: () -> Unit
) {
    val state by model.store.state.collectAsStateWithLifecycle()
    val ready by model.store.ready.collectAsStateWithLifecycle()
    val storageError by model.store.error.collectAsStateWithLifecycle()
    val apps by model.apps.apps.collectAsStateWithLifecycle()
    val badges by LauncherNotificationService.counts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as Activity
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val manager = rememberSaveable(saver = ScreenManager.stateSaver) { ScreenManager() }
    var panel by rememberSaveable { mutableStateOf("") }
    var screenManagerReturn by rememberSaveable { mutableStateOf("") }
    var appMenu by remember { mutableStateOf<AppEntry?>(null) }
    var appMenuScreen by remember { mutableStateOf(Screen.HOME) }
    var appMenuFromDock by remember { mutableStateOf(false) }
    var editApp by remember { mutableStateOf<AppEntry?>(null) }
    var folder by remember { mutableStateOf<FolderItem?>(null) }
    var folderFromDock by remember { mutableStateOf(false) }
    var emptyMenu by remember { mutableStateOf(false) }
    var widgetPicker by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var lastScroll by remember { mutableLongStateOf(0L) }
    var scrolling by remember { mutableStateOf(false) }
    var dockAwake by remember { mutableStateOf(true) }
    var dockWakeSignal by remember { mutableIntStateOf(0) }
    var usage by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var iconPacks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // Deletion snackbar state with Undo
    var snackbarVisible by remember { mutableStateOf(false) }
    var snackbarMessage by remember { mutableStateOf("Entry deleted") }
    var undoSignal by remember { mutableIntStateOf(0) }
    var lastDeletedWidget by remember { mutableStateOf<WidgetItem?>(null) }
    var lastDeletedAppKey by remember { mutableStateOf<Triple<LayoutArea, String, Int>?>(null) }

    // Multi-page desktop support
    var currentDesktopPage by rememberSaveable { mutableIntStateOf(0) }
    var desktopBounds by remember { mutableStateOf(Rect.Zero) }

    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    val launch: (AppEntry) -> Unit = { app ->
        if (model.apps.launch(app)) {
            model.store.recordLaunch(app.key)
            if (query.isNotBlank()) model.store.addHistory(query)
            query = ""
            folder = null
        } else toast("This app is unavailable in the current profile")
    }
    val navigate: (Screen) -> Unit = { screen ->
        manager.navigate(screen, state.prefs.navigation)
        dockWakeSignal++
        folder = null
    }

    fun perform(binding: GestureBinding) {
        when (binding.action) {
            Action.NONE -> Unit
            Action.NAVIGATE -> Unit
            Action.HOME -> { panel = ""; query = ""; navigate(Screen.HOME) }
            Action.SEARCH -> navigate(Screen.SEARCH)
            Action.APPS -> navigate(Screen.APPS)
            Action.DESKTOP -> navigate(Screen.DESKTOP)
            Action.FAVORITES -> navigate(Screen.FAVORITES)
            Action.SETTINGS -> panel = "settings"
            Action.SCREEN_MANAGER -> { screenManagerReturn = panel; panel = "screens" }
            Action.WALLPAPER -> SystemActions.wallpaper(activity)
            Action.NOTIFICATIONS -> SystemActions.notificationAccess(context)
            Action.APP -> apps.find { it.key == binding.target }?.let(launch) ?: toast("Choose an available app for this gesture")
            Action.SHORTCUT -> {
                val component = android.content.ComponentName.unflattenFromString(binding.target.substringBeforeLast('|'))
                val serial = binding.target.substringAfterLast('|').toLongOrNull()
                val users = context.getSystemService(UserManager::class.java)
                val user = serial?.let { users?.getUserForSerialNumber(it) }
                if (component == null || user == null || !model.apps.launchShortcut(NativeShortcut(binding.shortcutId, component.packageName, "Shortcut", user))) toast("The shortcut is no longer available")
            }
        }
    }

    val actions = WorkspaceActions(
        launch = launch, menu = { appMenuScreen = manager.current; appMenuFromDock = false; appMenu = it }, navigate = navigate, settings = { panel = "settings" },
        manageScreens = { screenManagerReturn = panel; panel = "screens" }, manageLayout = { panel = "layout" }, widgets = {
            if (state.prefs.desktop.preventChanges) toast("Desktop is locked")
            else { navigate(Screen.DESKTOP); widgetPicker = true }
        },
        folder = { item, dock -> folder = item; folderFromDock = dock }, requestContacts = onContacts, update = model.store::update,
        dockMenu = { appMenuFromDock = true; appMenuScreen = manager.current; appMenu = it },
        folderMenu = { appMenuFromDock = false; appMenuScreen = Screen.APPS; appMenu = it }
    )

    fun finishWidgetRemoval() {
        lastDeletedWidget?.let { deleted ->
            if (state.widgets.any { it.id == deleted.id }) widgets.cancelRemoval(deleted.id) else widgets.remove(deleted.id)
        }
        lastDeletedWidget = null
    }
    val removeEntry: (String, LayoutArea) -> Unit = { key, area ->
        if (state.layoutLocked(area)) toast("${area.title} is locked") else {
            lastDeletedAppKey = Triple(area, key, state.layoutApps(area).indexOf(key))
            finishWidgetRemoval()
            model.store.update { removeLayoutApp(it, area, key) }
            snackbarMessage = "Entry removed"
            snackbarVisible = true
            undoSignal++
        }
    }
    val undoRemoval: () -> Unit = undo@{
        val deletedWidget = lastDeletedWidget
        if (deletedWidget != null) widgets.cancelRemoval(deletedWidget.id)
        if (deletedWidget != null) model.store.update { current ->
            if (current.widgets.any { it.id == deletedWidget.id }) current else current.copy(widgets = current.widgets + deletedWidget)
        }
        val removedApp = lastDeletedAppKey
        if (removedApp != null) {
            val (area, key, index) = removedApp
            val result = restoreLayoutApp(state, area, key, index)
            if (result.error != null) {
                toast(result.error)
                return@undo
            }
            model.store.update { restoreLayoutApp(it, area, key, index).state }
        }
        lastDeletedWidget = null
        lastDeletedAppKey = null
        snackbarVisible = false
    }
    val onAppDrop: (DraggedApp, AppDropTarget) -> Unit = { item, target ->
        fun place(current: LauncherState): LayoutEditResult {
            if (target is AppDropTarget.Area) {
                if (target.area == LayoutArea.DOCK && !current.prefs.dock.enabled && item.source != LayoutArea.DOCK)
                    return LayoutEditResult(current, "Enable Dock in Settings before dropping apps there")
                if (target.area == LayoutArea.FAVORITES && Screen.FAVORITES !in current.prefs.navigation.enabledScreens && item.source != LayoutArea.FAVORITES)
                    return LayoutEditResult(current, "Enable the Favorites screen before dropping apps there")
            }
            return applyAppDrop(current, item.app.key, item.source, target, item.folderId)
        }
        val result = place(state)
        when {
            apps.none { it.key == item.app.key } -> toast("This app is no longer available")
            result.error != null -> toast(result.error)
            target == AppDropTarget.Remove && item.source != null -> removeEntry(item.app.key, item.source)
            else -> {
                model.store.update { place(it).state }
                if (item.source == null && panel.isEmpty() && target is AppDropTarget.Area) {
                    keyboard?.hide()
                    when (target.area) {
                        LayoutArea.HOME, LayoutArea.DOCK -> navigate(Screen.HOME)
                        LayoutArea.FAVORITES -> if (Screen.FAVORITES in state.prefs.navigation.enabledScreens) navigate(Screen.FAVORITES)
                    }
                }
            }
        }
    }
    val latestDrop by rememberUpdatedState(onAppDrop)
    val drag = remember { AppDragController { item, target -> latestDrop(item, target) } }
    LaunchedEffect(panel) { drag.cancel() }
    val hoveredFolderId = (drag.hovered?.target as? AppDropTarget.Folder)?.id
    LaunchedEffect(drag.active?.token, hoveredFolderId) {
        if (drag.active != null && hoveredFolderId != null && folder?.id != hoveredFolderId) {
            delay(500)
            state.folders.find { it.id == hoveredFolderId }?.let {
                folder = it
                folderFromDock = it.id in state.dockFolders
            }
        }
    }

    LaunchedEffect(ready, apps) {
        if (ready && apps.isNotEmpty() && !state.initialized) model.store.update { current ->
            if (current.initialized) current else {
                val preferred = listOf("phone", "message", "chrome", "camera", "photos", "calendar", "music", "mail")
                val curated = preferred.mapNotNull { word -> apps.firstOrNull { it.label.contains(word, true) || it.packageName.contains(word, true) } }.distinctBy { it.key }
                val selected = (curated + apps).distinctBy { it.key }
                current.copy(initialized = true, homeApps = selected.take(current.prefs.home.columns * current.prefs.home.rows).map { it.key }, dockApps = curated.take(5).map { it.key })
            }
        }
    }
    LaunchedEffect(ready) {
        if (ready) widgets.finishRemovals(state.widgets.mapTo(mutableSetOf()) { it.id })
    }
    LaunchedEffect(permissionSignal, apps.size) {
        usage = withContext(Dispatchers.IO) { model.apps.usage() }
        iconPacks = withContext(Dispatchers.IO) { model.apps.iconPacks() }
    }
    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) {
            drag.cancel()
            panel = ""; appMenu = null; editApp = null; folder = null; emptyMenu = false; widgetPicker = false
            perform(state.prefs.gestures[Gesture.HOME] ?: GestureBinding(Action.HOME))
        }
    }
    LaunchedEffect(voiceQuery) {
        if (voiceQuery.isNotBlank()) { query = voiceQuery; navigate(Screen.SEARCH); onConsumeVoice() }
    }
    LaunchedEffect(state.prefs.navigation.enabledScreens) {
        if (manager.current !in state.prefs.navigation.enabledScreens) navigate(Screen.HOME)
    }
    LaunchedEffect(state.prefs.desktop.pageCount) {
        currentDesktopPage = currentDesktopPage.coerceIn(0, state.prefs.desktop.pageCount.coerceAtLeast(1) - 1)
    }
    LaunchedEffect(lastScroll) { if (lastScroll != 0L) { scrolling = true; delay(650); scrolling = false } }
    LaunchedEffect(manager.current, state.prefs.dock.autoHide, dockWakeSignal, drag.active != null) {
        dockAwake = true
        if (state.prefs.dock.autoHide && drag.active == null) { delay(3500); dockAwake = false }
    }

    // Auto-dismiss deletion snackbar after 4 seconds
    LaunchedEffect(snackbarVisible, undoSignal) {
        if (snackbarVisible) {
            delay(4000)
            finishWidgetRemoval()
            lastDeletedAppKey = null
            snackbarVisible = false
        }
    }

    SideEffect {
        if (ready) widgets.updateLayout(state.prefs.desktop, state.widgets, currentDesktopPage)
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !state.prefs.appearance.lightStatusIcons
        controller.isAppearanceLightNavigationBars = !state.prefs.appearance.lightNavigationIcons
        if (state.prefs.behavior.showStatusBar) controller.show(WindowInsetsCompat.Type.statusBars())
        else controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Tablet-only rotation restriction: Phone locked to portrait; tablets allowed to rotate
        val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600
        val requested = if (state.prefs.behavior.lockRotation && !isTablet) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (activity.requestedOrientation != requested) activity.requestedOrientation = requested

        val offsets = when (state.prefs.behavior.wallpaperScroll) {
            "Off" -> .5f to .5f
            "Horizontal" -> (.5f + manager.current.x * .5f) to .5f
            else -> (.5f + manager.current.x * .5f) to (.5f + manager.current.y * .5f)
        }
        runCatching { if (view.windowToken != null) WallpaperManager.getInstance(context).setWallpaperOffsets(view.windowToken, offsets.first, offsets.second) }
    }

    BackHandler(enabled = panel.isNotEmpty() || manager.current != Screen.HOME || folder != null) {
        when { folder != null -> folder = null; panel.isNotEmpty() -> panel = ""; else -> { query = ""; navigate(Screen.HOME) } }
    }
    BackHandler(enabled = drag.active != null) { drag.cancel() }
    val nestedScroll = remember { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.getDistance() > 1f) lastScroll = android.os.SystemClock.uptimeMillis()
            return Offset.Zero
        }
    } }

    LauncherTheme(state.prefs.appearance) {
        val launcherSurface = state.prefs.appearance.launcher
        val baseScheme = MaterialTheme.colorScheme
        val foreground = if (state.prefs.appearance.darkTexts) Ink
            else if (launcherSurface == SurfaceTheme.GLASS) baseScheme.onBackground else surfaceContent(launcherSurface)
        val workspaceScheme = baseScheme.copy(onBackground = foreground, onSurface = foreground,
            onSurfaceVariant = foreground.copy(alpha = .65f), surface = when (launcherSurface) {
                SurfaceTheme.LIGHT -> Color(0xFFE6E6E6)
                SurfaceTheme.ACCENT -> baseScheme.primary.copy(alpha = .75f)
                else -> baseScheme.surface
            })

        val shouldScrim = manager.current == Screen.SEARCH || panel == "screens"

        MaterialTheme(colorScheme = workspaceScheme) {
        CompositionLocalProvider(LocalContentColor provides foreground, LocalAppDrag provides drag) {
            Box(Modifier.fillMaxSize()) {
                if (launcherSurface != SurfaceTheme.GLASS) Box(Modifier.fillMaxSize().background(surfaceColor(launcherSurface, MaterialTheme.colorScheme.primary)))
                val appearance = state.prefs.appearance
                val dimColor = when (appearance.dimMode) {
                    "Off" -> Color.Transparent
                    "Custom" -> parseColor(appearance.backgroundColor, Color.Black.copy(alpha = appearance.dim))
                    else -> Color.Black.copy(alpha = appearance.dim)
                }
                Box(Modifier.fillMaxSize().background(dimColor))

                if (shouldScrim) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)))
                }

                if (!ready) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cerulean) }
                } else if (panel == "screens") {
                    Box(Modifier.fillMaxSize().blur(8.dp).windowInsetsPadding(WindowInsets.safeDrawing).alpha(.5f)
                        .clearAndSetSemantics {}.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }) {
                        HomeScreen(state, apps, model.apps, badges, actions, {}, {})
                    }
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        ScreenManagerScreen(state, model.store::update, { panel = screenManagerReturn })
                    }
                } else if (panel.isNotEmpty()) {
                    MaterialTheme(colorScheme = baseScheme) {
                    CompositionLocalProvider(LocalContentColor provides baseScheme.onSurface) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                            when (panel) {
                                "settings" -> SettingsScreen(state, apps, iconPacks, model.store::update, { panel = "" }, { screenManagerReturn = "settings"; panel = "screens" }, onExport, onImport,
                                    onDefaultHome, { SystemActions.notificationAccess(context) }, { SystemActions.usageAccess(context) }, onContacts)
                                "layout" -> ManageLayoutScreen(state, apps, model.apps, model.store::update, { panel = "" },
                                    onManageScreens = { screenManagerReturn = "layout"; panel = "screens" },
                                    onRemoveApp = { area, key -> removeEntry(key, area) })
                            }
                        }
                    }
                    }
                    }
                } else {
                    val horizontalScreens = state.prefs.navigation.horizontalScreens()
                    val horizontalPaging = state.prefs.behavior.animations && state.prefs.behavior.horizontalTransition == "Slide"
                    val pager = rememberPagerState(initialPage = horizontalScreens.indexOf(manager.current).coerceAtLeast(0), pageCount = { horizontalScreens.size })
                    val navigationRevision = manager.navigationRevision
                    var completedNavigation by remember { mutableStateOf<Pair<Long, List<Screen>>?>(null) }
                    var workspacePointerCount by remember { mutableIntStateOf(0) }
                    LaunchedEffect(navigationRevision, horizontalScreens, horizontalPaging) {
                        completedNavigation = null
                        if (!horizontalPaging) return@LaunchedEffect
                        try {
                            val target = horizontalScreens.indexOf(manager.current)
                            if (target >= 0 && (pager.isScrollInProgress || pager.currentPage != target || pager.currentPageOffsetFraction != 0f)) {
                                pager.stopScroll(MutatePriority.PreventUserInput)
                                if (state.prefs.behavior.animations && state.prefs.behavior.horizontalTransition == "Slide") pager.animateScrollToPage(target)
                                else pager.scrollToPage(target)
                            }
                        } finally {
                            // A finger can interrupt the animation without creating a new navigation request.
                            if (manager.navigationRevision == navigationRevision) completedNavigation = navigationRevision to horizontalScreens
                        }
                    }
                    LaunchedEffect(pager, horizontalScreens, horizontalPaging) {
                        snapshotFlow {
                            if (horizontalPaging && !pager.isScrollInProgress && completedNavigation == (manager.navigationRevision to horizontalScreens) && manager.current in horizontalScreens)
                                horizontalScreens.getOrNull(pager.settledPage) else null
                        }.filterNotNull().collect { screen ->
                            if (screen != manager.current) {
                                manager.onPageSettled(screen, state.prefs.navigation)
                                dockWakeSignal++
                                folder = null
                            }
                        }
                    }
                    val swipeWithPager = state.prefs.navigation.fingers == 1 && !state.prefs.navigation.infinite && horizontalPaging &&
                        listOf(Gesture.SWIPE_LEFT, Gesture.SWIPE_RIGHT).all {
                            state.prefs.gestures[it]?.action in listOf(null, Action.NONE, Action.NAVIGATE)
                        }
                    BackHandler(enabled = horizontalPaging && pager.isScrollInProgress && manager.current in horizontalScreens && folder == null) {
                        navigate(Screen.HOME)
                    }
                    val gestureModifier = Modifier.launcherGestures(
                        fingers = state.prefs.navigation.fingers,
                        home = manager.current == Screen.HOME,
                        onPointerCountChange = { count ->
                            if (count >= 2) drag.cancel()
                            if (count >= 2 && workspacePointerCount < 2 && swipeWithPager && manager.current in horizontalScreens) {
                                manager.navigate(manager.current, state.prefs.navigation)
                            }
                            workspacePointerCount = count
                        },
                        onSwipe = swipe@{ direction, fingers ->
                            if (fingers == 1 && swipeWithPager && manager.current in horizontalScreens &&
                                (direction == Direction.LEFT || direction == Direction.RIGHT)) return@swipe
                            val gesture = when (direction) {
                                Direction.UP -> if (fingers >= 2) Gesture.TWO_UP else Gesture.SWIPE_UP
                                Direction.DOWN -> if (fingers >= 2) Gesture.TWO_DOWN else Gesture.SWIPE_DOWN
                                Direction.LEFT -> if (fingers >= 2) Gesture.TWO_LEFT else Gesture.SWIPE_LEFT
                                Direction.RIGHT -> if (fingers >= 2) Gesture.TWO_RIGHT else Gesture.SWIPE_RIGHT
                            }
                            val binding = state.prefs.gestures[gesture] ?: GestureBinding()
                            if (binding.action == Action.NONE || binding.action == Action.NAVIGATE) manager.swipe(direction, state.prefs.navigation)
                            else perform(binding)
                            dockWakeSignal++
                            folder = null
                        },
                        onPinch = { inward -> perform(state.prefs.gestures[if (inward) Gesture.PINCH_IN else Gesture.PINCH_OUT] ?: GestureBinding()) }
                    )

                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).then(gestureModifier).nestedScroll(nestedScroll)) {
                        if (storageError != null) Text(storageError!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp))

                        val renderPage: @Composable (Screen) -> Unit = { screen ->
                            val dockInset = if (screen == Screen.FAVORITES && state.prefs.dock.enabled) (56 * state.prefs.dock.iconScale + 12).dp else 0.dp
                            Box(Modifier.fillMaxSize()
                                .padding(start = if (state.prefs.dock.side == "Left") dockInset else 0.dp,
                                    end = if (state.prefs.dock.side == "Right") dockInset else 0.dp)
                            ) {
                                when (screen) {
                                    Screen.HOME -> HomeScreen(
                                        state = state,
                                        apps = apps,
                                        repository = model.apps,
                                        badges = badges,
                                        actions = actions,
                                        onEmpty = { emptyMenu = true },
                                        onDoubleTap = { perform(state.prefs.gestures[Gesture.DOUBLE_TAP] ?: GestureBinding()) },
                                        onVoice = onVoice
                                    )
                                    Screen.APPS -> AppDrawer(
                                        state = state,
                                        apps = apps,
                                        repository = model.apps,
                                        badges = badges,
                                        actions = actions,
                                        onVoice = onVoice
                                    )
                                    Screen.SEARCH -> SearchScreen(
                                        state = state,
                                        apps = apps,
                                        repository = model.apps,
                                        badges = badges,
                                        permissionSignal = permissionSignal,
                                        query = query,
                                        onQuery = { query = it },
                                        onVoice = onVoice,
                                        actions = actions
                                    )
                                    Screen.FAVORITES -> FavoritesScreen(
                                        state = state,
                                        apps = apps,
                                        repository = model.apps,
                                        badges = badges,
                                        usage = usage,
                                        permissionSignal = permissionSignal,
                                        actions = actions,
                                        onVoice = onVoice
                                    )
                                    Screen.DESKTOP -> Box(Modifier.fillMaxSize().combinedClickable(onClick = {}, onLongClick = actions.widgets)) {
                                        val pageWidgets = state.widgets.filter { it.page == currentDesktopPage }
                                        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                                            // Multi-page desktop pagination header
                                            val pageCount = state.prefs.desktop.pageCount
                                            if (pageCount > 1) {
                                                Row(
                                                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    PageIndicators(
                                                        pageCount = pageCount,
                                                        currentPage = currentDesktopPage,
                                                        onSelectPage = { currentDesktopPage = it }
                                                    )
                                                }
                                            }

                                            WidgetCanvas(
                                                controller = widgets,
                                                prefs = state.prefs.desktop,
                                                widgets = pageWidgets,
                                                onChange = { list ->
                                                    model.store.update { it.copy(widgets = mergeWidgetEdits(it.widgets, pageWidgets, list)) }
                                                },
                                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                                onCanvasBounds = { desktopBounds = it },
                                                onWidgetDeleted = { deleted ->
                                                    finishWidgetRemoval()
                                                    widgets.deferRemoval(deleted.id)
                                                    lastDeletedWidget = deleted
                                                    lastDeletedAppKey = null
                                                    snackbarMessage = "Widget deleted"
                                                    snackbarVisible = true
                                                    undoSignal++
                                                }
                                            )
                                        }

                                        if (pageWidgets.isEmpty() && state.prefs.desktop.hint) {
                                            ExtendedFloatingActionButton(
                                                onClick = actions.widgets,
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = Ink,
                                                shape = RoundedCornerShape(24.dp),
                                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
                                            ) {
                                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Add Widget", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AnimatedContent(
                            targetState = if (horizontalPaging && manager.current in horizontalScreens) Screen.HOME else manager.current,
                            modifier = Modifier.weight(1f),
                            transitionSpec = {
                                val horizontal = initialState.y == 0 && targetState.y == 0
                                val transition = if (horizontal) state.prefs.behavior.horizontalTransition else state.prefs.behavior.verticalTransition
                                if (!state.prefs.behavior.animations || transition == "None") {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else if (transition == "Slide") {
                                    val sign = if (targetState.y > initialState.y) 1 else -1
                                    slideInVertically(tween(240)) { it * sign } togetherWith slideOutVertically(tween(240)) { -it * sign }
                                } else fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                            },
                            label = "Directional workspace"
                        ) { screen ->
                            if (horizontalPaging && screen == Screen.HOME) {
                                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(),
                                    key = { horizontalScreens[it] }, userScrollEnabled = swipeWithPager && workspacePointerCount < 2 && drag.active == null) { page -> renderPage(horizontalScreens[page]) }
                            } else renderPage(screen)
                        }

                        if (state.prefs.behavior.pageIndicators && manager.current in listOf(Screen.DESKTOP, Screen.HOME, Screen.APPS)) {
                            val currentIdx = horizontalScreens.indexOf(manager.current).coerceAtLeast(0)
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                PageIndicators(
                                    pageCount = horizontalScreens.size,
                                    currentPage = currentIdx,
                                    onSelectPage = { idx -> navigate(horizontalScreens[idx]) }
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                    }

                    val dockShown = state.prefs.dock.enabled &&
                        (manager.current in listOf(Screen.HOME, Screen.FAVORITES) || drag.active != null) &&
                        (drag.active != null || dockAwake && !(scrolling && state.prefs.dock.hideOnScroll))
                    AnimatedVisibility(
                        visible = dockShown,
                        enter = fadeIn() + slideInHorizontally { if (state.prefs.dock.side == "Left") -it else it },
                        exit = fadeOut() + slideOutHorizontally { if (state.prefs.dock.side == "Left") -it else it },
                        modifier = Modifier.align(if (state.prefs.dock.side == "Left") Alignment.CenterStart else Alignment.CenterEnd)
                    ) {
                        FloatingDock(state, apps, model.apps, badges, actions)
                    }
                    if (state.prefs.dock.enabled && !dockShown && manager.current in listOf(Screen.HOME, Screen.FAVORITES)) {
                        Box(Modifier.align(if (state.prefs.dock.side == "Left") Alignment.CenterStart else Alignment.CenterEnd)
                            .width(16.dp).height(80.dp).clip(RoundedCornerShape(8.dp))
                            .background(foreground.copy(alpha = .18f)).clickable { dockWakeSignal++ })
                    }


                }

                val currentFolder = folder?.let { selected -> state.folders.find { it.id == selected.id } }
                if (currentFolder != null) {
                    FolderOverlay(currentFolder, folderFromDock, state, apps, model.apps, actions.copy(menu = actions.folderMenu), { folder = null })
                }
                DeletionSnackbar(snackbarVisible, snackbarMessage, undoRemoval, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
                AppDragOverlay(drag, state, model.apps)
                if (widgetPicker) WidgetPicker(
                    controller = widgets, prefs = state.prefs.desktop, page = currentDesktopPage, canvasBounds = desktopBounds,
                    onSelect = { provider, location ->
                        widgetPicker = false
                        widgets.add(provider, location)
                    },
                    onDismiss = { widgetPicker = false }
                )
            }

            MaterialTheme(colorScheme = baseScheme) {
            CompositionLocalProvider(LocalContentColor provides baseScheme.onSurface) {
                if (appMenu != null) AppContextDialog(
                    app = appMenu!!,
                    state = state,
                    repository = model.apps,
                    actions = actions,
                    onEdit = { editApp = appMenu; appMenu = null },
                    onDismiss = { appMenu = null },
                    source = appMenuScreen,
                    fromDock = appMenuFromDock,
                    onRemove = {
                        val key = appMenu!!.key
                        val area = if (appMenuFromDock) LayoutArea.DOCK else if (appMenuScreen == Screen.FAVORITES) LayoutArea.FAVORITES else LayoutArea.HOME
                        removeEntry(key, area)
                        appMenu = null
                    }
                )

                if (editApp != null) EditAppDialog(
                    app = editApp!!,
                    state = state,
                    update = model.store::update,
                    onDismiss = { editApp = null }
                )

                // Empty Space Context Menu: Dark card with Change Wallpaper, Add Widget, Settings, and Manage Layout
                if (emptyMenu) EmptySpaceMenuDialog(
                    onWallpaper = { emptyMenu = false; SystemActions.wallpaper(activity) },
                    onAddWidget = { emptyMenu = false; actions.widgets() },
                    onSettings = { emptyMenu = false; panel = "settings" },
                    onManageLayout = { emptyMenu = false; panel = "layout" },
                    onDismiss = { emptyMenu = false }
                )

            }
            }
        }
        }
    }
}


@Composable
private fun FloatingDock(
    state: LauncherState,
    apps: List<AppEntry>,
    repository: AppsRepository,
    badges: Map<String, Int>,
    actions: WorkspaceActions
) {
    val prefs = state.prefs.dock
    val isRight = prefs.side == "Right"
    val entries = state.dockApps.mapNotNull { key -> apps.find { it.key == key } }.take(prefs.maxItems)
    val folders = state.dockFolders.mapNotNull { id -> state.folders.find { it.id == id } }.take((prefs.maxItems - entries.size).coerceAtLeast(0))
    val dockWidth = (56 * prefs.iconScale + 12).dp.coerceAtLeast(56.dp)
    val theme = state.prefs.appearance.dock
    val shape = if (isRight) RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)
        else RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
    BoxWithConstraints(Modifier.fillMaxHeight().width(dockWidth).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val totalItems = entries.size + folders.size + if (prefs.allApps) 1 else 0
        val estimatedHeight = (totalItems.coerceAtLeast(1) * (56 * prefs.iconScale + if (prefs.labels) 36 else 16) + prefs.padding * 2).dp.coerceAtMost(maxHeight)
        val height = if (prefs.fullWidth) maxHeight else estimatedHeight
        val top = ((maxHeight - height) * prefs.alignment).coerceAtLeast(0.dp)
        CompositionLocalProvider(LocalContentColor provides surfaceContent(theme)) {
            Column(
                Modifier.offset(y = top).width(dockWidth).height(height).clip(shape).appDropTarget(AppDropTarget.Area(LayoutArea.DOCK), 1)
                    .background(surfaceColor(theme, MaterialTheme.colorScheme.primary))
                    .verticalScroll(rememberScrollState()).padding(vertical = prefs.padding.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val grid = GridPrefs(iconScale = prefs.iconScale, labels = prefs.labels, textScale = .9f)
                val allAppsButton = @Composable {
                    IconButton(onClick = { actions.navigate(Screen.APPS) }, modifier = Modifier.size((56 * prefs.iconScale + 16).dp)) {
                        Icon(Icons.Rounded.Apps, "All applications")
                    }
                }
                if (prefs.allApps && prefs.allAppsLeft) allAppsButton()
                entries.forEach { app ->
                    AppTile(app, repository, state, grid, badges[app.badgeKey] ?: 0,
                        { actions.launch(app) }, { actions.dockMenu(app) }, Modifier.fillMaxWidth(),
                        draggable = !prefs.preventChanges, dragArea = LayoutArea.DOCK)
                }
                folders.forEach { item ->
                    FolderTile(item, apps, repository, state, grid, { actions.folder(item, true) },
                        actions.manageLayout, Modifier.fillMaxWidth())
                }
                if (prefs.allApps && !prefs.allAppsLeft) allAppsButton()
                if (totalItems == 0) IconButton(onClick = actions.manageLayout) {
                    Icon(Icons.Rounded.Add, "Add apps to dock")
                }
            }
        }
    }
}
