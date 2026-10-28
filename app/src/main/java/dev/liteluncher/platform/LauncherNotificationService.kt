package dev.liteluncher.platform

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Rebuilds counts from active records, so updates and reconnects never accumulate duplicates. */
class LauncherNotificationService : NotificationListenerService() {
    private val records = linkedMapOf<String, StatusBarNotification>()
    private var receiverRegistered = false
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            rebuild()
        }
    }

    override fun onCreate() {
        super.onCreate()
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(this, profileReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_USER_UNLOCKED)
            }, ContextCompat.RECEIVER_EXPORTED)
            true
        }.getOrDefault(false)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        rebuild()
    }

    private fun rebuild() {
        synchronized(records) {
            records.clear()
            runCatching { activeNotifications }.getOrNull().orEmpty().forEach { records[it.key] = it }
            publish(runCatching { currentRanking }.getOrNull())
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        synchronized(records) {
            records[sbn.key] = sbn
            publish(rankingMap)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        synchronized(records) {
            records.remove(sbn.key)
            publish(rankingMap)
        }
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        synchronized(records) { publish(rankingMap) }
    }

    private fun publish(rankingMap: RankingMap?) {
        val users = getSystemService(UserManager::class.java) ?: run {
            mutableCounts.value = emptyMap()
            return
        }
        val allowed = runCatching {
            users.userProfiles.filter { user ->
                runCatching { users.isUserUnlocked(user) && !users.isQuietModeEnabled(user) }.getOrDefault(false)
            }.toSet()
        }.getOrDefault(setOf(Process.myUserHandle()))
        val rank = Ranking()
        val visible = records.values.asSequence().filter { sbn ->
            if (sbn.user !in allowed) return@filter false
            val badgeAllowed = rankingMap == null || !rankingMap.getRanking(sbn.key, rank) || rank.canShowBadge()
            val flags = sbn.notification.flags
            badgeAllowed && flags and Notification.FLAG_ONGOING_EVENT == 0 &&
                flags and Notification.FLAG_NO_CLEAR == 0
        }
        val result = mutableMapOf<String, Int>()
        val serials = mutableMapOf<UserHandle, Long>()
        visible.groupBy { it.packageName to it.user }.forEach { (identity, notifications) ->
            // Group summaries count only when no child is present. A summary's number is
            // otherwise commonly a duplicate of its children's unread counts.
            val total = notifications.groupBy { it.groupKey }.values.sumOf { group ->
                val children = group.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                val counted = children.ifEmpty { group.take(1) }
                counted.sumOf { it.notification.number.coerceAtLeast(1).coerceAtMost(9999) }
            }.coerceAtMost(9999)
            if (total > 0) {
                val (packageName, user) = identity
                val serial = serials.getOrPut(user) {
                    runCatching { users.getSerialNumberForUser(user) }.getOrDefault(-1L)
                }
                if (serial >= 0) result[badgeKey(packageName, serial)] = total
                // Bare package entries refer only to the current user; work badges stay separate.
                if (user == Process.myUserHandle()) result[packageName] = total
            }
        }
        mutableCounts.value = result.toMap()
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        clearCounts()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        if (receiverRegistered) runCatching { unregisterReceiver(profileReceiver) }
        clearCounts()
        super.onDestroy()
    }

    private fun clearCounts() {
        synchronized(records) { records.clear(); mutableCounts.value = emptyMap() }
    }

    companion object {
        private var connected: LauncherNotificationService? = null
        fun refresh(context: Context) {
            val service = connected
            if (service != null) service.rebuild()
            else NotificationListenerService.requestRebind(android.content.ComponentName(context, LauncherNotificationService::class.java))
        }
        private val mutableCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
        val counts: StateFlow<Map<String, Int>> = mutableCounts.asStateFlow()
        fun badgeKey(packageName: String, userSerial: Long): String = "$packageName|$userSerial"
    }
}
