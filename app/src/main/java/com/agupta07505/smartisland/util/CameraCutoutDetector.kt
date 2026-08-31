/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.DisplayCutout
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.agupta07505.smartisland.data.SmartIslandSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class DetectedCutoutInfo(
    val xOffsetDp: Float,
    val yOffsetDp: Float,
    val widthDp: Float,
    val heightDp: Float,
    val hasHardwareCutout: Boolean
)

object CameraCutoutDetector {

    private const val TAG = "CameraCutoutDetector"
    // Slim paddings: the pill must HUG the camera hole. The old 10dp height
    // padding made every detected pill a third taller than the actual cutout
    // (the "height identified incorrectly" report).
    private const val CUTOUT_PADDING_W_DP = 4f
    private const val CUTOUT_PADDING_H_DP = 3f
    // A real punch hole is never shorter than this; some ROMs report the
    // cutout rect as the full status-bar inset, which would otherwise clamp
    // the pill to a status-bar-sized slab.
    private const val MIN_CUTOUT_HEIGHT_DP = 14f

    /**
     * Calculates the physical pill position and dimensions matching the device's camera cutout coordinates.
     *
     * [yOffsetDp] is the WINDOW y that CENTERS the pill on the hole
     * (holeCenterY - pillHeight/2). It may be slightly negative — the overlay
     * window carries FLAG_LAYOUT_NO_LIMITS, so a y above the screen top still
     * renders correctly. The old code returned the raw hole top, which sat the
     * pill BELOW the camera (the window applies this offset, and the hole
     * starts at y=0).
     */
    fun calculateCutoutOffset(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenWidthPx: Int,
        density: Float
    ): DetectedCutoutInfo {
        if (density <= 0f) {
            return DetectedCutoutInfo(0f, 12f, 112f, 34f, false)
        }
        val cutoutCenterX = (left + right) / 2f
        val screenCenterX = screenWidthPx / 2f
        val xOffsetPx = cutoutCenterX - screenCenterX
        val xOffsetDp = (xOffsetPx / density).coerceIn(
            SmartIslandSettings.MIN_X_OFFSET,
            SmartIslandSettings.MAX_X_OFFSET
        )

        val widthDp = ((right - left).toFloat() / density + CUTOUT_PADDING_W_DP).coerceIn(
            SmartIslandSettings.MIN_IDLE_WIDTH,
            SmartIslandSettings.MAX_WIDTH
        )
        val heightDp = ((bottom - top).toFloat() / density + CUTOUT_PADDING_H_DP).coerceIn(
            MIN_CUTOUT_HEIGHT_DP,
            SmartIslandSettings.MAX_HEIGHT
        )

        val holeCenterYDp = (top + bottom) / 2f / density
        val yOffsetDp = (holeCenterYDp - heightDp / 2f).coerceIn(
            SmartIslandSettings.MIN_IDLE_Y_OFFSET,
            SmartIslandSettings.MAX_Y_OFFSET
        )

        return DetectedCutoutInfo(
            xOffsetDp = xOffsetDp,
            yOffsetDp = yOffsetDp,
            widthDp = widthDp,
            heightDp = heightDp,
            hasHardwareCutout = true
        )
    }

    /**
     * Calculates the physical pill position and dimensions matching the device's camera cutout rect.
     */
    fun calculateCutoutOffset(
        rect: Rect,
        screenWidthPx: Int,
        density: Float
    ): DetectedCutoutInfo = calculateCutoutOffset(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
        screenWidthPx = screenWidthPx,
        density = density
    )

    /**
     * Suspending auto-detection of the hardware camera cutout.
     *
     * Detection order:
     *  1. Display.getCutout() (API 30+) — works without any window layout.
     *  2. A transient 1x1 overlay probe view that reads WindowInsets display cutout (API 28+).
     *  3. Status-bar-height fallback (no hardware cutout reported).
     *
     * Must be invoked on the main thread (adds/removes a WindowManager view).
     */
    suspend fun detectAsync(context: Context): DetectedCutoutInfo {
        val fromDisplay = detectFromDisplay(context)
        if (fromDisplay != null) {
            Log.d(TAG, "detectAsync: resolved via Display.getCutout: $fromDisplay")
            return fromDisplay
        }
        val fromInsets = detectViaWindowInsets(context)
        if (fromInsets != null) {
            Log.d(TAG, "detectAsync: resolved via window insets probe: $fromInsets")
            return fromInsets
        }
        val fallback = fallbackInfo(context)
        Log.w(TAG, "detectAsync: no hardware cutout reported; using fallback $fallback")
        return fallback
    }

    /**
     * Synchronous detection using Display.getCutout() (API 30+) only.
     */
    fun detect(context: Context): DetectedCutoutInfo {
        return detectFromDisplay(context) ?: fallbackInfo(context)
    }

    private fun detectFromDisplay(context: Context): DetectedCutoutInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null
        val display = runCatching { windowManager.defaultDisplay }.getOrNull() ?: return null
        val cutout: DisplayCutout? = runCatching { display.cutout }.getOrNull()
        return cutoutToInfo(cutout, context)
    }

    // DisplayCutout itself is API 28; both call sites already gate on P+ (R+ for
    // detectFromDisplay, P for the window-insets probe), so the requirement is
    // declared here instead of duplicating version checks inside the mapping.
    @RequiresApi(Build.VERSION_CODES.P)
    private fun cutoutToInfo(cutout: DisplayCutout?, context: Context): DetectedCutoutInfo? {
        val boundingRects = cutout?.boundingRects
        if (boundingRects.isNullOrEmpty()) return null
        val topCutout = boundingRects.minByOrNull { it.top }
        if (topCutout == null || topCutout.height() <= 0) return null
        return calculateCutoutOffset(
            topCutout,
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.density
        )
    }

    /**
     * Transient probe window that reads the display cutout from WindowInsets.
     * Best-effort for API 28/29 where Display.getCutout() does not exist.
     */
    private suspend fun detectViaWindowInsets(context: Context): DetectedCutoutInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (!Settings.canDrawOverlays(context)) return null
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null
        var probeView: View? = null
        val result = suspendCancellableCoroutine { continuation ->
            var finished = false
            val finish = { result: DetectedCutoutInfo? ->
                if (!finished) {
                    finished = true
                    continuation.resume(result)
                }
            }
            val view = View(context)
            probeView = view
            view.setOnApplyWindowInsetsListener { _, insets ->
                val detected = cutoutToInfo(insets.displayCutout, context)
                finish(detected)
                insets
            }
            val params = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            val added = runCatching {
                windowManager.addView(view, params)
            }.isSuccess
            if (!added) {
                finish(null)
                return@suspendCancellableCoroutine
            }
            view.post { view.requestApplyInsets() }
            view.postDelayed({ finish(null) }, 1500L)
            continuation.invokeOnCancellation {
                runCatching { windowManager.removeView(view) }
            }
        }
        probeView?.let { v ->
            runCatching {
                if (v.isAttachedToWindow) {
                    windowManager.removeView(v)
                }
            }
        }
        return result
    }

    private fun fallbackInfo(context: Context): DetectedCutoutInfo {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val heightPx = if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
        val density = context.resources.displayMetrics.density
        val heightDp = if (density > 0f) heightPx / density else 24f
        val yOffsetDp = (heightDp * 0.3f).coerceIn(0f, 30f)

        return DetectedCutoutInfo(
            xOffsetDp = 0f,
            yOffsetDp = yOffsetDp,
            widthDp = 112f,
            heightDp = 34f,
            hasHardwareCutout = false
        )
    }
}