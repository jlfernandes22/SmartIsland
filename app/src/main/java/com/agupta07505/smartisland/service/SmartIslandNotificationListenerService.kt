/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.service

import com.agupta07505.smartisland.util.CrashGuard
import com.agupta07505.smartisland.util.isCallEnded
import com.agupta07505.smartisland.util.isDownloadComplete
import com.agupta07505.smartisland.util.isScreenRecordingComplete
import com.agupta07505.smartisland.util.runCatchingLogged
import com.agupta07505.smartisland.util.runSuspendCatchingLogged
import com.agupta07505.smartisland.util.toIslandMode
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.agupta07505.smartisland.data.INotificationHistoryRepository
import com.agupta07505.smartisland.data.INotificationRepository
import com.agupta07505.smartisland.data.NotificationHistoryEntry
import com.agupta07505.smartisland.data.SmartIslandCommand
import com.agupta07505.smartisland.data.SmartIslandSettings
import com.agupta07505.smartisland.data.SmartIslandSettingsRepository
import com.agupta07505.smartisland.model.IslandMode
import com.agupta07505.smartisland.model.IslandNotification
import com.agupta07505.smartisland.model.IslandNotificationAction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@AndroidEntryPoint
class SmartIslandNotificationListenerService : NotificationListenerService() {
    // Keys we have canceled ourselves to make island-only. Keeps island copy alive.
    private val suppressedKeys = ConcurrentHashMap<String, Long>()
    // Keys the user EXPLICITLY discarded from the island (hold + swipe-up
    // dismiss, dismiss-all, action-button dismiss). Many apps keep UPDATING
    // their notifications after they are relevant to the island (media
    // progress ticks, countdown timers, download progress); every such
    // update re-posted the dismissed notification to the island — the user
    // saw it "come back because it wasn't discarded". While a key is
    // tombstoned, re-posts of it are swallowed (and their system copy
    // cancelled when shade-hiding is active). The TTL is rolling: each
    // swallowed update re-arms the tombstone, so an actively-updating
    // notification stays dismissed; a genuinely NEW post after the user has
    // moved on (TTL expired with no further updates) shows normally.
    private val userDismissedKeys = ConcurrentHashMap<String, Long>()
    // LOCK-SCREEN UNREAD DEFERRAL: with hideFromNotificationShade on,
    // island-bound notifications were cancelled from the system the moment
    // they arrived — including while the keyguard was up. A cancelled
    // notification never reaches the system's lock screen, and the overlay
    // window renders BELOW the keyguard, so a message that arrived while the
    // screen was off was invisible EVERYWHERE until unlock (the "unopened
    // notifications do not appear in the lock screen" report). While the
    // keyguard is showing — and the Unread-on-Lock-Screen setting is on — the
    // cancel is therefore DEFERRED: the system lock screen presents the
    // notification natively (with the app's own privacy), and the pending
    // cancel executes on ACTION_USER_PRESENT. The island keeps its copy
    // through that later listener-cancel via the suppression window armed
    // right before it fires.
    private val lockDeferredKeys = ConcurrentHashMap<String, Long>()
    // LOCK-SCREEN MIRRORS: deferred cancels only help notifications that
    // arrive WHILE the keyguard is up. Ones that arrived while unlocked were
    // already cancelled from the system — nothing could ever resurface them
    // on the lock screen. On screen-off, LockScreenMirrorNotifier re-posts
    // those unread island notifications as silent SmartIsland notifications
    // so the system lock screen presents them; unlock cancels the mirrors
    // and restores the island-only model.
    // ROUND-Y ROOT-CAUSE FIX: this was an eager field initializer
    // (`LockScreenMirrorNotifier(this)`), which ran during the service
    // CONSTRUCTOR — before the framework attached the base Context. The
    // notifier's own getSystemService initializer then hit a null base and
    // NPE'd on every bind (the crash loop). by lazy defers BOTH this
    // construction and any context-dependent work inside the notifier to
    // the first access — which happens in onCreate, safely post-attach.
    private val lockScreenMirror: LockScreenMirrorNotifier by lazy {
        LockScreenMirrorNotifier(this)
    }
    private var userPresentReceiver: BroadcastReceiver? = null
    @Volatile private var currentSettings = SmartIslandSettings.Default
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
        android.util.Log.e(TAG, "Unhandled notification-listener coroutine failure", error)
    }
    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)

    @Inject lateinit var repository: SmartIslandSettingsRepository
    @Inject lateinit var notificationRepository: INotificationRepository
    @Inject lateinit var historyRepository: INotificationHistoryRepository
    private var lastHistoryCleanupTime = 0L

    override fun onCreate() {
        // ROUND-X CRASH-LOOP BREAKER — ORDER IS EVERYTHING HERE.
        // The live device (OnePlus CPH2581, SDK 37) died with
        // "java.lang.RuntimeException: Unable to create service" — the
        // exception came out of super.onCreate() (Hilt member injection),
        // which sat BEFORE the safe-mode gate. The gate therefore never
        // ran, every system rebind re-crashed the whole process, and the
        // safe-mode screen only ever survived for ~1s. The gate now runs
        // BEFORE super.onCreate(): in safe mode this service executes NO
        // injection code at all, so whatever throws in the injector can
        // never again reach process-killing distance.
        if (runCatching { CrashGuard.isSafeMode(this) }.getOrDefault(false)) {
            android.util.Log.w(TAG, "Safe mode latched — notification listener stays down until the user exits it")
            // Best-effort SYSTEM-LEVEL loop breaker: ask NotificationManager
            // to stop rebinding this listener while safe mode is on. Without
            // it the system rebinds after every crash/timeout and re-enters
            // onCreate forever. Restored via requestRebind() when the user
            // exits safe mode in MainActivity.
            runCatching { requestUnbind() }
            stopSelf()
            return
        }
        // super.onCreate() performs the Hilt injection. An exception escaping
        // it is fatal for the whole process ("Unable to create service") —
        // catch it, persist the FULL root-cause chain as boundary evidence,
        // and degrade: the app and overlay stay up, the listener stays down.
        // Two boundary catches inside the window latch safe mode via
        // markCrash, which then also requestUnbinds at the gate above.
        try {
            super.onCreate()
        } catch (t: Throwable) {
            CrashGuard.recordBoundaryCrash(this, "listener-super-onCreate", t)
            android.util.Log.e(TAG, "Injection failed in listener onCreate — listener disabled, process survives", t)
            stopSelf()
            return
        }
        // Hilt injects all-or-nothing, but verify before use so a future
        // partial-injection path can never surface as a lateinit death.
        if (!::repository.isInitialized ||
            !::notificationRepository.isInitialized ||
            !::historyRepository.isInitialized
        ) {
            CrashGuard.recordBoundaryCrash(
                this,
                "listener-injection-incomplete",
                IllegalStateException("listener @Inject fields not initialized")
            )
            stopSelf()
            return
        }
        CrashGuard.recordHeartbeat(this, "listener-create")
        // These ran raw for years; one throwing system call in a create
        // path kills the process. Degrade instead of dying.
        runCatchingLogged(TAG, "listener onCreate body failed") {
            lockScreenMirror.ensureChannel()
            registerLockStateReceivers()
        }
        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "Settings collector failed") {
                repository.settings.collect { settings ->
                    currentSettings = settings
                    if (!settings.enabled || !settings.hideFromNotificationShade) {
                        suppressedKeys.clear()
                    } else {
                        cleanupSuppressedKeys()
                    }
                    when {
                        // Island-only mode is off: the deferred system copies
                        // must simply stay in the shade where they are.
                        !settings.enabled || !settings.hideFromNotificationShade -> {
                            lockDeferredKeys.clear()
                            lockScreenMirror.cancelAllMirrors()
                        }
                        // Feature switched off mid-lock: restore the old
                        // island-only behavior immediately.
                        !settings.showUnreadOnLockScreen -> {
                            cancelLockDeferredKeys(force = true)
                            lockScreenMirror.cancelAllMirrors()
                        }
                        else -> Unit
                    }
                    if (settings.disabledNotificationPackages.isNotEmpty()) {
                        val currentIslandNotifications = notificationRepository.notifications.value
                        currentIslandNotifications
                            .filter { it.packageName in settings.disabledNotificationPackages }
                            .forEach { notificationRepository.removeNotification(it.key) }
                    }
                }
            }
        }
        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "Command collector failed") {
                notificationRepository.commands.collect { command ->
                    runCatchingLogged(TAG, "Notification command failed") {
                        when (command) {
                            is SmartIslandCommand.CancelNotification -> {
                                forceCancelNotification(command.key)
                            }
                            is SmartIslandCommand.SeekTo -> {
                                bestControllerFor(command.packageName)
                                    ?.transportControls
                                    ?.seekTo(command.positionMs)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        isSystemConnected = false
        userPresentReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        userPresentReceiver = null
        pendingRemovals.values.forEach { it.cancel() }
        pendingRemovals.clear()
        pendingSuppressionJobs.values.forEach { it.cancel() }
        pendingSuppressionJobs.clear()
        suppressedKeys.clear()
        userDismissedKeys.clear()
        lockDeferredKeys.clear()
        iconCache.evictAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    private val pendingRemovals = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val pendingSuppressionJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatchingLogged(TAG, "onNotificationPosted callback failed") {
        if (sbn.packageName == packageName) return@runCatchingLogged

        val notification = sbn.notification

        // ── Group summary handling ────────────────────────────────────────────────────────────
        // Apps like WhatsApp post two kinds of notifications per conversation:
        //   1. Child notifications  (individual messages) — these are cancelled via the block below
        //   2. A group SUMMARY notification (FLAG_GROUP_SUMMARY) — this is what causes WhatsApp to
        //      still appear in the system shade even after all children have been cancelled.
        //
        // `shouldSuppressFromIsland` correctly returns true for group summaries (so they're never
        // added to the island), but that also prevents the cancellation block below from running,
        // leaving the summary untouched in the system shade.
        //
        // Fix: intercept group summaries for third-party apps and cancel them from the system shade
        // immediately, BEFORE falling through to the normal island-or-ignore logic.
        val isGroupSummary = (notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) {
            if (currentSettings.enabled &&
                currentSettings.hideFromNotificationShade &&
                sbn.packageName !in currentSettings.disabledNotificationPackages &&
                com.agupta07505.smartisland.util.NotificationFilter.isThirdPartyApp(
                    sbn.packageName,
                    packageManager
                )
            ) {
                android.util.Log.d(TAG, "Cancelling group summary from system shade: ${sbn.key} pkg=${sbn.packageName}")
                if (shouldDeferCancelWhileLocked()) {
                    // While locked the system lock screen should present the
                    // group ("N new messages") natively — defer the cancel.
                    deferCancelWhileLocked(sbn.key)
                    android.util.Log.d(TAG, "Group summary while keyguard up; cancel deferred to unlock: ${sbn.key}")
                } else {
                    suppressSystemNotification(sbn.key) // adds to suppressedKeys + cancels with retry
                }
            }
            // Group summaries are never added to the island — stop processing here.
            pendingRemovals.remove(sbn.key)?.cancel()
            return@runCatchingLogged
        }

        // ── Regular notification: suppress from system shade if it belongs in the island ──────
        // We do this synchronously — before the coroutine is even scheduled — so the notification
        // never appears in the system shade. cancelNotification() is used exclusively;
        // snoozeNotification() is deliberately avoided because it moves to a "snoozed" shade
        // section instead of removing the notification entirely.
        try {
            if (currentSettings.enabled &&
                currentSettings.hideFromNotificationShade &&
                !com.agupta07505.smartisland.util.NotificationFilter.shouldSuppressFromIsland(
                    sbn,
                    packageManager,
                    currentSettings.liveActivitiesEnabled,
                    currentSettings.navigationEnabled,
                    currentSettings.disabledNotificationPackages,
                    currentSettings.deviceType
                )
            ) {
                val modeQuick = notification.toIslandMode(
                    sbn,
                    currentSettings.liveActivitiesEnabled,
                    currentSettings.navigationEnabled,
                    currentSettings.deviceType
                )
                if (shouldBeIslandOnly(notification, modeQuick)) {
                    if (shouldDeferCancelWhileLocked()) {
                        deferCancelWhileLocked(sbn.key)
                        android.util.Log.d(TAG, "Island-only while keyguard up; cancel deferred to unlock: ${sbn.key}")
                    } else {
                        markSuppressed(sbn.key)
                        runCatchingLogged(TAG, "Immediate cancel failed") { cancelNotification(sbn.key) }
                        android.util.Log.d(TAG, "Immediate island-only suppress: ${sbn.key}")
                    }
                }
            }
        } catch (t: Throwable) {
            // Throwable (not Exception): an Error in the suppress path must
            // cost one notification's suppression, not the listener process —
            // a crashed listener is rebound and re-crashed by the system.
            android.util.Log.w(TAG, "Immediate suppress error", t)
        }

        // Cancel any pending removal job for this key to keep island copy
        pendingRemovals.remove(sbn.key)?.cancel()

        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "NotificationPosted async failed") {
                if (shouldSuppressFromIsland(sbn)) return@runSuspendCatchingLogged

                val settings = repository.settings.first()
                currentSettings = settings
                if (!settings.enabled) return@runSuspendCatchingLogged

                android.util.Log.d(TAG, "Processing island-only async: key=${sbn.key}")
                handleNotificationPosted(sbn, settings)
            }
        }
        }
    }

    /**
     * Use the reason-aware overload so we can distinguish between:
     *  - REASON_LISTENER_CANCEL: we suppressed it ourselves → keep island copy, keep suppressedKeys
     *  - Any other reason (user dismissed, app canceled, etc.) → remove from island and suppressedKeys
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        runCatchingLogged(TAG, "onNotificationRemoved callback failed") {
        android.util.Log.d(TAG, "onNotificationRemoved: key=${sbn.key} pkg=${sbn.packageName} reason=$reason")

        pendingRemovals.remove(sbn.key)?.cancel()

        val job = serviceScope.launch {
            runSuspendCatchingLogged(TAG, "NotificationRemoved handling failed") {
                delay(350L)
                if (sbn.packageName == packageName) return@runSuspendCatchingLogged

                val now = SystemClock.elapsedRealtime()
                val lastSuppressedTime = suppressedKeys[sbn.key] ?: 0L
                val isRecentInitialSuppression = (now - lastSuppressedTime) < INITIAL_SUPPRESSION_WINDOW_MS

                if (reason == REASON_LISTENER_CANCEL && isRecentInitialSuppression) {
                    // Smart Island just suppressed this notification from system shade < 1.5s ago.
                    // Keep the island copy alive during initial suppression.
                    android.util.Log.d(TAG, "Recent listener-cancel (<1.5s), keeping island: ${sbn.key}")
                    return@runSuspendCatchingLogged
                }

                // Removed by posting app, user, framework timeout, or after initial suppression window.
                android.util.Log.d(TAG, "Genuinely removed, cleaning up: ${sbn.key}")
                lockDeferredKeys.remove(sbn.key)
                lockScreenMirror.cancelMirror(sbn.key)
                clearSuppressed(sbn.key)
                notificationRepository.removeNotification(sbn.key)
            }
            pendingRemovals.remove(sbn.key)
        }
        pendingRemovals[sbn.key] = job
        }
    }

    // Keep the no-arg override as a fallback (some OEMs may only call this one).
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatchingLogged(TAG, "onNotificationRemoved fallback callback failed") {
        android.util.Log.d(TAG, "onNotificationRemoved (no reason): key=${sbn.key} pkg=${sbn.packageName}")

        pendingRemovals.remove(sbn.key)?.cancel()

        val job = serviceScope.launch {
            runSuspendCatchingLogged(TAG, "NotificationRemoved (no reason) handling failed") {
                delay(350L)
                if (sbn.packageName == packageName) return@runSuspendCatchingLogged

                val now = SystemClock.elapsedRealtime()
                val lastSuppressedTime = suppressedKeys[sbn.key] ?: 0L
                val isRecentInitialSuppression = (now - lastSuppressedTime) < INITIAL_SUPPRESSION_WINDOW_MS

                if (isRecentInitialSuppression) {
                    android.util.Log.d(TAG, "Suppressed key recently (<1.5s), keeping island: ${sbn.key}")
                    return@runSuspendCatchingLogged
                }

                android.util.Log.d(TAG, "Removing from island repo: ${sbn.key}")
                lockDeferredKeys.remove(sbn.key)
                lockScreenMirror.cancelMirror(sbn.key)
                clearSuppressed(sbn.key)
                notificationRepository.removeNotification(sbn.key)
            }
            pendingRemovals.remove(sbn.key)
        }
        pendingRemovals[sbn.key] = job
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isSystemConnected = true
        CrashGuard.recordHeartbeat(this, "listener-connected")
        runCatchingLogged(TAG, "onListenerConnected callback failed") {
            android.util.Log.d(TAG, "onListenerConnected")
            serviceScope.launch {
                runSuspendCatchingLogged(TAG, "ListenerConnected failed") {
                    val settings = repository.settings.first()
                    currentSettings = settings
                    if (!settings.enabled) return@runSuspendCatchingLogged

                    // Safety sweep: if this is a fresh bind (process restart,
                    // listener rebind) any stale deferred keys from a previous
                    // incarnation cannot exist (in-memory map), but a rebind
                    // MID-SESSION can — execute their pending cancels ONLY if
                    // the keyguard is already down (unforced call).
                    cancelLockDeferredKeys()

                    // Stale-mirror sweep: a previous incarnation may have
                    // died while the screen was locked, leaving mirrors in
                    // the shade. They are enumerated from the system's own
                    // list (the in-memory set died with the process) and
                    // only cleared when the keyguard is DOWN — while locked
                    // the mirrors are still serving the lock screen.
                    if (!isKeyguardCurrentlyLocked()) {
                        lockScreenMirror.cancelAllMirrors()
                        runCatchingLogged(TAG, "Failed to sweep stale lock-screen mirrors") {
                            activeNotifications
                                ?.filter {
                                    it.packageName == packageName &&
                                        it.tag?.startsWith(LockScreenMirrorNotifier.MIRROR_TAG_PREFIX) == true
                                }
                                ?.forEach { cancelNotification(it.key) }
                        }
                    }

                    val overlayReady = ensureOverlayServiceRunning()
                    val active = runCatchingLogged(TAG, "Failed to get active notifications") {
                        activeNotifications?.toList()
                    }?.filter { it.packageName != packageName }
                        ?.filterNot { shouldSuppressFromIsland(it) }
                        .orEmpty()

                    android.util.Log.d(TAG, "ListenerConnected: ${active.size} active, overlayReady=$overlayReady")

                    active.forEach { sbn ->
                        val mode = sbn.notification.toIslandMode(
                            sbn,
                            settings.liveActivitiesEnabled,
                            settings.navigationEnabled,
                            settings.deviceType
                        )
                        if (settings.hideFromNotificationShade &&
                            shouldBeIslandOnly(sbn.notification, mode)
                        ) {
                            suppressSystemNotification(sbn.key)
                        }
                        handleNotificationPosted(sbn, settings)
                    }
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        isSystemConnected = false
        super.onListenerDisconnected()
        // ROUND-X: never self-rebind while safe mode is latched or while the
        // service is degraded (injection failed) — that would fight the
        // loop breaker by pulling the system straight back into the code
        // that breaks.
        val safeMode = runCatching { CrashGuard.isSafeMode(this) }.getOrDefault(false)
        val degraded = !::repository.isInitialized || !::notificationRepository.isInitialized
        if (safeMode || degraded) return
        runCatchingLogged(TAG, "Notification-listener self-rebind failed") {
            requestRebind(
                android.content.ComponentName(
                    this,
                    SmartIslandNotificationListenerService::class.java
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        // ROUND-X: the framework calls onBind right after onCreate. If
        // super.onCreate() never completed (safe mode early-return, caught
        // injection failure), the parent's binder plumbing may be
        // uninitialized — letting it throw would take the process down with
        // "Unable to bind service", defeating the whole breaker. Degrade to
        // null: the system simply unbinds and the process survives.
        val safeMode = runCatching { CrashGuard.isSafeMode(this) }.getOrDefault(false)
        val degraded = !::repository.isInitialized || !::notificationRepository.isInitialized
        if (safeMode || degraded) return null
        return try {
            super.onBind(intent)
        } catch (t: Throwable) {
            CrashGuard.recordBoundaryCrash(this, "listener-onBind", t)
            null
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = android.content.ComponentName(this, SmartIslandOverlayService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = android.text.TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val cn = android.content.ComponentName.unflattenFromString(splitter.next())
            if (cn != null && cn == expected) return true
        }
        return false
    }

    private fun ensureOverlayServiceRunning(): Boolean {
        return isAccessibilityServiceEnabled() || Settings.canDrawOverlays(this)
    }

    private fun isIncomingCall(notification: Notification): Boolean {
        return notification.actions?.any { action ->
            val label = action.title?.toString()?.lowercase().orEmpty()
            label.contains("answer") || label.contains("accept") || label.contains("take")
        } == true
    }

    private fun handleNotificationPosted(
        sbn: StatusBarNotification,
        settings: SmartIslandSettings
    ) {
        if (sbn.packageName == packageName) return
        // USER-DISMISS TOMBSTONE: this key was explicitly discarded by the
        // user. Swallow the re-post instead of resurrecting it: keep the
        // tombstone armed (rolling window) and, when shade-hiding is on,
        // cancel the freshly re-posted system copy too. Without this, every
        // app-side update of a dismissed notification re-added it to the
        // island — "sometimes the notification still comes back because it
        // wasn't discarded".
        if (isUserDismissed(sbn.key)) {
            android.util.Log.d(TAG, "User-dismissed key re-posted; swallowed: ${sbn.key}")
            markUserDismissed(sbn.key)
            if (settings.enabled && settings.hideFromNotificationShade) {
                suppressSystemNotification(sbn.key)
            }
            return
        }
        val notification = sbn.notification
        if (shouldSuppressFromIsland(sbn)) return

        val extras = notification.extras
        val mode = notification.toIslandMode(
            sbn,
            settings.liveActivitiesEnabled,
            settings.navigationEnabled,
            settings.deviceType
        )
        android.util.Log.d(TAG, "handleNotificationPosted: mode=$mode key=${sbn.key} title=${extras.getCharSequence(Notification.EXTRA_TITLE)}")

        val shouldIslandOnly = shouldBeIslandOnly(notification, mode)

        if (settings.hideFromNotificationShade && shouldIslandOnly) {
            // Ensure the notification is removed from the system shade.
            // - If posted via onNotificationPosted, the synchronous cancel already ran; this
            //   triggers the async retry loop inside suppressSystemNotification for reliability.
            // - If arriving via onListenerConnected, this is the first (and only) suppress call.
            suppressSystemNotification(sbn.key)
        }

        val mediaInfo = if (mode == IslandMode.Music) findMediaInfo(notification, sbn.packageName) else null
        val appName = runCatchingLogged(TAG, "GetApplicationInfo failed") {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } ?: sbn.packageName

        val isNewNotif = notificationRepository.notifications.value.none { it.key == sbn.key }

        val existingNotif = notificationRepository.notifications.value.find { it.key == sbn.key || (it.mode == IslandMode.IncomingCall && it.packageName == sbn.packageName) }
        val actions = notification.actions?.mapNotNull { action ->
            action.title?.toString()?.let { title ->
                val remoteInput = action.remoteInputs?.firstOrNull()
                val isReply = remoteInput != null || title.lowercase().contains("reply")
                IslandNotificationAction(
                    title = title,
                    pendingIntent = action.actionIntent,
                    isQuickReply = isReply,
                    remoteInputKey = remoteInput?.resultKey ?: "key_text_reply"
                )
            }
        }.orEmpty()
        val isNowRinging = actions.any { it.title.lowercase().let { t -> t.contains("answer") || t.contains("accept") || t.contains("take") } }

        val computedTimeMillis = when {
            mode == IslandMode.IncomingCall && existingNotif != null && existingNotif.isCallRinging && !isNowRinging -> {
                System.currentTimeMillis()
            }
            existingNotif != null && mode == IslandMode.IncomingCall && !isNowRinging && existingNotif.timeMillis > 0 -> {
                existingNotif.timeMillis
            }
            mode == IslandMode.Timer -> {
                val remSec = com.agupta07505.smartisland.util.TimerStopwatchParser.parseTimerRemainingSeconds(notification)
                if (remSec != null && remSec > 0) {
                    System.currentTimeMillis() + remSec * 1000L
                } else if (notification.`when` > System.currentTimeMillis()) {
                    notification.`when`
                } else {
                    System.currentTimeMillis()
                }
            }
            mode == IslandMode.Stopwatch -> {
                val elSec = com.agupta07505.smartisland.util.TimerStopwatchParser.parseStopwatchElapsedSeconds(notification)
                if (elSec != null && elSec > 0) {
                    System.currentTimeMillis() - elSec * 1000L
                } else if (existingNotif != null && existingNotif.mode == IslandMode.Stopwatch && existingNotif.timeMillis > 0) {
                    existingNotif.timeMillis
                } else if (notification.`when` in 1..System.currentTimeMillis()) {
                    notification.`when`
                } else {
                    System.currentTimeMillis()
                }
            }
            notification.`when` != 0L -> notification.`when`
            else -> sbn.postTime
        }

        val notifTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: (if (mode == IslandMode.Stopwatch) "Stopwatch" else if (mode == IslandMode.Timer) "Timer" else "")
        val notifText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
            ?: notification.tickerText?.toString()
            ?: (if (mode == IslandMode.Stopwatch) "Running" else if (mode == IslandMode.Timer) "Running" else "")

        notificationRepository.postNotification(
            IslandNotification(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = notifTitle,
                text = notifText,
                timeMillis = computedTimeMillis,
                icon = loadAppIconBitmap(sbn.packageName),
                largeIcon = mediaInfo?.artwork ?: notification.loadLargeIconBitmap(),
                actionIntents = actions,
                category = notification.category,
                progress = extras.getInt(Notification.EXTRA_PROGRESS, 0),
                progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
                mediaPositionMs = mediaInfo?.positionMs,
                mediaDurationMs = mediaInfo?.durationMs,
                mediaIsPlaying = mediaInfo?.isPlaying == true,
                mediaToken = runCatchingLogged(TAG, "GetMediaToken failed") {
                    val ex = notification.extras
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        ex.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        ex.getParcelable(Notification.EXTRA_MEDIA_SESSION)
                    }
                },
                mode = mode,
                contentIntent = notification.contentIntent
            ),
            autoExpand = shouldIslandOnly && settings.autoExpandOnNotification
        )

        if (settings.enableNotificationHistory && mode != IslandMode.Music) {
            serviceScope.launch {
                runSuspendCatchingLogged(TAG, "Failed to record notification history") {
                    historyRepository.saveEntry(
                        NotificationHistoryEntry(
                            notificationKey = sbn.key,
                            packageName = sbn.packageName,
                            appName = appName,
                            title = notifTitle,
                            text = notifText,
                            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                            postTimeMillis = computedTimeMillis,
                            category = notification.category,
                            channelId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) notification.channelId else null,
                            mode = mode.name,
                            actionTitles = actions.map { it.title }
                        )
                    )
                    val now = System.currentTimeMillis()
                    if (now - lastHistoryCleanupTime > 15 * 60 * 1000L) {
                        lastHistoryCleanupTime = now
                        historyRepository.cleanupOldEntries(settings.notificationHistoryRetentionHours)
                    }
                }
            }
        }

        if (isNewNotif && (mode == IslandMode.Notification || shouldIslandOnly) && mode != IslandMode.DownloadUpload) {
            playNotificationSound(sbn)
        }

        if (mode == IslandMode.Music) {
            val existing = notificationRepository.notifications.value
            existing.filter { it.packageName == sbn.packageName && it.key != sbn.key }
                .forEach { notificationRepository.removeNotification(it.key) }
        }

        if (mode == IslandMode.IncomingCall) {
            val isEnded = notification.isCallEnded()
            if (isEnded) {
                pendingRemovals.remove(sbn.key)?.cancel()
                clearSuppressed(sbn.key)
                notificationRepository.removeNotification(sbn.key)
            }
        }

        if (mode == IslandMode.DownloadUpload) {
            val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
            val isDone = notification.isDownloadComplete() || (progressMax > 0 && progress >= progressMax)
            if (isDone) {
                pendingRemovals.remove(sbn.key)?.cancel()
                val job = serviceScope.launch {
                    delay(3500L)
                    clearSuppressed(sbn.key)
                    notificationRepository.removeNotification(sbn.key)
                }
                pendingRemovals[sbn.key] = job
            }
        }

        if (mode == IslandMode.ScreenRecording) {
            val isDone = notification.isScreenRecordingComplete()
            if (isDone) {
                pendingRemovals.remove(sbn.key)?.cancel()
                val job = serviceScope.launch {
                    delay(3500L)
                    clearSuppressed(sbn.key)
                    notificationRepository.removeNotification(sbn.key)
                }
                pendingRemovals[sbn.key] = job
            }
        }

        if (mode == IslandMode.Timer) {
            val isDone = com.agupta07505.smartisland.util.TimerStopwatchParser.isTimerFinished(notification)
            if (isDone) {
                pendingRemovals.remove(sbn.key)?.cancel()
                val job = serviceScope.launch {
                    delay(5000L)
                    clearSuppressed(sbn.key)
                    notificationRepository.removeNotification(sbn.key)
                }
                pendingRemovals[sbn.key] = job
            }
        }
    }

    internal fun shouldSuppressFromIsland(sbn: StatusBarNotification): Boolean {
        return com.agupta07505.smartisland.util.NotificationFilter.shouldSuppressFromIsland(
            sbn,
            packageManager,
            currentSettings.liveActivitiesEnabled,
            currentSettings.navigationEnabled,
            currentSettings.disabledNotificationPackages,
            currentSettings.deviceType
        )
    }

    internal fun shouldBeIslandOnly(notification: Notification, mode: IslandMode): Boolean {
        if (mode == IslandMode.IncomingCall) {
            if (!isIncomingCall(notification)) return false // ongoing call stays in system
        }
        if (mode == IslandMode.Music || mode == IslandMode.Navigation || mode == IslandMode.Timer || mode == IslandMode.Stopwatch) {
            return false // Media/Music, Navigation, Timer & Stopwatch notifications must NOT be cancelled from system shade by default
        }
        // All others: island-only
        return true
    }

    /**
     * Suppress a notification from the system shade so it only appears in the island.
     *
     * Uses ONLY cancelNotification() — snoozeNotification() is deliberately avoided
     * because it moves the notification to a "snoozed" section in the system shade
     * rather than removing it, which causes notifications to appear in both the
     * system shade AND the island.
     *
     * Retries up to 3 times with increasing delays for reliability on devices where
     * the first cancel attempt may not take effect immediately.
     */
    private fun suppressSystemNotification(key: String) {
        if (!currentSettings.enabled || !currentSettings.hideFromNotificationShade) return
        if (shouldDeferCancelWhileLocked()) {
            // Keyguard up + Unread-on-Lock-Screen on: the system lock screen
            // must present this notification natively, so postpone the
            // island-only cancel to unlock (ACTION_USER_PRESENT sweep).
            deferCancelWhileLocked(key)
            return
        }
        val activeSbn = runCatchingLogged(TAG, "Failed to get active notifications for key lookup") {
            activeNotifications.find { it.key == key }
        }
        if (activeSbn != null && activeSbn.packageName in currentSettings.disabledNotificationPackages) return

        val now = SystemClock.elapsedRealtime()
        val lastSuppressedTime = suppressedKeys[key] ?: 0L
        val isRecentlySuppressed = (now - lastSuppressedTime) < 300L

        markSuppressed(key)

        // Cancel any pending suppression retry job for this key
        pendingSuppressionJobs.remove(key)?.cancel()

        // Synchronous attempt for fastest possible suppression (if not throttled)
        if (!isRecentlySuppressed) {
            runCatchingLogged(TAG, "sync cancel failed") { cancelNotification(key) }
        }

        // Asynchronous retry with delays for reliability. Runs on the Default
        // dispatcher: both activeNotifications and cancelNotification are plain
        // binder calls, and hammering them on the main thread during notification
        // bursts caused frame drops in the overlay.
        val job = serviceScope.launch {
            runSuspendCatchingLogged(TAG, "Notification suppression retries failed") {
                repeat(3) { attempt ->
                    delay(100L * (attempt + 1)) // 100, 200, 300ms
                    if (!currentSettings.enabled ||
                        !currentSettings.hideFromNotificationShade
                    ) {
                        clearSuppressed(key)
                        return@runSuspendCatchingLogged
                    }
                    val stillActive = runCatchingLogged(TAG, "Failed checking stillActive") {
                        activeNotifications?.any { it.key == key }
                    } ?: false
                    if (!stillActive) {
                        android.util.Log.d(TAG, "Successfully suppressed after ${attempt + 1} attempts: $key")
                        return@runSuspendCatchingLogged
                    }
                    android.util.Log.d(TAG, "Still active after attempt ${attempt + 1}, retrying: $key")
                    runCatchingLogged(TAG, "cancel retry $attempt failed") {
                        cancelNotification(key)
                    }
                }
                android.util.Log.w(TAG, "Failed to suppress after retries: $key")
            }
            pendingSuppressionJobs.remove(key)
        }
        pendingSuppressionJobs[key] = job
    }

    private fun forceCancelNotification(key: String) {
        // This is an explicit user action, so the island copy must disappear as well
        // and the key must stay dismissed (see userDismissedKeys).
        markUserDismissed(key)
        lockDeferredKeys.remove(key)
        lockScreenMirror.cancelMirror(key)
        clearSuppressed(key)
        runCatchingLogged(TAG, "forceCancel failed") { cancelNotification(key) }
        notificationRepository.removeNotification(key)
    }

    private fun isKeyguardCurrentlyLocked(): Boolean {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return false
        return runCatching { keyguardManager.isKeyguardLocked }.getOrDefault(false)
    }

    /**
     * True when an island-bound notification's system-side cancel should be
     * POSTPONED because the keyguard is showing: the system lock screen can
     * then present the notification natively (the overlay window renders
     * below the keyguard, so with an immediate cancel there was NO lock-screen
     * presentation at all). Honors the Unread-on-Lock-Screen setting.
     */
    private fun shouldDeferCancelWhileLocked(): Boolean {
        if (!currentSettings.showUnreadOnLockScreen) return false
        return isKeyguardCurrentlyLocked()
    }

    /**
     * Registers a key whose island-only cancel is postponed until unlock.
     * The island copy survives the later listener-cancel because
     * [cancelLockDeferredKeys] re-arms markSuppressed right before firing
     * it (keeping it inside the REASON_LISTENER_CANCEL keep-alive window).
     */
    private fun deferCancelWhileLocked(key: String) {
        // If a mirror for this key is live (it was cancelled before lock and
        // mirrored at screen-off), the ORIGINAL is now present natively on
        // the lock screen — drop the mirror so the message is not shown
        // twice (deferred original + stale mirror).
        lockScreenMirror.cancelMirror(key)
        lockDeferredKeys[key] = SystemClock.elapsedRealtime()
        cleanupLockDeferredKeys()
    }

    private fun cleanupLockDeferredKeys(now: Long = SystemClock.elapsedRealtime()) {
        sweepBoundedMap(lockDeferredKeys, LOCK_DEFERRED_TTL_MS, MAX_LOCK_DEFERRED_KEYS, now)
    }

    /**
     * Executes the deferred island-only cancels. Runs on unlock
     * (ACTION_USER_PRESENT, forced), when the setting is switched off
     * mid-lock (forced), and as a safety sweep on listener (re)connect
     * (unforced — see below). After this sweep the island-only model is
     * restored: the shade copies are gone, the island keeps (or re-gains)
     * its pills.
     *
     * [force] bypasses the keyguard check. The UNFORCED path must never
     * cancel while the keyguard is up: onListenerConnected used to call
     * this unconditionally, so EVERY listener rebind while locked (process
     * death, battery throttling, OEM job scheduling) executed the pending
     * cancels and wiped the notifications off the lock screen — the
     * recurring "they never appear on the lock screen" failure. The
     * per-notification re-suppress loop below re-defers anything still
     * active, but the just-cancelled keys were already gone by then.
     */
    private fun cancelLockDeferredKeys(force: Boolean = false) {
        if (lockDeferredKeys.isEmpty()) return
        if (!currentSettings.enabled || !currentSettings.hideFromNotificationShade) {
            // Island-only mode is off: the system copies must simply stay.
            lockDeferredKeys.clear()
            return
        }
        if (!force && isKeyguardCurrentlyLocked()) return
        val keys = lockDeferredKeys.keys.toList()
        lockDeferredKeys.clear()
        val activeKeys = runCatchingLogged(TAG, "Failed to list active notifications for unlock sweep") {
            activeNotifications?.map { it.key }?.toSet()
        } ?: return
        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "Deferred lock-screen cancel failed") {
                for (key in keys) {
                    if (key !in activeKeys) continue
                    // Arm the suppression window NOW so the
                    // REASON_LISTENER_CANCEL removal that follows keeps the
                    // island copy alive.
                    markSuppressed(key)
                    runCatchingLogged(TAG, "Deferred cancel failed") { cancelNotification(key) }
                    android.util.Log.d(TAG, "Lock-deferred notification cancelled at unlock: $key")
                }
            }
        }
    }

    private fun registerLockStateReceivers() {
        if (userPresentReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> {
                        cancelLockDeferredKeys(force = true)
                        lockScreenMirror.cancelAllMirrors()
                    }
                    // SCREEN OFF → the lock screen is about to become the
                    // only surface; mirror every unread island notification
                    // whose system copy is gone so the lock screen presents
                    // it natively. Both actions are PROTECTED system
                    // broadcasts — only the system can send them — so an
                    // EXPORTED registration is spoof-safe AND delivery-safe
                    // (a NOT_EXPORTED receiver depends on platform-side
                    // system-exemption behavior that has been flaky on some
                    // OEM builds).
                    Intent.ACTION_SCREEN_OFF -> postLockScreenMirrors()
                }
            }
        }
        runCatchingLogged(TAG, "Failed to register lock-state receivers") {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_USER_PRESENT).apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            userPresentReceiver = receiver
        }
    }

    /**
     * Mirrors every unread island notification that lost its system copy so
     * the lock screen shows it. Called on SCREEN OFF; the originals stay
     * deferred (never cancelled while locked), so nothing that arrived
     * WHILE locked is duplicated here — only pre-lock cancellations are
     * mirrored back into existence.
     */
    private fun postLockScreenMirrors() {
        val settings = currentSettings
        if (!settings.enabled || !settings.hideFromNotificationShade || !settings.showUnreadOnLockScreen) {
            lockScreenMirror.cancelAllMirrors()
            return
        }
        val activeKeys = runCatchingLogged(TAG, "Failed to list active notifications for mirror sweep") {
            activeNotifications?.map { it.key }?.toSet()
        }.orEmpty()
        val candidates = notificationRepository.notifications.value.filter { island ->
            island.mode in LockScreenMirrorNotifier.MIRROR_MODES && island.key !in activeKeys
        }
        if (candidates.isEmpty()) return
        lockScreenMirror.postMirrors(candidates)
    }

    private fun markUserDismissed(key: String) {
        userDismissedKeys[key] = SystemClock.elapsedRealtime()
        cleanupUserDismissedKeys()
    }

    private fun isUserDismissed(key: String): Boolean {
        cleanupUserDismissedKeys()
        return userDismissedKeys.containsKey(key)
    }

    private fun cleanupUserDismissedKeys(now: Long = SystemClock.elapsedRealtime()) {
        sweepBoundedMap(userDismissedKeys, USER_DISMISS_TOMBSTONE_TTL_MS, MAX_USER_DISMISSED_KEYS, now)
    }

    private fun playNotificationSound(sbn: StatusBarNotification) {
        if (currentSettings.disabledSoundPackages.contains(sbn.packageName)) return
        runCatchingLogged(TAG, "Failed to play notification sound for ${sbn.packageName}") {
            val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null || audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

            val notification = sbn.notification
            var soundUri: Uri? = null
            var audioAttributes: AudioAttributes? = null

            // 1. Check Notification Channel (Android 8.0+, API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ranking = Ranking()
                val hasRanking = currentRanking?.getRanking(sbn.key, ranking) == true
                val channel = if (hasRanking) ranking.channel else null

                if (channel != null) {
                    // Silent channels (IMPORTANCE_NONE, IMPORTANCE_MIN, IMPORTANCE_LOW) must not play sound
                    if (channel.importance < NotificationManager.IMPORTANCE_DEFAULT) {
                        return
                    }
                    val chSound = channel.sound
                    if (chSound == null || chSound == Uri.EMPTY || chSound.toString().isEmpty()) {
                        // Channel is explicitly configured without sound (Silent)
                        return
                    }
                    soundUri = chSound
                    audioAttributes = channel.audioAttributes
                }
            }

            // 2. Fallback to notification payload if channel was not present
            if (soundUri == null) {
                @Suppress("DEPRECATION")
                val notifSound = notification.sound
                @Suppress("DEPRECATION")
                val defaults = notification.defaults

                if (notifSound != null && notifSound != Uri.EMPTY && notifSound.toString().isNotEmpty()) {
                    soundUri = notifSound
                } else if ((defaults and Notification.DEFAULT_SOUND) != 0) {
                    soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
                audioAttributes = notification.audioAttributes
            }

            if (soundUri == null) {
                return
            }

            // 3. Resolve and play the exact notification sound
            var ringtone: Ringtone? = runCatching {
                RingtoneManager.getRingtone(applicationContext, soundUri)
            }.getOrNull()

            // Fallback to default tone if custom URI failed to load
            if (ringtone == null) {
                val fallbackUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringtone = runCatching {
                    RingtoneManager.getRingtone(applicationContext, fallbackUri)
                }.getOrNull()
            }

            if (ringtone != null) {
                val attrs = audioAttributes ?: AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                ringtone.audioAttributes = attrs
                ringtone.play()
            }
        }
    }

    private fun markSuppressed(key: String) {
        val now = SystemClock.elapsedRealtime()
        suppressedKeys[key] = now
        cleanupSuppressedKeys(now)
    }

    private fun clearSuppressed(key: String) {
        pendingSuppressionJobs.remove(key)?.cancel()
        suppressedKeys.remove(key)
    }

    /**
     * Shared TTL + size bound for the listener's key bookkeeping maps.
     * Entries older than [ttlMs] are dropped, then the oldest entries are
     * evicted down to [maxEntries] (bounds pathological growth). Previously
     * this identical sweep logic existed in three copies — one per map.
     */
    private fun sweepBoundedMap(
        map: ConcurrentHashMap<String, Long>,
        ttlMs: Long,
        maxEntries: Int,
        now: Long = SystemClock.elapsedRealtime()
    ) {
        val cutoff = now - ttlMs
        map.entries.forEach { entry ->
            if (entry.value < cutoff) {
                map.remove(entry.key, entry.value)
            }
        }
        val overflow = map.size - maxEntries
        if (overflow > 0) {
            map.entries
                .sortedBy { it.value }
                .take(overflow)
                .forEach { map.remove(it.key, it.value) }
        }
    }

    private fun cleanupSuppressedKeys(now: Long = SystemClock.elapsedRealtime()) {
        sweepBoundedMap(suppressedKeys, SUPPRESSED_KEY_TTL_MS, MAX_SUPPRESSED_KEYS, now)
    }

    private val iconCache = android.util.LruCache<String, Bitmap>(50)

    private fun loadAppIconBitmap(packageName: String): Bitmap? {
        iconCache.get(packageName)?.let { return it }
        return runCatchingLogged(TAG, "LoadAppIconBitmap failed") {
            val drawable = packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(width = ICON_BITMAP_SIZE, height = ICON_BITMAP_SIZE).also { iconCache.put(packageName, it) }
        }
    }

    private fun Notification.loadLargeIconBitmap(): Bitmap? {
        val extraLarge = extras.get(Notification.EXTRA_LARGE_ICON)
        extraLarge.toBitmapOrNull()?.let { return it }
        val extraLargeBig = extras.get(Notification.EXTRA_LARGE_ICON_BIG)
        extraLargeBig.toBitmapOrNull()?.let { return it }
        val largeIconObj = getLargeIcon()
        runCatchingLogged(TAG, "LoadLargeIconBitmap failed") {
            largeIconObj?.loadDrawable(this@SmartIslandNotificationListenerService)
                ?.toBitmap(width = LARGE_ICON_BITMAP_SIZE, height = LARGE_ICON_BITMAP_SIZE)
        }?.let { return it }
        return runCatchingLogged(TAG, "LoadMessagingStyleAvatar failed") {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (!messages.isNullOrEmpty()) {
                    val lastMessageBundle = messages.lastOrNull() as? android.os.Bundle
                    val senderPerson = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        lastMessageBundle?.getParcelable("sender_person", android.app.Person::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        lastMessageBundle?.getParcelable("sender_person") as? android.app.Person
                    }
                    senderPerson?.icon?.loadDrawable(this@SmartIslandNotificationListenerService)
                        ?.toBitmap(width = LARGE_ICON_BITMAP_SIZE, height = LARGE_ICON_BITMAP_SIZE)
                } else null
            } else null
        }
    }

    private fun Any?.toBitmapOrNull(): Bitmap? {
        return when (this) {
            is Bitmap -> capBitmapSize(this, MAX_EXTRA_BITMAP_SIZE)
            is Icon -> runCatchingLogged(TAG, "Icon toBitmapOrNull failed") {
                loadDrawable(this@SmartIslandNotificationListenerService)
                    ?.toBitmap(width = 128, height = 128)
            }
            else -> null
        }
    }

    /**
     * Keeps the biggest island-list residents bounded: media artwork and OEM
     * extra bitmaps can arrive far larger than anything the UI ever shows
     * (e.g. 1024×1024+ album art = 4 MB each), and up to
     * [MAX_STORED_ISLAND_NOTIFICATIONS] of them are retained at once.
     */
    private fun capBitmapSize(bitmap: Bitmap, maxDim: Int): Bitmap {
        val largestSide = maxOf(bitmap.width, bitmap.height)
        if (largestSide <= maxDim || largestSide <= 0) return bitmap
        val scale = maxDim.toFloat() / largestSide
        val scaled = runCatching {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }.getOrNull() ?: return bitmap
        // NOTE: never recycle() the original here — it may still be referenced
        // by the cached MediaMetadata bundle / notification extras and would
        // crash later draws with "trying to use a recycled bitmap".
        return scaled
    }

    private fun controllersFor(packageName: String): List<MediaController> =
        activeMediaControllers.filter { it.packageName == packageName }

    private fun bestControllerFor(packageName: String): MediaController? {
        val matches = controllersFor(packageName)
        return matches.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: matches.firstOrNull()
    }

    private fun findMediaInfo(notification: Notification, packageName: String): MediaInfo? {
        notification.mediaSessionController()?.extractMediaInfo()?.let { return it }
        val controller = bestControllerFor(packageName) ?: return null
        return controller.extractMediaInfo()
    }

    private fun Notification.mediaSessionController(): MediaController? {
        val token = runCatchingLogged(TAG, "GetMediaSessionToken failed") {
            val ex = extras
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ex.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                ex.getParcelable(Notification.EXTRA_MEDIA_SESSION)
            }
        } ?: return null
        return runCatchingLogged(TAG, "MediaController init failed") { MediaController(this@SmartIslandNotificationListenerService, token) }
    }

    private fun MediaController.extractMediaInfo(): MediaInfo {
        val metadata = this.metadata
        val playbackState = this.playbackState
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val positionMs = playbackState?.estimatedPosition()
        val artwork = (metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
            ?.let { capBitmapSize(it, MAX_ARTWORK_BITMAP_SIZE) }
        return MediaInfo(artwork, positionMs, durationMs, playbackState?.state == PlaybackState.STATE_PLAYING)
    }

    private val activeMediaControllers: List<MediaController>
        get() = runCatchingLogged(TAG, "GetActiveSessions failed") {
            val mgr = mediaSessionManager ?: return emptyList()
            val componentName = android.content.ComponentName(this, SmartIslandNotificationListenerService::class.java)
            mgr.getActiveSessions(componentName)
        } ?: emptyList()

    private val mediaSessionManager: android.media.session.MediaSessionManager? by lazy {
        runCatching { getSystemService(MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager }.getOrNull()
    }

    private fun PlaybackState.estimatedPosition(): Long? {
        if (position < 0) return null
        if (state != PlaybackState.STATE_PLAYING) return position
        val elapsed = android.os.SystemClock.elapsedRealtime() - lastPositionUpdateTime
        return (position + (elapsed * playbackSpeed).toLong()).coerceAtLeast(0L)
    }

    private data class MediaInfo(val artwork: Bitmap?, val positionMs: Long?, val durationMs: Long?, val isPlaying: Boolean)

    companion object {
        @Volatile
        var isSystemConnected: Boolean = false
            private set

        private const val TAG = "SmartIslandNotificationListener"
        private const val ICON_BITMAP_SIZE = 96
        private const val LARGE_ICON_BITMAP_SIZE = 128
        // MediaMetadata art is usually fine, but some apps push huge art — this
        // only downscales pathological cases, never typical artwork.
        private const val MAX_ARTWORK_BITMAP_SIZE = 1024
        private const val MAX_EXTRA_BITMAP_SIZE = 256
        private const val MAX_STORED_ISLAND_NOTIFICATIONS = 50
        private const val MAX_SUPPRESSED_KEYS = 100
        private const val SUPPRESSED_KEY_TTL_MS = 10 * 60 * 1000L
        private const val INITIAL_SUPPRESSION_WINDOW_MS = 1500L
        // How long a user-dismissed key stays tombstoned against re-posts.
        // Long enough to outlive any sane update cadence (media ticks run at
        // 1-17s), short enough that a genuinely new notification from the
        // same app key shows up again.
        private const val USER_DISMISS_TOMBSTONE_TTL_MS = 30_000L
        private const val MAX_USER_DISMISSED_KEYS = 100
        // Lock-deferred keys outlive the whole lock session (a phone can sit
        // locked for days); the unlock sweep and the removal handler are the
        // real cleanup paths — the TTL only bounds pathological growth.
        private const val LOCK_DEFERRED_TTL_MS = 12 * 60 * 60 * 1000L
        private const val MAX_LOCK_DEFERRED_KEYS = 100
    }
}
