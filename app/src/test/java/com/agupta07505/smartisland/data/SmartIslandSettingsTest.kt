/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartIslandSettingsTest {
    @Test
    fun testDefaultSettings() {
        val settings = SmartIslandSettings.Default
        assertEquals(false, settings.enabled)
        assertEquals(112f, settings.width)
        assertEquals(34f, settings.height)
        assertEquals(0f, settings.xOffset)
        assertEquals(12f, settings.yOffset)
        assertEquals(22f, settings.cornerRadius)
        assertEquals(1f, settings.opacity)
        assertEquals(true, settings.showNotificationActions)
        assertEquals(true, settings.hideFromNotificationShade)
        assertEquals(true, settings.autoExpandOnNotification)
        assertEquals(true, settings.enableShadow)
        assertEquals(true, settings.enableMusicArtworkBackground)
        assertEquals(0xFF2563EBL, settings.bluetoothColor)
        assertEquals(0xFFF59E0BL, settings.flashlightColor)
        assertEquals(0xFFEF4444L, settings.screenRecordingColor)
        assertEquals(0xFFF59E0BL, settings.timerColor)
        assertEquals(0xFF06B6D4L, settings.stopwatchColor)
        assertEquals(true, settings.allowNetworkChecks)
        assertEquals(false, settings.enableNotificationHistory)
        assertEquals(72, settings.notificationHistoryRetentionHours)
        assertEquals(false, settings.useCutoutSizeWhenIdle)
        assertEquals(112f, settings.idleWidth)
        assertEquals(34f, settings.idleHeight)
        assertEquals(false, settings.idleSizeAutoDetected)
        assertEquals(true, settings.hideWhenShadeOpen)
        assertEquals(SmartIslandSettings.GestureActions.DISMISS, settings.swipeUpAction)
        assertEquals(SmartIslandSettings.GestureActions.DISMISS, settings.holdSwipeUpAction)
        assertEquals(SmartIslandSettings.GestureActions.FLOATING_WINDOW, settings.swipeDownAction)
        assertEquals(SmartIslandSettings.GestureActions.TOGGLE, settings.tapAction)
        assertEquals(SmartIslandSettings.IdleTapModes.APPS, settings.idleTapMode)
        assertEquals(true, settings.idleInfoShowTime)
        assertEquals(true, settings.idleInfoShowDate)
        assertEquals(true, settings.idleInfoShowBattery)
        assertEquals(true, settings.idleInfoShowBluetooth)
    }

    @Test
    fun testEquality() {
        val settings1 = SmartIslandSettings(enabled = true, width = 120f)
        val settings2 = SmartIslandSettings(enabled = true, width = 120f)
        val settings3 = SmartIslandSettings(enabled = false, width = 120f)

        assertEquals(settings1, settings2)
        assertNotEquals(settings1, settings3)
    }

    @Test
    fun testGestureActionsValidation() {
        assertTrue(SmartIslandSettings.GestureActions.isValid("DISMISS"))
        assertTrue(SmartIslandSettings.GestureActions.isValid("COLLAPSE"))
        assertTrue(SmartIslandSettings.GestureActions.isValid("NONE"))
        assertFalse(SmartIslandSettings.GestureActions.isValid("SWIPE_UP"))
        assertFalse(SmartIslandSettings.GestureActions.isValid(""))
    }
}
