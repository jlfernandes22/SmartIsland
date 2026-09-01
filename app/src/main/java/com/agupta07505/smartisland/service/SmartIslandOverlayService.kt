/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.service

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.agupta07505.smartisland.MainActivity
import com.agupta07505.smartisland.R
import com.agupta07505.smartisland.data.INotificationRepository
import com.agupta07505.smartisland.data.SmartIslandCommand
import com.agupta07505.smartisland.data.SmartIslandSettings
import com.agupta07505.smartisland.data.SmartIslandSettingsRepository
import com.agupta07505.smartisland.model.IslandNotification
import com.agupta07505.smartisland.ui.IslandViewModel
import com.agupta07505.smartisland.ui.OverlayIsland
import com.agupta07505.smartisland.ui.expanded.sendIntentWithOptions
import com.agupta07505.smartisland.util.ShizukuManager
import com.agupta07505.smartisland.util.runCatchingLogged
import com.agupta07505.smartisland.util.runSuspendCatchingLogged
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import javax.inject.Inject

@AndroidEntryPoint
class SmartIslandOverlayService : AccessibilityService() {
    private lateinit var windowManager: WindowManager
    @Inject lateinit var repository: SmartIslandSettingsRepository
    @Inject lateinit var notificationRepository: INotificationRepository
    private var islandView: ComposeView? = null
    private val overlayOwners = OverlayViewTreeOwners()
    private lateinit var systemEventReceiver: SystemEventReceiver
    private lateinit var viewModel: IslandViewModel
    private var isLockScreenActive: Boolean = false
    private var systemEventReceiverRegistered = false
    private var screenStateReceiverRegistered = false
    private var torchCallbackRegistered = false
    private var foregroundStarted = false
    private var isTouchableRegionSupported = false
    @Volatile private var destroyed = false
    private var isWindowExpanded: Boolean = false
    private var isShadeOpen: Boolean = false
    // While true, the "hide when shade open" rule is suspended so the island
    // stays visible during in-menu toggles (e.g. the Bluetooth QS tile tap).
    @Volatile private var suppressShadeHide: Boolean = false
    private var collapseJob: kotlinx.coroutines.Job? = null
    private var lastParams: WindowManager.LayoutParams? = null
    // TWO-WINDOW ARCHITECTURE (no-reflection devices): the content window
    // above is MATCH_PARENT in every state and NEVER resizes; while collapsed
    // it is FLAG_NOT_TOUCHABLE and this separate, fully transparent window
    // carries the collapsed-pill gestures instead. Because the catcher has no
    // visible content, its add/remove/resize can never produce a ghost or a
    // blink — which is what kills the end-of-collapse flash for good.
    private var pillTouchView: PillTouchHandlerView? = null
    private var pillTouchParams: WindowManager.LayoutParams? = null
    private var pillDragAnimJob: kotlinx.coroutines.Job? = null
    // WINDOW CENTERING INVARIANT: every window this service attaches is
    // horizontally centered (x = 0) in every state — expanded, collapsed, and
    // narrow-fallback alike. The collapsed content is screen-anchored (all
    // targets include "- screenCenter" in IslandOverlayView), so
    // renderedX = windowCenter + target = screenCenter + target holds purely by
    // geometry. The delayed expanded→collapsed resize only narrows the clip
    // bounds AROUND the content and can therefore never displace it sideways,
    // with no per-frame window animation and no window-center compensation
    // flow (both removed — their per-frame updateViewLayout churn plus the
    // unavoidable one-frame lag between the relayout and the Compose
    // recomposition made the collapsed island visibly shake).
    // The EXPANDED window is MATCH_PARENT in both axes on every device (the
    // original upstream's mechanics). A content-sized expanded window (a
    // former touch-passthrough experiment) made every collapse a mid-morph
    // window resize whose surface-relayout transient displaced the rendered
    // pill and bubbles LEFT for a few frames — the root cause of the
    // "collapse jumps left" bug report.
    private var lastForegroundPackage: String? = null
    private var launcherPackage: String? = null

    private val torchCallback = object : android.hardware.camera2.CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (destroyed || !::viewModel.isInitialized) return
            if (enabled) {
                notificationRepository.postNotification(
                    IslandNotification(
                        key = "system_flashlight",
                        packageName = "com.android.systemui",
                        appName = "Flashlight",
                        title = "Flashlight ON",
                        text = "Tap to turn off",
                        mode = com.agupta07505.smartisland.model.IslandMode.Flashlight,
                        timeMillis = System.currentTimeMillis(),
                        actionIntents = listOf(
                            com.agupta07505.smartisland.model.IslandNotificationAction("Turn Off", null)
                        )
                    ),
                    autoExpand = true
                )
            } else {
                notificationRepository.removeNotification("system_flashlight")
            }
        }
    }

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, error ->
                android.util.Log.e(TAG, "Unhandled overlay coroutine failure", error)
            }
    )

    // Monitor screen state and unlock events to show/hide the island accordingly
    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatchingLogged(TAG, "Screen-state callback failed") {
                if (destroyed || !::viewModel.isInitialized) return@runCatchingLogged
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        overlayOwners.resume()
                        isLockScreenActive = keyguardManager?.isKeyguardLocked == true
                        isShadeOpen = false
                        updateWindowLayoutParams(
                            isWindowExpanded,
                            viewModel.settings.value
                        )
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        overlayOwners.pause()
                        isLockScreenActive = true
                        isShadeOpen = false
                        updateWindowLayoutParams(
                            isWindowExpanded,
                            viewModel.settings.value
                        )
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        isLockScreenActive = false
                        updateWindowLayoutParams(
                            isWindowExpanded,
                            viewModel.settings.value
                        )
                    }
                }
            }
        }
    }

    // Fallback sync: check if keyguard locked state changed on window changes.
    // Wrapped: an uncaught throw here makes Android auto-disable the
    // AccessibilityService, which is exactly the "turns off by itself" symptom.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatchingLogged(TAG, "onAccessibilityEvent failed") {
            if (destroyed || !::viewModel.isInitialized) return@runCatchingLogged

            // Keyguard fallback sync runs ONLY on window transitions:
            // isKeyguardLocked() is a binder call, and this service also receives
            // high-frequency TYPE_WINDOW_CONTENT_CHANGED events (list scrolls,
            // progress bars, ...) that previously triggered it on every tick.
            // Lock/unlock on content-only changes is already covered by the
            // SCREEN_ON / SCREEN_OFF / USER_PRESENT receiver above.
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val locked = keyguardManager?.isKeyguardLocked == true
                if (isLockScreenActive != locked) {
                    isLockScreenActive = locked
                    updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
                }
            }

            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val openedPackage = event.packageName?.toString()
                val openedClass = event.className?.toString() ?: ""

                // Reverse "app shrinks into the island" illusion: whenever the
                // launcher comes back to the foreground (app closed / home
                // pressed) and the island is collapsed, replay a spring
                // scale-in from the punch hole. A third-party app cannot animate
                // another app's window exit, so this replay is the closest
                // supported effect (see docs/BLUETOOTH_TOGGLE_AND_UI_NOTES.md).
                if (!openedPackage.isNullOrEmpty() && openedPackage != packageName) {
                    val launcher = launcherPackage ?: resolveLauncherPackage().also { launcherPackage = it }
                    if (launcher != null &&
                        openedPackage == launcher &&
                        lastForegroundPackage != launcher &&
                        ::viewModel.isInitialized &&
                        !viewModel.expanded.value
                    ) {
                        viewModel.notifyIslandReappeared()
                    }
                    lastForegroundPackage = openedPackage
                }

                val shadeToggled = if (openedPackage == "com.android.systemui") {
                    isShadeOpen = isNotificationShadeWindow(event)
                    true
                } else if (!openedPackage.isNullOrEmpty() && openedPackage != packageName) {
                    if (isShadeOpen) {
                        isShadeOpen = false
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
                if (shadeToggled) {
                    android.util.Log.d(TAG, "Shade state changed: isShadeOpen=$isShadeOpen")
                    updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
                }

                if (!openedPackage.isNullOrEmpty() &&
                    openedPackage != packageName &&
                    openedPackage != "com.android.systemui"
                ) {
                    viewModel.foregroundPackage.value = openedPackage
                    // Only clear plain chat/app notifications when their app is opened.
                    // System status islands (Hotspot, Battery, Flashlight, Screen
                    // Recording, ...) must survive even when e.g. Settings is opened,
                    // otherwise the hotspot card disappears while expanded.
                    val hasPlainNotifications = notificationRepository.notifications.value.any {
                        it.packageName == openedPackage && it.mode == com.agupta07505.smartisland.model.IslandMode.Notification
                    }
                    if (hasPlainNotifications) {
                        notificationRepository.removeNotificationsForPackage(openedPackage)
                    }
                }
            }

            if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                // Fallback: some ROMs report the shade via windows-changed events only.
                val shadeNow = detectShadeFromWindows()
                if (shadeNow != isShadeOpen) {
                    isShadeOpen = shadeNow
                    updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
                }
            }
        }
    }

    override fun onInterrupt() {
        // Required override, no-op
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Wrapped: a throw here would make Android disable the service automatically.
        runCatchingLogged(TAG, "onConfigurationChanged failed") {
            if (destroyed || !::viewModel.isInitialized) return@runCatchingLogged
            updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
        }
    }

    override fun onCreate() {
        super.onCreate()
        destroyed = false

        runCatchingLogged(TAG, "createNotificationChannel failed") {
            createNotificationChannel()
        }

        val resolvedWindowManager = runCatchingLogged(TAG, "WindowManager initialization failed") {
            getSystemService(WindowManager::class.java)
        }
        if (resolvedWindowManager == null) {
            android.util.Log.e(TAG, "WindowManager is unavailable; overlay cannot start")
            return
        }
        windowManager = resolvedWindowManager

        val initializedViewModel = runCatchingLogged(TAG, "Overlay ViewModel initialization failed") {
            // Lifecycle must be restored before the service-owned ViewModel is created.
            overlayOwners.resume()
            ViewModelProvider(
                overlayOwners,
                IslandViewModel.provideFactory(repository, notificationRepository)
            )[IslandViewModel::class.java]
        }
        if (initializedViewModel == null) {
            android.util.Log.e(TAG, "Overlay ViewModel is unavailable; overlay cannot start")
            return
        }
        viewModel = initializedViewModel
        
        systemEventReceiver = SystemEventReceiver(notificationRepository)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        
        // CRASH FIX: Android 13+/14+ requires explicit export flag for system broadcasts
        runCatchingLogged(TAG, "registerReceiver failed") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(systemEventReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(systemEventReceiver, filter)
            }
            systemEventReceiverRegistered = true
        }

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        isLockScreenActive = keyguardManager?.isKeyguardLocked == true

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatchingLogged(TAG, "registerReceiver screenStateReceiver failed") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenStateReceiver, screenFilter)
            }
            screenStateReceiverRegistered = true
        }

        runCatchingLogged(TAG, "registerTorchCallback failed") {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            cameraManager?.registerTorchCallback(torchCallback, android.os.Handler(android.os.Looper.getMainLooper()))
            torchCallbackRegistered = true
        }

        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "Settings collector failed") {
                repository.settings.collect { settings ->
                    if (destroyed) return@collect
                    if (!settings.enabled) {
                        stopOverlaySession()
                    } else {
                        startOverlaySession(settings)
                    }
                }
            }
        }

        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "Expanded-state collector failed") {
                viewModel.expanded.collectLatest { expanded ->
                    if (destroyed || !viewModel.settings.value.enabled) {
                        return@collectLatest
                    }
                    collapseJob?.cancel()
                    if (expanded) {
                        isWindowExpanded = true
                        // Two-window architecture: expanding only flips the
                        // content window's touch flags and removes the (always
                        // transparent) pill touch-catcher. The window frame
                        // itself never changes size, so there is nothing to
                        // mask and nothing that can ghost.
                        updateWindowLayoutParams(true, viewModel.settings.value)
                        // Pre-bind the Shizuku user service while the menu is
                        // opening so the first hotspot tap dispatches
                        // immediately instead of paying the cold-start bind
                        // latency (fire-and-forget; the toggle rebinds on
                        // demand if this never completes).
                        if (viewModel.notifications.value.isEmpty()) {
                            serviceScope.launch {
                                runSuspendCatchingLogged(TAG, "Tethering service warmup failed") {
                                    ShizukuManager.warmUpTetheringUserService()
                                }
                            }
                        }
                    } else {
                        collapseJob = serviceScope.launch {
                            kotlinx.coroutines.delay(AUTO_COLLAPSE_DELAY_MS)
                            isWindowExpanded = false
                            // Same flag-only swap, delayed until the collapse
                            // springs are visually settled: the morph plays out
                            // entirely inside the stable full-screen window and
                            // the touch handover afterwards is invisible.
                            updateWindowLayoutParams(false, viewModel.settings.value)
                        }
                    }
                }
            }
        }

        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "Notifications-state collector failed") {
                viewModel.notifications.collectLatest {
                    if (destroyed || !viewModel.settings.value.enabled) {
                        return@collectLatest
                    }
                    updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
                }
            }
        }

        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "Input-active collector failed") {
                viewModel.isInputActive.collectLatest {
                    if (destroyed || !viewModel.settings.value.enabled) {
                        return@collectLatest
                    }
                    updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isSystemConnected = true
        if (destroyed || !::viewModel.isInitialized) return
        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "Service reconnect failed") {
                val settings = repository.settings.first()
                if (settings.enabled) {
                    startOverlaySession(settings)
                } else {
                    stopOverlaySession()
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isSystemConnected = false
        // Return true so Android system knows to re-bind the accessibility service automatically
        return true
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        runCatchingLogged(TAG, "onTaskRemoved recovery failed") {
            if (!destroyed &&
                ::viewModel.isInitialized &&
                viewModel.settings.value.enabled
            ) {
                ensureForegroundStarted()
                ensureCollapsedWindow()
                // The window (re)creation above must be followed by the full
                // window sync or a recreated collapsed state would be left
                // without its pill touch-catcher (untouchable pill).
                updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
            }
        }
    }

    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        isSystemConnected = false
        serviceScope.cancel()

        if (::systemEventReceiver.isInitialized && systemEventReceiverRegistered) {
            runCatchingLogged(TAG, "unregisterReceiver failed") {
                unregisterReceiver(systemEventReceiver)
            }
            systemEventReceiverRegistered = false
        }
        if (screenStateReceiverRegistered) {
            runCatchingLogged(TAG, "unregisterReceiver screenStateReceiver failed") {
                unregisterReceiver(screenStateReceiver)
            }
            screenStateReceiverRegistered = false
        }
        if (torchCallbackRegistered) {
            runCatchingLogged(TAG, "unregisterTorchCallback failed") {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                cameraManager?.unregisterTorchCallback(torchCallback)
            }
            torchCallbackRegistered = false
        }

        removeCollapsedWindow()
        stopForegroundSafely()
        runCatchingLogged(TAG, "Overlay owners destroy failed") {
            overlayOwners.destroy()
        }
        super.onDestroy()
    }

    private fun startOverlaySession(settings: SmartIslandSettings) {
        if (destroyed || !::windowManager.isInitialized || !::viewModel.isInitialized) return
        ensureForegroundStarted()
        ensureCollapsedWindow()
        updateWindowLayoutParams(isWindowExpanded, settings)
    }

    private fun stopOverlaySession() {
        removeCollapsedWindow()
        stopForegroundSafely()
        if (::viewModel.isInitialized) {
            viewModel.collapse()
        }
    }

    private fun ensureForegroundStarted() {
        if (foregroundStarted || destroyed) return
        runCatchingLogged(TAG, "startForeground failed") {
            startForeground(NOTIFICATION_ID, buildNotification())
            foregroundStarted = true
        }
    }

    private fun stopForegroundSafely() {
        if (!foregroundStarted) return
        runCatchingLogged(TAG, "stopForeground failed") {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        foregroundStarted = false
    }

    private val statusBarHeight: Float
        get() {
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val heightPx = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
            val heightDp = heightPx / resources.displayMetrics.density
            return if (heightDp > 0f) heightDp else 24f
        }

    private fun ensureCollapsedWindow() {
        if (destroyed ||
            islandView != null ||
            !::windowManager.isInitialized ||
            !::viewModel.isInitialized
        ) return
        try {
            islandView = ComposeView(this).apply {
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val isLocked = keyguardManager?.isKeyguardLocked == true
                isLockScreenActive = isLocked
                val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                // LOCK SCREEN UNREAD: even when the island is hidden on the
                // lock screen, unopened notifications must still surface there
                // — with hideFromNotificationShade on they were cancelled from
                // the shade, so otherwise they are invisible EVERYWHERE until
                // unlock (the "they don't appear in the lock screen either"
                // report).
                val unreadOnLockScreen = viewModel.settings.value.showUnreadOnLockScreen &&
                    isLocked &&
                    viewModel.notifications.value.isNotEmpty()
                val isHidden = (!viewModel.settings.value.showOnLockScreen && isLocked && !unreadOnLockScreen) ||
                    (isLandscape && !viewModel.settings.value.showInLandscape) ||
                    (viewModel.settings.value.hideWhenShadeOpen && isShadeOpen && !suppressShadeHide)
                visibility = if (isHidden) android.view.View.GONE else android.view.View.VISIBLE

                installOverlayViewTreeOwners()
                isFocusable = true
                isFocusableInTouchMode = true
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    OverlayIsland(
                        viewModel = this@SmartIslandOverlayService.viewModel,
                        statusBarHeight = statusBarHeight,
                        onOpenNotification = { notification -> openNotification(notification) },
                        onLaunchApp = { packageName -> launchApp(packageName) },
                        onOpenFloatingWindow = { openCurrentNotificationInFloatingWindow() },
                        onOpenIdleInfoItem = { item -> openIdleInfoItem(item) }
                    )
                }

                setupTouchableRegion(this)
            }
            runCatchingLogged(TAG, "windowManager.addView failed") {
                windowManager.addView(islandView, collapsedParams(viewModel.settings.value))
            } ?: run {
                islandView = null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "ensureCollapsedWindow fatal", e)
            islandView = null
        }
    }

    // Use reflection to set up OnComputeInternalInsetsListener since it is a hidden system API.
    // This allows the overlay window to pass through touches outside the pill boundary.
    // Keep the suppression local: this best-effort workaround is guarded by
    // runCatchingLogged so unsupported devices fall back without crashing.
    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    private fun setupTouchableRegion(view: ComposeView) {
        android.util.Log.d(TAG, "setupTouchableRegion: starting registration for view=$view")
        runCatchingLogged(TAG, "Failed to setup touchable region") {
            val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val insetsClass = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            
            val setTouchableInsetsMethod = insetsClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
            val touchableRegionField = insetsClass.getDeclaredField("touchableRegion").apply {
                isAccessible = true
            }
            
            // InternalInsetsInfo touchable insets options
            val TOUCHABLE_INSETS_FRAME = 0
            val TOUCHABLE_INSETS_REGION = 3
            
            // Create a dynamic proxy implementation of OnComputeInternalInsetsListener
            val proxyListener = java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "onComputeInternalInsets" && args != null && args.isNotEmpty()) {
                    val insets = args[0]
                    val isExpanded = viewModel.expanded.value
                    val isGone = view.visibility == android.view.View.GONE
                    android.util.Log.d(TAG, "onComputeInternalInsets callback: isExpanded=$isExpanded isGone=$isGone")
                    if (isGone) {
                        setTouchableInsetsMethod.invoke(insets, TOUCHABLE_INSETS_REGION)
                        val region = touchableRegionField.get(insets) as android.graphics.Region
                        region.setEmpty()
                    } else if (isExpanded) {
                        // When expanded, let the entire frame intercept touches so clicking outside collapses it
                        setTouchableInsetsMethod.invoke(insets, TOUCHABLE_INSETS_FRAME)
                    } else {
                        // PILL-ONLY TOUCHABLE REGION:
                        // Restrict touch interception to ONLY the pill bounds + padding.
                        // The region is local to this already-offset window. Touches outside
                        // the visible collapsed group pass through to the system.
                        setTouchableInsetsMethod.invoke(insets, TOUCHABLE_INSETS_REGION)
                        
                        val density = resources.displayMetrics.density
                        val screenWidth = resources.displayMetrics.widthPixels
                        val settingsVal = viewModel.settings.value
                        val notificationsCount = viewModel.notifications.value.size
                        val isSplitMode = notificationsCount >= 2

                        // IDLE pill awareness: with no notifications and the
                        // cutout-size mode on, the RENDERED pill is the idle
                        // one (IslandOverlayView effectiveWidth/Height) — the
                        // touch region must match it, not the wide island.
                        val isIdlePill = notificationsCount == 0 && settingsVal.useCutoutSizeWhenIdle
                        val pillWidthDp = if (isIdlePill) settingsVal.idleWidth else settingsVal.width
                        val pillHeightDp = if (isIdlePill) settingsVal.idleHeight else settingsVal.height
                        // The idle pill's X is its own setting (idleXOffset)
                        // whenever the pill is the IDLE one (no notifications)
                        // — mirroring IslandOverlayView's collapsedMainOffset,
                        // which keys the X on the idle state alone, not on the
                        // cutout-size mode (that only keys the WIDTH). The wide
                        // island's xOffset must not move the idle pill.
                        val pillXOffsetDp = if (notificationsCount == 0) settingsVal.idleXOffset else settingsVal.xOffset

                        val mainWidthPx = pillWidthDp * density
                        // Same group width as collapsedParams(): with 3+
                        // notifications the tertiary circle is drawn too and
                        // must stay inside the touchable region.
                        val groupWidthPx = (
                            pillWidthDp + when {
                                notificationsCount >= 3 -> 2 * (8f + settingsVal.height)
                                isSplitMode -> 8f + settingsVal.height
                                else -> 0f
                            }
                        ) * density
                        val edgePaddingPx = 8f * density
                        val touchPaddingPx = 6f * density
                        val pillHeightPx = (pillHeightDp + 16f) * density

                        val desiredMainLeftPx = screenWidth / 2f +
                            pillXOffsetDp * density - mainWidthPx / 2f
                        val maxMainLeftPx = (screenWidth - groupWidthPx - edgePaddingPx)
                            .coerceAtLeast(edgePaddingPx)
                        val mainLeftPx = desiredMainLeftPx.coerceIn(edgePaddingPx, maxMainLeftPx)
                        // The collapsed WIDE pill renders at window-y +
                        // (yOffset - idleYOffset) — the precision "vertical
                        // offset" (IslandOverlayView collapsedWideYDelta); the
                        // idle pill renders at window-y. The region is
                        // window-local, so the delta shifts the band's top.
                        val topPx = (if (notificationsCount == 0) 0f
                            else settingsVal.yOffset - settingsVal.idleYOffset) * density
                        val left = (mainLeftPx - touchPaddingPx).toInt()
                        val top = topPx.toInt()
                        val right = (mainLeftPx + groupWidthPx + touchPaddingPx).toInt()
                        val bottom = (topPx + pillHeightPx).toInt()
                        
                        android.util.Log.d(TAG, "onComputeInternalInsets: region set to ($left, $top, $right, $bottom), isSplitMode=$isSplitMode")
                        val region = touchableRegionField.get(insets) as android.graphics.Region
                        region.set(left, top, right, bottom)
                    }
                }
                null
            }
            
            val registerListener = {
                val observer = view.viewTreeObserver
                android.util.Log.d(TAG, "registerListener lambda: viewTreeObserver=$observer, isAlive=${observer.isAlive}")
                if (observer.isAlive) {
                    val addListenerMethod = observer.javaClass.getMethod(
                        "addOnComputeInternalInsetsListener",
                        listenerClass
                    )
                    addListenerMethod.invoke(observer, proxyListener)
                    isTouchableRegionSupported = true
                    android.util.Log.d(TAG, "OnComputeInternalInsetsListener successfully registered on live ViewTreeObserver")
                    if (::viewModel.isInitialized) {
                        // The insets listener just came alive (reflection
                        // class): the content window must drop its collapsed
                        // FLAG_NOT_TOUCHABLE and the pill touch-catcher window
                        // is no longer needed. Re-run the window sync — on
                        // this device class the window frame itself never
                        // changes size (MATCH_PARENT in every state), so this
                        // is a pure invisible flag/child-window swap.
                        serviceScope.launch {
                            runSuspendCatchingLogged(TAG, "TouchableRegion window sync failed") {
                                updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
                            }
                        }
                    }
                }
            }
            
            // ViewTreeObserver changes when the view is attached to a window.
            // We must register the listener on the live ViewTreeObserver of the attached window.
            android.util.Log.d(TAG, "setupTouchableRegion: isAttachedToWindow=${view.isAttachedToWindow}")
            if (view.isAttachedToWindow) {
                registerListener()
            } else {
                view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: android.view.View) {
                        android.util.Log.d(TAG, "onViewAttachedToWindow: registering listener now")
                        registerListener()
                    }
                    override fun onViewDetachedFromWindow(v: android.view.View) {
                        android.util.Log.d(TAG, "onViewDetachedFromWindow called")
                    }
                })
            }
        } ?: run {
            isTouchableRegionSupported = false
            android.util.Log.w(TAG, "Touchable region reflection unsupported or blocked, falling back to physical bounds")
        }
    }

    private fun updateWindowLayoutParams(expanded: Boolean, settings: SmartIslandSettings) {
        if (destroyed || !::windowManager.isInitialized || !::viewModel.isInitialized) return
        val view = islandView ?: return

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked == true
        isLockScreenActive = isLocked
        viewModel.isLocked.value = isLocked
        
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // LOCK SCREEN UNREAD: even when the island is hidden on the lock
        // screen, unopened notifications must still surface there — with
        // hideFromNotificationShade on they were cancelled from the shade, so
        // otherwise they are invisible EVERYWHERE until unlock (the "they
        // don't appear in the lock screen either" report).
        val notificationCount = viewModel.notifications.value.size
        val unreadOnLockScreen = settings.showUnreadOnLockScreen &&
            isLocked &&
            notificationCount > 0
        val isHidden = (!settings.showOnLockScreen && isLocked && !unreadOnLockScreen) ||
            (isLandscape && !settings.showInLandscape) ||
            (settings.hideWhenShadeOpen && isShadeOpen && !suppressShadeHide)

        val targetVisibility = if (isHidden) android.view.View.GONE else android.view.View.VISIBLE
        if (view.visibility != targetVisibility) {
            // GONE -> VISIBLE means the island is coming back (app closed, shade
            // closed, unlock, ...): let the overlay replay its scale-in
            // "reappear" animation for the reverse-shrink illusion.
            if (targetVisibility == android.view.View.VISIBLE &&
                view.visibility == android.view.View.GONE &&
                ::viewModel.isInitialized
            ) {
                viewModel.notifyIslandReappeared()
            }
            view.visibility = targetVisibility
        }

        // TWO-WINDOW ARCHITECTURE: the content window is MATCH_PARENT in BOTH
        // axes in EVERY state on EVERY device class — the exact frame the
        // original upstream uses while expanded, now permanent. Expand and
        // collapse therefore NEVER resize this window: there is no surface
        // relayout transient to ghost (the "duplicate island") and nothing to
        // mask (the end-of-collapse blink). Touch containment is solved per
        // device class instead:
        //  - WITH the touchableRegion reflection: the insets listener
        //    restricts touches to the pill group (unchanged).
        //  - WITHOUT it: while collapsed the window carries
        //    FLAG_NOT_TOUCHABLE and a separate fully transparent pill
        //    touch-catcher window owns the collapsed gestures (see
        //    syncPillTouchWindow). The catcher has no visible content, so its
        //    add/remove/resize can never produce a visible artifact.
        val h = WindowManager.LayoutParams.MATCH_PARENT
        val w = WindowManager.LayoutParams.MATCH_PARENT
        val isInput = viewModel.isInputActive.value && expanded
        val contentUntouchable = suppressShadeHide ||
            (!isTouchableRegionSupported && !expanded)
        val focusFlags = if (isInput) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val currentFlags = focusFlags or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (contentUntouchable) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

        // WINDOW-Y DECOUPLING: the overlay window always sits at the IDLE
        // pill's y (settings.idleYOffset). The wide island's yOffset is applied
        // INSIDE Compose (IslandOverlayView adds yOffset - idleYOffset to the
        // expanded card's top offset), so the window itself NEVER moves — the
        // precision-tuning "wide island Y" slider can no longer drag the idle
        // punch-hole pill with it, and no window position transient can ghost
        // or snap the morph either.
        val currentY = settings.idleYOffset.dpToPx()
        val currentSoftInputMode = if (isInput) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        } else {
            0
        }

        val currentX = 0
        // The pill touch-catcher must be synced on EVERY call (its bounds
        // depend on the notification count, which can change while the content
        // window's params stay identical) — so it runs BEFORE the dedup
        // early-return below.
        syncPillTouchWindow(expanded, settings, isHidden)
        val lp = lastParams
        if (lp != null &&
            lp.width == w &&
            lp.height == h &&
            lp.flags == currentFlags &&
            lp.x == currentX &&
            lp.y == currentY &&
            lp.softInputMode == currentSoftInputMode
        ) {
            return
        }

        val params = WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            currentFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = currentX
            y = currentY
            softInputMode = currentSoftInputMode
        }
        lastParams = params
        runCatchingLogged(TAG, "Failed to update view layout") { 
            windowManager.updateViewLayout(view, params) 
        }
    }

    /**
     * Keeps the pill TOUCH-CATCHER window in sync (devices WITHOUT the
     * touchableRegion reflection). The catcher exists exactly while
     *  - the island is collapsed,
     *  - the content window is visible,
     *  - the shade-hide suppression is off, and
     *  - the insets reflection is unavailable.
     * It is a fully transparent window sized over the collapsed pill group
     * (the exact footprint of the old narrow window), so all of its
     * add/remove/resize operations are invisible by construction — the
     * structural replacement for the masked resize (and its end-of-collapse
     * blink) that this class previously needed.
     */
    private fun syncPillTouchWindow(
        expanded: Boolean,
        settings: SmartIslandSettings,
        contentHidden: Boolean
    ) {
        if (destroyed || !::windowManager.isInitialized || !::viewModel.isInitialized) return
        if (islandView == null) {
            // No content window: a catcher alone would be an invisible touch
            // sink over the pill area.
            removePillTouchWindow()
            return
        }
        val needed = !expanded &&
            !contentHidden &&
            !suppressShadeHide &&
            !isTouchableRegionSupported
        if (!needed) {
            removePillTouchWindow()
            return
        }

        val density = resources.displayMetrics.density
        val notificationCount = viewModel.notifications.value.size
        val widthPx = collapsedWindowWidthPx(settings, notificationCount, density)
        val heightPx = ((settings.height + 16f) * density).toInt()
        // The catcher's band must cover the pill's SCREEN band: the idle pill
        // renders at idleYOffset, the collapsed WIDE island at its own
        // precision yOffset (IslandOverlayView applies the delta inside
        // Compose) — mirror that mapping here, or touches would land one
        // (yOffset - idleYOffset) band above the visible pill.
        val yPx = (if (notificationCount == 0) settings.idleYOffset else settings.yOffset).dpToPx()
        val existing = pillTouchView
        if (existing == null) {
            val view = PillTouchHandlerView(this).apply {
                listener = pillTouchListener
                holdThresholdMs = PILL_HOLD_THRESHOLD_MS
            }
            val params = WindowManager.LayoutParams(
                widthPx,
                heightPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Same centering invariant as the content window (x = 0).
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = yPx
            }
            runCatchingLogged(TAG, "Failed to add pill touch-catcher window") {
                windowManager.addView(view, params)
                pillTouchView = view
                pillTouchParams = params
                applyPillTouchGeometry(view, settings, notificationCount, widthPx, heightPx)
            } ?: run {
                pillTouchView = null
                pillTouchParams = null
            }
        } else {
            val params = pillTouchParams
            if (params != null &&
                (params.width != widthPx || params.height != heightPx || params.y != yPx)
            ) {
                params.width = widthPx
                params.height = heightPx
                params.y = yPx
                runCatchingLogged(TAG, "Failed to resize pill touch-catcher window") {
                    windowManager.updateViewLayout(existing, params)
                }
            }
            applyPillTouchGeometry(existing, settings, notificationCount, widthPx, heightPx)
        }
    }

    private fun removePillTouchWindow() {
        val view = pillTouchView ?: return
        pillTouchView = null
        pillTouchParams = null
        // A removal mid-gesture also kills the drag: settle the pill back.
        springPillDragBack()
        if (!::windowManager.isInitialized) return
        runCatchingLogged(TAG, "Failed to remove pill touch-catcher window") {
            if (view.isAttachedToWindow) {
                windowManager.removeViewImmediate(view)
            }
        }
    }

    /** Streams the collapsed-pill gestures into the ViewModel/content. */
    private val pillTouchListener = object : PillTouchHandlerView.Listener {
        override fun onTouchDown() {
            if (destroyed || !::viewModel.isInitialized) return
            pillDragAnimJob?.cancel()
            pillDragAnimJob = null
        }

        override fun onTouchMove(totalDyPx: Float) {
            if (destroyed || !::viewModel.isInitialized) return
            // While the pill is UI-hidden (auto-hide / hide-when-idle) only
            // reveal-taps matter; the invisible pill must not chase drags.
            if (viewModel.isPillUiHidden.value) return
            val maxPx = PILL_DRAG_MAX_DP * resources.displayMetrics.density
            viewModel.pillDragOffsetPx.value = totalDyPx.coerceIn(-maxPx, maxPx)
        }

        override fun onTouchUp(
            totalDyPx: Float,
            isDragging: Boolean,
            holdRegistered: Boolean,
            elapsedMs: Long,
            downX: Float,
            downY: Float
        ) {
            if (destroyed || !::viewModel.isInitialized) return
            resolvePillTouchUp(
                totalDyPx,
                isDragging,
                holdRegistered,
                elapsedMs,
                downX,
                downY
            )
        }

        override fun onTouchCancelled() {
            if (destroyed || !::viewModel.isInitialized) return
            springPillDragBack()
        }

        override fun onHoldRegistered() {
            if (destroyed) return
            triggerPillHoldHaptic()
        }
    }

    /**
     * Resolves the end of a collapsed-pill gesture with EXACTLY the semantics
     * of the Compose collapsed-pill pointer handler (IslandOverlayView):
     * swipe-up past 35dp fires the configured dismiss action (hold selects
     * holdSwipeUpAction, a quick swipe swipeUpAction), a release under 10px
     * is a tap (bubble hit → select + expand, otherwise the configured
     * tapAction), and while the pill is UI-hidden only in-band reveal taps
     * fire.
     */
    private fun resolvePillTouchUp(
        totalDyPx: Float,
        isDragging: Boolean,
        holdRegistered: Boolean,
        elapsedMs: Long,
        downX: Float,
        downY: Float
    ) {
        val view = pillTouchView
        val settings = viewModel.settings.value
        val density = resources.displayMetrics.density
        val swipeUpThresholdPx = -PILL_SWIPE_THRESHOLD_DP * density
        val uiHidden = viewModel.isPillUiHidden.value
        val bubbleIndex = view?.bubbleIndexAt(downX, downY) ?: -1

        var firedSwipeAction = false
        if (!uiHidden &&
            bubbleIndex < 0 &&
            isDragging &&
            totalDyPx < swipeUpThresholdPx
        ) {
            val action = if (holdRegistered || elapsedMs >= PILL_HOLD_THRESHOLD_MS) {
                settings.holdSwipeUpAction
            } else {
                settings.swipeUpAction
            }
            when (action) {
                SmartIslandSettings.GestureActions.DISMISS_ALL -> {
                    firedSwipeAction = true
                    viewModel.dismissAllNotifications()
                }
                SmartIslandSettings.GestureActions.DISMISS -> {
                    firedSwipeAction = true
                    viewModel.dismissCurrentNotification()
                }
                else -> Unit
            }
        }

        if (firedSwipeAction) {
            // Fired an action: snap to rest so the transition runs from a
            // settled pill with nothing else moving (Compose parity).
            pillDragAnimJob?.cancel()
            viewModel.pillDragOffsetPx.value = 0f
        } else {
            springPillDragBack()
        }

        val isTap = !isDragging || kotlin.math.abs(totalDyPx) < 10f
        if (!isTap) return
        if (uiHidden) {
            // Hidden pill: only taps inside the pill's own band reveal it
            // (parity with the in-Compose hidden tap target).
            if (view?.isInsidePillBand(downX, downY) == true) {
                viewModel.requestPillReveal()
            }
            return
        }
        if (bubbleIndex >= 0) {
            // Companion bubble tap: select that notification and expand
            // (parity with the Compose bubbles' clickables).
            val list = viewModel.visibleNotifications.value
            val secondary = list.firstOrNull { it.key != list.getOrNull(viewModel.selectedIndex.value)?.key }
            val tertiary = list.firstOrNull {
                it.key != list.getOrNull(viewModel.selectedIndex.value)?.key && it.key != secondary?.key
            }
            val target = if (bubbleIndex == 0) secondary else tertiary
            val index = target?.let { t -> list.indexOfFirst { it.key == t.key } } ?: -1
            if (index >= 0) {
                viewModel.setSelectedNotificationIndex(index)
            }
            viewModel.toggleExpanded()
        } else {
            when (settings.tapAction) {
                SmartIslandSettings.GestureActions.TOGGLE -> viewModel.toggleExpanded()
                else -> Unit
            }
        }
    }

    /**
     * Fills the catcher's pill/bubble hit geometry (view-local rectangles).
     * Mirrors collapsedWindowWidthPx + IslandOverlayView's collapsedMainLeft
     * so a tap lands on exactly the bubble/pill the user sees.
     */
    private fun applyPillTouchGeometry(
        view: PillTouchHandlerView,
        settings: SmartIslandSettings,
        notificationCount: Int,
        viewWidthPx: Int,
        viewHeightPx: Int
    ) {
        val density = resources.displayMetrics.density
        val metrics = resources.displayMetrics
        val isIdlePill = notificationCount == 0 && settings.useCutoutSizeWhenIdle
        val mainWidthDp = if (isIdlePill) settings.idleWidth else settings.width
        // Mirror IslandOverlayView's collapsedMainOffset: the X follows the
        // IDLE offset whenever no notification is shown, regardless of the
        // cutout-size mode (which only keys the WIDTH).
        val pillXOffsetDp = if (notificationCount == 0) settings.idleXOffset else settings.xOffset
        val mainWidthPx = mainWidthDp * density
        val circleSizePx = settings.height * density
        val gapPx = 8f * density
        val edgePaddingPx = 8f * density
        val touchPaddingPx = 6f * density

        val companionExtraPx = when {
            notificationCount >= 3 -> 2 * (gapPx + circleSizePx)
            notificationCount >= 2 -> gapPx + circleSizePx
            else -> 0f
        }
        val screenCenter = metrics.widthPixels / 2f
        val desiredMainLeftPx = screenCenter + pillXOffsetDp * density - mainWidthPx / 2f
        val maxMainLeftPx = (metrics.widthPixels - companionExtraPx - mainWidthPx - edgePaddingPx)
            .coerceAtLeast(edgePaddingPx)
        val mainLeftPx = desiredMainLeftPx.coerceIn(edgePaddingPx, maxMainLeftPx)

        // The catcher window is horizontally centered (x = 0), so its left
        // edge on screen is (screenWidth - viewWidth) / 2. The band height is
        // the window height — NOT view.height, which is still 0 before the
        // first layout pass on the add path.
        val windowLeftPx = (metrics.widthPixels - viewWidthPx) / 2f
        val bandBottom = viewHeightPx.toFloat().coerceAtLeast(1f)

        val pillRect = RectF(
            mainLeftPx - windowLeftPx - touchPaddingPx,
            0f,
            mainLeftPx - windowLeftPx + mainWidthPx + touchPaddingPx,
            bandBottom
        )
        val secondaryLeft = mainLeftPx + mainWidthPx + gapPx
        val secondaryRect = if (notificationCount >= 2) {
            RectF(
                secondaryLeft - windowLeftPx,
                0f,
                secondaryLeft + circleSizePx - windowLeftPx,
                bandBottom
            )
        } else {
            RectF()
        }
        val tertiaryLeft = secondaryLeft + circleSizePx + gapPx
        val tertiaryRect = if (notificationCount >= 3) {
            RectF(
                tertiaryLeft - windowLeftPx,
                0f,
                tertiaryLeft + circleSizePx - windowLeftPx,
                bandBottom
            )
        } else {
            RectF()
        }
        view.setGestureGeometry(pillRect, secondaryRect, tertiaryRect)
    }

    /**
     * Bouncy return of the pill to its rest y after a released/cancelled drag.
     *
     * Implemented as a tiny semi-implicit spring integrator instead of
     * androidx Animatable: Animatable.animateTo needs a Compose
     * MonotonicFrameClock in the coroutine context, which the service scope
     * does not carry — a plain loop with ~16ms steps reproduces the same
     * MediumBouncy feel with zero frame-clock requirements.
     */
    private fun springPillDragBack() {
        pillDragAnimJob?.cancel()
        if (!::viewModel.isInitialized) return
        val start = viewModel.pillDragOffsetPx.value
        if (start == 0f) return
        pillDragAnimJob = serviceScope.launch {
            var value = start
            var velocity = 0f
            // spring(dampingRatio = MediumBouncy (0.5), stiffness = StiffnessMedium (1500))
            val stiffness = 1500f
            val damping = 2f * 0.5f * kotlin.math.sqrt(stiffness)
            val dt = 0.016f
            try {
                while (kotlin.math.abs(value) > 0.1f || kotlin.math.abs(velocity) > 1f) {
                    val acceleration = -stiffness * value - damping * velocity
                    velocity += acceleration * dt
                    value += velocity * dt
                    if (destroyed || !::viewModel.isInitialized) return@launch
                    viewModel.pillDragOffsetPx.value = value
                    delay(16)
                }
            } finally {
                if (!destroyed && ::viewModel.isInitialized) {
                    viewModel.pillDragOffsetPx.value = 0f
                }
            }
        }
    }

    /** Same hold feedback the Compose pill produces (device suppresses toasts). */
    private fun triggerPillHoldHaptic() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                val vibrator = vm?.defaultVibrator
                if (vibrator?.hasVibrator() == true) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(60L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    return
                }
            }
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(60L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(60L)
                }
            }
        }
    }

    /**
     * Width of the pill TOUCH-CATCHER window (devices without the
     * touchableRegion API). The catcher is horizontally CENTERED (x = 0, same
     * center as the content window) and sized to cover the screen-anchored
     * collapsed content: the main pill at screenCenter + xOffset, companion
     * bubbles extending to the right, plus the usual 16dp side padding.
     *
     * For a single notification (or idle) this is exactly the old
     * (groupWidth + 32dp) size. In split/tertiary mode it is wider than the
     * old group-centered window: a centered window must span the content's
     * right extent symmetrically, so the empty left half grows as well. That
     * extra transparent band is the deliberate price of the fixed window
     * center — it buys a collapsed state whose window never moves at all,
     * which is what makes the transition and the steady state jitter-free.
     *
     * Centering is what keeps the collapsed state stable: the catcher and the
     * content share the same center, so renderedX = screenCenter + target for
     * every element — no compensation, no animation, no jumps.
     */
    private fun collapsedWindowWidthPx(
        settings: SmartIslandSettings,
        notificationCount: Int,
        density: Float
    ): Int {
        // Idle pill awareness: mirrors IslandOverlayView's effectiveWidth and
        // collapsedMainOffset so the narrow window never clips a cutout-sized
        // idle pill (which can be wider or narrower than the wide island) and
        // stays centered on the IDLE pill's own x (idleXOffset).
        val isIdlePill = notificationCount == 0 && settings.useCutoutSizeWhenIdle
        val mainWidthDp = if (isIdlePill) settings.idleWidth else settings.width
        val mainWidthPx = mainWidthDp * density
        val circleSizePx = settings.height * density
        val compactGapPx = 8f * density
        val companionExtraPx = when {
            notificationCount >= 3 -> 2 * (compactGapPx + circleSizePx)
            notificationCount >= 2 -> compactGapPx + circleSizePx
            else -> 0f
        }
        val xOffsetPx = (if (notificationCount == 0) settings.idleXOffset else settings.xOffset) * density
        val sidePaddingPx = 16f * density
        val leftExtentPx = mainWidthPx / 2f - xOffsetPx
        val rightExtentPx = mainWidthPx / 2f + companionExtraPx + xOffsetPx
        // Floor the half-extent BEFORE doubling: the window width is then
        // always EVEN, so the CENTER_HORIZONTAL gravity centers it on an exact
        // integer pixel. An odd width would park the window on a half pixel
        // and let WindowManager's rounding disagree with Compose's by 1px.
        return 2 * (maxOf(leftExtentPx, rightExtentPx) + sidePaddingPx).toInt()
    }

    private fun removeCollapsedWindow() {
        val view = islandView ?: return
        // Clear the reference before removal so repeated teardown calls are harmless,
        // even when an OEM WindowManager throws while detaching an already-removed view.
        islandView = null
        lastParams = null
        isWindowExpanded = false
        collapseJob?.cancel()
        collapseJob = null
        removePillTouchWindow()
        if (!::windowManager.isInitialized) return
        runCatchingLogged(TAG, "Failed to remove view") {
            if (view.isAttachedToWindow) {
                windowManager.removeViewImmediate(view)
            }
        }
    }

    private fun collapsedParams(settings: SmartIslandSettings): WindowManager.LayoutParams {
        // TWO-WINDOW ARCHITECTURE: the content window is born MATCH_PARENT in
        // BOTH axes on EVERY device class and is never resized afterwards —
        // expand/collapse are pure flag/child-window swaps (see
        // updateWindowLayoutParams + syncPillTouchWindow). Touch containment
        // while collapsed comes from the insets-listener region (reflection
        // class) or the FLAG_NOT_TOUCHABLE + touch-catcher pair (no-reflection
        // class), never from a narrow frame.
        val currentFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            currentFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // WINDOW CENTERING INVARIANT: x = 0 in every window state.
            x = 0
            // Window always sits at the IDLE pill y (see updateWindowLayoutParams):
            // the wide island's yOffset is applied inside Compose instead.
            y = settings.idleYOffset.dpToPx()
        }.also {
            lastParams = it
        }
    }

    private fun openNotification(notification: IslandNotification) {
        val zoomOptions = buildZoomLaunchOptions(viewModel.settings.value)
        if (notification.contentIntent != null) {
            sendIntentWithOptions(this, notification.contentIntent, zoomOptions)
        } else {
            runCatchingLogged(TAG, "Failed to launch package activity") {
                when (notification.mode) {
                    com.agupta07505.smartisland.model.IslandMode.Bluetooth -> {
                        val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent, zoomOptions)
                    }
                    com.agupta07505.smartisland.model.IslandMode.Battery -> {
                        val intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent, zoomOptions)
                        } else {
                            val altIntent = Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(altIntent, zoomOptions)
                        }
                    }
                    com.agupta07505.smartisland.model.IslandMode.Hotspot -> {
                        val intent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent, zoomOptions)
                    }
                    com.agupta07505.smartisland.model.IslandMode.Timer,
                    com.agupta07505.smartisland.model.IslandMode.Stopwatch -> {
                        val launchIntent = packageManager.getLaunchIntentForPackage(notification.packageName)
                            ?: Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        if (launchIntent.resolveActivity(packageManager) != null) {
                            startActivity(launchIntent, zoomOptions)
                        } else {
                            val clockIntent = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(clockIntent, zoomOptions)
                        }
                    }
                    else -> {
                        val launchIntent = packageManager.getLaunchIntentForPackage(notification.packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent, zoomOptions)
                        } else {
                            Toast.makeText(this, "Opening ${notification.appName} (Demo)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        if (notification.mode != com.agupta07505.smartisland.model.IslandMode.Music) {
            notificationRepository.removeNotificationsForPackage(notification.packageName)
        }
        // Hide the overlay instantly so the app-open isn't held up by the
        // collapse animation; it returns when the app closes.
        runCatchingLogged(TAG, "hide island failed") {
            islandView?.visibility = android.view.View.GONE
        }
        viewModel.collapse()
    }

    private fun launchApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Toast.makeText(this, "App is no longer available", Toast.LENGTH_SHORT).show()
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatchingLogged(TAG, "Failed to launch shortcut app") {
            startActivity(launchIntent, buildZoomLaunchOptions(viewModel.settings.value))
        }
        notificationRepository.removeNotificationsForPackage(packageName)
        runCatchingLogged(TAG, "hide island failed") {
            islandView?.visibility = android.view.View.GONE
        }
        viewModel.collapse()
    }

    /**
     * Toggles Bluetooth from the idle info menu. The island never hides and the
     * menu never closes — the toggle runs entirely behind the overlay.
     *
     * Preferred path — Shizuku, three mechanisms tried in order: the shell-uid
     * user service's BluetoothAdapter call (see TetheringShizukuService
     * .setBluetoothEnabled), the hidden BluetoothManagerService commands
     * (`cmd bluetooth_manager enable|disable`), then `svc bluetooth
     * enable|disable`. All work in both directions on Android 12+, where
     * BluetoothAdapter.enable()/disable() return false for normal apps. No
     * dialogs, no settings pages, no shade pull-down.
     *
     * Fallback path — Quick Settings tile: used ONLY when Shizuku is entirely
     * offline (no binder). The accessibility service opens QS, waits for the
     * shade window, locates the Bluetooth tile (scrolling the tile carousel
     * if needed) and taps it with a synthetic gesture while the island window
     * carries FLAG_NOT_TOUCHABLE (suppressShadeHide also keeps the island
     * visible). QS is closed again with GLOBAL_ACTION_BACK. When Shizuku IS
     * reachable but every mechanism failed, the exact failure reason is shown
     * in-menu instead — the user must never see the QS panel from a failed
     * toggle (same rule as the hotspot toggle).
     *
     * The Shizuku path verifies the result through the permission-free
     * Settings.Global "bluetooth_on" switch and reports the failing stage
     * in-menu on failure.
     */
    private fun toggleBluetoothViaShade() {
        // Keep the island fully visible; make its window transparent to touches
        // while system UI below may need the tap (QS fallback path).
        suppressShadeHide = true
        if (::viewModel.isInitialized) {
            updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
        }
        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "Bluetooth toggle failed") {
                val before = isBluetoothOn()
                if (::viewModel.isInitialized) {
                    viewModel.postMenuFeedback(
                        if (before) "Turning Bluetooth off…" else "Turning Bluetooth on…"
                    )
                }
                var changed = false
                var shizukuAvailable = false
                var shizukuReason: String? = null

                // 1) Shizuku: shell-privileged toggle — reliable both ways.
                //    Availability + permission are checked inside, on IO.
                if (ShizukuManager.isBinderAvailable()) {
                    shizukuAvailable = true
                    val dispatched = ShizukuManager.toggleBluetooth(!before)
                    if (dispatched.isSuccess) {
                        changed = waitForBluetoothState(!before, timeoutMs = 4000L)
                        if (!changed) {
                            shizukuReason = "state did not change within 4s"
                        }
                    } else {
                        shizukuReason = dispatched.exceptionOrNull()?.message
                    }
                    android.util.Log.d(
                        TAG,
                        "Shizuku bluetooth toggle: dispatched=${dispatched.isSuccess} changed=$changed " +
                            "(reason=${shizukuReason ?: "ok"})"
                    )
                } else {
                    android.util.Log.d(
                        TAG,
                        "Shizuku unavailable (installed=${ShizukuManager.isInstalled(this@SmartIslandOverlayService)} " +
                            "binder=${ShizukuManager.isBinderAvailable()}); using QS tile fallback"
                    )
                }

                // 2) Fallback: pull down Quick Settings and tap the Bluetooth
                //    tile — ONLY when Shizuku is entirely offline (no binder).
                //    When Shizuku IS up but every mechanism failed, the exact
                //    failure reason is shown in-menu instead: the user must
                //    never see the QS panel from a failed toggle (same rule
                //    as the hotspot toggle).
                if (!changed && !shizukuAvailable) {
                    changed = toggleBluetoothViaQsTile(before)
                }

                if (::viewModel.isInitialized) {
                    if (changed) {
                        viewModel.postMenuFeedback(if (before) "Bluetooth off" else "Bluetooth on")
                        // The info menu stays open behind the toggle; restart the
                        // auto-collapse window so the menu does not linger.
                        viewModel.resetAutoCollapseTimer()
                    } else {
                        viewModel.postMenuFeedback(
                            if (shizukuReason.isNullOrBlank()) "Couldn't toggle Bluetooth"
                            else "Couldn't toggle Bluetooth — $shizukuReason"
                        )
                    }
                }
            }
            // Always restore touch handling, even if the block above threw.
            suppressShadeHide = false
            if (::viewModel.isInitialized) {
                updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
            }
        }
    }

    /** Permission-free Bluetooth state: Settings.Global "bluetooth_on". */
    private fun isBluetoothOn(): Boolean = runCatching {
        Settings.Global.getInt(contentResolver, "bluetooth_on", 0) != 0
    }.getOrDefault(false)

    /** Polls Settings.Global until Bluetooth reaches [target] or [timeoutMs] elapses. */
    private suspend fun waitForBluetoothState(target: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isBluetoothOn() == target) return true
            delay(200L)
        }
        return isBluetoothOn() == target
    }

    /**
     * Finds a Quick Settings tile across the SystemUI windows by label.
     * A candidate matches when its content description or text contains
     * [mustContain] (case-insensitive) and NONE of [excludeContains]; it
     * must also be visibly tile-sized so background SystemUI nodes never
     * receive the synthetic tap.
     */
    private fun findQuickSettingsTile(
        mustContain: String,
        excludeContains: List<String> = emptyList()
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val candidateRoots = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        rootInActiveWindow?.let { candidateRoots.add(it) }
        runCatchingLogged(TAG, "QS window roots failed") {
            getWindows()
                ?.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM }
                ?.forEach { window ->
                    val root = window.root ?: return@forEach
                    if (!candidateRoots.contains(root)) candidateRoots.add(root)
                }
        }
        for (root in candidateRoots) {
            val matches = root.findAccessibilityNodeInfosByText(mustContain) ?: continue
            val tile = matches.firstOrNull { node ->
                val description = node.contentDescription?.toString().orEmpty()
                val text = node.text?.toString().orEmpty()
                val haystack = "$description $text".lowercase()
                haystack.contains(mustContain, ignoreCase = true) &&
                    excludeContains.none { haystack.contains(it, ignoreCase = true) }
            } ?: continue
            val bounds = android.graphics.Rect()
            tile.getBoundsInScreen(bounds)
            val tileSized = bounds.width() in 1..(screenWidth / 2) &&
                bounds.height() in 1..(screenHeight / 3)
            if (tileSized) return tile
        }
        return null
    }

    /**
     * QS-tile fallback toggle. Returns true when the state actually flipped.
     * Opens QS, taps the Bluetooth tile (retrying and scrolling the tile
     * carousel as needed) and closes QS again via BACK — but only if QS was
     * really opened, so a failed open never sends a stray BACK to the launcher.
     */
    private suspend fun toggleBluetoothViaQsTile(before: Boolean): Boolean {
        performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        )
        val opened = waitForQuickSettings(timeoutMs = 2500L)
        if (!opened) {
            android.util.Log.w(TAG, "QS fallback: quick settings window never appeared")
            return false
        }
        try {
            var carouselSwipes = 0
            val deadline = System.currentTimeMillis() + 6000L
            while (System.currentTimeMillis() < deadline) {
                val tile = findBluetoothTileNode()
                if (tile == null) {
                    if (carouselSwipes < 2) {
                        // The tile may live on the next QS carousel page.
                        swipeQuickSettingsCarousel()
                        carouselSwipes++
                        continue
                    }
                    delay(300L)
                    continue
                }
                val bounds = android.graphics.Rect()
                tile.getBoundsInScreen(bounds)
                if (bounds.width() <= 0 || bounds.height() <= 0) {
                    delay(300L)
                    continue
                }
                val tapPath = android.graphics.Path().apply {
                    moveTo(bounds.exactCenterX(), bounds.exactCenterY())
                }
                val tap = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(
                            tapPath,
                            0,
                            80
                        )
                    )
                    .build()
                dispatchGesture(tap, null, null)
                if (waitForBluetoothState(!before, timeoutMs = 2000L)) {
                    return true
                }
                // The tap may have landed on a stale node or missed the tile;
                // loop and try again until the deadline.
            }
            return false
        } finally {
            // Close QS again so the user is returned to what was below. The
            // island menu itself is overlay content and is unaffected by BACK.
            delay(500L)
            performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            delay(400L)
        }
    }

    private suspend fun waitForQuickSettings(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // The expanded QS panel is a full-screen SystemUI window, exactly
            // what the shade detector already looks for.
            if (detectShadeFromWindows()) return true
            delay(150L)
        }
        return false
    }

    /**
     * Finds the Quick Settings "Bluetooth" tile across the SystemUI windows.
     * Matching is layered: exact content description first, then a containing
     * description, then the tile text — each candidate must be visibly on
     * screen (non-empty bounds that fit inside a tile-sized area).
     */
    private fun findBluetoothTileNode(): android.view.accessibility.AccessibilityNodeInfo? {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val candidateRoots = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        rootInActiveWindow?.let { candidateRoots.add(it) }
        runCatchingLogged(TAG, "QS window roots failed") {
            getWindows()
                ?.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM }
                ?.forEach { window ->
                    val root = window.root ?: return@forEach
                    if (!candidateRoots.contains(root)) candidateRoots.add(root)
                }
        }
        for (root in candidateRoots) {
            val matches = root.findAccessibilityNodeInfosByText("Bluetooth")
            val tile = matches?.firstOrNull { node ->
                node.contentDescription?.toString()?.equals("Bluetooth", ignoreCase = true) == true &&
                    node.text?.toString().isNullOrEmpty()
            } ?: matches?.firstOrNull { node ->
                node.contentDescription?.toString()?.contains("Bluetooth", ignoreCase = true) == true
            } ?: matches?.firstOrNull { node ->
                node.text?.toString()?.contains("Bluetooth", ignoreCase = true) == true
            } ?: continue
            val bounds = android.graphics.Rect()
            tile.getBoundsInScreen(bounds)
            val tileSized = bounds.width() in 1..(screenWidth / 2) &&
                bounds.height() in 1..(screenHeight / 3)
            if (tileSized) return tile
        }
        return null
    }

    /** Swipes the QS tile carousel sideways to reveal tiles on the next page. */
    private suspend fun swipeQuickSettingsCarousel() {
        val dm = resources.displayMetrics
        val y = dm.heightPixels * 0.16f
        val path = android.graphics.Path().apply {
            moveTo(dm.widthPixels * 0.80f, y)
            lineTo(dm.widthPixels * 0.15f, y)
        }
        val swipe = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path,
                    0,
                    220
                )
            )
            .build()
        dispatchGesture(swipe, null, null)
        delay(600L)
    }

    /**
     * Opens the app or settings screen matching the tapped idle info item
     * (clock app for time, battery settings, bluetooth settings, hotspot settings).
     * Returns true when something was actually opened (menu collapses then),
     * false for no-op feedback (menu stays open).
     */
    private fun openIdleInfoItem(item: String) {
        val zoomOptions = buildZoomLaunchOptions(viewModel.settings.value)
        var openedSomething = false
        runCatchingLogged(TAG, "Failed to open idle info item") {
            when (item) {
                "time" -> {
                    openedSomething = true
                    val clockPackages = listOf(
                        "com.google.android.deskclock",
                        "com.android.deskclock",
                        "com.sec.android.app.clockpackage",
                        "com.miui.clock",
                        "com.coloros.clock",
                        "com.huawei.deskclock"
                    )
                    val clockIntent = clockPackages
                        .mapNotNull { packageManager.getLaunchIntentForPackage(it) }
                        .firstOrNull()
                    if (clockIntent != null) {
                        clockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(clockIntent, zoomOptions)
                    } else {
                        val fallback = Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(fallback, zoomOptions)
                    }
                }
                "battery" -> {
                    val intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent, zoomOptions)
                    } else {
                        val alt = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(alt, zoomOptions)
                    }
                }
                "bluetooth" -> {
                    // Toggle Bluetooth without leaving the menu: Shizuku shell
                    // command first (works both directions on Android 12+),
                    // Quick Settings tile gesture as fallback. The island stays
                    // visible and the menu stays open either way.
                    openedSomething = false
                    toggleBluetoothViaShade()
                }
            }
        }
        if (openedSomething) {
            // Close the menu instantly instead of waiting for the collapse
            // animation: the app/screen opening covers the transition.
            runCatchingLogged(TAG, "hide island failed") {
                islandView?.visibility = android.view.View.GONE
            }
            viewModel.collapse()
        }
    }

    /**
     * Builds launch options that scale the incoming app window up from the island pill's
     * on-screen position, so the app visually grows out of the pill (Dynamic Island style)
     * instead of sliding in from the top of the screen. The source is always the small
     * collapsed pill rect — growing from the tall expanded-card rect makes the app appear
     * to open from the bottom of that strip.
     */
    private fun buildZoomLaunchOptions(settings: SmartIslandSettings): Bundle? {
        val view = islandView ?: return null
        return runCatchingLogged(TAG, "Failed to build zoom launch options") {
            val density = resources.displayMetrics.density
            val screenWidthPx = resources.displayMetrics.widthPixels
            val startW = (settings.width * density).toInt()
            val startH = (settings.height * density).toInt()
            val screenLeft = (screenWidthPx / 2f + settings.xOffset * density - startW / 2f).toInt()
            val screenTop = (settings.yOffset * density).toInt()
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val options = ActivityOptions.makeScaleUpAnimation(
                view,
                screenLeft - location[0],
                screenTop - location[1],
                startW,
                startH
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            options.toBundle()
        }
    }

    private fun openCurrentNotificationInFloatingWindow() {
        val list = viewModel.notifications.value
        val index = viewModel.selectedIndex.value
        if (list.isNotEmpty() && index in list.indices) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                Toast.makeText(this, "Floating window requires Android 7+.", Toast.LENGTH_SHORT).show()
                viewModel.collapse()
                return
            }
            val notification = list[index]
            val options = ActivityOptions.makeBasic()
            runCatchingLogged(TAG, "Failed to set launch bounds") {
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                val w = (screenWidth * 0.90f).toInt()
                val h = (screenHeight * 0.65f).toInt()
                val left = (screenWidth - w) / 2
                val top = (screenHeight - h) / 2
                options.setLaunchBounds(android.graphics.Rect(left, top, left + w, top + h))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatchingLogged(TAG, "Failed to set background activity start mode") {
                    options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
            }
            val bundle = options.toBundle() ?: android.os.Bundle()
            bundle.putInt("android.activity.windowingMode", WINDOWING_MODE_FREEFORM)

            val fillInIntent = Intent().apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }

            if (notification.contentIntent != null) {
                runCatchingLogged(TAG, "Failed to send content intent") {
                    notification.contentIntent.send(this, 0, fillInIntent, null, null, null, bundle)
                }
            } else {
                runCatchingLogged(TAG, "Failed to launch package activity") {
                    val launchIntent = packageManager.getLaunchIntentForPackage(notification.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        startActivity(launchIntent, bundle)
                    } else {
                        Toast.makeText(this, "Opening ${notification.appName} in floating window (Demo)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            notificationRepository.removeNotification(notification.key)
            notificationRepository.sendCommand(SmartIslandCommand.CancelNotification(notification.key))
        }
        viewModel.collapse()
    }

    private fun Float.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    /** Package name of the current home/launcher app, for exit detection. */
    private fun resolveLauncherPackage(): String? = runCatchingLogged(TAG, "Launcher resolve failed") {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    }

    private fun ComposeView.installOverlayViewTreeOwners() {
        setViewTreeLifecycleOwner(overlayOwners)
        setViewTreeViewModelStoreOwner(overlayOwners)
        setViewTreeSavedStateRegistryOwner(overlayOwners)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                OVERLAY_CHANNEL_ID,
                OVERLAY_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Smart Island overlay running"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.agupta07505.smartisland.MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, OVERLAY_CHANNEL_ID)
            .setContentTitle("Smart Island is active")
            .setContentText("Tap to open Smart Island")
            .setSmallIcon(R.drawable.ic_stat_smart_island)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    companion object {
        @Volatile
        var isSystemConnected: Boolean = false
            private set

        private const val TAG = "SmartIslandOverlayService"
        private const val NOTIFICATION_ID = 8105
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val OVERLAY_CHANNEL_ID = "smart_island_overlay"
        private const val OVERLAY_CHANNEL_NAME = "Smart Island overlay"
        // Delay before the expanded→collapsed TOUCH HANDOVER (where the
        // touchableRegion reflection is unavailable). Rounds O–Q showed BOTH
        // the raw resize transient (SurfaceFlinger stretches the old
        // full-screen buffer into the narrow window — the "duplicate island")
        // AND the masked-resize blink (island invisible for ~100ms) are
        // conspicuous around the collapse. The two-window architecture removed
        // the resize entirely — expand/collapse are invisible flag/child-window
        // swaps now — so this delay's remaining job is purely behavioral: keep
        // the full-screen content window touchable (tap-outside-to-toggle,
        // card gestures) until the collapse springs are visually settled
        // (stiffness 520, damping .72–.76 → ~300ms), then hand touches to the
        // pill catcher.
        private const val AUTO_COLLAPSE_DELAY_MS = 420L

        // Collapsed-pill gesture constants (parity with IslandOverlayView's
        // pointer handler, which serves the touchableRegion class).
        private const val PILL_HOLD_THRESHOLD_MS = 300L
        private const val PILL_SWIPE_THRESHOLD_DP = 35f
        private const val PILL_DRAG_MAX_DP = 100f
    }

    /**
     * Detects whether the notification shade is open from a SystemUI window-state
     * event. Class names vary wildly across ROMs (often just "FrameLayout"), so the
     * reliable signal is the window's on-screen bounds: the status bar strip is small
     * when collapsed, and covers most of the screen when the shade is open.
     */
    private fun isNotificationShadeWindow(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString() ?: ""
        // ONLY the shade window itself counts as "shade open". The plain
        // status-bar window (class ...StatusBar...) exists at ALL times, so
        // matching it made every status-bar window-state event flip
        // isShadeOpen true — the island then hid (hideWhenShadeOpen) until the
        // next windows-changed pass corrected it and replayed the reappear
        // scale-in: a recurring shrink-and-bounce that reads as the collapsed
        // island jittering whenever SystemUI churns (music, clocks, ...).
        if (className.contains("NotificationShade")) {
            return true
        }
        val source = event.source
        if (source != null) {
            val rect = android.graphics.Rect()
            runCatchingLogged(TAG, "source bounds failed") {
                source.getBoundsInScreen(rect)
            }
            val screenHeight = resources.displayMetrics.heightPixels
            if (rect.height() > screenHeight / 2) {
                return true
            }
        }
        return detectShadeFromWindows()
    }

    /**
     * Scans the currently visible accessibility windows for an expanded SystemUI
     * status bar window (the shade). The status bar (TYPE_SYSTEM) window covers the
     * full screen while the shade is open and is a small strip when collapsed.
     */
    private fun detectShadeFromWindows(): Boolean {
        val windows = getWindows() ?: return false
        val screenHeight = resources.displayMetrics.heightPixels
        for (window in windows) {
            if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) {
                val bounds = android.graphics.Rect()
                runCatchingLogged(TAG, "window bounds failed") {
                    window.getBoundsInScreen(bounds)
                }
                if (bounds.height() > screenHeight / 2) {
                    return true
                }
            }
        }
        return false
    }
}
