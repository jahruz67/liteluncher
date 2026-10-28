package dev.liteluncher.platform

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast

object SystemActions {
    fun notifications(context: Context) {
        val service = context.getSystemService("statusbar")
        if (service == null || runCatching { service.javaClass.getMethod("expandNotificationsPanel").invoke(service) }.isFailure)
            error(context, "Android does not allow this launcher to expand notifications. Swipe down from the status bar.")
    }
    fun defaultHome(activity: Activity) {
        if (!tryOpen(activity, Intent(Settings.ACTION_HOME_SETTINGS))) {
            fallbackSettings(activity, "Default home settings are unavailable.")
        }
    }

    fun wallpaper(activity: Activity) {
        if (tryOpen(activity, Intent(Intent.ACTION_SET_WALLPAPER))) return
        fallbackSettings(activity, "Wallpaper settings are unavailable.")
    }

    fun usageAccess(context: Context) {
        if (tryOpen(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))) return
        if (tryOpen(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))) return
        fallbackSettings(context, "Usage access settings are unavailable.")
    }

    fun notificationAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, LauncherNotificationService::class.java).flattenToString())
            if (tryOpen(context, intent)) return
        }
        if (tryOpen(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return
        fallbackSettings(context, "Notification access settings are unavailable.")
    }

    fun open(context: Context, intent: Intent): Boolean {
        if (tryOpen(context, intent)) return true
        error(context, "No available app can open this action.")
        return false
    }

    fun openLauncherApp(context: Context, key: String): Boolean = runCatching {
        val component = ComponentName.unflattenFromString(key.substringBeforeLast('|')) ?: return@runCatching false
        val serial = key.substringAfterLast('|').toLongOrNull()?.takeIf { it >= 0 } ?: return@runCatching false
        val users = context.getSystemService(UserManager::class.java) ?: return@runCatching false
        val user = users.getUserForSerialNumber(serial) ?: return@runCatching false
        if (user !in users.userProfiles || !users.isUserUnlocked(user) || users.isQuietModeEnabled(user)) {
            return@runCatching false
        }
        val launcher = context.getSystemService(LauncherApps::class.java) ?: return@runCatching false
        launcher.startMainActivity(component, user, null, null)
        true
    }.getOrDefault(false)

    private fun tryOpen(context: Context, intent: Intent): Boolean = runCatching {
        val launch = Intent(intent)
        if (context !is Activity) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        true
    }.getOrDefault(false)

    private fun fallbackSettings(context: Context, message: String) {
        error(context, message)
        if (!tryOpen(context, Intent(Settings.ACTION_SETTINGS))) error(context, "System settings could not be opened.")
    }

    internal fun error(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
