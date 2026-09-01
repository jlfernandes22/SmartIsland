/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.agupta07505.smartisland.BuildConfig
import com.agupta07505.smartisland.R
import com.agupta07505.smartisland.model.IslandNotification
import com.agupta07505.smartisland.model.IslandMode
import java.util.concurrent.ConcurrentHashMap

/**
 * LOCK-SCREEN MIRROR for island-only notifications.
 *
 * WHY THIS EXISTS: with "hide from notification shade" on (island-only mode),
 * island-bound notifications are CANCELLED from the system the moment they
 * arrive. A cancelled notification can never appear on the system lock
 * screen, and the overlay window renders BELOW the keyguard on modern
 * Android — so any message that arrived while the phone was unlocked was
 * invisible EVERYWHERE once the user locked it ("notifications still do not
 * appear in the lock screen").
 *
 * Deferring the cancel (the previous strategy) only helps notifications that
 * arrive WHILE the keyguard is up; it can never resurface ones that were
 * already cancelled before lock. This notifier closes that gap: when the
 * screen turns off, every unread island notification that lost its system
 * copy is re-posted as a SILENT SmartIsland notification, so the system lock
 * screen presents it natively. On unlock the mirrors are cancelled and the
 * island-only model is restored (the deferred system copies are swept then
 * too — see SmartIslandNotificationListenerService).
 *
 * Identity: mirrors are posted under SmartIsland's own package (a listener
 * cannot re-post another app's notification). The original app's launcher
 * intent is wrapped as the content intent, so tapping a mirror opens the
 * messaging app directly. Mirrors are silent (IMPORTANCE_LOW channel) and
 * never badge, so they add nothing on top of the original's own alert.
 *
 * Bookkeeping: each mirror is tagged with the ORIGINAL notification key
 * (tag + fixed id = unique slot, so re-posting updates in place and can
 * never duplicate). The live set is tracked in memory; a rebind sweep in the
 * listener service additionally enumerates stale mirrors left behind if the
 * process died while the screen was locked (notifications survive process
 * death; the in-memory set does not).
 */
class LockScreenMirrorNotifier(private val context: Context) {

    companion object {
        private const val TAG = "LockScreenMirror"
        const val MIRROR_TAG_PREFIX = "smartisland_lockscreen_mirror|"
        const val MIRROR_NOTIFICATION_ID = 424242
        const val MIRROR_CHANNEL_ID = "smartisland_lockscreen_mirror"

        /**
         * Island modes that are eligible for mirroring. Modes whose system
         * copy is deliberately KEPT in the shade (Music, Navigation, Timer,
         * Stopwatch — see shouldBeIslandOnly) and device-state modes driven
         * by the system itself (IncomingCall, Bluetooth, Flashlight, …) are
         * excluded: the lock screen already shows their real notifications.
         */
        val MIRROR_MODES: Set<IslandMode> = setOf(
            IslandMode.Notification,
            IslandMode.DownloadUpload,
            IslandMode.ScreenRecording,
            IslandMode.LiveActivity
        )
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun logD(message: String) {
        runCatching { if (Log.isLoggable(TAG, Log.DEBUG) || BuildConfig.DEBUG) Log.d(TAG, message) }
    }

    private fun logE(message: String, error: Throwable) {
        runCatching { if (BuildConfig.DEBUG) Log.e(TAG, message, error) }
    }

    /** Original key -> last mirror post time (bounded, TTL-swept). */
    private val mirrorKeys = ConcurrentHashMap<String, Long>()

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Local capture: smart casts on member properties do not survive
        // lambda boundaries (runCatching below).
        val nm = notificationManager ?: return
        runCatching {
            val channel = NotificationChannel(
                MIRROR_CHANNEL_ID,
                context.getString(R.string.mirror_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.mirror_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }.onFailure {
            logE("Failed to create mirror channel", it)
        }
    }

    /**
     * (Re-)posts mirrors for the given island notifications. Each post
     * updates its slot in place — a pocket on/off cycle never duplicates.
     * Returns the set of keys that now have a live mirror.
     */
    fun postMirrors(notifications: List<IslandNotification>): Set<String> {
        // Local capture (see ensureChannel) — used inside forEach below.
        val nm = notificationManager ?: return emptySet()
        ensureChannel()
        val now = System.currentTimeMillis()
        notifications.forEach { island ->
            runCatching {
                nm.notify(
                    MIRROR_TAG_PREFIX + island.key,
                    MIRROR_NOTIFICATION_ID,
                    buildMirror(island)
                )
                mirrorKeys[island.key] = now
                logD("Mirror posted for " + island.key)
            }.onFailure {
                logE("Mirror post failed for " + island.key, it)
            }
        }
        return mirrorKeys.keys.toSet()
    }

    /** Cancels the mirror for one original key (explicit dismissal, dedupe). */
    fun cancelMirror(originalKey: String) {
        if (mirrorKeys.remove(originalKey) == null && notificationManager == null) return
        runCatching {
            notificationManager?.cancel(MIRROR_TAG_PREFIX + originalKey, MIRROR_NOTIFICATION_ID)
        }
    }

    /** Cancels every mirror this process posted (unlock sweep). */
    fun cancelAllMirrors() {
        if (mirrorKeys.isEmpty() && notificationManager == null) return
        val keys = mirrorKeys.keys.toList()
        mirrorKeys.clear()
        keys.forEach { key ->
            runCatching {
                notificationManager?.cancel(MIRROR_TAG_PREFIX + key, MIRROR_NOTIFICATION_ID)
            }
        }
        if (keys.isNotEmpty()) {
            logD("Cancelled " + keys.size + " lock-screen mirror(s) at unlock")
        }
    }

    private fun buildMirror(island: IslandNotification): Notification {
        val title = island.title.ifBlank { island.appName }
        val text = island.text.orEmpty()
        val contentIntent = context.packageManager.getLaunchIntentForPackage(island.packageName)
            ?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                PendingIntent.getActivity(
                    context,
                    island.packageName.hashCode(),
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
        val builder = NotificationCompat.Builder(context, MIRROR_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_smart_island)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(text)
                    .setBigContentTitle(title)
            )
            // The posting package is SmartIsland; the sub-text carries the
            // source app so the lock screen still reads "WhatsApp · John".
            .setSubText(island.appName)
            .setShowWhen(true)
            .setWhen(if (island.timeMillis > 0) island.timeMillis else System.currentTimeMillis())
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(categoryFor(island))
        (island.largeIcon ?: island.icon)?.let { builder.setLargeIcon(it) }
        contentIntent?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun categoryFor(island: IslandNotification): String? {
        island.category?.let { return it }
        return when (island.mode) {
            IslandMode.DownloadUpload, IslandMode.ScreenRecording ->
                NotificationCompat.CATEGORY_PROGRESS
            else -> NotificationCompat.CATEGORY_MESSAGE
        }
    }
}
