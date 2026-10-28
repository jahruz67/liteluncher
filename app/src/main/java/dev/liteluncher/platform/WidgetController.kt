package dev.liteluncher.platform

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.BundleCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.liteluncher.data.DesktopPrefs
import dev.liteluncher.data.PinnedShortcut
import dev.liteluncher.data.WidgetItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Construct exactly once per activity, before STARTED (normally immediately after super.onCreate).
 * start/stop are also attached to the activity lifecycle. Closing never deletes saved widgets.
 */
class WidgetController(
    private val activity: ComponentActivity,
    private val onAdded: (WidgetItem) -> Unit
) : AutoCloseable {
    private val manager = AppWidgetManager.getInstance(activity)
    private val users = requireNotNull(activity.getSystemService(UserManager::class.java))
    private val launcher = requireNotNull(activity.getSystemService(LauncherApps::class.java))
    private val journal = activity.getSharedPreferences("widget_host_pending", Context.MODE_PRIVATE)
    private val mutableProviderChanges = MutableStateFlow(0)
    internal val providerChanges = mutableProviderChanges.asStateFlow()
    private val providerPaddings = mutableMapOf<ComponentName, Rect>()
    private var paddingConfiguration = Configuration(activity.resources.configuration)
    private var paddingRevision = 0
    private val nativeHost = LauncherWidgetHost(activity, HOST_ID) {
        mutableProviderChanges.value += 1
    }
    val host: AppWidgetHost = nativeHost
    private var pending: Pending? = null
    private var restored = false
    private var listening = false
    private var closed = false
    private var pinDialog: AlertDialog? = null
    private var pinConfirmation: LauncherApps.PinItemRequest? = null
    private var columns = 4
    private var rows = 8
    private var cellWidthDp = 80f
    private var cellHeightDp = 80f
    private var layoutItems = emptyList<WidgetItem>()
    private var obstacles = emptyList<WidgetItem>()
    private var locked = false
    private var cachedPlacementLayout: PlacementLayout? = null
    private var currentPage = 0
    private val mutableRefreshes = MutableStateFlow<Map<Int, Int>>(emptyMap())
    internal val refreshes = mutableRefreshes.asStateFlow()

    private data class Pending(
        val id: Int,
        val provider: AppWidgetProviderInfo,
        val stage: String,
        val pin: LauncherApps.PinItemRequest? = null,
        val location: WidgetItem? = null
    )

    private data class PlacementLayout(
        val items: List<WidgetItem>, val columns: Int, val rows: Int, val page: Int, val layout: WidgetGrid.Layout
    )

    private val bindLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        restorePending()
        val current = pending ?: return@registerForActivityResult
        if (current.stage != BINDING) return@registerForActivityResult
        val returnedId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, current.id) ?: current.id
        if (result.resultCode != Activity.RESULT_OK || returnedId != current.id ||
            runCatching { manager.getAppWidgetInfo(current.id) }.getOrNull() == null) {
            cancelPending()
        } else configureOrFinish()
    }

    /**
     * Forward Activity.onActivityResult here; true consumes CONFIGURE_REQUEST_CODE (0x4C49).
     * Return false to let the activity dispatch other results, including the bind launcher.
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != CONFIGURE_REQUEST_CODE) return false
        if (closed) return true
        restorePending()
        val current = pending ?: return true
        if (current.stage != CONFIGURING) return true
        val returnedId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, current.id) ?: current.id
        if (resultCode == Activity.RESULT_OK && returnedId == current.id) finishAdding() else cancelPending()
        return true
    }

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_CREATE -> restorePending()
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }

    init {
        check(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            "Create WidgetController before the activity is STARTED."
        }
        activity.savedStateRegistry.registerSavedStateProvider(STATE_KEY) {
            Bundle().apply {
                putInt("page", currentPage)
                putInt("columns", columns)
                putInt("rows", rows)
                putFloat("cellWidth", cellWidthDp)
                putFloat("cellHeight", cellHeightDp)
                putIntArray("occupied", layoutItems.flatMap {
                    listOf(it.id, it.column, it.row, it.columns, it.rows)
                }.toIntArray())
                putIntArray("occupiedPages", layoutItems.map { it.page }.toIntArray())
                putParcelable("confirmation", pinConfirmation)
                pending?.let {
                    putInt("id", it.id)
                    putParcelable("provider", it.provider)
                    putString("stage", it.stage)
                    putParcelable("pin", it.pin)
                    it.location?.let { location ->
                        putIntArray("location", intArrayOf(location.column, location.row, location.page, location.columns, location.rows))
                        putString("restoreKey", location.restoreKey)
                    }
                }
            }
        }
        if (activity.savedStateRegistry.isRestored) restorePending()
        activity.lifecycle.addObserver(lifecycleObserver)
    }

    // Only the current user's providers are offered; profile availability is checked again
    // when adding and completing a widget.
    fun providers(): List<AppWidgetProviderInfo> = runCatching {
        if (!users.isUserUnlocked(Process.myUserHandle())) return@runCatching emptyList<AppWidgetProviderInfo>()
        manager.getInstalledProvidersForProfile(Process.myUserHandle()).map { provider ->
            provider to runCatching { provider.loadLabel(activity.packageManager) }
                .getOrDefault(provider.provider.packageName).lowercase()
        }.sortedBy { it.second }.map { it.first }
    }.getOrDefault(emptyList())

    fun add(provider: AppWidgetProviderInfo, location: WidgetItem? = null) {
        beginAdd(provider, null, location)
    }

    private fun beginAdd(provider: AppWidgetProviderInfo, pin: LauncherApps.PinItemRequest?, location: WidgetItem? = null) {
        restorePending()
        if (closed || locked) return
        if (pending != null || pinDialog != null) {
            SystemActions.error(activity, "Finish adding the current widget first.")
            return
        }
        if (provider.profile != Process.myUserHandle() || !profileAvailable(provider)) {
            SystemActions.error(activity, "Widgets are available only from your unlocked current profile.")
            return
        }
        if (placement(AppWidgetManager.INVALID_APPWIDGET_ID, provider, location) == null) {
            SystemActions.error(activity, "There is no free space for this widget. Move or remove a widget first.")
            return
        }
        val id = runCatching { host.allocateAppWidgetId() }.getOrElse {
            SystemActions.error(activity, "Android could not allocate a widget.")
            return
        }
        if (id <= 0) {
            SystemActions.error(activity, "This device does not support hosting widgets.")
            return
        }
        pending = Pending(id, provider, BINDING, pin, location ?: WidgetItem(id, page = currentPage))
        journal.edit().putInt("id", id).apply()
        val bound = runCatching {
            manager.bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider, widgetOptions())
        }.getOrDefault(false)
        if (bound) configureOrFinish() else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, widgetOptions())
            if (runCatching { bindLauncher.launch(intent) }.isFailure) {
                cancelPending()
                SystemActions.error(activity, "Android's widget binding screen is unavailable.")
            }
        }
    }

    private fun configureOrFinish() {
        val current = pending ?: return
        val info = runCatching { manager.getAppWidgetInfo(current.id) }.getOrNull()
        if (info == null || info.provider != current.provider.provider || info.profile != current.provider.profile) {
            cancelPending()
            return
        }
        if (info.configure == null) {
            finishAdding()
            return
        }
        pending = current.copy(provider = info, stage = CONFIGURING)
        if (runCatching {
            host.startAppWidgetConfigureActivityForResult(activity, current.id, 0, CONFIGURE_REQUEST_CODE, null)
        }.isFailure) {
            cancelPending()
            SystemActions.error(activity, "This widget's setup activity could not be opened.")
        }
    }

    private fun finishAdding() {
        val current = pending ?: return
        val info = runCatching { manager.getAppWidgetInfo(current.id) }.getOrNull()
        if (info == null || info.provider != current.provider.provider ||
            info.profile != current.provider.profile || !profileAvailable(info)) {
            cancelPending()
            return
        }
        val item = placement(current.id, info, current.location)
        if (item == null) {
            cancelPending()
            SystemActions.error(activity, "There is no free grid space for this widget.")
            return
        }
        if (current.pin != null) {
            val accepted = runCatching {
                current.pin.isValid && current.pin.accept(Bundle().apply {
                    putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, current.id)
                })
            }.getOrDefault(false)
            if (!accepted) {
                cancelPending()
                SystemActions.error(activity, "The widget pin request expired or was refused.")
                return
            }
        }
        // The callback owns persistence; keep the id pending until it has been delivered.
        synchronized(additionLock) {
            inFlightAdditions += item.id
            journal.edit().putStringSet("uncommitted",
                journal.getStringSet("uncommitted", emptySet()).orEmpty() + item.id.toString()).apply()
        }
        try {
            onAdded(item)
            layoutItems = layoutItems.filterNot { it.id == item.id } + item
            pending = null
            journal.edit().remove("id").apply()
        } catch (error: Exception) {
            cancelPending()
            finishAddition(item.id, saved = false)
            SystemActions.error(activity, "The widget could not be saved.")
        }
    }

    private fun widgetOptions() = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
    }

    private fun profileAvailable(provider: AppWidgetProviderInfo) = runCatching {
        users.userProfiles.contains(provider.profile) && users.isUserUnlocked(provider.profile) &&
            !users.isQuietModeEnabled(provider.profile)
    }.getOrDefault(false)

    private fun defaultPadding(info: AppWidgetProviderInfo): Rect {
        val configuration = activity.resources.configuration
        val revision = mutableProviderChanges.value
        if (paddingConfiguration != configuration || paddingRevision != revision) {
            providerPaddings.clear()
            paddingConfiguration = Configuration(configuration)
            paddingRevision = revision
        }
        return providerPaddings.getOrPut(info.provider) {
            AppWidgetHostView.getDefaultPaddingForWidget(activity, info.provider, null)
        }
    }

    internal fun placement(id: Int, info: AppWidgetProviderInfo, location: WidgetItem? = null): WidgetItem? {
        val density = activity.resources.displayMetrics.density
        val padding = defaultPadding(info)
        val width = ceil((info.minWidth + padding.left + padding.right) / density / cellWidthDp).toInt().coerceAtLeast(1)
        val height = ceil((info.minHeight + padding.top + padding.bottom) / density / cellHeightDp).toInt().coerceAtLeast(1)
        val desiredColumns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0) info.targetCellWidth else width
        val desiredRows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellHeight > 0) info.targetCellHeight else height
        val minimum = minimumSpan(info)
        if (minimum.first > columns || minimum.second > rows) return null
        val page = location?.page ?: currentPage
        val occupied = cachedPlacementLayout?.takeIf {
            it.columns == columns && it.rows == rows && it.page == page && it.items == layoutItems
        }?.layout ?: WidgetGrid.arrange(layoutItems.filter { it.page == page }, columns, rows).also {
            cachedPlacementLayout = PlacementLayout(layoutItems, columns, rows, page, it)
        }
        val occupiedItems = occupied.placed + obstacles.filter { it.page == page && it.restoreKey != location?.restoreKey }
        val maximum = maximumSpan(info)
        if (!location?.restoreKey.isNullOrEmpty()) {
            if (location!!.columns !in minimum.first..maximum.first || location.rows !in minimum.second..maximum.second) return null
            val exact = location.copy(id = id, provider = info.provider.flattenToString())
            return exact.takeIf { WidgetGrid.fits(it, occupiedItems, columns, rows) }
        }
        val maxColumns = desiredColumns.coerceIn(minimum.first, maximum.first)
        val maxRows = desiredRows.coerceIn(minimum.second, maximum.second)
        for (rowSpan in maxRows downTo minimum.second) {
            for (columnSpan in maxColumns downTo minimum.first) {
                val candidate = WidgetGrid.place(WidgetItem(id, column = location?.column ?: 0, row = location?.row ?: 0,
                    columns = columnSpan, rows = rowSpan, page = page, provider = info.provider.flattenToString()),
                    occupiedItems, columns, rows)
                if (candidate != null) return candidate
            }
        }
        return null
    }

    internal fun minimumSpan(
        info: AppWidgetProviderInfo, widthDp: Float = cellWidthDp, heightDp: Float = cellHeightDp
    ): Pair<Int, Int> {
        val density = activity.resources.displayMetrics.density
        val padding = defaultPadding(info)
        val width = if (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0 && info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val height = if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0 && info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        return ceil((width + padding.left + padding.right) / density / widthDp).toInt().coerceAtLeast(1) to
            ceil((height + padding.top + padding.bottom) / density / heightDp).toInt().coerceAtLeast(1)
    }

    internal fun maximumSpan(
        info: AppWidgetProviderInfo, widthDp: Float = cellWidthDp, heightDp: Float = cellHeightDp,
        gridColumns: Int = columns, gridRows: Int = rows
    ): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return gridColumns to gridRows
        val density = activity.resources.displayMetrics.density
        val padding = defaultPadding(info)
        val min = minimumSpan(info, widthDp, heightDp)
        val width = if (info.maxResizeWidth > 0) {
            kotlin.math.floor((info.maxResizeWidth + padding.left + padding.right) / density / widthDp)
                .toInt().coerceAtLeast(min.first)
        } else gridColumns
        val height = if (info.maxResizeHeight > 0) {
            kotlin.math.floor((info.maxResizeHeight + padding.top + padding.bottom) / density / heightDp)
                .toInt().coerceAtLeast(min.second)
        } else gridRows
        return width.coerceAtMost(gridColumns) to height.coerceAtMost(gridRows)
    }

    /** The workspace owns the complete layout snapshot and selected page. */
    fun updateLayout(prefs: DesktopPrefs, widgets: List<WidgetItem>, page: Int = currentPage, blocked: List<WidgetItem> = emptyList()) {
        locked = prefs.preventChanges
        obstacles = blocked
        columns = prefs.columns.coerceIn(1, 24)
        rows = prefs.rows.coerceIn(1, 32)
        layoutItems = widgets.distinctBy { it.id }
        currentPage = page.coerceIn(0, prefs.pageCount.coerceAtLeast(1) - 1)
    }

    internal fun syncGeometry(prefs: DesktopPrefs, widthDp: Float, heightDp: Float) {
        cellWidthDp = (widthDp / prefs.columns.coerceIn(1, 24)).coerceAtLeast(1f)
        cellHeightDp = (heightDp / prefs.rows.coerceIn(1, 32)).coerceAtLeast(1f)
    }

    internal fun provider(id: Int): AppWidgetProviderInfo? = runCatching {
        manager.getAppWidgetInfo(id)?.takeIf { profileAvailable(it) }
    }.getOrNull()

    internal fun createView(context: Context, id: Int, onLongPress: () -> Unit): AppWidgetHostView? {
        val info = provider(id) ?: return null
        return runCatching {
            (host.createView(context, id, info) as LauncherWidgetHostView).apply {
                edit = onLongPress
                contentDescription = info.loadLabel(context.packageManager)
            }
        }.getOrNull()
    }

    internal fun refresh(id: Int) {
        val info = provider(id)
        // Recreate the host view to reload Android's most recent RemoteViews, and ask the
        // provider to refresh its data. Providers may apply their own refresh throttling.
        mutableRefreshes.value = mutableRefreshes.value + (id to ((mutableRefreshes.value[id] ?: 0) + 1))
        if (info == null) return
        val exported = runCatching { activity.packageManager.getReceiverInfo(info.provider, 0).exported }.getOrDefault(false)
        if (!exported) {
            SystemActions.error(activity, "Widget view reloaded. Its app controls when fresh data is available.")
            return
        }
        runCatching {
            activity.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(info.provider).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id)))
        }.onFailure { SystemActions.error(activity, "Widget view reloaded, but its app refused the refresh request.") }
    }

    fun deferRemoval(id: Int) {
        journal.edit().putStringSet("removed", journal.getStringSet("removed", emptySet()).orEmpty() + id.toString()).apply()
    }

    /** Failed saves stay journaled so startup can retry Android resource cleanup. */
    fun finishAddition(id: Int, saved: Boolean = true) {
        synchronized(additionLock) {
            inFlightAdditions -= id
            if (saved) {
                journal.edit().putStringSet("uncommitted",
                    journal.getStringSet("uncommitted", emptySet()).orEmpty() - id.toString()).apply()
            }
        }
    }

    /** Reconcile after loading persisted widgets; saves survive activity recreation. */
    fun finishAdditions(activeIds: Set<Int>) {
        val abandoned = synchronized(additionLock) {
            journal.getStringSet("uncommitted", emptySet()).orEmpty()
                .mapNotNull { it.toIntOrNull() }.filterNot { it in inFlightAdditions }
        }
        abandoned.forEach { id ->
            if (id in activeIds || runCatching { host.deleteAppWidgetId(id) }.isSuccess) {
                finishAddition(id)
            }
        }
    }

    fun finishRemovals(activeIds: Set<Int>) {
        journal.getStringSet("removed", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.forEach { id ->
            if (id in activeIds) cancelRemoval(id) else remove(id)
        }
    }

    fun cancelRemoval(id: Int) {
        journal.edit().putStringSet("removed", journal.getStringSet("removed", emptySet()).orEmpty() - id.toString()).apply()
    }

    fun remove(id: Int) {
        deferRemoval(id)
        if (runCatching { host.deleteAppWidgetId(id) }.isFailure) return
        if (pending?.id == id) { pending = null; journal.edit().remove("id").apply() }
        cancelRemoval(id)
        layoutItems = layoutItems.filterNot { it.id == id }
        mutableRefreshes.value = mutableRefreshes.value - id
    }

    fun start() {
        restorePending()
        if (closed || listening) return
        listening = runCatching { host.startListening(); true }.getOrElse {
            SystemActions.error(activity, "Android could not start widget updates.")
            false
        }
        if (listening) mutableProviderChanges.value += 1
    }

    fun stop() {
        if (!listening) return
        runCatching { host.stopListening() }
        listening = false
    }

    /**
     * Call on every onCreate, including restoration, and for new pin intents. Restored
     * confirmations use this call's callback; an in-progress widget is never restarted.
     * True means a recognized request was handled or shown, not that the user accepted it.
     */
    fun acceptPin(
        intent: Intent,
        shortcutError: (PinnedShortcut) -> String? = { null },
        onShortcut: (PinnedShortcut) -> Unit
    ): Boolean {
        restorePending()
        if (closed) return false
        val pinIntent = intent.action == LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT ||
            intent.action == LauncherApps.ACTION_CONFIRM_PIN_APPWIDGET
        // The activity still holds its original pin intent while Android is binding or
        // configuring that widget. Replaying it must not create another confirmation.
        if (pending != null || pinDialog != null) {
            if (pinIntent) SystemActions.error(activity, "Finish the current pin request, then request this item again.")
            return pinIntent
        }
        val request = pinConfirmation ?: if (pinIntent) {
            runCatching { launcher.getPinItemRequest(intent) }.getOrNull()
        } else null
        if (request == null) return false
        if (!runCatching { request.isValid }.getOrDefault(false)) {
            pinConfirmation = null
            return false
        }
        when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                val shortcut = request.shortcutInfo ?: return false
                val serial = runCatching {
                    if (!users.userProfiles.contains(shortcut.userHandle) ||
                        !users.isUserUnlocked(shortcut.userHandle) || users.isQuietModeEnabled(shortcut.userHandle)) -1L
                    else users.getSerialNumberForUser(shortcut.userHandle)
                }.getOrDefault(-1L)
                if (serial < 0) return false
                val label = shortcut.shortLabel?.toString() ?: shortcut.id
                val pinned = PinnedShortcut(shortcut.id, shortcut.`package`, label, serial)
                showPinDialog(request, "Add shortcut?", "$label\n${shortcut.`package`}") {
                    val error = shortcutError(pinned)
                    if (error != null) {
                        SystemActions.error(activity, error)
                        return@showPinDialog
                    }
                    val accepted = runCatching { request.isValid && request.accept() }.getOrDefault(false)
                    if (accepted) {
                        runCatching { onShortcut(pinned) }
                            .onFailure { SystemActions.error(activity, "The shortcut was pinned but could not be saved.") }
                    } else SystemActions.error(activity, "The shortcut pin request is no longer available.")
                }
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                val provider = runCatching { request.getAppWidgetProviderInfo(activity) }.getOrNull() ?: return false
                if (provider.profile != Process.myUserHandle() || !profileAvailable(provider)) {
                    SystemActions.error(activity, "This widget is unavailable in the current profile.")
                    return true
                }
                val label = runCatching { provider.loadLabel(activity.packageManager) }.getOrDefault(provider.provider.packageName)
                showPinDialog(request, "Add widget?", "$label\n${provider.provider.packageName}") {
                    if (runCatching { request.isValid }.getOrDefault(false)) beginAdd(provider, request)
                    else SystemActions.error(activity, "The widget pin request expired.")
                }
            }
            else -> return false
        }
        return true
    }

    private fun showPinDialog(request: LauncherApps.PinItemRequest, title: String, label: String, accepted: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(label)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Add") { _, _ ->
                pinDialog = null
                pinConfirmation = null
                accepted()
            }.create()
        dialog.setOnDismissListener {
            if (pinDialog === dialog) {
                pinDialog = null
                pinConfirmation = null
            }
        }
        pinConfirmation = request
        pinDialog = dialog
        if (runCatching { dialog.show() }.isFailure) {
            pinDialog = null
            pinConfirmation = null
            SystemActions.error(activity, "The add confirmation could not be displayed.")
        }
    }

    private fun restorePending() {
        if (restored || !activity.savedStateRegistry.isRestored) return
        restored = true
        val bundle = activity.savedStateRegistry.consumeRestoredStateForKey(STATE_KEY)
        if (bundle != null) {
            currentPage = bundle.getInt("page", currentPage).coerceAtLeast(0)
            columns = bundle.getInt("columns", columns).coerceIn(1, 24)
            rows = bundle.getInt("rows", rows).coerceIn(1, 32)
            cellWidthDp = bundle.getFloat("cellWidth", cellWidthDp).takeIf { it.isFinite() && it > 0 } ?: 80f
            cellHeightDp = bundle.getFloat("cellHeight", cellHeightDp).takeIf { it.isFinite() && it > 0 } ?: 80f
            val occupied = bundle.getIntArray("occupied") ?: intArrayOf()
            val pages = bundle.getIntArray("occupiedPages") ?: intArrayOf()
            layoutItems = (0 until occupied.size / 5).map { index ->
                val offset = index * 5
                WidgetItem(occupied[offset], occupied[offset + 1], occupied[offset + 2],
                    occupied[offset + 3], occupied[offset + 4], pages.getOrElse(index) { 0 })
            }.filter { it.id > 0 }.distinctBy { it.id }
        }
        val id = bundle?.getInt("id", AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val info = bundle?.let { BundleCompat.getParcelable(it, "provider", AppWidgetProviderInfo::class.java) }
        val stage = bundle?.getString("stage")
        if (id > 0 && info != null && stage in listOf(BINDING, CONFIGURING)) {
            val pin = BundleCompat.getParcelable(requireNotNull(bundle), "pin", LauncherApps.PinItemRequest::class.java)
            val location = bundle.getIntArray("location")?.takeIf { it.size == 3 || it.size == 5 }?.let {
                WidgetItem(id, column = it[0], row = it[1], page = it[2], columns = it.getOrElse(3) { 4 }, rows = it.getOrElse(4) { 2 },
                    provider = info.provider.flattenToString(), restoreKey = bundle.getString("restoreKey").orEmpty())
            }
            pending = Pending(id, info, requireNotNull(stage), pin, location)
        }
        // Confirmed widgets belong exclusively to Pending. Only an unaccepted, still-valid
        // confirmation is eligible for redisplay when acceptPin supplies a fresh callback.
        if (pending == null && bundle != null) {
            pinConfirmation = BundleCompat.getParcelable(bundle, "confirmation", LauncherApps.PinItemRequest::class.java)
                ?.takeIf { runCatching { it.isValid }.getOrDefault(false) }
        }
        val orphan = journal.getInt("id", AppWidgetManager.INVALID_APPWIDGET_ID)
        val awaitingSave = journal.getStringSet("uncommitted", emptySet()).orEmpty().contains(orphan.toString())
        if (orphan != AppWidgetManager.INVALID_APPWIDGET_ID && orphan != pending?.id && !awaitingSave) {
            runCatching { host.deleteAppWidgetId(orphan) }
        }
        if (pending == null) journal.edit().remove("id").apply()
        else journal.edit().putInt("id", requireNotNull(pending).id).apply()
    }

    private fun cancelPending() {
        pending?.let { runCatching { host.deleteAppWidgetId(it.id) } }
        pending = null
        journal.edit().remove("id").apply()
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        pinDialog?.dismiss()
        pinDialog = null
        pinConfirmation = null
        if (!activity.isChangingConfigurations) cancelPending()
        nativeHost.releaseViews()
        activity.lifecycle.removeObserver(lifecycleObserver)
        activity.savedStateRegistry.unregisterSavedStateProvider(STATE_KEY)
        bindLauncher.unregister()
    }

    private companion object {
        const val HOST_ID = 0x4C495445
        const val STATE_KEY = "dev.liteluncher.widget.pending"
        const val CONFIGURE_REQUEST_CODE = 0x4C49
        const val BINDING = "bind"
        const val CONFIGURING = "configure"
        val additionLock = Any()
        val inFlightAdditions = mutableSetOf<Int>()
    }
}

private class LauncherWidgetHost(context: Context, id: Int, private val onProviderChange: () -> Unit) : AppWidgetHost(context, id) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo): AppWidgetHostView =
        LauncherWidgetHostView(context)

    override fun onProviderChanged(appWidgetId: Int, appWidget: AppWidgetProviderInfo) {
        super.onProviderChanged(appWidgetId, appWidget)
        onProviderChange()
    }

    override fun onProvidersChanged() {
        super.onProvidersChanged()
        onProviderChange()
    }

    fun releaseViews() = clearViews()
}

/** Normal child clicks/scrolling remain native; only a stationary hold enters launcher editing. */
internal class LauncherWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var edit: (() -> Unit)? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var down: MotionEvent? = null
    private var holding = false
    private var waiting = false
    private val hold = Runnable {
        waiting = false
        val event = down ?: return@Runnable
        if (!isAttachedToWindow || edit == null) return@Runnable
        holding = true
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        edit?.invoke()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            clearHold()
            holding = false
            down = MotionEvent.obtain(event)
            waiting = true
            postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
        }
        val first = down
        if (event.pointerCount > 1 || (event.actionMasked == MotionEvent.ACTION_MOVE && first != null &&
                (abs(event.x - first.x) > slop || abs(event.y - first.y) > slop))) clearHold()
        val consumed = if (holding) true else super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            clearHold()
            holding = false
        }
        // Capture a down on non-clickable widget background too, so subsequent movement
        // can cancel the hold before launcher editing is triggered.
        return consumed || (event.actionMasked == MotionEvent.ACTION_DOWN && edit != null)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) clearHold()
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    private fun clearHold() {
        if (waiting) removeCallbacks(hold)
        waiting = false
        down?.recycle()
        down = null
    }

    override fun onDetachedFromWindow() {
        clearHold()
        super.onDetachedFromWindow()
    }
}
