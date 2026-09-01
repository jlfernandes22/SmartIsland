/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.data

data class SmartIslandSettings(
    val enabled: Boolean = false,
    val width: Float = 112f,
    val height: Float = 34f,
    val xOffset: Float = 0f,
    val yOffset: Float = 12f,
    val cornerRadius: Float = 22f,
    val opacity: Float = 1f,
    val batteryColor: Long = 0xFF10B981L,
    val notificationDotColor: Long = 0xFF2563EBL,
    val musicVisualizerColor: Long = 0xFFFF6B9AL,
    val hotspotColor: Long = 0xFFF59E0BL,
    val callColor: Long = 0xFF22C55EL,
    val liveActivityColor: Long = 0xFF8B5CF6L,
    val transferColor: Long = 0xFF06B6D4L,
    val navigationColor: Long = 0xFF10B981L,
    val bluetoothColor: Long = 0xFF2563EBL,
    val flashlightColor: Long = 0xFFF59E0BL,
    val screenRecordingColor: Long = 0xFFEF4444L,
    val timerColor: Long = 0xFFF59E0BL,
    val stopwatchColor: Long = 0xFF06B6D4L,
    val shortcutPackages: Set<String> = emptySet(),
    val showRecentApps: Boolean = false,
    val welcomeDialogShown: Boolean = false,
    val showOnLockScreen: Boolean = false,
    // While the keyguard is showing, the island stays visible whenever there
    // are unopened notifications — even when showOnLockScreen is off. With
    // hideFromNotificationShade on, island-only notifications were cancelled
    // from the system shade, so without this they were invisible EVERYWHERE
    // until unlock (they never reached the lock screen either).
    val showUnreadOnLockScreen: Boolean = true,
    val lockScreenPrivacy: String = "AppIconOnly",
    val showNotificationActions: Boolean = true,
    val hideFromNotificationShade: Boolean = true,
    val liveActivitiesEnabled: Boolean = true,
    val navigationEnabled: Boolean = true,
    val disabledNotificationPackages: Set<String> = emptySet(),
    val disabledSoundPackages: Set<String> = emptySet(),
    val hideWhenIdle: Boolean = false,
    val autoHidePill: Boolean = false,
    val autoHideTimeoutSeconds: Int = 5,
    val showInLandscape: Boolean = false,
    val autoExpandOnNotification: Boolean = true,
    val enableShadow: Boolean = true,
    val enableMusicArtworkBackground: Boolean = true,
    val deviceType: String = "AUTO",
    val allowNetworkChecks: Boolean = true,
    val enableNotificationHistory: Boolean = false,
    val notificationHistoryRetentionHours: Int = 72,
    val useCutoutSizeWhenIdle: Boolean = false,
    val idleWidth: Float = 112f,
    val idleHeight: Float = 34f,
    // Position of the IDLE pill, independent from the wide island's offsets
    // (the precision-tuning sliders): moving the wide island must never drag
    // the idle punch-hole pill with it. The X twin of idleYOffset: without it
    // every dismiss-to-idle morph slid the tiny pill sideways from the
    // expanded card's center to the wide island's xOffset, which read as
    // "the idle pill comes from the left and snaps into place". Y defaults
    // to the old shared value so existing installs keep their position; X
    // defaults to the hole-centered 0.
    val idleYOffset: Float = 12f,
    val idleXOffset: Float = 0f,
    val idleSizeAutoDetected: Boolean = false,
    val hideWhenShadeOpen: Boolean = true,
    val swipeUpAction: String = "DISMISS",
    val holdSwipeUpAction: String = "DISMISS",
    val swipeDownAction: String = "FLOATING_WINDOW",
    val tapAction: String = "TOGGLE",
    val idleTapMode: String = "APPS",
    val idleInfoShowTime: Boolean = true,
    val idleInfoShowBattery: Boolean = true,
    val idleInfoShowBluetooth: Boolean = true,
    val statusBarIconsHidden: Boolean = false
) {
    companion object {
        val Default = SmartIslandSettings()

        const val MIN_WIDTH = 76f
        const val MAX_WIDTH = 180f
        const val MIN_HEIGHT = 24f
        const val MAX_HEIGHT = 60f
        const val MIN_X_OFFSET = -140f
        const val MAX_X_OFFSET = 140f
        const val MIN_Y_OFFSET = 0f
        const val MAX_Y_OFFSET = 80f
        const val MIN_CORNER_RADIUS = 8f
        const val MAX_CORNER_RADIUS = 40f
        const val MIN_OPACITY = 0.2f
        const val MAX_OPACITY = 1f
        const val MIN_IDLE_WIDTH = 20f
        const val MIN_IDLE_HEIGHT = 24f
        // The idle-Y slider is 0..MAX_Y_OFFSET, but the cutout auto-detect may
        // produce a slightly negative value when the pill must CENTER on a
        // camera hole that starts at the very top of the screen (the overlay
        // window carries FLAG_LAYOUT_NO_LIMITS, so a negative y renders fine).
        const val MIN_IDLE_Y_OFFSET = -20f
    }

    object GestureActions {
        const val DISMISS = "DISMISS"
        const val DISMISS_ALL = "DISMISS_ALL"
        const val COLLAPSE = "COLLAPSE"
        const val FLOATING_WINDOW = "FLOATING_WINDOW"
        const val TOGGLE = "TOGGLE"
        const val NONE = "NONE"

        val SWIPE_UP_OPTIONS = listOf(DISMISS, COLLAPSE, NONE)
        val HOLD_SWIPE_UP_OPTIONS = listOf(DISMISS_ALL, DISMISS, COLLAPSE, NONE)
        val SWIPE_DOWN_OPTIONS = listOf(FLOATING_WINDOW, COLLAPSE, NONE)
        val TAP_OPTIONS = listOf(TOGGLE, NONE)

        val VALID_VALUES = setOf(
            DISMISS, DISMISS_ALL, COLLAPSE, FLOATING_WINDOW, TOGGLE, NONE
        )

        fun isValid(value: String): Boolean = value in VALID_VALUES
    }

    object IdleTapModes {
        const val APPS = "APPS"
        const val INFO = "INFO"

        val VALID_VALUES = setOf(APPS, INFO)

        fun isValid(value: String): Boolean = value in VALID_VALUES
    }
}
