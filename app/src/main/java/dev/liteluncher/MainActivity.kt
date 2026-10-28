package dev.liteluncher

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import dev.liteluncher.data.LauncherState
import dev.liteluncher.data.LauncherBackup
import dev.liteluncher.data.LayoutArea
import dev.liteluncher.data.PinnedShortcut
import dev.liteluncher.data.layoutSpace
import dev.liteluncher.data.desktopObstacles
import dev.liteluncher.platform.SystemActions
import dev.liteluncher.platform.WidgetController
import dev.liteluncher.platform.WidgetGrid
import dev.liteluncher.ui.LauncherApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: LauncherViewModel by viewModels()
    private lateinit var widgets: WidgetController
    private var homeSignal by mutableIntStateOf(0)
    private var permissionSignal by mutableIntStateOf(0)
    private var voiceQuery by androidx.compose.runtime.mutableStateOf("")

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionSignal++
        if (!it) toast("Contact access is off. You can enable it in Android app settings.")
    }
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.viewModelScope.launch {
            var missingIcons = 0
            val appContext = applicationContext
            runCatching {
                val json = model.store.exportJson()
                withContext(Dispatchers.IO + NonCancellable) {
                    val portable = LauncherBackup.portableIcons(appContext, json) { missingIcons = it }
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(portable) }
                        ?: error("Cannot open the selected document")
                }
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, if (missingIcons == 0) "Launcher backup saved" else "Backup saved; $missingIcons unavailable custom icons omitted", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, it.message ?: "Backup failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.viewModelScope.launch {
            val appContext = applicationContext
            runCatching {
                val json = withContext(Dispatchers.IO + NonCancellable) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        LauncherBackup.read(stream)
                    } ?: error("Cannot read the selected document")
                }
                model.store.importJson(json).getOrThrow()
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Layout restored. Tap saved widget placeholders to reconnect widgets.", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, it.message ?: "This backup could not be restored", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private val voiceSearch = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            voiceQuery = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgets = WidgetController(this, onAdded = { item, completed ->
            model.viewModelScope.launch {
                // Resolve the resource handoff even if the ViewModel is cleared mid-save.
                withContext(NonCancellable) {
                    val saved = runCatching {
                        model.store.updateAndAwait { state ->
                            require(!state.prefs.desktop.preventChanges) { "Desktop is locked" }
                            val columns = state.prefs.desktop.columns
                            val rows = state.prefs.desktop.rows
                            val width = item.columns
                            val height = item.rows
                            require(width in 1..columns && height in 1..rows) { "This widget no longer fits the desktop grid" }
                            val others = state.widgets.filterNot { it.id == item.id }
                            val occupied = others + state.desktopObstacles(item.restoreKey)
                            require(item.page in 0 until state.prefs.desktop.pageCount) { "This desktop page is no longer available" }
                            val placed = if (item.restoreKey.isNotBlank()) item.takeIf { WidgetGrid.fits(it, occupied, columns, rows) }
                                else WidgetGrid.place(item, occupied, columns, rows)
                            requireNotNull(placed) { "The widget canvas is full. Resize or remove a widget first." }
                            state.copy(widgets = others + placed,
                                pendingWidgets = state.pendingWidgets.filterNot { it.key == item.restoreKey })
                        }.getOrThrow()
                    }
                    saved.onFailure {
                        toast(it.message ?: "The widget could not be saved")
                    }
                    completed(saved.isSuccess)
                }
            }
        }, onRollbackAdded = { item ->
            model.viewModelScope.launch {
                withContext(NonCancellable) {
                    model.store.updateAndAwait { state ->
                        state.copy(widgets = state.widgets.filterNot { it.id == item.id },
                            pendingWidgets = state.pendingWidgets.filterNot { it.key == item.restoreKey })
                    }
                }
            }
        })
        setContent {
            LauncherApp(
                model = model, widgets = widgets, homeSignal = homeSignal, permissionSignal = permissionSignal,
                voiceQuery = voiceQuery, onConsumeVoice = { voiceQuery = "" },
                onVoice = {
                    runCatching {
                        voiceSearch.launch(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search your launcher")
                        })
                    }.onFailure { toast("Install a voice recognition service to use voice search") }
                },
                onContacts = { contactsPermission.launch(Manifest.permission.READ_CONTACTS) },
                onExport = { exportBackup.launch("lite-luncher-backup.json") },
                onImport = { importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "application/zip", "application/x-zip-compressed")) },
                onDefaultHome = { SystemActions.defaultHome(this) }
            )
        }
        handlePin(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handlePin(intent)) homeSignal++
    }

    private fun handlePin(intent: Intent): Boolean = widgets.acceptPin(intent, shortcutError = { shortcut ->
        if (!model.store.ready.value) "Launcher is still loading. Try adding the shortcut again."
        else if (model.store.error.value != null) "Launcher preferences are unavailable. Try adding the shortcut after storage recovers."
        else shortcutPinError(model.store.state.value, shortcut)
    }) { shortcut ->
        model.viewModelScope.launch {
            model.store.updateAndAwait { state ->
                val failure = shortcutPinError(state, shortcut)
                require(failure == null) { failure.orEmpty() }
                val existing = state.shortcuts.find {
                    it.id == shortcut.id && it.packageName == shortcut.packageName && it.userSerial == shortcut.userSerial && it.container == "home"
                }
                state.copy(shortcuts = state.shortcuts.filterNot {
                    it.id == shortcut.id && it.packageName == shortcut.packageName && it.userSerial == shortcut.userSerial && it.container == "home"
                } + (existing ?: shortcut).copy(container = "home"))
            }.onFailure { toast(it.message ?: "The shortcut could not be saved") }
        }
    }

    private fun shortcutPinError(state: LauncherState, shortcut: PinnedShortcut): String? {
        if (state.prefs.home.preventChanges) return "Home is locked"
        val alreadyPinned = state.shortcuts.any {
            it.id == shortcut.id && it.packageName == shortcut.packageName && it.userSerial == shortcut.userSerial && it.container == "home"
        }
        return if (!alreadyPinned && state.layoutSpace(LayoutArea.HOME) == 0)
            "Home is full. Remove an entry or increase the grid size." else null
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (::widgets.isInitialized && widgets.onActivityResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onStart() {
        super.onStart()
        widgets.start()
    }
    override fun onResume() {
        super.onResume()
        model.apps.refresh()
        permissionSignal++
    }
    override fun onStop() {
        widgets.stop()
        super.onStop()
    }
    override fun onDestroy() {
        widgets.close()
        super.onDestroy()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
