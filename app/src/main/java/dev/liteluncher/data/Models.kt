package dev.liteluncher.data

import kotlinx.serialization.Serializable

@Serializable
enum class Screen(val title: String, val x: Int, val y: Int) {
    HOME("Home", 0, 0), SEARCH("Search", 0, -1), APPS("Applications", 1, 0),
    DESKTOP("Desktop", -1, 0), FAVORITES("Favorites", 0, 1)
}

@Serializable
enum class Direction { UP, DOWN, LEFT, RIGHT }
@Serializable
enum class ThemeMode { LIGHT, DARK, SYSTEM, ADVANCED }
@Serializable
enum class SurfaceTheme { GLASS, DARK, LIGHT, ACCENT }
@Serializable
enum class IconShape { SQUIRCLE, CIRCLE, ROUNDED_SQUARE, ORIGINAL }
@Serializable
enum class WebEngine { GOOGLE, BING, DUCKDUCKGO, YAHOO, WIKIPEDIA, BAIDU, ECOSIA, STARTPAGE, CUSTOM }
@Serializable
enum class Gesture { HOME, DOUBLE_TAP, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, TWO_UP, TWO_DOWN, TWO_LEFT, TWO_RIGHT, PINCH_IN, PINCH_OUT }
@Serializable
enum class Action { NONE, HOME, SEARCH, APPS, DESKTOP, FAVORITES, SETTINGS, SCREEN_MANAGER, WALLPAPER, APP, SHORTCUT, NAVIGATE }
@Serializable
data class GestureBinding(val action: Action = Action.NONE, val target: String = "", val shortcutId: String = "")

fun defaultGestures(): Map<Gesture, GestureBinding> = mapOf(
    Gesture.HOME to GestureBinding(Action.HOME), Gesture.DOUBLE_TAP to GestureBinding(Action.SEARCH),
    Gesture.SWIPE_UP to GestureBinding(Action.NAVIGATE), Gesture.SWIPE_DOWN to GestureBinding(Action.NAVIGATE),
    Gesture.SWIPE_LEFT to GestureBinding(Action.NAVIGATE), Gesture.SWIPE_RIGHT to GestureBinding(Action.NAVIGATE),
    Gesture.TWO_UP to GestureBinding(Action.NAVIGATE), Gesture.TWO_DOWN to GestureBinding(Action.NAVIGATE),
    Gesture.TWO_LEFT to GestureBinding(Action.NAVIGATE), Gesture.TWO_RIGHT to GestureBinding(Action.NAVIGATE),
    Gesture.PINCH_IN to GestureBinding(Action.SCREEN_MANAGER), Gesture.PINCH_OUT to GestureBinding(Action.HOME)
)

@Serializable
data class GridPrefs(
    val columns: Int = 4, val rows: Int = 6, val iconScale: Float = 1f,
    val labels: Boolean = true, val multiline: Boolean = false,
    val padding: Int = 20, val spacing: Int = 16, val listMode: Boolean = false
)
@Serializable
data class ClockPrefs(
    val enabled: Boolean = true, val use24Hour: Boolean = false, val size: Int = 76,
    val color: String = "#F3F5ED", val font: String = "Light", val showYear: Boolean = true
)
@Serializable
data class DockPrefs(
    val enabled: Boolean = true, val side: String = "Right", val alignment: Float = .55f,
    val iconScale: Float = .9f, val maxItems: Int = 5, val autoHide: Boolean = false,
    val hideOnScroll: Boolean = true, val fullWidth: Boolean = false, val folderStack: Boolean = true,
    val labels: Boolean = false, val padding: Int = 8
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
    val web: Boolean = true, val playStore: Boolean = true, val history: Boolean = true,
    val engine: WebEngine = WebEngine.GOOGLE, val customUrl: String = "https://www.google.com/search?q={query}",
    val grid: GridPrefs = GridPrefs(columns = 4)
)
@Serializable
data class DesktopPrefs(
    val columns: Int = 4, val rows: Int = 8, val padding: Int = 8, val cornerRadius: Int = 24,
    val grid: GridPrefs = GridPrefs(columns = 4, rows = 8)
)
@Serializable
data class FavoritesPrefs(
    val byUsage: Boolean = true, val excludeDock: Boolean = true, val excludeFolders: Boolean = true,
    val showContacts: Boolean = true, val maxItems: Int = 12, val grid: GridPrefs = GridPrefs()
)
@Serializable
data class AppearancePrefs(
    val mode: ThemeMode = ThemeMode.DARK, val launcher: SurfaceTheme = SurfaceTheme.GLASS,
    val dock: SurfaceTheme = SurfaceTheme.DARK, val search: SurfaceTheme = SurfaceTheme.DARK,
    val dim: Float = .32f, val accent: String = "#C1F17C", val iconPack: String = "",
    val iconShape: IconShape = IconShape.SQUIRCLE, val lightStatusIcons: Boolean = true,
    val lightNavigationIcons: Boolean = true
)
@Serializable
data class BadgePrefs(val enabled: Boolean = false, val counts: Boolean = false, val color: String = "#C1F17C", val scale: Float = 1f)
@Serializable
data class FolderPrefs(val color: String = "#242A25", val opacity: Float = .95f, val stacked: Boolean = true, val iconScale: Float = 1f)
@Serializable
data class BehaviorPrefs(
    val lockRotation: Boolean = true, val showStatusBar: Boolean = true,
    val wallpaperScroll: String = "Parallax", val horizontalTransition: String = "Slide",
    val verticalTransition: String = "Fade", val borders: Boolean = true, val animations: Boolean = true
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
data class AppOverride(val label: String = "", val iconUri: String = "")
@Serializable
data class FolderItem(val id: String, val name: String, val apps: List<String> = emptyList())
@Serializable
data class WidgetItem(val id: Int, val column: Int = 0, val row: Int = 0, val columns: Int = 4, val rows: Int = 2)
@Serializable
data class PinnedShortcut(val id: String, val packageName: String, val label: String, val userSerial: Long = 0)
@Serializable
data class LauncherState(
    val schemaVersion: Int = 1, val prefs: LauncherPrefs = LauncherPrefs(),
    val homeApps: List<String> = emptyList(), val dockApps: List<String> = emptyList(),
    val favoriteApps: List<String> = emptyList(), val hiddenApps: Set<String> = emptySet(),
    val folders: List<FolderItem> = emptyList(), val homeFolders: List<String> = emptyList(),
    val dockFolders: List<String> = emptyList(), val widgets: List<WidgetItem> = emptyList(),
    val contacts: List<String> = emptyList(), val shortcuts: List<PinnedShortcut> = emptyList(),
    val overrides: Map<String, AppOverride> = emptyMap(), val history: List<String> = emptyList(),
    val launches: Map<String, Int> = emptyMap(), val initialized: Boolean = false
)
