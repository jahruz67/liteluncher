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
import dev.liteluncher.data.Screen
import dev.liteluncher.platform.SystemActions
import dev.liteluncher.platform.WidgetController
import dev.liteluncher.ui.LauncherApp
import kotlinx.coroutines.Dispatchers
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
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val json = model.store.exportJson()
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                        ?: error("Cannot open the selected document")
                }
            }.onSuccess { toast("Launcher backup saved") }.onFailure { toast(it.message ?: "Backup failed") }
        }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var count = stream.read(buffer)
                        while (count >= 0) {
                            require(output.size() + count <= 1024 * 1024) { "Backup exceeds the 1 MiB limit" }
                            output.write(buffer, 0, count)
                            count = stream.read(buffer)
                        }
                        output.toString("UTF-8")
                    } ?: error("Cannot read the selected document")
                }
                model.store.importJson(json).getOrThrow()
            }.onSuccess { toast("Layout restored. Add device widgets again if needed.") }
                .onFailure { toast(it.message ?: "This backup could not be restored") }
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
        widgets = WidgetController(this) { item ->
            model.viewModelScope.launch {
                val saved = model.store.updateAndAwait { state ->
                    val columns = state.prefs.desktop.columns
                    val rows = state.prefs.desktop.rows
                    val width = item.columns
                    val height = item.rows
                    require(width in 1..columns && height in 1..rows) { "This widget no longer fits the desktop grid" }
                    val others = state.widgets.filterNot { it.id == item.id }
                    val free = (0..rows - height).asSequence().flatMap { row ->
                        (0..columns - width).asSequence().map { column -> column to row }
                    }.firstOrNull { (column, row) -> others.none {
                        column < it.column + it.columns && column + width > it.column && row < it.row + it.rows && row + height > it.row
                    } }
                    requireNotNull(free) { "The widget canvas is full. Resize or remove a widget first." }
                    state.copy(widgets = others + item.copy(column = free.first, row = free.second))
                }
                saved.onFailure {
                    widgets.remove(item.id)
                    toast(it.message ?: "The widget could not be saved")
                }
            }
        }
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
                onImport = { importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
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

    private fun handlePin(intent: Intent): Boolean = widgets.acceptPin(intent) { shortcut ->
        model.store.update { state -> state.copy(shortcuts = state.shortcuts.filterNot { it.id == shortcut.id && it.packageName == shortcut.packageName && it.userSerial == shortcut.userSerial } + shortcut) }
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
