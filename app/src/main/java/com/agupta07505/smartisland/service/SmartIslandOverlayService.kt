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
import com.agupta07505.smartisland.ui.expanded.IDLE_ITEM_BT_TETHERING
import com.agupta07505.smartisland.ui.expanded.IDLE_ITEM_USB_TETHERING
import com.agupta07505.smartisland.ui.expanded.sendIntentWithOptions
import com.agupta07505.smartisland.util.HotspotUtil
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
    // Content-sized expanded window (touch-passthrough fallback): reported by the
    // Compose tree when the hidden touchableRegion API is unavailable, so the
    // expanded window can be sized to the card instead of swallowing the screen.
    @Volatile private var expandedWindowWidthPx: Int = 0
    @Volatile private var expandedWindowHeightPx: Int = 0
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
            runSuspendCatchingLogged(TAG, "Idle cutout auto-detect collector failed") {
                repository.settings.collect { settings ->
                    if (destroyed) return@collect
                    if (!settings.enabled || !settings.useCutoutSizeWhenIdle || settings.idleSizeAutoDetected) {
                        return@collect
                    }
                    val detected = com.agupta07505.smartisland.util.CameraCutoutDetector.detectAsync(this@SmartIslandOverlayService)
                    if (detected.hasHardwareCutout) {
                        repository.setIdleSize(detected.widthDp, detected.heightDp)
                        repository.setIdleSizeAutoDetected(true)
                        if (::viewModel.isInitialized) {
                            updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
                        }
                        android.util.Log.d(
                            TAG,
                            "Idle cutout auto-detected: ${detected.widthDp}x${detected.heightDp}dp"
                        )
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
                        updateWindowLayoutParams(true, viewModel.settings.value)
                    } else {
                        collapseJob = serviceScope.launch {
                            kotlinx.coroutines.delay(AUTO_COLLAPSE_DELAY_MS)
                            isWindowExpanded = false
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
                val isHidden = (!viewModel.settings.value.showOnLockScreen && isLocked) ||
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
                        isFullWidth = isTouchableRegionSupported,
                        onOpenIdleInfoItem = { item -> openIdleInfoItem(item) },
                        onExpandedWindowContentSize = { widthPx, heightPx ->
                            onExpandedWindowContentSizeChanged(widthPx, heightPx)
                        }
                    )
                }

                // Supported touch-passthrough for the EXPANDED overlay on devices
                // where the hidden touchableRegion reflection is blocked: the
                // window is sized to the island content (see
                // updateWindowLayoutParams) and FLAG_WATCH_OUTSIDE_TOUCH turns
                // every tap outside it into an ACTION_OUTSIDE event, which
                // collapses the island — replacing the old full-screen window's
                // "tap anywhere outside to dismiss" behaviour.
                setOnTouchListener { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE &&
                        !suppressShadeHide &&
                        ::viewModel.isInitialized &&
                        viewModel.expanded.value
                    ) {
                        android.util.Log.d(TAG, "Touch outside expanded island window; collapsing")
                        viewModel.collapse()
                        true
                    } else {
                        false
                    }
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

                        val mainWidthPx = settingsVal.width * density
                        // Same group width as collapsedParams(): with 3+
                        // notifications the tertiary circle is drawn too and
                        // must stay inside the touchable region.
                        val groupWidthPx = (
                            settingsVal.width + when {
                                notificationsCount >= 3 -> 2 * (8f + settingsVal.height)
                                isSplitMode -> 8f + settingsVal.height
                                else -> 0f
                            }
                        ) * density
                        val edgePaddingPx = 8f * density
                        val touchPaddingPx = 6f * density
                        val pillHeightPx = (settingsVal.height + 16f) * density

                        val desiredMainLeftPx = screenWidth / 2f +
                            settingsVal.xOffset * density - mainWidthPx / 2f
                        val maxMainLeftPx = (screenWidth - groupWidthPx - edgePaddingPx)
                            .coerceAtLeast(edgePaddingPx)
                        val mainLeftPx = desiredMainLeftPx.coerceIn(edgePaddingPx, maxMainLeftPx)
                        val left = (mainLeftPx - touchPaddingPx).toInt()
                        val top = 0
                        val right = (mainLeftPx + groupWidthPx + touchPaddingPx).toInt()
                        val bottom = pillHeightPx.toInt()
                        
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
                    if (::viewModel.isInitialized && !isWindowExpanded) {
                        updateWindowLayoutParams(false, viewModel.settings.value)
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
        val density = resources.displayMetrics.density
        val screenWidthPx = resources.displayMetrics.widthPixels.toFloat()
        
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked == true
        isLockScreenActive = isLocked
        viewModel.isLocked.value = isLocked
        
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val isHidden = (!settings.showOnLockScreen && isLocked) ||
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

        val notificationCount = viewModel.notifications.value.size
        // Collapsed group extent = main pill + one gap+circle per companion
        // bubble. 3+ notifications also draw a tertiary circle, so the narrow
        // window must cover a second gap+circle or it clips that bubble (mirrors
        // companionGroupWidth in IslandOverlayView).
        val collapsedWidthPx = collapsedWindowWidthPx(settings, notificationCount, density)

        val h = if (expanded) {
            if (!isTouchableRegionSupported) {
                // Touch-passthrough fallback: size the window to the expanded
                // card (reported by the Compose tree) instead of MATCH_PARENT,
                // so touches on the rest of the screen reach the app underneath.
                expandedWindowHeightPx.takeIf { it > 0 } ?: estimatedExpandedWindowHeightPx()
            } else {
                WindowManager.LayoutParams.MATCH_PARENT
            }
        } else {
            ((settings.height + 16f) * density).toInt()
        }
        val w = when {
            expanded && !isTouchableRegionSupported ->
                expandedWindowWidthPx.takeIf { it > 0 }
                    ?: (screenWidthPx * EXPANDED_WINDOW_WIDTH_RATIO).toInt()
            expanded || isTouchableRegionSupported -> WindowManager.LayoutParams.MATCH_PARENT
            else -> collapsedWidthPx
        }
        val isInput = viewModel.isInputActive.value && expanded
        val focusFlags = if (isInput) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val currentFlags = focusFlags or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (suppressShadeHide) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0) or
            // Collapses on taps that land anywhere outside the content-sized
            // expanded window (delivered to the view as ACTION_OUTSIDE).
            (if (expanded && !isTouchableRegionSupported) {
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            } else 0)

        // WINDOW CENTERING INVARIANT: x is 0 in every state. The expanded and
        // collapsed windows share the screen center, so the delayed
        // expanded→collapsed resize cannot displace the screen-anchored
        // content — only its clip bounds change (see the class-level comment).
        val currentX = 0
        val currentY = settings.yOffset.dpToPx()
        val currentSoftInputMode = if (isInput) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        } else {
            0
        }

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
     * Width of the narrow collapsed window on devices without the
     * touchableRegion API. The window is horizontally CENTERED (x = 0, same
     * center as the expanded window) and sized to contain the screen-anchored
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
     * Centering is what makes the collapsed state stable: the delayed
     * expanded→collapsed resize keeps the window center fixed, so
     * renderedX = screenCenter + target for every element before, during and
     * after the resize — no compensation, no animation, no jumps.
     */
    private fun collapsedWindowWidthPx(
        settings: SmartIslandSettings,
        notificationCount: Int,
        density: Float
    ): Int {
        val mainWidthPx = settings.width * density
        val circleSizePx = settings.height * density
        val compactGapPx = 8f * density
        val companionExtraPx = when {
            notificationCount >= 3 -> 2 * (compactGapPx + circleSizePx)
            notificationCount >= 2 -> compactGapPx + circleSizePx
            else -> 0f
        }
        val xOffsetPx = settings.xOffset * density
        val sidePaddingPx = 16f * density
        val leftExtentPx = mainWidthPx / 2f - xOffsetPx
        val rightExtentPx = mainWidthPx / 2f + companionExtraPx + xOffsetPx
        return (2f * (maxOf(leftExtentPx, rightExtentPx) + sidePaddingPx)).toInt()
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
        if (!::windowManager.isInitialized) return
        runCatchingLogged(TAG, "Failed to remove view") {
            if (view.isAttachedToWindow) {
                windowManager.removeViewImmediate(view)
            }
        }
    }

    private fun collapsedParams(settings: SmartIslandSettings): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val notificationCount = if (::viewModel.isInitialized) viewModel.notifications.value.size else 0
        // Initial collapsed window: horizontally centered (x = 0) like every
        // other window state, sized to contain the screen-anchored collapsed
        // content (see collapsedWindowWidthPx).
        val w = if (isTouchableRegionSupported) {
            WindowManager.LayoutParams.MATCH_PARENT
        } else {
            collapsedWindowWidthPx(settings, notificationCount, density)
        }
        val currentFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        return WindowManager.LayoutParams(
            w,
            ((settings.height + 16f) * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            currentFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // WINDOW CENTERING INVARIANT: x = 0 in every window state.
            x = 0
            y = settings.yOffset.dpToPx()
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
     * Preferred path — Shizuku: with shell-level privileges the hidden
     * BluetoothManagerService commands (`cmd bluetooth_manager enable|disable`,
     * `svc bluetooth enable|disable`) work in both directions on Android 12+,
     * where BluetoothAdapter.enable()/disable() return false for normal apps.
     * No dialogs, no settings pages, no shade pull-down.
     *
     * Fallback path — Quick Settings tile: the accessibility service opens QS,
     * waits for the shade window, locates the Bluetooth tile (scrolling the
     * tile carousel if needed) and taps it with a synthetic gesture while the
     * island window carries FLAG_NOT_TOUCHABLE (suppressShadeHide also keeps
     * the island visible). QS is closed again with GLOBAL_ACTION_BACK.
     *
     * Both paths verify the result through the permission-free
     * Settings.Global "bluetooth_on" switch and the fallback retries the tap.
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

                // 1) Shizuku: shell-privileged toggle — reliable both ways.
                //    Availability + permission are checked inside, on IO.
                if (ShizukuManager.isBinderAvailable()) {
                    val dispatched = ShizukuManager.toggleBluetooth(!before)
                    changed = dispatched.isSuccess && waitForBluetoothState(!before, timeoutMs = 4000L)
                    android.util.Log.d(
                        TAG,
                        "Shizuku bluetooth toggle: dispatched=${dispatched.isSuccess} changed=$changed " +
                            "(reason=${dispatched.exceptionOrNull()?.message ?: "ok"})"
                    )
                } else {
                    android.util.Log.d(
                        TAG,
                        "Shizuku unavailable (installed=${ShizukuManager.isInstalled(this@SmartIslandOverlayService)} " +
                            "binder=${ShizukuManager.isBinderAvailable()}); using QS tile fallback"
                    )
                }

                // 2) Fallback: pull down Quick Settings and tap the Bluetooth tile.
                if (!changed) {
                    changed = toggleBluetoothViaQsTile(before)
                }

                if (::viewModel.isInitialized) {
                    if (changed) {
                        viewModel.postMenuFeedback(if (before) "Bluetooth off" else "Bluetooth on")
                        // The info menu stays open behind the toggle; restart the
                        // auto-collapse window so the menu does not linger.
                        viewModel.resetAutoCollapseTimer()
                    } else {
                        viewModel.postMenuFeedback("Couldn't toggle Bluetooth")
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
     * Idle-menu tethering toggles (Wi-Fi hotspot, USB tethering, Bluetooth
     * tethering), same spirit as the Bluetooth row: Shizuku shell command, no
     * dialogs, no settings pages — the island stays visible and the menu stays
     * open either way. Best-effort: when a state reader is unavailable the
     * dispatch result is trusted; when the command fails the user gets in-menu
     * feedback instead of a Toast or a Settings round-trip.
     */
    private fun toggleTetheringViaShizuku(kind: String, label: String) {
        // Keep the island fully visible; make its window transparent to touches
        // while the shell command runs (mirrors the Bluetooth toggle path).
        suppressShadeHide = true
        if (::viewModel.isInitialized) {
            updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
        }
        serviceScope.launch {
            runSuspendCatchingLogged(TAG, "$label toggle failed") {
                val before = readTetheringState(kind)
                val target = before != true
                if (::viewModel.isInitialized) {
                    viewModel.postMenuFeedback(
                        if (before == true) "Turning $label off…" else "Turning $label on…"
                    )
                }
                val dispatched = if (ShizukuManager.isBinderAvailable()) {
                    ShizukuManager.toggleTethering(kind, target)
                } else {
                    Result.failure(
                        IllegalStateException("Shizuku binder offline (installed=${ShizukuManager.isInstalled(this@SmartIslandOverlayService)})")
                    )
                }
                // Verify with the best reader available. null = no reliable
                // reader on this device → trust the dispatch (optimistic).
                val verified = if (dispatched.isSuccess) {
                    waitForTetheringState(kind, target, TETHERING_TOGGLE_VERIFY_TIMEOUT_MS)
                } else {
                    null
                }
                val changed = when {
                    dispatched.isFailure -> false
                    verified == null -> true
                    else -> verified == target
                }
                if (::viewModel.isInitialized) {
                    if (changed) {
                        viewModel.postMenuFeedback(if (target) "$label on" else "$label off")
                        // The info menu stays open behind the toggle; restart
                        // the auto-collapse window so the menu does not linger.
                        viewModel.resetAutoCollapseTimer()
                    } else {
                        viewModel.postMenuFeedback("Couldn't toggle $label")
                    }
                }
                android.util.Log.d(
                    TAG,
                    "Tethering toggle $kind: dispatched=${dispatched.isSuccess} " +
                        "verified=$verified (reason=${dispatched.exceptionOrNull()?.message ?: "ok"})"
                )
            }
            // Always restore touch handling, even if the block above threw.
            suppressShadeHide = false
            if (::viewModel.isInitialized) {
                updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
            }
        }
    }

    /**
     * Best-effort tethering state reader, shared with the idle info menu
     * (HotspotUtil). Returns true/false when readable, null when this device
     * offers no reliable read (the caller then trusts the dispatch result).
     */
    private fun readTetheringState(kind: String): Boolean? = when (kind) {
        "wifi" -> HotspotUtil.isWifiTetheringActive(this)
        "usb" -> HotspotUtil.isUsbTetheringActive(this)
        else -> HotspotUtil.isBluetoothTetheringActive(this)
    }

    /**
     * Polls [readTetheringState] until the tethering state reaches [target] or
     * [timeoutMs] elapses. Returns null as soon as the reader turns out to be
     * unavailable on this device (nothing to wait for — the caller then trusts
     * the dispatch result instead).
     */
    private suspend fun waitForTetheringState(
        kind: String,
        target: Boolean,
        timeoutMs: Long
    ): Boolean? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = readTetheringState(kind)
            if (state == target) return target
            if (state == null) return null
            delay(200L)
        }
        return readTetheringState(kind)
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
                "hotspot" -> {
                    // Toggle the Wi-Fi hotspot in place (Shizuku shell command)
                    // instead of opening the hotspot settings page — same
                    // spirit as the Bluetooth row: no dialogs, no settings
                    // pages, island stays visible, menu stays open.
                    openedSomething = false
                    toggleTetheringViaShizuku(kind = "wifi", label = "Hotspot")
                }
                IDLE_ITEM_USB_TETHERING -> {
                    openedSomething = false
                    toggleTetheringViaShizuku(kind = "usb", label = "USB tethering")
                }
                IDLE_ITEM_BT_TETHERING -> {
                    openedSomething = false
                    toggleTetheringViaShizuku(kind = "bluetooth", label = "BT tethering")
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

    /**
     * Called by the Compose tree whenever the measured expanded island content
     * size changes. Only used on devices where the hidden touchableRegion API
     * is blocked and the expanded window must be sized to its content for
     * touch passthrough (see updateWindowLayoutParams).
     */
    private fun onExpandedWindowContentSizeChanged(widthPx: Int, heightPx: Int) {
        if (destroyed || !::viewModel.isInitialized) return
        if (widthPx <= 0 || heightPx <= 0) return
        if (widthPx == expandedWindowWidthPx && heightPx == expandedWindowHeightPx) return
        expandedWindowWidthPx = widthPx
        expandedWindowHeightPx = heightPx
        android.util.Log.d(TAG, "Expanded window content size: ${widthPx}x${heightPx}px")
        if (isWindowExpanded && !isTouchableRegionSupported) {
            updateWindowLayoutParams(isWindowExpanded, viewModel.settings.value)
        }
    }

    /**
     * Upper bound for the expanded card height (250dp measured clamp) plus
     * status-bar offset and room for the shadow — used only until the Compose
     * tree reports the real content size.
     */
    private fun estimatedExpandedWindowHeightPx(): Int =
        ((statusBarHeight + 250f + 32f) * resources.displayMetrics.density).toInt()

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
        private const val AUTO_COLLAPSE_DELAY_MS = 220L
        // How long a tethering toggle waits for the system state to confirm
        // before falling back to the (optimistic) dispatch result.
        private const val TETHERING_TOGGLE_VERIFY_TIMEOUT_MS = 4000L
        private const val EXPANDED_WINDOW_WIDTH_RATIO = 0.95f
    }

    /**
     * Detects whether the notification shade is open from a SystemUI window-state
     * event. Class names vary wildly across ROMs (often just "FrameLayout"), so the
     * reliable signal is the window's on-screen bounds: the status bar strip is small
     * when collapsed, and covers most of the screen when the shade is open.
     */
    private fun isNotificationShadeWindow(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString() ?: ""
        if (className.contains("NotificationShade") ||
            className.contains("statusbar") ||
            className.contains("StatusBar")
        ) {
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
