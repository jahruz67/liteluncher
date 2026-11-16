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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Rebuilds counts from active records, so updates and reconnects never accumulate duplicates. */
class LauncherNotificationService : NotificationListenerService() {
    private val records = linkedMapOf<String, StatusBarNotification>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val workLock = Any()
    private var listenerConnected = false
    private var generation = 0L
    private var pendingChanges = mutableMapOf<String, StatusBarNotification?>()
    private var pendingRanking: RankingMap? = null
    private var rankingChanged = false
    private var rebuildRequested = false
    private var receiverRegistered = false

    private data class Batch(
        val changes: Map<String, StatusBarNotification?>, val ranking: RankingMap?,
        val rankingChanged: Boolean, val rebuild: Boolean, val generation: Long
    )
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            rebuild()
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch { processRequests() }
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(this, profileReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
                addAction(Intent.ACTION_USER_UNLOCKED)
            }, ContextCompat.RECEIVER_EXPORTED)
            true
        }.getOrDefault(false)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        synchronized(workLock) { listenerConnected = true; generation += 1 }
        connected = this
        rebuild()
    }

    private fun rebuild() {
        synchronized(workLock) {
            if (!listenerConnected) return
            pendingChanges = mutableMapOf()
            pendingRanking = null
            rankingChanged = false
            rebuildRequested = true
            requests.trySend(Unit)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        enqueueChange(sbn, false, rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn == null) return
        enqueueChange(sbn, true, rankingMap)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        enqueueChange(null, false, rankingMap)
    }

    private fun enqueueChange(sbn: StatusBarNotification?, removed: Boolean, rankingMap: RankingMap?) {
        synchronized(workLock) {
            if (!listenerConnected) return
            if (sbn != null) pendingChanges[sbn.key] = if (removed) null else sbn
            pendingRanking = rankingMap
            rankingChanged = true
            requests.trySend(Unit)
        }
    }

    private suspend fun processRequests() {
        var appliedGeneration = -1L
        var ranking: RankingMap? = null
        var profiles: Map<UserHandle, Long>? = null
        for (request in requests) {
            val batch = synchronized(workLock) {
                if (!listenerConnected) null else Batch(pendingChanges, pendingRanking,
                    rankingChanged, rebuildRequested, generation).also {
                    pendingChanges = mutableMapOf()
                    rankingChanged = false
                    rebuildRequested = false
                }
            }
            if (batch == null) {
                records.clear()
                profiles = null
                continue
            }
            if (batch.changes.isEmpty() && !batch.rankingChanged && !batch.rebuild &&
                appliedGeneration == batch.generation) continue
            if (appliedGeneration != batch.generation) {
                records.clear()
                profiles = null
                ranking = null
                appliedGeneration = batch.generation
            }
            if (batch.rebuild) {
                records.clear()
                runCatching { activeNotifications }.getOrNull().orEmpty().forEach { records[it.key] = it }
                ranking = runCatching { currentRanking }.getOrNull()
                profiles = null
            }
            batch.changes.forEach { (key, notification) ->
                if (notification == null) records.remove(key) else records[key] = notification
            }
            if (batch.rankingChanged) ranking = batch.ranking
            val allowed = profiles ?: loadProfiles().also { profiles = it }
            val result = countNotifications(ranking, allowed)
            synchronized(workLock) {
                if (listenerConnected && generation == batch.generation) mutableCounts.value = result
            }
        }
    }

    private fun loadProfiles(): Map<UserHandle, Long> {
        val users = getSystemService(UserManager::class.java) ?: return emptyMap()
        val allowed = runCatching {
            users.userProfiles.filter { user ->
                runCatching { users.isUserUnlocked(user) && !users.isQuietModeEnabled(user) }.getOrDefault(false)
            }.toSet()
        }.getOrDefault(setOf(Process.myUserHandle()))
        return allowed.associateWith { user ->
            runCatching { users.getSerialNumberForUser(user) }.getOrDefault(-1L)
        }
    }

    private fun countNotifications(rankingMap: RankingMap?, profiles: Map<UserHandle, Long>): Map<String, Int> {
        val rank = Ranking()
        val visible = records.values.asSequence().filter { sbn ->
            if (sbn.user !in profiles) return@filter false
            val badgeAllowed = rankingMap == null || !rankingMap.getRanking(sbn.key, rank) || rank.canShowBadge()
            val flags = sbn.notification.flags
            badgeAllowed && flags and Notification.FLAG_ONGOING_EVENT == 0 &&
                flags and Notification.FLAG_NO_CLEAR == 0
        }
        val result = mutableMapOf<String, Int>()
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
                val serial = profiles.getValue(user)
                if (serial >= 0) result[badgeKey(packageName, serial)] = total
                // Bare package entries refer only to the current user; work badges stay separate.
                if (user == Process.myUserHandle()) result[packageName] = total
            }
        }
        return result
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
        requests.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun clearCounts() {
        synchronized(workLock) {
            listenerConnected = false
            generation += 1
            pendingChanges = mutableMapOf()
            pendingRanking = null
            rankingChanged = false
            rebuildRequested = false
            mutableCounts.value = emptyMap()
            requests.trySend(Unit)
        }
    }

    companion object {
        @Volatile private var connected: LauncherNotificationService? = null
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
