/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCutoutDetectorTest {

    @Test
    fun testCenterCameraCutoutOffset() {
        val screenWidthPx = 1080
        val density = 2.75f // 1080p density ~ 440 dpi

        // A center punch hole at top-center (x: 490 to 590, y: 0 to 80)
        val result = CameraCutoutDetector.calculateCutoutOffset(
            left = 490,
            top = 0,
            right = 590,
            bottom = 80,
            screenWidthPx = screenWidthPx,
            density = density
        )

        assertTrue(result.hasHardwareCutout)
        // Center X = 540, Screen Center X = 540 -> xOffsetPx = 0 -> xOffsetDp = 0
        assertEquals(0f, result.xOffsetDp, 0.01f)
        // width = 100px / 2.75 + 4dp padding = ~40.36dp (slim hug padding)
        assertEquals(40.36f, result.widthDp, 0.01f)
        // height = 80px / 2.75 + 3dp padding = ~32.09dp (was +10dp: too tall to hug the hole)
        assertEquals(32.09f, result.heightDp, 0.01f)
        // yOffset centers the pill on the hole: 40/2.75 - 32.09/2 = -1.5dp
        // (slightly negative is fine — the overlay window has FLAG_LAYOUT_NO_LIMITS)
        assertEquals(-1.5f, result.yOffsetDp, 0.01f)
    }

    @Test
    fun testLeftCameraCutoutOffset() {
        val screenWidthPx = 1080
        val density = 2.5f

        // A punch hole on the left (x: 50 to 130, y: 10 to 90)
        val result = CameraCutoutDetector.calculateCutoutOffset(
            left = 50,
            top = 10,
            right = 130,
            bottom = 90,
            screenWidthPx = screenWidthPx,
            density = density
        )

        assertTrue(result.hasHardwareCutout)
        // Center X = 90, Screen Center X = 540 -> xOffsetPx = -450 -> xOffsetDp = -180 -> clamped to MIN_X_OFFSET (-140)
        assertEquals(-140f, result.xOffsetDp, 0.01f)
        // height = 80px / 2.5 + 3dp = 35dp; hole center = 50/2.5 = 20dp
        // yOffset = 20 - 35/2 = 2.5dp (pill vertically centered on the hole)
        assertEquals(2.5f, result.yOffsetDp, 0.01f)
    }

    @Test
    fun testFullStatusBarRectDoesNotInflateThePill() {
        // Some ROMs report the cutout rect as the full status-bar inset. The
        // width clamps to MAX_WIDTH and the height stays hole-sized, so the
        // pill never becomes a status-bar-sized slab.
        val result = CameraCutoutDetector.calculateCutoutOffset(
            left = 0,
            top = 0,
            right = 1080,
            bottom = 80,
            screenWidthPx = 1080,
            density = 2.75f
        )

        assertTrue(result.hasHardwareCutout)
        assertEquals(180f, result.widthDp, 0.01f) // clamped to MAX_WIDTH
        assertEquals(32.09f, result.heightDp, 0.01f)
    }

    @Test
    fun testTinyHoleKeepsAMinimumTouchableHeight() {
        val result = CameraCutoutDetector.calculateCutoutOffset(
            left = 530,
            top = 0,
            right = 550,
            bottom = 10,
            screenWidthPx = 1080,
            density = 2.75f
        )

        assertTrue(result.hasHardwareCutout)
        // 10px/2.75 + 3dp = 6.6dp -> clamped to the 14dp hole floor
        assertEquals(14f, result.heightDp, 0.01f)
    }
}
