package dev.liteluncher.data

import kotlinx.serialization.Serializable

@Serializable
enum class Screen(val title: String, val x: Int, val y: Int) {
    HOME("Home Screen", 0, 0), SEARCH("Search", 0, -1), APPS("Application List", 1, 0),
    DESKTOP("Desktop", -1, 0), FAVORITES("Favorites", 0, 1)
}

@Serializable
enum class Direction { UP, DOWN, LEFT, RIGHT }
@Serializable
enum class ThemeMode { LIGHT, DARK, SYSTEM, ADVANCED }
@Serializable
enum class SurfaceTheme { GLASS, DARK, LIGHT, ACCENT }
@Serializable
enum class IconShape { SQUIRCLE, CIRCLE, ROUNDED_SQUARE, TEARDROP, ORIGINAL }
@Serializable
enum class WebEngine { GOOGLE, BING, DUCKDUCKGO, YAHOO, WIKIPEDIA, BAIDU, ECOSIA, NAVER, QWANT, STARTPAGE, BRAVE, CUSTOM }

fun WebEngine.displayName(): String = when (this) {
    WebEngine.GOOGLE -> "Google"
    WebEngine.BING -> "Bing"
    WebEngine.DUCKDUCKGO -> "DuckDuckGo"
    WebEngine.YAHOO -> "Yahoo!"
    WebEngine.WIKIPEDIA -> "Wikipedia"
    WebEngine.BAIDU -> "Baidu"
    WebEngine.ECOSIA -> "Ecosia"
    WebEngine.NAVER -> "Naver"
    WebEngine.QWANT -> "Qwant"
    WebEngine.STARTPAGE -> "Startpage.com"
    WebEngine.BRAVE -> "Brave"
    WebEngine.CUSTOM -> "Custom URL"
}

@Serializable
enum class Gesture { HOME, DOUBLE_TAP, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, TWO_UP, TWO_DOWN, TWO_LEFT, TWO_RIGHT, PINCH_IN, PINCH_OUT }
@Serializable
enum class Action { NONE, HOME, SEARCH, APPS, DESKTOP, FAVORITES, SETTINGS, SCREEN_MANAGER, WALLPAPER, APP, SHORTCUT, NAVIGATE, NOTIFICATIONS }
@Serializable
data class GestureBinding(val action: Action = Action.NONE, val target: String = "", val shortcutId: String = "")

fun defaultGestures(): Map<Gesture, GestureBinding> = Gesture.entries.associateWith {
    GestureBinding(when (it) {
        Gesture.TWO_DOWN -> Action.NOTIFICATIONS
        Gesture.SWIPE_UP, Gesture.SWIPE_DOWN, Gesture.SWIPE_LEFT, Gesture.SWIPE_RIGHT,
        Gesture.TWO_UP, Gesture.TWO_LEFT, Gesture.TWO_RIGHT -> Action.NAVIGATE
        else -> Action.NONE
    })
}

@Serializable
enum class SearchTarget(val title: String) {
    APPS("Apps"), HIDDEN_APPS("Hidden Apps"), CONTACTS("Contacts"), SHORTCUTS("Shortcuts"),
    SETTINGS("Settings"), MATH("Math calculation"), WEB("Web-Search"), PLAY_STORE("Search on Google Play"), WEBSITE("Open Website")
}

@Serializable
data class GridPrefs(
    val columns: Int = 4, val rows: Int = 5, val iconScale: Float = 1f,
    val labels: Boolean = true, val multiline: Boolean = false,
    val padding: Int = 12, val spacing: Int = 16, val listMode: Boolean = false,
    val textScale: Float = 1f, val intermediateSteps: Int = 0, val borderPadding: String = "Default",
    val preventChanges: Boolean = false
)

val GridPrefs.borderPaddingDp: Int get() = when (borderPadding) {
    "None" -> 0
    "Small" -> 8
    "Large" -> 24
    else -> padding
}
@Serializable
data class ClockPrefs(
    val enabled: Boolean = true, val use24Hour: Boolean = false, val size: Int = 52,
    val color: String = "#C0FFFFFF", val font: String = "Light", val showYear: Boolean = false,
    val analog: Boolean = false, val clockApp: String = "Clock", val showDate: Boolean = true,
    val showDay: Boolean = true, val textScale: Float = 1f
)
@Serializable
data class DockPrefs(
    val enabled: Boolean = true, val side: String = "Right", val alignment: Float = .5f,
    val iconScale: Float = 1f, val maxItems: Int = 5, val autoHide: Boolean = false,
    val hideOnScroll: Boolean = false, val fullWidth: Boolean = false, val folderStack: Boolean = true,
    val labels: Boolean = false, val padding: Int = 8, val allApps: Boolean = false,
    val allAppsLeft: Boolean = false, val showWhenScrolling: Boolean = false, val preventChanges: Boolean = false
)
@Serializable
data class NavigationPrefs(
    val enabledScreens: Set<Screen> = Screen.entries.toSet(), val fingers: Int = 1,
    val infinite: Boolean = false, val advanced: Boolean = false, val preset: String = "Cross"
)
@Serializable
data class SearchPrefs(
    val apps: Boolean = true, val hiddenApps: Boolean = false, val contacts: Boolean = false,
    val shortcuts: Boolean = true, val settings: Boolean = true, val math: Boolean = true,
    val web: Boolean = false, val playStore: Boolean = true, val history: Boolean = true,
    val engine: WebEngine = WebEngine.GOOGLE, val customUrl: String = "https://www.google.com/search?q={query}",
    val grid: GridPrefs = GridPrefs(columns = 4), val website: Boolean = true,
    val targetOrder: List<SearchTarget> = SearchTarget.entries.toList(),
    val barScreens: Set<Screen> = setOf(Screen.HOME, Screen.APPS, Screen.FAVORITES),
    val barAtBottom: Boolean = false, val barText: String = "Search", val hint: Boolean = true, val voice: Boolean = true
)
@Serializable
data class DesktopPrefs(
    val columns: Int = 5, val rows: Int = 7, val padding: Int = 8, val cornerRadius: Int = 24,
    val grid: GridPrefs = GridPrefs(columns = 5, rows = 7), val hint: Boolean = true,
    val widgetPadding: Boolean = false, val rounded: Boolean = true, val pageCount: Int = 1,
    val preventChanges: Boolean = false
)
@Serializable
data class FavoritesPrefs(
    val byUsage: Boolean = true, val excludeDock: Boolean = true, val excludeFolders: Boolean = false,
    val showContacts: Boolean = false, val maxItems: Int = 12, val grid: GridPrefs = GridPrefs(), val itemsPerRow: Int = 0,
    val reverse: Boolean = false, val excluded: Set<String> = emptySet()
)
@Serializable
data class AppearancePrefs(
    val mode: ThemeMode = ThemeMode.DARK, val launcher: SurfaceTheme = SurfaceTheme.GLASS,
    val dock: SurfaceTheme = SurfaceTheme.GLASS, val search: SurfaceTheme = SurfaceTheme.GLASS,
    val dim: Float = .12f, val accent: String = "#6FA8D2", val iconPack: String = "",
    val iconShape: IconShape = IconShape.SQUIRCLE, val lightStatusIcons: Boolean = true,
    val lightNavigationIcons: Boolean = true, val darkTexts: Boolean = false,
    val dimMode: String = "Follow Theme", val backgroundColor: String = "#80000000", val forceAdaptive: Boolean = false,
    val statusIcons: String = "Follow Theme", val navigationIcons: String = "Follow Theme",
    val customBarColor: Boolean = false, val statusColor: String = "#00000000", val navigationColor: String = "#00000000"
)
@Serializable
data class BadgePrefs(val enabled: Boolean = true, val counts: Boolean = false, val color: String = "#FF266AB3", val scale: Float = 1f, val style: String = "Follow Theme")
@Serializable
data class FolderPrefs(val color: String = "#80303030", val opacity: Float = 1f, val stacked: Boolean = true, val iconScale: Float = 1f,
    val background: String = "Follow Theme", val shape: IconShape = IconShape.SQUIRCLE, val iconColor: String = "#A0FFFFFF",
    val labels: Boolean = true, val textScale: Float = 1f, val multiline: Boolean = false)
@Serializable
data class BehaviorPrefs(
    val lockRotation: Boolean = true, val showStatusBar: Boolean = true,
    val wallpaperScroll: String = "Off", val horizontalTransition: String = "Slide",
    val verticalTransition: String = "Fade", val borders: Boolean = false, val animations: Boolean = true,
    val pageIndicators: Boolean = true, val returnHome: Boolean = true, val contextSettings: Boolean = true,
    val wallpaperAlignment: String = "Center"
)
@Serializable
data class LauncherPrefs(
    val home: GridPrefs = GridPrefs(), val clock: ClockPrefs = ClockPrefs(), val dock: DockPrefs = DockPrefs(),
    val drawer: GridPrefs = GridPrefs(), val navigation: NavigationPrefs = NavigationPrefs(),
    val search: SearchPrefs = SearchPrefs(), val desktop: DesktopPrefs = DesktopPrefs(),
    val favorites: FavoritesPrefs = FavoritesPrefs(), val appearance: AppearancePrefs = AppearancePrefs(),
    val badges: BadgePrefs = BadgePrefs(), val folders: FolderPrefs = FolderPrefs(),
    val behavior: BehaviorPrefs = BehaviorPrefs(), val gestures: Map<Gesture, GestureBinding> = defaultGestures()
)
@Serializable
data class BackupIcon(val bitmap: String = "", val packageName: String = "", val resourceName: String = "")
@Serializable
data class AppOverride(val label: String = "", val iconUri: String = "", val shape: IconShape? = null, val backupIcon: BackupIcon? = null)
@Serializable
data class FolderItem(val id: String, val name: String, val apps: List<String> = emptyList(), val iconUri: String = "", val shape: IconShape? = null,
    val entryOrder: List<String> = emptyList(), val backupIcon: BackupIcon? = null)
@Serializable
data class WidgetItem(val id: Int, val column: Int = 0, val row: Int = 0, val columns: Int = 4, val rows: Int = 2, val page: Int = 0,
    val provider: String = "", val restoreKey: String = "")
@Serializable
data class PinnedShortcut(val id: String, val packageName: String, val label: String, val userSerial: Long = 0,
    val container: String = "home", val backupIcon: BackupIcon? = null)

val PinnedShortcut.layoutKey: String get() = "shortcut:$packageName:$id|$userSerial"

@Serializable
data class ItemPosition(val key: String, val container: String, val column: Int, val row: Int, val columns: Int = 1, val rows: Int = 1)
@Serializable
data class PortableWidget(val key: String, val provider: String, val column: Int, val row: Int, val columns: Int, val rows: Int, val page: Int = 0) {
    fun location() = WidgetItem(0, column, row, columns, rows, page, provider, key)
}
@Serializable
data class LauncherState(
    val schemaVersion: Int = 1, val prefs: LauncherPrefs = LauncherPrefs(),
    val homeApps: List<String> = emptyList(), val dockApps: List<String> = emptyList(),
    val favoriteApps: List<String> = emptyList(), val hiddenApps: Set<String> = emptySet(),
    val folders: List<FolderItem> = emptyList(), val homeFolders: List<String> = emptyList(),
    val dockFolders: List<String> = emptyList(), val widgets: List<WidgetItem> = emptyList(),
    val contacts: List<String> = emptyList(), val shortcuts: List<PinnedShortcut> = emptyList(),
    val overrides: Map<String, AppOverride> = emptyMap(), val history: List<String> = emptyList(),
    val launches: Map<String, Int> = emptyMap(), val desktopApps: Map<Int, List<String>> = emptyMap(), val initialized: Boolean = false,
    val positions: List<ItemPosition> = emptyList(), val pendingWidgets: List<PortableWidget> = emptyList(),
    val lynxSettings: Map<String, String> = emptyMap()
)
