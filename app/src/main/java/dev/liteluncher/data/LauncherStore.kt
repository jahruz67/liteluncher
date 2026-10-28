package dev.liteluncher.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// The delegate shares one DataStore for this file throughout the application process.
private val Context.launcherPreferences by preferencesDataStore(
    name = "launcher_state",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** Own at application scope. Every accepted command runs in order, including backup barriers. */
class LauncherStore(context: Context) {
    private val dataStore = context.applicationContext.launcherPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val lifecycleLock = Any()
    private var closed = false
    private val mutableState = MutableStateFlow(LauncherState())
    private val mutableReady = MutableStateFlow(false)
    private val mutableError = MutableStateFlow<String?>(null)

    val state: StateFlow<LauncherState> = mutableState.asStateFlow()
    /** True once persisted preferences have been loaded or recovered successfully. */
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()

    init {
        scope.launch {
            try {
                try {
                    commit { it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.e(TAG, "Unable to load launcher preferences; later writes will retry", failure)
                    mutableError.value = "Launcher preferences could not be loaded. Check available device storage."
                    mutableReady.value = true
                }
                for (command in commands) {
                    when (command) {
                        is Command.Update -> {
                            try {
                                commit(command.transform)
                                command.result?.complete(Result.success(mutableState.value))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                // A failed transform/write leaves the last committed state intact.
                                Log.e(TAG, "Unable to update launcher preferences", failure)
                                if (command.result == null) mutableError.value = "Your last change could not be saved. Check available device storage."
                                command.result?.complete(Result.failure(failure))
                            }
                        }
                        is Command.Export -> {
                            try {
                                val current = decodeStored(dataStore.data.first()[SNAPSHOT])
                                mutableState.value = current
                                mutableReady.value = true
                                command.result.complete(encode(current.copy(widgets = emptyList())))
                            } catch (cancelled: CancellationException) {
                                command.result.completeExceptionally(cancelled)
                                throw cancelled
                            } catch (failure: Exception) {
                                command.result.completeExceptionally(failure)
                            }
                        }
                        is Command.Import -> {
                            try {
                                // Decode and sanitize completely before opening the write transaction.
                                val imported = decode(command.json, portable = true)
                                commit { local -> imported.copy(widgets = local.widgets) }
                                command.result.complete(Result.success(Unit))
                            } catch (cancelled: CancellationException) {
                                command.result.completeExceptionally(cancelled)
                                throw cancelled
                            } catch (failure: Exception) {
                                command.result.complete(Result.failure(failure))
                            }
                        }
                    }
                }
            } finally {
                synchronized(lifecycleLock) {
                    closed = true
                    commands.close()
                }
                val failure = IllegalStateException("LauncherStore is closed")
                while (true) {
                    val pending = commands.tryReceive().getOrNull() ?: break
                    when (pending) {
                        is Command.Export -> pending.result.completeExceptionally(failure)
                        is Command.Import -> pending.result.complete(Result.failure(failure))
                        is Command.Update -> pending.result?.complete(Result.failure(failure))
                    }
                }
                scope.cancel()
            }
        }
    }

    /** The transform runs on the IO worker; it should be pure, quick, and free of UI work. */
    fun update(transform: (LauncherState) -> LauncherState) = enqueue(Command.Update(transform))

    /** An ordered write with acknowledgement, for Android resources that need failure cleanup. */
    suspend fun updateAndAwait(transform: (LauncherState) -> LauncherState): Result<LauncherState> {
        val result = CompletableDeferred<Result<LauncherState>>()
        enqueue(Command.Update(transform, result))
        return result.await()
    }

    fun updatePrefs(transform: (LauncherPrefs) -> LauncherPrefs) =
        update { current -> current.copy(prefs = transform(current.prefs)) }

    fun recordLaunch(key: String) {
        val safeKey = Companion.key(key) ?: return
        update { current ->
            val launches = current.launches.toMutableMap()
            if (safeKey !in launches && launches.size >= MAX_LAUNCHES) {
                launches.minByOrNull { it.value }?.key?.let(launches::remove)
            }
            val count = (launches[safeKey] ?: 0).coerceAtLeast(0)
            launches[safeKey] = if (count == Int.MAX_VALUE) count else count + 1
            current.copy(launches = launches)
        }
    }

    fun addHistory(query: String) {
        val entry = text(query, MAX_HISTORY_LENGTH)
        if (entry.isEmpty()) return
        update { current ->
            if (!current.prefs.search.history) current else current.copy(
                history = (listOf(entry) + current.history.filterNot { it.equals(entry, ignoreCase = true) })
                    .take(MAX_HISTORY)
            )
        }
    }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    /** Waits for earlier commands. Device-local app widget allocations are excluded. */
    suspend fun exportJson(): String {
        val result = CompletableDeferred<String>()
        enqueue(Command.Export(result))
        return result.await()
    }

    /** Failure is atomic: neither the stored snapshot nor the exposed state is replaced. */
    suspend fun importJson(json: String): Result<Unit> {
        return try {
            // Reject oversized input before retaining it in the command queue.
            checkSize(json)
            val result = CompletableDeferred<Result<Unit>>()
            enqueue(Command.Import(json, result))
            result.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    /** Stops accepting work immediately; already accepted work drains asynchronously. */
    fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            commands.close()
        }
    }

    private fun enqueue(command: Command) {
        synchronized(lifecycleLock) {
            check(!closed) { "LauncherStore is closed" }
            commands.trySend(command).getOrThrow()
        }
    }

    private suspend fun commit(transform: (LauncherState) -> LauncherState) {
        var committed: LauncherState? = null
        dataStore.edit { preferences ->
            // Read inside the transaction so even a second store cannot overwrite newer data.
            val current = decodeStored(preferences[SNAPSHOT])
            val next = sanitize(transform(current))
            preferences[SNAPSHOT] = encode(next)
            committed = next
        }
        mutableState.value = checkNotNull(committed)
        mutableReady.value = true
        mutableError.value = null
    }

    private fun decodeStored(raw: String?): LauncherState {
        if (raw == null) return LauncherState()
        return try {
            decode(raw, portable = false)
        } catch (failure: Exception) {
            Log.w(TAG, "Recovering an invalid launcher snapshot", failure)
            LauncherState()
        }
    }

    private sealed interface Command {
        class Update(val transform: (LauncherState) -> LauncherState,
            val result: CompletableDeferred<Result<LauncherState>>? = null) : Command
        class Export(val result: CompletableDeferred<String>) : Command
        class Import(val json: String, val result: CompletableDeferred<Result<Unit>>) : Command
    }

    private companion object {
        const val TAG = "LauncherStore"
        const val SCHEMA_VERSION = 1
        const val MAX_JSON_BYTES = 1_048_576
        const val MAX_JSON_DEPTH = 32
        const val MAX_APPS = MAX_LAYOUT_APPS
        const val MAX_FOLDERS = MAX_LAYOUT_FOLDERS
        const val MAX_SHORTCUTS = 512
        const val MAX_CONTACTS = 256
        const val MAX_WIDGETS = 64
        const val MAX_LAUNCHES = 4_096
        const val MAX_HISTORY = 100
        const val MAX_HISTORY_LENGTH = 512
        const val MAX_COLUMNS = 16
        const val MAX_ROWS = 32
        val SNAPSHOT = stringPreferencesKey("snapshot_json")
        val HEX_COLOR = Regex("^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
        val AUTHORITY = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]*$")
        val PACKAGE = Regex("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$")
        val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            isLenient = false
            allowSpecialFloatingPointValues = false
        }

        fun checkSize(value: String) {
            require(value.length <= MAX_JSON_BYTES && value.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
                "Backup exceeds the 1 MiB limit"
            }
        }

        fun encode(value: LauncherState): String = JSON.encodeToString(value).also(::checkSize)

        fun decode(value: String, portable: Boolean): LauncherState {
            checkSize(value)
            // Bound recursion before the JSON parser sees potentially hostile nested input.
            var depth = 0
            var inString = false
            var escaped = false
            for (character in value) {
                if (inString) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') inString = false
                } else when (character) {
                    '"' -> inString = true
                    '{', '[' -> { depth++; require(depth <= MAX_JSON_DEPTH) { "Backup is nested too deeply" } }
                    '}', ']' -> { depth--; require(depth >= 0) { "Malformed backup" } }
                }
            }
            require(depth == 0 && !inString) { "Malformed backup" }
            val root = JSON.parseToJsonElement(value) as? JsonObject
                ?: throw IllegalArgumentException("Backup must be a JSON object")
            val version = root["schemaVersion"] as? JsonPrimitive
            require(version != null && !version.isString && version.intOrNull == SCHEMA_VERSION) {
                "Unsupported or missing backup schemaVersion"
            }
            require(root["prefs"] is JsonObject) { "Backup must contain a preferences object" }
            // Discard this field before decoding: foreign IDs can never enter the local state.
            val payload = if (portable) JsonObject(root - "widgets") else root
            return sanitize(JSON.decodeFromJsonElement(LauncherState.serializer(), payload))
        }

        fun sanitize(value: LauncherState): LauncherState {
            val prefs = sanitizePrefs(value.prefs)
            val folders = value.folders.asSequence().mapNotNull { folder ->
                val id = key(folder.id) ?: return@mapNotNull null
                folder.copy(id = id, name = text(folder.name, 128).ifEmpty { "Folder" }, apps = keys(folder.apps, MAX_APPS), iconUri = safeUri(folder.iconUri))
            }.distinctBy { it.id }.take(MAX_FOLDERS).toList()
            val folderIds = folders.map { it.id }.toSet()
            val overrides = linkedMapOf<String, AppOverride>()
            for ((rawKey, override) in value.overrides) {
                val id = key(rawKey) ?: continue
                overrides[id] = override.copy(label = text(override.label, 128), iconUri = safeUri(override.iconUri))
                if (overrides.size >= MAX_APPS) break
            }
            val launches = linkedMapOf<String, Int>()
            for ((rawKey, count) in value.launches) {
                val id = key(rawKey) ?: continue
                launches[id] = count.coerceAtLeast(0)
                if (launches.size >= MAX_LAUNCHES) break
            }
            val widgets = value.widgets.asSequence().filter { it.id > 0 }.distinctBy { it.id }
                .take(MAX_WIDGETS).map { widget ->
                    val column = widget.column.coerceIn(0, MAX_COLUMNS - 1)
                    val row = widget.row.coerceIn(0, MAX_ROWS - 1)
                    widget.copy(column = column, row = row, page = widget.page.coerceIn(0, prefs.desktop.pageCount - 1),
                        columns = widget.columns.coerceIn(1, MAX_COLUMNS - column),
                        rows = widget.rows.coerceIn(1, MAX_ROWS - row))
                }.toList()
            val shortcuts = value.shortcuts.asSequence().mapNotNull { shortcut ->
                val id = key(shortcut.id, 1_024) ?: return@mapNotNull null
                val packageName = shortcut.packageName.trim()
                if (packageName.length > 255 || !PACKAGE.matches(packageName)) return@mapNotNull null
                shortcut.copy(id = id, packageName = packageName, label = text(shortcut.label, 128),
                    userSerial = shortcut.userSerial.coerceAtLeast(0))
            }.distinctBy { Triple(it.packageName, it.id, it.userSerial) }.take(MAX_SHORTCUTS).toList()
            return value.copy(
                schemaVersion = SCHEMA_VERSION, prefs = prefs,
                homeApps = keys(value.homeApps, MAX_APPS), dockApps = keys(value.dockApps, MAX_APPS),
                favoriteApps = keys(value.favoriteApps, MAX_APPS), hiddenApps = keys(value.hiddenApps, MAX_APPS).toSet(),
                folders = folders,
                homeFolders = keys(value.homeFolders, MAX_FOLDERS).filter { it in folderIds },
                dockFolders = keys(value.dockFolders, MAX_FOLDERS).filter { it in folderIds },
                widgets = widgets,
                desktopApps = value.desktopApps.filterKeys { it in 0 until prefs.desktop.pageCount }
                    .mapValues { keys(it.value, MAX_APPS) },
                contacts = value.contacts.asSequence().map { safeUri(it, contact = true) }
                    .filter { it.isNotEmpty() }.distinct().take(MAX_CONTACTS).toList(),
                shortcuts = shortcuts, overrides = overrides, launches = launches,
                history = value.history.asSequence().map { text(it, MAX_HISTORY_LENGTH) }
                    .filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }.take(MAX_HISTORY).toList()
            )
        }

        fun sanitizePrefs(value: LauncherPrefs): LauncherPrefs {
            val defaults = LauncherPrefs()
            return value.copy(
                home = grid(value.home), drawer = grid(value.drawer),
                clock = value.clock.copy(size = value.clock.size.coerceIn(8, 512),
                    color = color(value.clock.color, defaults.clock.color),
                    font = text(value.clock.font, 64).ifEmpty { defaults.clock.font },
                    textScale = bounded(value.clock.textScale, .25f, 3f, .85f), clockApp = key(value.clock.clockApp).orEmpty()),
                dock = value.dock.copy(side = text(value.dock.side, 32).ifEmpty { defaults.dock.side },
                    alignment = bounded(value.dock.alignment, 0f, 1f, defaults.dock.alignment),
                    iconScale = bounded(value.dock.iconScale, .25f, 3f, defaults.dock.iconScale),
                    maxItems = value.dock.maxItems.coerceIn(1, 32), padding = value.dock.padding.coerceIn(0, 256)),
                navigation = value.navigation.copy(
                    enabledScreens = value.navigation.enabledScreens.toSet() + Screen.HOME,
                    fingers = value.navigation.fingers.coerceIn(1, 2),
                    preset = text(value.navigation.preset, 64).ifEmpty { defaults.navigation.preset }),
                search = value.search.copy(grid = grid(value.search.grid),
                    barText = text(value.search.barText, 128), targetOrder = (value.search.targetOrder + SearchTarget.entries).distinct(),
                    customUrl = SearchEngine.validCustomUrl(value.search.customUrl) ?: SearchEngine.DEFAULT_CUSTOM_URL),
                desktop = value.desktop.copy(columns = value.desktop.columns.coerceIn(1, MAX_COLUMNS),
                    rows = value.desktop.rows.coerceIn(1, MAX_ROWS), padding = value.desktop.padding.coerceIn(0, 256),
                    cornerRadius = value.desktop.cornerRadius.coerceIn(0, 96), pageCount = value.desktop.pageCount.coerceIn(1, 16), grid = grid(value.desktop.grid)),
                favorites = value.favorites.copy(maxItems = value.favorites.maxItems.coerceIn(1, 512),
                    grid = grid(value.favorites.grid), itemsPerRow = value.favorites.itemsPerRow.coerceIn(0, 10), excluded = keys(value.favorites.excluded, MAX_APPS).toSet()),
                appearance = value.appearance.copy(dim = bounded(value.appearance.dim, 0f, 1f, defaults.appearance.dim),
                    accent = color(value.appearance.accent, defaults.appearance.accent),
                    backgroundColor = color(value.appearance.backgroundColor, defaults.appearance.backgroundColor),
                    statusColor = color(value.appearance.statusColor, "#00000000"), navigationColor = color(value.appearance.navigationColor, "#00000000"),
                    iconPack = value.appearance.iconPack.trim().takeIf { it.length <= 255 && PACKAGE.matches(it) }.orEmpty()),
                badges = value.badges.copy(color = color(value.badges.color, defaults.badges.color),
                    scale = bounded(value.badges.scale, .25f, 3f, defaults.badges.scale)),
                folders = value.folders.copy(color = color(value.folders.color, defaults.folders.color),
                    opacity = bounded(value.folders.opacity, 0f, 1f, defaults.folders.opacity),
                    iconScale = bounded(value.folders.iconScale, .25f, 3f, defaults.folders.iconScale),
                    iconColor = color(value.folders.iconColor, "#A0FFFFFF"), textScale = bounded(value.folders.textScale, .25f, 3f, 1f)),
                behavior = value.behavior.copy(
                    wallpaperScroll = text(value.behavior.wallpaperScroll, 64).ifEmpty { defaults.behavior.wallpaperScroll },
                    horizontalTransition = text(value.behavior.horizontalTransition, 64)
                        .ifEmpty { defaults.behavior.horizontalTransition },
                    verticalTransition = text(value.behavior.verticalTransition, 64)
                        .ifEmpty { defaults.behavior.verticalTransition }),
                gestures = value.gestures.mapValues { (_, binding) ->
                    binding.copy(target = key(binding.target, 1_024).orEmpty(),
                        shortcutId = key(binding.shortcutId, 1_024).orEmpty())
                }.toMap()
            )
        }

        fun grid(value: GridPrefs): GridPrefs = value.copy(
            columns = value.columns.coerceIn(1, MAX_COLUMNS), rows = value.rows.coerceIn(1, MAX_ROWS),
            iconScale = bounded(value.iconScale, .25f, 3f, 1f), padding = value.padding.coerceIn(0, 256),
            spacing = value.spacing.coerceIn(0, 256), textScale = bounded(value.textScale, .25f, 3f, 1f),
            intermediateSteps = value.intermediateSteps.coerceIn(0, 4), borderPadding = value.borderPadding.takeIf { it in setOf("Default", "None", "Small", "Large") } ?: "Default"
        )

        fun bounded(value: Float, min: Float, max: Float, fallback: Float): Float =
            if (value.isFinite()) value.coerceIn(min, max) else fallback

        fun color(value: String, fallback: String): String = value.trim()
            .takeIf { HEX_COLOR.matches(it) }?.uppercase(Locale.ROOT) ?: fallback

        fun text(value: String, maxLength: Int): String =
            value.filterNot { it.isISOControl() }.trim().take(maxLength)

        fun key(value: String, maxLength: Int = 512): String? = value.trim().takeIf { candidate ->
            candidate.isNotEmpty() && candidate.length <= maxLength && candidate.none { it.isISOControl() }
        }

        fun keys(values: Iterable<String>, limit: Int): List<String> =
            values.asSequence().mapNotNull { key(it) }.distinct().take(limit).toList()

        fun safeUri(value: String, contact: Boolean = false): String {
            val candidate = value.trim()
            if (candidate.isEmpty() || candidate.length > 2_048 ||
                candidate.any { it.isISOControl() || it == '\\' }
            ) return ""
            return try {
                val uri = URI(candidate)
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                val authority = uri.rawAuthority.orEmpty()
                if (uri.isOpaque || uri.rawUserInfo != null || uri.port != -1 || !AUTHORITY.matches(authority)) return ""
                if (listOf(uri.path, uri.query, uri.fragment).any { part ->
                        part?.any { it.isISOControl() || it == '\\' } == true
                    }) return ""
                if (contact) {
                    if (scheme != "content" || authority != "com.android.contacts" || uri.rawFragment != null) return ""
                } else if (scheme !in setOf("content", "android.resource")) return ""
                scheme + candidate.substring(candidate.indexOf(':'))
            } catch (_: java.net.URISyntaxException) {
                ""
            } catch (_: IllegalArgumentException) {
                ""
            }
        }
    }
}
