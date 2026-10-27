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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import kotlin.math.roundToInt

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
    val update: ((LauncherState) -> LauncherState) -> Unit
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
    val manager = rememberSaveable(saver = ScreenManager.stateSaver) { ScreenManager() }
    var panel by rememberSaveable { mutableStateOf("") }
    var appMenu by remember { mutableStateOf<AppEntry?>(null) }
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
    var lastDeletedWidget by remember { mutableStateOf<WidgetItem?>(null) }
    var lastDeletedAppKey by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    // Multi-page desktop support
    var currentDesktopPage by rememberSaveable { mutableIntStateOf(0) }

    // Drag and drop state
    var draggingAppKey by remember { mutableStateOf<String?>(null) }
    var dragDestinationCapsule by remember { mutableStateOf<String?>(null) }

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
            Action.SCREEN_MANAGER -> panel = "screens"
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
        launch = launch, menu = { appMenu = it }, navigate = navigate, settings = { panel = "settings" },
        manageScreens = { panel = "screens" }, manageLayout = { panel = "layout" }, widgets = { widgetPicker = true },
        folder = { item, dock -> folder = item; folderFromDock = dock }, requestContacts = onContacts, update = model.store::update
    )

    LaunchedEffect(ready, apps) {
        if (ready && apps.isNotEmpty() && !state.initialized) model.store.update { current ->
            if (current.initialized) current else {
                val preferred = listOf("phone", "message", "chrome", "camera", "photos", "calendar", "music", "mail")
                val curated = preferred.mapNotNull { word -> apps.firstOrNull { it.label.contains(word, true) || it.packageName.contains(word, true) } }.distinctBy { it.key }
                val selected = (curated + apps).distinctBy { it.key }
                current.copy(initialized = true, homeApps = selected.take(8).map { it.key }, dockApps = curated.take(4).map { it.key })
            }
        }
    }
    LaunchedEffect(permissionSignal, apps.size) {
        usage = withContext(Dispatchers.IO) { model.apps.usage() }
        iconPacks = withContext(Dispatchers.IO) { model.apps.iconPacks() }
    }
    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) {
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
    LaunchedEffect(lastScroll) { if (lastScroll != 0L) { scrolling = true; delay(650); scrolling = false } }
    LaunchedEffect(manager.current, state.prefs.dock.autoHide, dockWakeSignal) {
        dockAwake = true
        if (state.prefs.dock.autoHide) { delay(3500); dockAwake = false }
    }

    // Auto-dismiss deletion snackbar after 4 seconds
    LaunchedEffect(snackbarVisible) {
        if (snackbarVisible) {
            delay(4000)
            snackbarVisible = false
        }
    }

    SideEffect {
        if (ready) widgets.updateLayout(state.prefs.desktop, state.widgets)
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
    val nestedScroll = remember { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.getDistance() > 1f) lastScroll = android.os.SystemClock.uptimeMillis()
            return Offset.Zero
        }
    } }

    LauncherTheme(state.prefs.appearance) {
        val launcherSurface = state.prefs.appearance.launcher
        val baseScheme = MaterialTheme.colorScheme
        val foreground = if (launcherSurface == SurfaceTheme.GLASS) baseScheme.onBackground else surfaceContent(launcherSurface)
        val workspaceScheme = baseScheme.copy(onBackground = foreground, onSurface = foreground,
            onSurfaceVariant = foreground.copy(alpha = .65f), surface = when (launcherSurface) {
                SurfaceTheme.LIGHT -> Color(0xFFDCE7D6)
                SurfaceTheme.ACCENT -> baseScheme.primary.copy(alpha = .75f)
                else -> baseScheme.surface
            })

        // Background Blur & Scrim: Entering Search or Screen Manager applies a real-time Gaussian blur and 50% black scrim (#80000000)
        val shouldBlur = manager.current == Screen.SEARCH || panel == "screens"

        MaterialTheme(colorScheme = workspaceScheme) {
        CompositionLocalProvider(LocalContentColor provides foreground) {
            val gradient = if (baseScheme.background.luminance() > .5f) listOf(Color(0xFFEAF3DF).copy(alpha = .88f), Color(0xFFB5CCAC).copy(alpha = .8f))
                else listOf(Color(0xFF101E19).copy(alpha = .56f), Color(0xFF36503E).copy(alpha = .3f), Color(0xFF18211D).copy(alpha = .68f))

            Box(
                Modifier.fillMaxSize()
                    .then(if (shouldBlur) Modifier.blur(20.dp) else Modifier)
                    .background(Brush.linearGradient(gradient))
            ) {
                if (launcherSurface != SurfaceTheme.GLASS) Box(Modifier.fillMaxSize().background(surfaceColor(launcherSurface, MaterialTheme.colorScheme.primary)))
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = state.prefs.appearance.dim)))

                // 50% black scrim (#80000000) when Search or Screen Manager is active
                if (shouldBlur) {
                    Box(Modifier.fillMaxSize().background(Color(0x80000000)))
                }

                // Ghosted Clock Watermark: When fading into Favorites or Search, retain the Home screen’s Date & Time header as stationary background layer at ~25% opacity
                if (manager.current == Screen.SEARCH || manager.current == Screen.FAVORITES) {
                    Box(
                        Modifier.fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = state.prefs.home.padding.dp)
                    ) {
                        Box(Modifier.alpha(0.25f)) {
                            ClockHeader(state.prefs.clock)
                        }
                    }
                }

                if (!ready) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cerulean) }
                } else if (panel.isNotEmpty()) {
                    MaterialTheme(colorScheme = baseScheme) {
                    CompositionLocalProvider(LocalContentColor provides baseScheme.onSurface) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                            when (panel) {
                                "settings" -> SettingsScreen(state, apps, iconPacks, model.store::update, { panel = "" }, { panel = "screens" }, onExport, onImport,
                                    onDefaultHome, { SystemActions.notificationAccess(context) }, { SystemActions.usageAccess(context) }, onContacts)
                                "screens" -> ScreenManagerScreen(state, model.store::update, { panel = "settings" })
                                "layout" -> ManageLayoutScreen(state, apps, model.apps, model.store::update, { panel = "" })
                            }
                        }
                    }
                    }
                    }
                } else {
                    val dockSpace = if (state.prefs.dock.enabled && manager.current != Screen.SEARCH) (maxOf(64f, 48 * state.prefs.dock.iconScale + 20) + 12).dp else 0.dp
                    val gestureModifier = Modifier.launcherGestures(state.prefs.navigation.fingers, manager.current == Screen.HOME,
                        onSwipe = { direction, count ->
                            val gesture = when (direction) {
                                Direction.UP -> if (count >= 2) Gesture.TWO_UP else Gesture.SWIPE_UP
                                Direction.DOWN -> if (count >= 2) Gesture.TWO_DOWN else Gesture.SWIPE_DOWN
                                Direction.LEFT -> if (count >= 2) Gesture.TWO_LEFT else Gesture.SWIPE_LEFT
                                Direction.RIGHT -> if (count >= 2) Gesture.TWO_RIGHT else Gesture.SWIPE_RIGHT
                            }
                            val binding = state.prefs.gestures[gesture] ?: defaultGestures()[gesture] ?: GestureBinding()
                            if (binding.action == Action.NAVIGATE) {
                                if (count >= state.prefs.navigation.fingers) manager.swipe(direction, state.prefs.navigation)
                            } else perform(binding)
                            dockWakeSignal++
                        }, onPinch = { inward -> perform(state.prefs.gestures[if (inward) Gesture.PINCH_IN else Gesture.PINCH_OUT] ?: GestureBinding()) })

                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).then(gestureModifier).nestedScroll(nestedScroll)) {
                        if (storageError != null) Text(storageError!!, color = MaterialTheme.colorScheme.error, fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp))

                        Row(Modifier.fillMaxWidth().padding(start = 23.dp, end = 15.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable { panel = "screens" }, verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text("Lite Luncher", fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                                Text("  /  ${manager.current.title.lowercase()}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { navigate(Screen.SEARCH) }) { Icon(Icons.Rounded.Search, "Search", modifier = Modifier.size(21.dp)) }
                            IconButton(onClick = { panel = "settings" }) { Icon(Icons.Rounded.Tune, "Launcher settings", modifier = Modifier.size(20.dp)) }
                        }

                        // Directional Asymmetry Transitions:
                        // - Horizontal Swipes: 1:1 slide physics with spring-damping between Desktop, Home, Apps
                        // - Vertical Swipes: Dissolve/crossfade fade transitions (no vertical sliding) between Search, Home, Favorites
                        AnimatedContent(
                            targetState = manager.current,
                            modifier = Modifier.weight(1f),
                            transitionSpec = {
                                val horizontal = initialState.x != targetState.x
                                if (horizontal) {
                                    val sign = if (targetState.x > initialState.x) 1 else -1
                                    (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 400f)) { it * sign } + fadeIn(tween(180))) togetherWith
                                        (slideOutHorizontally(spring(dampingRatio = 0.85f, stiffness = 400f)) { -it * sign } + fadeOut(tween(180)))
                                } else {
                                    // Vertical Swipes: Dissolve/crossfade fade transitions (no vertical sliding)
                                    fadeIn(tween(240)) togetherWith fadeOut(tween(240))
                                }
                            },
                            label = "Directional workspace"
                        ) { screen ->
                            Box(Modifier.fillMaxSize().padding(
                                start = if (state.prefs.dock.side == "Left") dockSpace else 0.dp,
                                end = if (state.prefs.dock.side == "Right") dockSpace else 0.dp
                            )) {
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
                                    Screen.DESKTOP -> Box(Modifier.fillMaxSize()) {
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

                                            val pageWidgets = state.widgets.filter { it.page == currentDesktopPage }
                                            if (pageWidgets.isEmpty() && state.prefs.desktop.hint) {
                                                EmptyCard(Icons.Rounded.Widgets, "Room for your widgets", "A quiet canvas for calendars, notes, and the things you want to keep in view.", "Add a widget", { widgetPicker = true })
                                            }

                                            WidgetCanvas(
                                                controller = widgets,
                                                prefs = state.prefs.desktop,
                                                widgets = pageWidgets,
                                                onChange = { list ->
                                                    model.store.update { it.copy(widgets = mergeWidgetEdits(it.widgets, pageWidgets, list)) }
                                                },
                                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                                onWidgetDeleted = { deleted ->
                                                    lastDeletedWidget = deleted
                                                    snackbarMessage = "Widget deleted"
                                                    snackbarVisible = true
                                                }
                                            )
                                        }

                                        // Centered floating + Add Widget capsule button at the bottom of the canvas
                                        ExtendedFloatingActionButton(
                                            onClick = { widgetPicker = true },
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

                        // Centered bottom page indicators (where inactive dots are translucent white #60FFFFFF and active morphs into elongated solid white capsule)
                        if (state.prefs.behavior.pageIndicators && manager.current in listOf(Screen.DESKTOP, Screen.HOME, Screen.APPS)) {
                            val horizontalScreens = listOf(Screen.DESKTOP, Screen.HOME, Screen.APPS)
                            val currentIdx = horizontalScreens.indexOf(manager.current).coerceAtLeast(0)
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                PageIndicators(
                                    pageCount = horizontalScreens.size,
                                    currentPage = currentIdx,
                                    onSelectPage = { idx -> navigate(horizontalScreens[idx]) }
                                )
                            }
                        }

                        if (state.prefs.behavior.borders) WorkspaceNavigation(manager.current, state.prefs.navigation, navigate)
                        else Spacer(Modifier.height(8.dp))
                    }

                    // Scalloped Pod Architecture: Dock icons sit inside individual, solid white (#FFFFFF) curved pods
                    val dockShown = state.prefs.dock.enabled && manager.current != Screen.SEARCH && dockAwake && !(scrolling && state.prefs.dock.hideOnScroll)
                    AnimatedVisibility(
                        visible = dockShown,
                        enter = fadeIn() + slideInHorizontally { if (state.prefs.dock.side == "Left") -it else it },
                        exit = fadeOut() + slideOutHorizontally { if (state.prefs.dock.side == "Left") -it else it },
                        modifier = Modifier.align(if (state.prefs.dock.side == "Left") Alignment.CenterStart else Alignment.CenterEnd)
                    ) {
                        FloatingDock(state, apps, model.apps, badges, actions)
                    }

                    if (state.prefs.dock.enabled && !dockShown && manager.current != Screen.SEARCH) {
                        Box(
                            Modifier.align(if (state.prefs.dock.side == "Left") Alignment.CenterStart else Alignment.CenterEnd)
                                .width(16.dp).height(100.dp).clickable { dockWakeSignal++ }.padding(horizontal = 5.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = .55f), RoundedCornerShape(8.dp))
                        )
                    }

                    // Drag and Drop destination capsule
                    if (dragDestinationCapsule != null) {
                        Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
                            DestinationCapsule(dragDestinationCapsule!!)
                        }
                    }

                    // Bottom Pill Deletion Snackbar with interactive Undo button
                    Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
                        DeletionSnackbar(
                            visible = snackbarVisible,
                            message = snackbarMessage,
                            onUndo = {
                                if (lastDeletedWidget != null) {
                                    model.store.update { it.copy(widgets = it.widgets + lastDeletedWidget!!) }
                                    lastDeletedWidget = null
                                }
                                if (lastDeletedAppKey != null) {
                                    val (type, list) = lastDeletedAppKey!!
                                    model.store.update { cur ->
                                        when (type) {
                                            "home" -> cur.copy(homeApps = list)
                                            "dock" -> cur.copy(dockApps = list)
                                            "favorite" -> cur.copy(favoriteApps = list)
                                            else -> cur
                                        }
                                    }
                                    lastDeletedAppKey = null
                                }
                                snackbarVisible = false
                            }
                        )
                    }
                }

                val currentFolder = folder?.let { selected -> state.folders.find { it.id == selected.id } }
                if (currentFolder != null) {
                    FolderOverlay(currentFolder, folderFromDock, state, apps, model.apps, actions, { folder = null })
                }
            }

            MaterialTheme(colorScheme = baseScheme) {
            CompositionLocalProvider(LocalContentColor provides baseScheme.onSurface) {
                if (appMenu != null) AppContextDialog(
                    app = appMenu!!,
                    state = state,
                    repository = model.apps,
                    actions = actions,
                    onEdit = { editApp = appMenu; appMenu = null },
                    onDismiss = { appMenu = null }
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
                    onAddWidget = { emptyMenu = false; navigate(Screen.DESKTOP); widgetPicker = true },
                    onSettings = { emptyMenu = false; panel = "settings" },
                    onManageLayout = { emptyMenu = false; panel = "layout" },
                    onDismiss = { emptyMenu = false }
                )

                // Categorized widget picker
                if (widgetPicker) WidgetPicker(
                    controller = widgets,
                    onSelect = { provider ->
                        widgetPicker = false
                        navigate(Screen.DESKTOP)
                        widgets.add(provider)
                    },
                    onDismiss = { widgetPicker = false }
                )
            }
            }
        }
        }
    }
}

@Composable
private fun WorkspaceNavigation(current: Screen, prefs: NavigationPrefs, navigate: (Screen) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Screen.entries.filter { it in prefs.enabledScreens || it == Screen.HOME }.sortedBy { when (it) {
            Screen.DESKTOP -> 0; Screen.HOME -> 1; Screen.APPS -> 2; Screen.SEARCH -> 3; Screen.FAVORITES -> 4
        } }.forEach { screen ->
            val selected = screen == current
            val icon = when (screen) { Screen.HOME -> Icons.Rounded.Home; Screen.SEARCH -> Icons.Rounded.Search; Screen.APPS -> Icons.Rounded.Apps; Screen.DESKTOP -> Icons.Rounded.Widgets; Screen.FAVORITES -> Icons.Rounded.StarOutline }
            IconButton(onClick = { navigate(screen) }, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, screen.title, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f), modifier = Modifier.size(19.dp))
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.size(3.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape))
                }
            }
        }
    }
}

/** Scalloped Pods: Dock icons sit inside individual, solid white (#FFFFFF) curved pods
 * that extend horizontally from the display edge with an ~8dp vertical gap between pods */
@Composable
private fun FloatingDock(
    state: LauncherState,
    apps: List<AppEntry>,
    repository: AppsRepository,
    badges: Map<String, Int>,
    actions: WorkspaceActions
) {
    val prefs = state.prefs.dock
    val theme = state.prefs.appearance.dock
    val isRight = prefs.side == "Right"
    val entries = state.dockApps.mapNotNull { key -> apps.find { it.key == key } }.take(prefs.maxItems)
    val folders = state.dockFolders.mapNotNull { id -> state.folders.find { it.id == id } }.take((prefs.maxItems - entries.size).coerceAtLeast(0))
    val dockWidth = (48 * prefs.iconScale + 24).dp.coerceAtLeast(64.dp)

    BoxWithConstraints(Modifier.fillMaxHeight().width(dockWidth + 16.dp).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val totalItems = entries.size + folders.size + (if (prefs.allApps) 1 else 0)
        val estimatedHeight = (totalItems.coerceAtLeast(1) * (48 * prefs.iconScale + 14) + prefs.padding * 2).dp.coerceAtMost(maxHeight)
        val top = if (prefs.fullWidth) 12.dp else ((maxHeight - estimatedHeight) * prefs.alignment).coerceAtLeast(0.dp)
        Surface(
            modifier = Modifier
                .padding(start = if (!isRight) 8.dp else 0.dp, end = if (isRight) 8.dp else 0.dp)
                .offset(y = top)
                .width(dockWidth)
                .then(if (prefs.fullWidth) Modifier.fillMaxHeight().padding(vertical = 12.dp) else Modifier),
            color = surfaceColor(theme, MaterialTheme.colorScheme.primary),
            contentColor = surfaceContent(theme),
            shape = RoundedCornerShape(28.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, surfaceContent(theme).copy(alpha = .08f)),
            shadowElevation = 8.dp
        ) {
            Column(
                Modifier
                    .heightIn(max = (maxHeight - top - 8.dp).coerceAtLeast(1.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 5.dp, vertical = prefs.padding.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (prefs.fullWidth) Arrangement.SpaceEvenly else Arrangement.spacedBy(3.dp)
            ) {
                val grid = GridPrefs(iconScale = prefs.iconScale, labels = prefs.labels, spacing = 4)
                entries.forEach { app ->
                    AppTile(app, repository, state, grid, badges[app.badgeKey] ?: 0, { actions.launch(app) }, { actions.menu(app) }, Modifier.fillMaxWidth())
                }
                folders.forEach { item ->
                    FolderTile(item, apps, repository, state, grid, { actions.folder(item, true) }, { actions.manageLayout() }, Modifier.fillMaxWidth())
                }
                if (entries.isNotEmpty() || folders.isNotEmpty()) {
                    HorizontalDivider(Modifier.width(25.dp).padding(vertical = 5.dp), color = surfaceContent(theme).copy(alpha = .12f))
                }
                if (prefs.allApps) {
                    IconButton(onClick = { actions.navigate(Screen.APPS) }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Apps, "All applications", tint = surfaceContent(theme), modifier = Modifier.size(23.dp))
                    }
                }
            }
        }
    }
}
