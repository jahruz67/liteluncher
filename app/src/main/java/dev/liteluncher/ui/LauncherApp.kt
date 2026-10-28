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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

data class WorkspaceActions(
    val launch: (AppEntry) -> Boolean,
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
    val folderMenu: (AppEntry) -> Unit = menu,
    val editFolder: (FolderItem) -> Unit = {},
    val folderActions: (FolderItem) -> Unit = editFolder,
    val shortcut: (NativeShortcut) -> Boolean = { false }
)

private val widgetUndoSaver = Saver<WidgetItem?, ArrayList<Any>>(
    save = { item -> item?.let { arrayListOf(it.id, it.column, it.row, it.columns, it.rows, it.page, it.provider, it.restoreKey) } },
    restore = { saved -> WidgetItem(saved[0] as Int, saved[1] as Int, saved[2] as Int, saved[3] as Int,
        saved[4] as Int, saved[5] as Int, saved[6] as String, saved[7] as String) }
)
private val deletedAppSaver = Saver<Triple<LayoutArea, String, Int>?, ArrayList<Any>>(
    save = { item -> item?.let { arrayListOf(it.first.name, it.second, it.third) } },
    restore = { saved -> Triple(LayoutArea.valueOf(saved[0] as String), saved[1] as String, saved[2] as Int) }
)
private val deletedPositionSaver = Saver<ItemPosition?, ArrayList<Any>>(
    save = { item -> item?.let { arrayListOf(it.key, it.container, it.column, it.row, it.columns, it.rows) } },
    restore = { saved -> ItemPosition(saved[0] as String, saved[1] as String, saved[2] as Int, saved[3] as Int, saved[4] as Int, saved[5] as Int) }
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
    val focusManager = LocalFocusManager.current
    val accessibility = LocalAccessibilityManager.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (state.prefs.appearance.mode) { ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemDark; else -> true }
    val manager = rememberSaveable(saver = ScreenManager.stateSaver) { ScreenManager() }
    var panel by rememberSaveable { mutableStateOf("") }
    var screenManagerReturn by rememberSaveable { mutableStateOf("") }
    var appMenu by remember { mutableStateOf<AppEntry?>(null) }
    var appMenuScreen by remember { mutableStateOf(Screen.HOME) }
    var appMenuFromDock by remember { mutableStateOf(false) }
    var appMenuDesktopPage by remember { mutableIntStateOf(0) }
    var editAppKey by rememberSaveable { mutableStateOf<String?>(null) }
    val editApp = apps.find { it.key == editAppKey }
    var folder by remember { mutableStateOf<FolderItem?>(null) }
    var editingFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingFolder = state.folders.find { it.id == editingFolderId }
    var folderMenu by remember { mutableStateOf<FolderItem?>(null) }
    var folderFromDock by remember { mutableStateOf(false) }
    var emptyMenu by remember { mutableStateOf(false) }
    var widgetPicker by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var usage by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var iconPacks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // Deletion snackbar state with Undo
    var snackbarVisible by rememberSaveable { mutableStateOf(false) }
    var snackbarMessage by rememberSaveable { mutableStateOf("Entry deleted") }
    var undoSignal by rememberSaveable { mutableIntStateOf(0) }
    var lastDeletedWidget by rememberSaveable(stateSaver = widgetUndoSaver) { mutableStateOf<WidgetItem?>(null) }
    var lastDeletedAppKey by rememberSaveable(stateSaver = deletedAppSaver) { mutableStateOf<Triple<LayoutArea, String, Int>?>(null) }
    var lastDeletedPosition by rememberSaveable(stateSaver = deletedPositionSaver) { mutableStateOf<ItemPosition?>(null) }
    var undoInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Multi-page desktop support
    var currentDesktopPage by rememberSaveable { mutableIntStateOf(0) }
    var desktopBounds by remember { mutableStateOf(Rect.Zero) }
    val horizontalScreens = state.prefs.navigation.horizontalScreens()
    val horizontalPaging = state.prefs.behavior.animations && state.prefs.behavior.horizontalTransition == "Slide"
    val pager = rememberPagerState(initialPage = horizontalScreens.indexOf(manager.current).coerceAtLeast(0), pageCount = { horizontalScreens.size })

    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    val launch: (AppEntry) -> Boolean = { app ->
        val launched = model.apps.launch(app)
        if (launched) {
            model.store.recordLaunch(app.key)
            if (query.isNotBlank()) model.store.addHistory(query)
            query = ""
            folder = null
            if (state.prefs.behavior.returnHome) {
                panel = ""
                keyboard?.hide()
                focusManager.clearFocus(force = true)
                manager.navigate(Screen.HOME, state.prefs.navigation)
            }
        } else toast("This app is unavailable in the current profile")
        launched
    }
    val navigate: (Screen) -> Unit = { screen ->
        if (screen != Screen.SEARCH) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
        if (!manager.navigate(screen, state.prefs.navigation)) toast("Enable this screen in Manage screens first")
        folder = null
        folderMenu = null
        editingFolderId = null
    }
    val launchShortcut: (NativeShortcut) -> Boolean = { shortcut ->
        val launched = model.apps.launchShortcut(shortcut)
        if (launched) {
            if (query.isNotBlank()) model.store.addHistory(query)
            query = ""
            folder = null
            if (state.prefs.behavior.returnHome) { panel = ""; navigate(Screen.HOME) }
        }
        launched
    }

    fun perform(binding: GestureBinding, navigationDirection: Direction = Direction.UP) {
        when (binding.action) {
            Action.NONE -> Unit
            Action.NAVIGATE -> manager.swipe(navigationDirection, state.prefs.navigation)
            Action.HOME -> { panel = ""; query = ""; navigate(Screen.HOME) }
            Action.SEARCH -> navigate(Screen.SEARCH)
            Action.APPS -> navigate(Screen.APPS)
            Action.DESKTOP -> navigate(Screen.DESKTOP)
            Action.FAVORITES -> navigate(Screen.FAVORITES)
            Action.SETTINGS -> panel = "settings"
            Action.SCREEN_MANAGER -> { screenManagerReturn = panel; panel = "screens" }
            Action.WALLPAPER -> SystemActions.wallpaper(activity)
            Action.NOTIFICATIONS -> SystemActions.notifications(context)
            Action.APP -> apps.find { it.key == binding.target }?.let(launch) ?: toast("Choose an available app for this gesture")
            Action.SHORTCUT -> {
                val component = android.content.ComponentName.unflattenFromString(binding.target.substringBeforeLast('|'))
                val serial = binding.target.substringAfterLast('|').toLongOrNull()
                val users = context.getSystemService(UserManager::class.java)
                val user = serial?.let { users?.getUserForSerialNumber(it) }
                if (component == null || user == null || !launchShortcut(NativeShortcut(binding.shortcutId, component.packageName, "Shortcut", user))) toast("The shortcut is no longer available")
            }
        }
    }

    val actions = WorkspaceActions(
        launch = launch, menu = { appMenuScreen = manager.current; appMenuDesktopPage = currentDesktopPage; appMenuFromDock = false; appMenu = it }, navigate = navigate, settings = { panel = "settings" },
        manageScreens = { screenManagerReturn = panel; panel = "screens" }, manageLayout = { panel = "layout" }, widgets = {
            if (Screen.DESKTOP !in state.prefs.navigation.enabledScreens) toast("Enable Desktop in Manage screens first")
            else if (state.prefs.desktop.preventChanges) toast("Desktop is locked")
            else { navigate(Screen.DESKTOP); widgetPicker = true }
        },
        folder = { item, dock -> folder = item; folderFromDock = dock }, requestContacts = onContacts, update = model.store::update,
        dockMenu = { appMenuFromDock = true; appMenuScreen = manager.current; appMenu = it },
        folderMenu = { appMenuFromDock = false; appMenuScreen = Screen.APPS; appMenu = it },
        editFolder = { editingFolderId = it.id },
        folderActions = { folderMenu = it }, shortcut = launchShortcut
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
            lastDeletedPosition = if (area == LayoutArea.FAVORITES) null else state.workspacePositions(
                if (area == LayoutArea.HOME) "home" else "dock", if (area == LayoutArea.HOME) state.prefs.home.columns else 1,
                if (area == LayoutArea.HOME) state.prefs.home.rows else MAX_LAYOUT_APPS).find { it.key == key }
            finishWidgetRemoval()
            model.store.update { removeLayoutApp(it, area, key) }
            snackbarMessage = "Entry removed"
            snackbarVisible = true
            undoSignal++
        }
    }
    val undoRemoval: () -> Unit = undo@{
        if (undoInProgress) return@undo
        val deletedWidget = lastDeletedWidget
        val removedApp = lastDeletedAppKey
        val position = lastDeletedPosition
        undoInProgress = true
        scope.launch {
            try {
                model.store.updateAndAwait { current ->
                    when {
                        deletedWidget != null -> {
                            require(!current.prefs.desktop.preventChanges) { "Desktop is locked" }
                            require(deletedWidget.page in 0 until current.prefs.desktop.pageCount && WidgetGrid.fits(deletedWidget,
                                current.widgets + current.desktopObstacles(), current.prefs.desktop.columns, current.prefs.desktop.rows)) {
                                "Free the widget's original space before undoing"
                            }
                            if (current.widgets.any { it.id == deletedWidget.id }) current else current.copy(widgets = current.widgets + deletedWidget)
                        }
                        removedApp != null -> {
                            val result = restoreLayoutApp(current, removedApp.first, removedApp.second, removedApp.third, position)
                            require(result.error == null) { result.error.orEmpty() }
                            result.state
                        }
                        else -> current
                    }
                }.onSuccess {
                    deletedWidget?.let { widgets.cancelRemoval(it.id) }
                    lastDeletedWidget = null
                    lastDeletedAppKey = null
                    snackbarVisible = false
                }.onFailure { toast(it.message ?: "The entry could not be restored") }
            } finally { undoInProgress = false }
        }
    }
    val onAppDrop: (DraggedApp, AppDropTarget) -> Unit = { item, target ->
        fun place(current: LauncherState): LayoutEditResult {
            if (item.movingFolderId != null) return applyFolderDrop(current, item.movingFolderId, item.source, target)
            if (item.desktopPage != null && current.prefs.desktop.preventChanges) return LayoutEditResult(current, "Desktop is locked")
            if (target is AppDropTarget.Area) {
                if (target.area == LayoutArea.DOCK && !current.prefs.dock.enabled && item.source != LayoutArea.DOCK)
                    return LayoutEditResult(current, "Enable Dock in Settings before dropping apps there")
                if (target.area == LayoutArea.FAVORITES && Screen.FAVORITES !in current.prefs.navigation.enabledScreens && item.source != LayoutArea.FAVORITES)
                    return LayoutEditResult(current, "Enable the Favorites screen before dropping apps there")
            }
            val result = if (target == AppDropTarget.Remove && item.desktopPage != null) LayoutEditResult(current)
                else applyAppDrop(current, item.app.key, item.source, target, item.folderId)
            val keepDesktop = target is AppDropTarget.Area && target.area == LayoutArea.FAVORITES ||
                target is AppDropTarget.DesktopCell && target.page == item.desktopPage
            return if (result.error != null || item.desktopPage == null || keepDesktop) result else result.copy(state = result.state.copy(
                desktopApps = result.state.desktopApps.mapValues { (page, keys) -> if (page == item.desktopPage) keys - item.app.key else keys },
                positions = result.state.positions.filterNot { it.container == "desktop:${item.desktopPage}" && it.key == item.app.key }
            ))
        }
        val result = place(state)
        when {
            item.movingFolderId == null && apps.none { it.key == item.app.key } -> toast("This app is no longer available")
            result.error != null -> toast(result.error)
            target == AppDropTarget.Remove && item.source != null && item.movingFolderId == null -> removeEntry(item.app.key, item.source)
            else -> {
                    scope.launch {
                        model.store.updateAndAwait { current ->
                            val queued = place(current)
                            require(queued.error == null) { queued.error.orEmpty() }
                            queued.state
                        }.onSuccess {
                            val targetArea = when (target) {
                                is AppDropTarget.Area -> target.area
                                is AppDropTarget.CreateFolder -> target.area
                                is AppDropTarget.Folder -> if (target.id in state.dockFolders) LayoutArea.DOCK else LayoutArea.HOME
                                AppDropTarget.Remove -> null
                                is AppDropTarget.DesktopCell -> null
                            }
                            if (item.source == null && panel.isEmpty() && targetArea != null &&
                                (target !is AppDropTarget.Folder || target.id != item.folderId)) {
                                keyboard?.hide()
                                when (targetArea) {
                                    LayoutArea.HOME, LayoutArea.DOCK -> navigate(Screen.HOME)
                                    LayoutArea.FAVORITES -> navigate(Screen.FAVORITES)
                                }
                            }
                        }.onFailure { toast(it.message ?: "The entry could not be moved") }
                    }
                }
            }
        }
    val latestDrop by rememberUpdatedState(onAppDrop)
    val drag = remember { AppDragController(onDragStart = { appMenu = null; folderMenu = null }) { item, target -> latestDrop(item, target) } }
    var previousPanel by remember { mutableStateOf(panel) }
    LaunchedEffect(panel) {
        drag.cancel()
        if (previousPanel.isNotEmpty() && panel.isEmpty() && state.prefs.behavior.returnHome) navigate(Screen.HOME)
        previousPanel = panel
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
    LaunchedEffect(widgets) {
        model.store.ready.first { it }
        model.store.error.first { it == null }
        val activeIds = model.store.state.value.widgets.mapTo(mutableSetOf()) { it.id }
        widgets.finishRemovals(activeIds)
        widgets.finishAdditions(activeIds)
    }
    val needsUsage = panel.isEmpty() && manager.current == Screen.FAVORITES && state.prefs.favorites.byUsage
    LaunchedEffect(permissionSignal, apps, needsUsage) {
        if (needsUsage) usage = withContext(Dispatchers.IO) { model.apps.usage() }
    }
    val needsIconPacks = panel == "settings"
    LaunchedEffect(permissionSignal, apps, needsIconPacks) {
        if (needsIconPacks) iconPacks = withContext(Dispatchers.IO) { model.apps.iconPacks() }
    }
    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) {
            val dismissing = panel.isNotEmpty() || appMenu != null || editApp != null || folder != null || emptyMenu || widgetPicker || folderMenu != null || editingFolder != null
            drag.cancel()
            panel = ""; appMenu = null; editAppKey = null; folder = null; emptyMenu = false; widgetPicker = false
            folderMenu = null; editingFolderId = null
            query = ""
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            if (manager.current != Screen.HOME) navigate(Screen.HOME)
            else if (!dismissing) perform(state.prefs.gestures[Gesture.HOME] ?: GestureBinding(Action.HOME))
        }
    }
    LaunchedEffect(voiceQuery) {
        if (voiceQuery.isNotBlank()) { query = voiceQuery; navigate(Screen.SEARCH); onConsumeVoice() }
    }
    LaunchedEffect(state.prefs.navigation.enabledScreens) {
        if (manager.current !in state.prefs.navigation.enabledScreens) navigate(Screen.HOME)
    }
    LaunchedEffect(ready, state.prefs.desktop.pageCount) {
        if (ready) currentDesktopPage = currentDesktopPage.coerceIn(0, state.prefs.desktop.pageCount.coerceAtLeast(1) - 1)
    }
    // Auto-dismiss deletion snackbar after 4 seconds
    LaunchedEffect(snackbarVisible, undoSignal, undoInProgress) {
        if (snackbarVisible && !undoInProgress) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(4000L, containsText = true, containsControls = true) ?: 4000L)
            finishWidgetRemoval()
            lastDeletedAppKey = null
            snackbarVisible = false
        }
    }

    LaunchedEffect(widgets, ready, state, currentDesktopPage) {
        if (ready) widgets.updateLayout(state.prefs.desktop, state.widgets, currentDesktopPage, state.desktopObstacles())
    }
    val shortcutRevision by model.apps.shortcutRevision.collectAsStateWithLifecycle()
    LaunchedEffect(ready, state.shortcuts, state.prefs.gestures, permissionSignal, shortcutRevision) {
        if (ready) {
            val gesturePins = state.prefs.gestures.values.mapNotNull { binding ->
                val component = android.content.ComponentName.unflattenFromString(binding.target.substringBeforeLast('|'))
                val serial = binding.target.substringAfterLast('|').toLongOrNull()
                val shortcutId = binding.shortcutId.takeIf { binding.action == Action.SHORTCUT && it.isNotBlank() }
                if (component == null || serial == null || shortcutId == null) null
                else PinnedShortcut(shortcutId, component.packageName, "Gesture shortcut", serial)
            }
            withContext(Dispatchers.IO) { model.apps.syncShortcutPins(state.shortcuts + gesturePins) }
        }
    }
    var previousIconUris by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(ready, state.overrides, state.folders) {
        if (ready) {
            val used = state.overrides.values.map { it.iconUri }.toSet() + state.folders.map { it.iconUri }
            val obsolete = previousIconUris - used
            previousIconUris = used
            withContext(Dispatchers.IO) {
                obsolete.filter { it.startsWith("content://") }.forEach { uri ->
                    runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
        }
    }
    LaunchedEffect(activity, view, state.prefs.appearance, darkTheme, state.prefs.behavior.showStatusBar) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val appearance = state.prefs.appearance
        controller.isAppearanceLightStatusBars = when (appearance.statusIcons) { "Always dark" -> true; "Always light" -> false; else -> !darkTheme }
        controller.isAppearanceLightNavigationBars = when (appearance.navigationIcons) { "Always dark" -> true; "Always light" -> false; else -> !darkTheme }
        activity.window.statusBarColor = if (appearance.customBarColor) android.graphics.Color.parseColor(appearance.statusColor) else android.graphics.Color.TRANSPARENT
        activity.window.navigationBarColor = if (appearance.customBarColor) android.graphics.Color.parseColor(appearance.navigationColor) else android.graphics.Color.TRANSPARENT
        if (state.prefs.behavior.showStatusBar) controller.show(WindowInsetsCompat.Type.statusBars())
        else controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    // Tablet-only rotation restriction: Phone locked to portrait; tablets allowed to rotate
    val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    LaunchedEffect(activity, state.prefs.behavior.lockRotation, isTablet) {
        val requested = if (state.prefs.behavior.lockRotation && !isTablet) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (activity.requestedOrientation != requested) activity.requestedOrientation = requested
    }
    val pagerProgress = if (horizontalScreens.size <= 1) 0f else
        (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (horizontalScreens.size - 1).toFloat()) / (horizontalScreens.size - 1)
    val wallpaperPageProgress = if (manager.current == Screen.DESKTOP && state.prefs.desktop.pageCount > 1)
        currentDesktopPage.toFloat() / (state.prefs.desktop.pageCount - 1) else pagerProgress
    val wallpaperOffsets = when (state.prefs.behavior.wallpaperScroll) {
        "Off" -> (when (state.prefs.behavior.wallpaperAlignment) { "Left" -> 0f; "Right" -> 1f; else -> .5f }) to .5f
        "Horizontal" -> wallpaperPageProgress to .5f
        else -> wallpaperPageProgress to (.5f + manager.current.y * .5f)
    }
    DisposableEffect(view, wallpaperOffsets, permissionSignal) {
        fun updateOffsets() {
            runCatching {
                view.windowToken?.let { token ->
                    WallpaperManager.getInstance(context).setWallpaperOffsets(token, wallpaperOffsets.first, wallpaperOffsets.second)
                }
            }
        }
        val listener = object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) = updateOffsets()
            override fun onViewDetachedFromWindow(view: android.view.View) = Unit
        }
        view.addOnAttachStateChangeListener(listener)
        updateOffsets()
        onDispose { view.removeOnAttachStateChangeListener(listener) }
    }

    BackHandler(enabled = panel.isNotEmpty() || manager.current != Screen.HOME || folder != null) {
        when { folder != null -> folder = null; panel.isNotEmpty() -> panel = ""; else -> { query = ""; navigate(Screen.HOME) } }
    }
    BackHandler(enabled = drag.active != null) { drag.cancel() }


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

        val shouldScrim = manager.current == Screen.SEARCH || panel == "screens" || panel == "layout"

        MaterialTheme(colorScheme = workspaceScheme) {
        CompositionLocalProvider(LocalContentColor provides foreground, LocalAppDrag provides drag) {
            Box(Modifier.fillMaxSize()) {
                if (launcherSurface != SurfaceTheme.GLASS && panel != "screens" && panel != "layout") Box(Modifier.fillMaxSize().background(surfaceColor(launcherSurface, MaterialTheme.colorScheme.primary)))
                val appearance = state.prefs.appearance
                val dimColor = when (appearance.dimMode) {
                    "Off" -> Color.Transparent
                    "Custom" -> parseColor(appearance.backgroundColor).copy(alpha = appearance.dim)
                    else -> Color.Black.copy(alpha = appearance.dim)
                }
                Box(Modifier.fillMaxSize().background(dimColor))

                if (shouldScrim) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)))
                }

                if (!ready) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cerulean) }
                } else if (panel == "screens" || panel == "layout") {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        if (panel == "layout") ManageLayoutScreen(state, model.store::update, { panel = "" })
                        else ScreenManagerScreen(state, model.store::update, { panel = screenManagerReturn })
                    }
                } else if (panel.isNotEmpty()) {
                    MaterialTheme(colorScheme = baseScheme) {
                    CompositionLocalProvider(LocalContentColor provides baseScheme.onSurface) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                            when (panel) {
                                "settings" -> Column(Modifier.fillMaxSize()) {
                                    if (storageError != null) Text(storageError!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp,
                                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp))
                                    SettingsScreen(state, apps, iconPacks, model.store::update, { panel = "" }, { screenManagerReturn = "settings"; panel = "screens" }, onExport, onImport,
                                        onDefaultHome, { SystemActions.notificationAccess(context) }, { SystemActions.usageAccess(context) }, onContacts)
                                }
                            }
                        }
                    }
                    }
                    }
                } else {
                    val navigationRevision = manager.navigationRevision
                    var completedNavigation by remember { mutableStateOf<Pair<Long, List<Screen>>?>(null) }
                    var workspacePointerCount by remember { mutableIntStateOf(0) }
                    var pagerHandledGesture by remember { mutableStateOf(false) }
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
                                folder = null
                            }
                        }
                    }
                    val swipeWithPager = state.prefs.navigation.fingers == 1 && !state.prefs.navigation.infinite && horizontalPaging &&
                        listOf(Gesture.SWIPE_LEFT, Gesture.SWIPE_RIGHT).all {
                            state.prefs.gestures[it]?.action == Action.NAVIGATE
                        }
                    BackHandler(enabled = horizontalPaging && pager.isScrollInProgress && manager.current in horizontalScreens && folder == null) {
                        navigate(Screen.HOME)
                    }
                    val gestureModifier = Modifier.launcherGestures(
                        fingers = state.prefs.navigation.fingers,
                        home = manager.current == Screen.HOME,
                        onPointerCountChange = { count ->
                            if (count > 0) {
                                if (workspacePointerCount == 0) pagerHandledGesture = false
                                pagerHandledGesture = pagerHandledGesture || pager.isScrollInProgress
                            }
                            if (count >= 2) drag.cancel()
                            if (count >= 2 && workspacePointerCount < 2 && swipeWithPager && manager.current in horizontalScreens) {
                                manager.navigate(manager.current, state.prefs.navigation)
                            }
                            workspacePointerCount = count
                        },
                        onSwipe = swipe@{ direction, fingers ->
                            if (fingers == 1 && swipeWithPager && pagerHandledGesture && manager.current in horizontalScreens &&
                                (direction == Direction.LEFT || direction == Direction.RIGHT)) return@swipe
                            val gesture = when (direction) {
                                Direction.UP -> if (fingers >= 2) Gesture.TWO_UP else Gesture.SWIPE_UP
                                Direction.DOWN -> if (fingers >= 2) Gesture.TWO_DOWN else Gesture.SWIPE_DOWN
                                Direction.LEFT -> if (fingers >= 2) Gesture.TWO_LEFT else Gesture.SWIPE_LEFT
                                Direction.RIGHT -> if (fingers >= 2) Gesture.TWO_RIGHT else Gesture.SWIPE_RIGHT
                            }
                            val binding = state.prefs.gestures[gesture] ?: GestureBinding()
                            if (binding.action == Action.NAVIGATE) {
                                if (fingers >= state.prefs.navigation.fingers) manager.swipe(direction, state.prefs.navigation)
                            }
                            else perform(binding)
                            folder = null
                        },
                        onPinch = { inward -> perform(
                            state.prefs.gestures[if (inward) Gesture.PINCH_IN else Gesture.PINCH_OUT] ?: GestureBinding(),
                            if (inward) Direction.UP else Direction.DOWN
                        ) }
                    )

                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).then(gestureModifier)) {
                        val renderPage: @Composable (Screen) -> Unit = { screen ->
                            // Clip each workspace page while it moves through the pager.
                            Box(Modifier.fillMaxSize().graphicsLayer { clip = true }
                                .then(if (state.prefs.behavior.borders && (pager.isScrollInProgress || screen != manager.current)) Modifier.border(1.dp, MaterialTheme.colorScheme.outline) else Modifier)) {
                                when (screen) {
                                    Screen.HOME -> HomeScreen(
                                        state = state,
                                        apps = apps,
                                        repository = model.apps,
                                        badges = badges,
                                        actions = actions,
                                        onEmpty = { emptyMenu = true },
                                        onDoubleTap = { perform(state.prefs.gestures[Gesture.DOUBLE_TAP] ?: GestureBinding()) },
                                        onVoice = onVoice,
                                        showDock = !horizontalPaging
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
                                        Column(Modifier.fillMaxSize()) {
                                            // Multi-page desktop pagination header
                                            val pageCount = state.prefs.desktop.pageCount
                                            if (pageCount > 1 && state.prefs.behavior.pageIndicators) {
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
                                                    model.store.update { current ->
                                                        if (current.prefs.desktop.preventChanges) current else current.copy(widgets = mergeWidgetEdits(current.widgets, pageWidgets, list))
                                                    }
                                                },
                                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                                onCanvasBounds = { desktopBounds = it },
                                                blocked = state.desktopObstacles().filter { it.page == currentDesktopPage },
                                                isActive = manager.current == Screen.DESKTOP && (!horizontalPaging || !pager.isScrollInProgress),
                                                overlay = {
                                                    val grid = state.prefs.desktop.grid.copy(columns = state.prefs.desktop.columns, rows = state.prefs.desktop.rows)
                                                    PositionedWorkspace("desktop:$currentDesktopPage", state, apps, model.apps, grid, badges, actions, Modifier.fillMaxSize())
                                                    val cellWidth = maxWidth / grid.columns
                                                    val cellHeight = maxHeight / grid.rows
                                                    state.pendingWidgets.filter { it.page == currentDesktopPage }.forEach { item ->
                                                        key(item.key) {
                                                            PendingWidgetTile(item, widgets, Modifier.offset(x = cellWidth * item.column, y = cellHeight * item.row)
                                                                .width(cellWidth * item.columns).height(cellHeight * item.rows), enabled = !state.prefs.desktop.preventChanges) {
                                                                model.store.update { current -> if (current.prefs.desktop.preventChanges) current else current.copy(pendingWidgets = current.pendingWidgets.filterNot { it.key == item.key }) }
                                                            }
                                                        }
                                                    }
                                                },
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

                                        if (pageWidgets.isEmpty() && state.pendingWidgets.none { it.page == currentDesktopPage } &&
                                            state.workspaceKeys("desktop:$currentDesktopPage").isEmpty() && state.prefs.desktop.hint) {
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
                                Box(Modifier.fillMaxSize().clipToBounds()) {
                                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(),
                                        beyondViewportPageCount = 1,
                                        key = { horizontalScreens[it] }, userScrollEnabled = swipeWithPager && workspacePointerCount < 2 && drag.active == null) { page -> renderPage(horizontalScreens[page]) }
                                    val homePage = horizontalScreens.indexOf(Screen.HOME)
                                    val dockHideProgress by remember(pager, homePage) {
                                        derivedStateOf {
                                            (kotlin.math.abs(pager.currentPage - homePage + pager.currentPageOffsetFraction) * 2f).coerceIn(0f, 1f)
                                        }
                                    }
                                    if (state.prefs.dock.enabled) {
                                        val left = state.prefs.dock.side == "Left"
                                        Box(Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                                            .graphicsLayer {
                                                // Follow the finger without rebuilding the dock as it leaves or returns.
                                                val progress = if (state.prefs.dock.showWhenScrolling && pager.isScrollInProgress) 0f else dockHideProgress
                                                translationX = size.width * progress * if (left) -1f else 1f
                                            }) {
                                            FloatingDock(state, apps, model.apps, badges, actions, pager.isScrollInProgress)
                                        }
                                    }
                                }
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

                }

                val currentFolder = folder?.let { selected -> state.folders.find { it.id == selected.id } }
                if (currentFolder != null) {
                    FolderOverlay(currentFolder, folderFromDock, state, apps, model.apps, actions.copy(menu = actions.folderMenu), { folder = null })
                }
                folderMenu?.let { selected ->
                    FolderContextMenu(selected, drag.menuAnchor, state.folderLocked(selected.id), {
                        folderMenu = null
                        editingFolderId = selected.id
                    }, { folderMenu = null }, onRemove = {
                        model.store.update { applyFolderDrop(it, selected.id, null, AppDropTarget.Remove).state }
                        folderMenu = null
                    })
                }
                editingFolder?.let { selected ->
                    FolderEditor(selected, apps, { edited ->
                        model.store.update { current ->
                            if (current.folderLocked(edited.id)) current else current.copy(folders = current.folders.map {
                                if (it.id == edited.id) it.copy(name = edited.name, apps = edited.apps) else it
                            })
                        }
                        editingFolderId = null
                    }, { editingFolderId = null }, state.overrides)
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

            MaterialTheme(colorScheme = baseScheme) {
            CompositionLocalProvider(LocalContentColor provides baseScheme.onSurface) {
                if (appMenu != null && drag.active == null) AppContextMenu(
                    app = appMenu!!,
                    anchor = drag.menuAnchor,
                    state = state,
                    repository = model.apps,
                    actions = actions,
                    onEdit = { editAppKey = appMenu?.key; appMenu = null },
                    onDismiss = { appMenu = null },
                    source = appMenuScreen,
                    fromDock = appMenuFromDock,
                    onRemove = {
                        val key = appMenu!!.key
                        val desktopPage = appMenuDesktopPage
                        if (appMenuScreen == Screen.DESKTOP && !appMenuFromDock) model.store.update { current ->
                            if (current.prefs.desktop.preventChanges) current else current.copy(desktopApps = current.desktopApps.mapValues { (page, keys) -> if (page == desktopPage) keys - key else keys })
                        } else {
                            val area = if (appMenuFromDock) LayoutArea.DOCK else if (appMenuScreen == Screen.FAVORITES) LayoutArea.FAVORITES else LayoutArea.HOME
                            removeEntry(key, area)
                        }
                        appMenu = null
                    }
                )

                if (editApp != null) EditAppDialog(
                    app = editApp!!,
                    state = state,
                    update = model.store::update,
                    onDismiss = { editAppKey = null }
                )

                // Empty Space Context Menu: Dark card with Change Wallpaper, Add Widget, Settings, and Manage Layout
                if (emptyMenu) EmptySpaceMenuDialog(
                    onWallpaper = { emptyMenu = false; SystemActions.wallpaper(activity) },
                    onAddWidget = { emptyMenu = false; actions.widgets() },
                    onSettings = { emptyMenu = false; panel = "settings" },
                    onManageLayout = { emptyMenu = false; panel = "layout" },
                    onDismiss = { emptyMenu = false },
                    showSettings = state.prefs.behavior.contextSettings
                )

            }
            }
                if (state.prefs.appearance.customBarColor) {
                    val nav = WindowInsets.navigationBars.asPaddingValues()
                    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
                    val navColor = parseColor(state.prefs.appearance.navigationColor, Color.Transparent)
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                        .background(parseColor(state.prefs.appearance.statusColor, Color.Transparent)))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(nav.calculateBottomPadding()).background(navColor))
                    Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(nav.calculateStartPadding(direction)).background(navColor))
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(nav.calculateEndPadding(direction)).background(navColor))
                }
            }
        }
        }
    }
}


@Composable
internal fun FloatingDock(
    state: LauncherState,
    apps: List<AppEntry>,
    repository: AppsRepository,
    badges: Map<String, Int>,
    actions: WorkspaceActions,
    scrolling: Boolean = false
) {
    val prefs = state.prefs.dock
    var idleHidden by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var touching by remember { mutableStateOf(false) }
    val accessibility = LocalAccessibilityManager.current
    val dragging = LocalAppDrag.current?.active != null
    LaunchedEffect(prefs.autoHide, interaction, scrolling, dragging, touching) {
        idleHidden = false
        if (prefs.autoHide && !scrolling && !dragging && !touching) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(3000L, containsControls = true) ?: 3000L)
            idleHidden = true
        }
    }
    val hidden = if (scrolling && prefs.showWhenScrolling) false else idleHidden || (scrolling && prefs.hideOnScroll)
    val isRight = prefs.side == "Right"
    val entries = state.dockApps.mapNotNull { key -> apps.find { it.key == key } }
    val drag = LocalAppDrag.current
    val target = drag?.hovered?.target as? AppDropTarget.Area
    val hovering = !prefs.preventChanges && target?.area == LayoutArea.DOCK && drag?.active?.movingFolderId == null
    val movingKey = drag?.active?.app?.key
    val from = entries.indexOfFirst { it.key == movingKey }
    val to = entries.indexOfFirst { it.key == target?.atKey }.let { if (it < 0) entries.size else it }
    val preview = if (hovering && from >= 0) entries.moveLayoutItem(from, to.coerceAtMost(entries.lastIndex)) else entries
    val gap = hovering && from < 0
    val dockScroll = rememberScrollState()
    val scrollOffset = with(androidx.compose.ui.platform.LocalDensity.current) { dockScroll.value.toDp() }
    val folders = state.dockFolders.mapNotNull { id -> state.folders.find { it.id == id } }
    val dockWidth = (56 * prefs.iconScale + 12).dp.coerceAtLeast(56.dp)
    val theme = state.prefs.appearance.dock
    val shape = if (isRight) RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)
        else RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
    BoxWithConstraints(Modifier.fillMaxHeight().width(dockWidth).windowInsetsPadding(WindowInsets.safeDrawing)
        .pointerInput(Unit) {
            awaitPointerEventScope { while (true) {
                touching = awaitPointerEvent().changes.any { it.pressed }
                interaction++
            } }
        }
        .appDropTarget(AppDropTarget.Area(LayoutArea.DOCK), 1)) {
        val positionedDock = state.positions.any { it.container == "dock" }
        val dockKeys = state.workspaceKeys("dock")
        val dockRows = state.workspacePositions("dock", 1, MAX_LAYOUT_APPS).associate { it.key to it.row }
        val totalItems = (if (positionedDock) dockKeys.size else entries.size + folders.size) + (if (prefs.allApps) 1 else 0) + (if (gap) 1 else 0)
        val itemHeight = (56 * prefs.iconScale + if (prefs.labels) 36 else 16).dp
        val estimatedHeight = (totalItems.coerceIn(1, prefs.maxItems) * (56 * prefs.iconScale + if (prefs.labels) 36 else 16) + prefs.padding * 2).dp.coerceAtMost(maxHeight)
        val height = if (prefs.fullWidth) maxHeight else estimatedHeight
        val top = ((maxHeight - height) * prefs.alignment).coerceAtLeast(0.dp)
        CompositionLocalProvider(LocalContentColor provides surfaceContent(theme)) {
            if (hidden) {
                IconButton(onClick = { idleHidden = false; interaction++ }, modifier = Modifier.align(Alignment.Center)) {
                    Icon(Icons.Rounded.ChevronLeft, "Show dock")
                }
            } else {
            Column(
                Modifier.offset(y = top).width(dockWidth).height(height).clip(shape)
                    .background(surfaceColor(theme, MaterialTheme.colorScheme.primary))
                    .verticalScroll(dockScroll).padding(vertical = prefs.padding.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val grid = GridPrefs(iconScale = prefs.iconScale, labels = prefs.labels, textScale = .9f)
                val allAppsButton = @Composable {
                    IconButton(onClick = { actions.navigate(Screen.APPS) }, modifier = Modifier.size((56 * prefs.iconScale + 16).dp)) {
                        Icon(Icons.Rounded.Apps, "All applications")
                    }
                }
                if (prefs.allApps && prefs.allAppsLeft) allAppsButton()
                if (positionedDock) {
                    var nextDockRow = 0
                    dockKeys.forEach { itemKey ->
                        val row = dockRows[itemKey] ?: nextDockRow
                        repeat((row - nextDockRow).coerceAtLeast(0)) { Spacer(Modifier.height(itemHeight)) }
                        key(itemKey) { WorkspaceEntry(itemKey, "dock", state, apps, repository, grid, badges, actions,
                            Modifier.fillMaxWidth().height(itemHeight)) }
                        nextDockRow = row + 1
                    }
                } else {
                preview.forEachIndexed { index, app ->
                    if (gap && index == to) Spacer(Modifier.height(itemHeight))
                    key(app.key) {
                        AppTile(app, repository, state, grid, badges[app.badgeKey] ?: 0,
                            { actions.launch(app) }, { actions.dockMenu(app) }, Modifier.fillMaxWidth().height(itemHeight),
                            draggable = !prefs.preventChanges, dragArea = LayoutArea.DOCK)
                    }
                }
                if (gap && to == entries.size) Spacer(Modifier.height(itemHeight))
                folders.forEach { item ->
                    FolderTile(item, apps, repository, state, grid, { actions.folder(item, true) },
                        { actions.folderActions(item) }, Modifier.fillMaxWidth(), "dock")
                }
                }
                if (prefs.allApps && !prefs.allAppsLeft) allAppsButton()
                if (totalItems == 0) IconButton(onClick = { actions.navigate(Screen.APPS) }) {
                    Icon(Icons.Rounded.Add, "Add apps to dock")
                }
            }
            }
        }
        // Stable hit regions prevent the preview gap from moving its own drop target.
        if (drag?.active != null && !prefs.preventChanges && !positionedDock) {
            entries.forEachIndexed { index, app ->
                Box(Modifier.offset(y = top + prefs.padding.dp + itemHeight * index - scrollOffset +
                    (if (prefs.allApps && prefs.allAppsLeft) (56 * prefs.iconScale + 16).dp else 0.dp))
                    .width(dockWidth).height(itemHeight)
                    .appDropTarget(AppDropTarget.Area(LayoutArea.DOCK, app.key), 2))
            }
        }
    }
}
