package dev.liteluncher.platform

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.text.Collator

data class AppEntry(
    val key: String,
    val packageName: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle
) {
    val badgeKey: String get() = "$packageName|${key.substringAfterLast('|')}"
}

data class ContactEntry(val id: String, val name: String, val lookupUri: String)

data class NativeShortcut(
    val id: String,
    val packageName: String,
    val label: String,
    val user: UserHandle
)

/** Launcher identities are flattened component names followed by | and the user's serial. */
class AppsRepository(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val launcher = requireNotNull(this.context.getSystemService(LauncherApps::class.java))
    private val users = requireNotNull(this.context.getSystemService(UserManager::class.java))
    private val packages = this.context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableApps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = mutableApps.asStateFlow()
    val badges: StateFlow<Map<String, Int>> = LauncherNotificationService.counts
    private val refreshLock = Any()
    private var refreshGeneration = 0L
    @Volatile private var closed = false
    private var callbackRegistered = false
    private var receiverRegistered = false
    private val iconMappings = mutableMapOf<String, Map<String, String>>()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = invalidate()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = invalidate()
        override fun onPackageChanged(packageName: String, user: UserHandle) = invalidate()
        override fun onPackagesAvailable(packages: Array<out String>, user: UserHandle, replacing: Boolean) = invalidate()
        override fun onPackagesUnavailable(packages: Array<out String>, user: UserHandle, replacing: Boolean) = invalidate()
        override fun onPackagesSuspended(packages: Array<out String>, user: UserHandle) = invalidate()
        override fun onPackagesUnsuspended(packages: Array<out String>, user: UserHandle) = invalidate()
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) = refresh()
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    init {
        callbackRegistered = runCatching {
            launcher.registerCallback(callback, Handler(Looper.getMainLooper()))
            true
        }.getOrDefault(false)
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(this.context, profileReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_USER_UNLOCKED)
            }, ContextCompat.RECEIVER_EXPORTED)
            true
        }.getOrDefault(false)
        refresh()
    }

    private fun invalidate() {
        synchronized(iconMappings) { iconMappings.clear() }
        refresh()
    }

    fun refresh() {
        val generation = synchronized(refreshLock) {
            if (closed) return
            ++refreshGeneration
        }
        scope.launch {
            val entries = accessibleProfiles().flatMap { user ->
                val serial = runCatching { users.getSerialNumberForUser(user) }.getOrDefault(-1L)
                if (serial < 0) emptyList() else runCatching {
                    launcher.getActivityList(null, user).mapNotNull { info ->
                        if (info.componentName.packageName == context.packageName) null else AppEntry(
                            key(info.componentName, serial), info.componentName.packageName,
                            info.label?.toString().orEmpty().ifBlank { info.componentName.packageName },
                            info.componentName, user
                        )
                    }
                }.getOrDefault(emptyList())
            }
            val collator = Collator.getInstance()
            val sorted = entries.distinctBy { it.key }.sortedWith { a, b ->
                collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.key.compareTo(b.key)
            }
            synchronized(refreshLock) {
                if (!closed && generation == refreshGeneration) mutableApps.value = sorted
            }
        }
    }

    private fun accessibleProfiles(): List<UserHandle> = runCatching {
        users.userProfiles.filter(::profileAvailable)
    }.getOrDefault(emptyList())

    private fun profileAvailable(user: UserHandle): Boolean = runCatching {
        users.userProfiles.contains(user) && users.isUserUnlocked(user) && !users.isQuietModeEnabled(user)
    }.getOrDefault(false)

    fun launch(app: AppEntry): Boolean {
        if (!profileAvailable(app.user)) {
            SystemActions.error(context, "Unlock this app's profile first.")
            return false
        }
        return runCatching {
            launcher.startMainActivity(app.component, app.user, null, null)
            true
        }.getOrElse {
            SystemActions.error(context, "This app is unavailable or its profile is locked.")
            false
        }
    }

    fun appInfo(app: AppEntry) {
        if (!profileAvailable(app.user)) {
            SystemActions.error(context, "Unlock this app's profile first.")
            return
        }
        if (runCatching {
            launcher.startAppDetailsActivity(app.component, app.user, null, null)
        }.isSuccess) return
        // A personal-profile settings fallback must never be used for another user.
        if (app.user == Process.myUserHandle()) {
            SystemActions.open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null)))
        } else SystemActions.error(context, "App information is unavailable for this profile.")
    }

    fun shortcuts(app: AppEntry): List<NativeShortcut> {
        if (!profileAvailable(app.user)) return emptyList()
        return runCatching {
            if (!launcher.hasShortcutHostPermission()) return emptyList()
            launcher.getShortcuts(LauncherApps.ShortcutQuery().apply {
                setPackage(app.packageName)
                setActivity(app.component)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }, app.user).orEmpty().filter { it.isEnabled }.sortedBy { it.rank }.map {
                NativeShortcut(it.id, it.`package`,
                    it.shortLabel?.toString() ?: it.longLabel?.toString() ?: it.id, it.userHandle)
            }
        }.getOrDefault(emptyList())
    }

    fun launchShortcut(shortcut: NativeShortcut): Boolean {
        if (!profileAvailable(shortcut.user)) {
            SystemActions.error(context, "Unlock this shortcut's profile first.")
            return false
        }
        return runCatching {
            launcher.startShortcut(shortcut.packageName, shortcut.id, null, null, shortcut.user)
            true
        }.getOrElse {
            SystemActions.error(context, "This shortcut is no longer available.")
            false
        }
    }

    /** Requires the caller to request READ_CONTACTS; queries stay in the current profile. */
    fun contacts(query: String = ""): List<ContactEntry> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val uri = if (query.isBlank()) ContactsContract.Contacts.CONTENT_URI else Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        return runCatching {
            val result = mutableListOf<ContactEntry>()
            context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.LOOKUP_KEY),
                null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty().ifBlank { "Unnamed contact" }
                    val lookup = cursor.getString(2) ?: continue
                    val lookupUri = ContactsContract.Contacts.getLookupUri(id, lookup) ?: continue
                    result += ContactEntry(id.toString(), name, lookupUri.toString())
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    /** Foreground milliseconds during the last seven days. Android only exposes this user's usage. */
    fun usage(): Map<String, Long> = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return@runCatching emptyMap<String, Long>()
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) return@runCatching emptyMap<String, Long>()
        val now = System.currentTimeMillis()
        val usageManager = context.getSystemService(UsageStatsManager::class.java) ?: return@runCatching emptyMap<String, Long>()
        val totals = usageManager.queryAndAggregateUsageStats(now - 7L * 24 * 60 * 60 * 1000, now)
        apps.value.filter { it.user == Process.myUserHandle() }.associate { app ->
            app.key to (totals[app.packageName]?.totalTimeInForeground ?: 0L)
        }
    }.getOrDefault(emptyMap())

    fun icon(app: AppEntry, iconPack: String = ""): Drawable? {
        if (!profileAvailable(app.user)) return null
        if (iconPack.isNotBlank()) {
            val themed = runCatching {
                val resources = packages.getResourcesForApplication(iconPack)
                val mapping = synchronized(iconMappings) {
                    iconMappings.getOrPut(iconPack) { readIconMappings(iconPack) }
                }
                val name = mapping[app.component.flattenToString()]
                    ?: mapping[app.component.flattenToShortString()]
                val id = name?.let { resources.getIdentifier(it.substringAfterLast('/'), "drawable", iconPack) } ?: 0
                if (id == 0) null else packages.getUserBadgedIcon(resources.getDrawable(id, null), app.user)
            }.getOrNull()
            if (themed != null) return themed
        }
        return runCatching {
            launcher.getActivityList(app.packageName, app.user)
                .firstOrNull { it.componentName == app.component }
                ?.getBadgedIcon(context.resources.displayMetrics.densityDpi)
        }.getOrNull()
    }

    fun iconPacks(): List<Pair<String, String>> = listOf("org.adw.launcher.THEMES", "com.gau.go.launcherex.theme")
        .flatMap { action -> runCatching {
            @Suppress("DEPRECATION")
            packages.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
                .map { it.activityInfo.packageName to it.loadLabel(packages).toString() }
        }.getOrDefault(emptyList()) }.distinctBy { it.first }.sortedBy { it.second.lowercase() }

    private fun readIconMappings(packageName: String): Map<String, String> {
        val resources = packages.getResourcesForApplication(packageName)
        val xmlId = resources.getIdentifier("appfilter", "xml", packageName)
        fun parse(parser: XmlPullParser): Map<String, String> {
            val result = mutableMapOf<String, String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                    val raw = parser.getAttributeValue(null, "component").orEmpty()
                    val drawable = parser.getAttributeValue(null, "drawable").orEmpty()
                    val component = ComponentName.unflattenFromString(raw.removePrefix("ComponentInfo{").removeSuffix("}"))
                    if (component != null && drawable.isNotBlank()) {
                        result[component.flattenToString()] = drawable
                        result[component.flattenToShortString()] = drawable
                    }
                }
                parser.next()
            }
            return result
        }
        return if (xmlId != 0) resources.getXml(xmlId).use(::parse) else {
            resources.assets.open("appfilter.xml").use { stream ->
                val parser = android.util.Xml.newPullParser()
                parser.setInput(stream, "UTF-8")
                parse(parser)
            }
        }
    }

    override fun close() {
        synchronized(refreshLock) { closed = true; ++refreshGeneration }
        if (callbackRegistered) runCatching { launcher.unregisterCallback(callback) }
        if (receiverRegistered) runCatching { context.unregisterReceiver(profileReceiver) }
        synchronized(iconMappings) { iconMappings.clear() }
        scope.cancel()
    }

    companion object {
        fun key(component: ComponentName, userSerial: Long): String = "${component.flattenToString()}|$userSerial"
    }
}
