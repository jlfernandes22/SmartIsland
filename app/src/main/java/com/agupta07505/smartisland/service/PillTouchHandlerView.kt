/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.service

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Transparent touch-catcher window content for the COLLAPSED island on
 * devices WITHOUT the touchableRegion reflection.
 *
 * WHY A SECOND WINDOW: the collapsed pill needs (a) touches outside the pill
 * group to fall through to whatever is behind, and (b) a stable surface for
 * the collapse/expand morph. A single window cannot give both without the
 * OnComputeInternalInsets reflection: the window must be narrow to bound the
 * touches, and resizing it between the full-screen expanded state and the
 * narrow collapsed state makes SurfaceFlinger stretch the last submitted
 * buffer into the new bounds (the ghost) — masking it makes the resting pill
 * blink at the end of every collapse (the "flashes after the animation ends"
 * report).
 *
 * The fix splits the two jobs:
 *  - the CONTENT window is MATCH_PARENT in every state and never resizes
 *    (nothing to stretch, nothing to mask), and
 *  - THIS window carries the touches. It is fully transparent, so its own
 *    add/remove/resize can never produce a visible artifact.
 *
 * The view is deliberately dumb: it tracks a single pointer (down/move/up,
 * hold timer, drag accumulation) and forwards primitive events to the
 * service, which owns the settings, the ViewModel and the pill/bubble hit
 * geometry. Behavior parity with the Compose collapsed-pill pointer handler:
 *  - drag is claimed when any move exceeds 0.5px and is never handed back,
 *  - hold fires once after [holdThresholdMs],
 *  - taps only resolve when the total drag stayed under 10px.
 */
class PillTouchHandlerView(context: Context) : View(context) {

    interface Listener {
        /** First pointer went down inside the catcher. */
        fun onTouchDown()

        /**
         * Accumulated vertical movement since the down, in pixels. Positive
         * = finger moving down the screen.
         */
        fun onTouchMove(totalDyPx: Float)

        /**
         * Pointer released (not cancelled). [downX]/[downY] are view-local
         * coordinates of the ORIGINAL down so the service can hit-test the
         * companion bubbles exactly once per gesture.
         */
        fun onTouchUp(
            totalDyPx: Float,
            isDragging: Boolean,
            holdRegistered: Boolean,
            elapsedMs: Long,
            downX: Float,
            downY: Float
        )

        /** Pointer lost (window removed, system stole the gesture). */
        fun onTouchCancelled()

        /** The hold threshold elapsed while the pointer is still down. */
        fun onHoldRegistered()
    }

    var listener: Listener? = null
    var holdThresholdMs: Long = 300L

    // Pill/bubble hit geometry in VIEW-LOCAL coordinates. The pill band is
    // vertical-full (the whole catcher height is the pill's touch band, like
    // the old narrow window); only the horizontal split between the main
    // pill and the companion bubbles matters.
    private var pillRect = RectF()
    private var secondaryRect = RectF()
    private var tertiaryRect = RectF()

    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var totalDy = 0f
    private var isDragging = false
    private var pressTimeMs = 0L
    private var holdRegistered = false
    private var holdPosted = false
    // Set when ACTION_UP resolves as a TAP so the click can be routed
    // through performClick (ClickableViewAccessibility contract) while still
    // carrying the original down position for the service's bubble hit-test.
    private var pendingTapValid = false
    private var pendingTapX = 0f
    private var pendingTapY = 0f

    private val holdRunnable = Runnable {
        holdPosted = false
        if (pointerId == MotionEvent.INVALID_POINTER_ID) return@Runnable
        holdRegistered = true
        listener?.onHoldRegistered()
    }

    /** (Re)configures the hit geometry. Rects are view-local. */
    fun setGestureGeometry(pill: RectF, secondary: RectF, tertiary: RectF) {
        pillRect = pill
        secondaryRect = secondary
        tertiaryRect = tertiary
    }

    /** -1 = none/inside the pill band, 0 = secondary bubble, 1 = tertiary bubble. */
    fun bubbleIndexAt(x: Float, y: Float): Int = when {
        secondaryRect.contains(x, y) -> 0
        tertiaryRect.contains(x, y) -> 1
        else -> -1
    }

    fun isInsidePillBand(x: Float, y: Float): Boolean = pillRect.contains(x, y)

    /**
     * Click path. Two callers:
     *  1. ACTION_UP routing a detected TAP (with the original down position
     *     stashed in pendingTapX/Y for the service's bubble hit-test),
     *  2. accessibility activation (switch access, TalkBack double-tap) —
     *     synthesizes a clean tap at the pill's center.
     * Both resolve as a zero-drag tap; no hold, no swipe.
     */
    override fun performClick(): Boolean {
        super.performClick()
        if (pointerId != MotionEvent.INVALID_POINTER_ID && !pendingTapValid) {
            // A touch gesture is still in flight; ignore spurious clicks.
            return true
        }
        val x: Float
        val y: Float
        if (pendingTapValid) {
            x = pendingTapX
            y = pendingTapY
            pendingTapValid = false
        } else {
            x = pillRect.centerX()
            y = pillRect.centerY()
        }
        listener?.onTouchUp(0f, false, false, 0L, x, y)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastY = event.y
                totalDy = 0f
                isDragging = false
                holdRegistered = false
                pressTimeMs = System.currentTimeMillis()
                scheduleHold()
                listener?.onTouchDown()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                val y = event.getY(index)
                val dy = y - lastY
                lastY = y
                totalDy += dy
                if (kotlin.math.abs(dy) > 0.5f) {
                    isDragging = true
                }
                listener?.onTouchMove(totalDy)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Extra pointers lifting are ignored; if the TRACKED pointer
                // lifted while others remain, end the gesture quietly (spring
                // back, no action fired) — matching how the Compose handler
                // loses its tracked pointer.
                val idx = event.actionIndex
                if (idx >= 0 && event.getPointerId(idx) == pointerId) {
                    cancelHold()
                    pointerId = MotionEvent.INVALID_POINTER_ID
                    listener?.onTouchCancelled()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelHold()
                pointerId = MotionEvent.INVALID_POINTER_ID
                if (!isDragging || kotlin.math.abs(totalDy) < 10f) {
                    // Tap detected: route through performClick so the
                    // accessibility contract holds and a11y tooling sees the
                    // click. The original down position rides along for the
                    // bubble hit-test.
                    pendingTapValid = true
                    pendingTapX = downX
                    pendingTapY = downY
                    performClick()
                } else {
                    listener?.onTouchUp(
                        totalDy,
                        isDragging,
                        holdRegistered,
                        System.currentTimeMillis() - pressTimeMs,
                        downX,
                        downY
                    )
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelHold()
                pointerId = MotionEvent.INVALID_POINTER_ID
                listener?.onTouchCancelled()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scheduleHold() {
        cancelHold()
        holdPosted = true
        postDelayed(holdRunnable, holdThresholdMs)
    }

    private fun cancelHold() {
        if (holdPosted) {
            removeCallbacks(holdRunnable)
            holdPosted = false
        }
    }

    override fun onDetachedFromWindow() {
        cancelHold()
        pointerId = MotionEvent.INVALID_POINTER_ID
        super.onDetachedFromWindow()
    }
}
