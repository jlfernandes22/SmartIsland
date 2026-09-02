/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui

import com.agupta07505.smartisland.ui.expanded.IslandExpandedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.agupta07505.smartisland.data.SmartIslandSettings
import com.agupta07505.smartisland.di.SmartIslandRepositories
import com.agupta07505.smartisland.model.IslandMode
import com.agupta07505.smartisland.model.IslandNotification
import com.agupta07505.smartisland.data.LaunchableApp
import com.agupta07505.smartisland.ui.expanded.idleInfoMenuHeightDp
import com.agupta07505.smartisland.ui.expanded.idleInfoMenuWidthDp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.asImageBitmap

@Composable
fun IslandOverlayView(
    settings: SmartIslandSettings,
    expanded: Boolean,
    notifications: List<IslandNotification>,
    selectedIndex: Int,
    launcherApps: List<LaunchableApp>?,
    onPageSelected: (Int) -> Unit,
    onOpenNotification: (IslandNotification) -> Unit,
    onLaunchApp: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onDismissNotification: () -> Unit,
    onOpenFloatingWindow: () -> Unit,
    statusBarHeight: Float,
    modifier: Modifier = Modifier,
    isInputActive: Boolean = false,
    onReplyStateChanged: (Boolean) -> Unit = {},
    onDismissAllNotifications: () -> Unit = {},
    onOpenIdleInfoItem: (String) -> Unit = {},
    menuFeedback: String? = null,
    reappearTick: Int = 0,
    // Live vertical drag offset (px) streamed by the service's pill
    // touch-catcher window on devices WITHOUT the touchableRegion reflection
    // (the content window is FLAG_NOT_TOUCHABLE while collapsed there, so
    // this composable never sees the gesture itself). Added to the pill's
    // translationY so the collapsed pill follows the finger exactly like the
    // in-Compose dragOffset does on reflection-capable devices.
    pillDragOffsetPx: Float = 0f,
    // Incremented when the pill touch-catcher reports a tap on the (possibly
    // hidden) collapsed pill: the auto-hidden pill awakens, the idle-hidden
    // one toggles — the exact logic of the in-Compose hidden-pill tap target.
    pillRevealTick: Int = 0,
    // Writes the Compose-side isHiding state back to the service so the
    // touch-catcher knows when only reveal-taps should fire.
    onPillUiHiddenChanged: (Boolean) -> Unit = {}
) {
    // Fix #1: rememberUpdatedState ensures the lambda is always fresh
    // even though pointerInput(Unit) never restarts its coroutine
    val currentOnToggle by rememberUpdatedState(onToggleExpanded)
    val currentOnDismiss by rememberUpdatedState(onDismissNotification)
    val currentOnDismissAll by rememberUpdatedState(onDismissAllNotifications)
    val currentOnOpenFloatingWindow by rememberUpdatedState(onOpenFloatingWindow)
    val currentOnOpenNotification by rememberUpdatedState(onOpenNotification)
    val currentExpanded by rememberUpdatedState(expanded)
    val currentSettings by rememberUpdatedState(settings)
    // Gesture handlers below run inside pointerInput(Unit)-keyed coroutines that
    // never restart; without these they would capture first-composition values
    // (opening a stale notification / missing the reply-dismiss on tap-outside).
    val currentIsInputActive by rememberUpdatedState(isInputActive)
    val currentNotifications by rememberUpdatedState(notifications)
    val haptic = LocalHapticFeedback.current

    val scope = rememberCoroutineScope()
    var dragOffset by remember { mutableStateOf(0f) }
    var infoPageActive by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics
    val density = LocalDensity.current
    val screenWidth = with(density) { displayMetrics.widthPixels.toDp() }
    val screenCenter = screenWidth / 2f
    val expandedWidth = ((displayMetrics.widthPixels / displayMetrics.density) * EXPANDED_WIDTH_RATIO).dp
    val transition = updateTransition(targetState = expanded, label = "islandTransition")

    val sizeSpec = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = 0.72f,
        stiffness = 520f
    )
    val sizeSpecFloat = spring<Float>(
        dampingRatio = 0.72f,
        stiffness = 520f
    )
    val heightSpec = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = 0.76f,
        stiffness = 520f
    )
    val alphaSpec = tween<Float>(
        durationMillis = 190,
        easing = FastOutSlowInEasing
    )
    // POSITION springs (X offsets): critically damped — an overshooting X
    // spring visibly flew past the collapsed spot and settled BACK left,
    // which read as a small snap to the left at the end of every collapse
    // that involved companion bubbles. Sizes keep their playful bounce.
    val positionSpec = spring<androidx.compose.ui.unit.Dp>(
        dampingRatio = 1f,
        stiffness = 520f
    )

    val safeIndex = selectedIndex.coerceIn(0, (notifications.size - 1).coerceAtLeast(0))
    val activeNotification = notifications.getOrNull(safeIndex)
    val activeMode = activeNotification?.mode ?: IslandMode.Empty
    val currentSafeIndex by rememberUpdatedState(safeIndex)

    // MENU HEIGHT ESTIMATE (post-settle centering fix): the idle info menu's
    // natural height is fully deterministic (tile count + tile grid), so the
    // estimate below is the SAME value the first real measurement reports.
    // With estimate == measurement the card never changes height after the
    // menu opens — no late overlay-window resize, hence zero post-settle
    // movement and a perfectly centered icon grid (see idleInfoMenuHeightDp).
    //
    // CONTENT-SIZED CARD (idle info): when the expanded state IS the idle info
    // menu, the card also wraps its content horizontally — a 4-tile grid is
    // ~232dp wide, not the 0.95-screen-width band the other pages use. The
    // width target, the height estimate and the width handed to the content
    // all derive from idleInfoMenuWidthDp so all three stay consistent.
    // With notifications active the menu lives at pager page 0; while that
    // page is the one on screen the card sizes to the menu too — the old
    // notifications.isEmpty() gate kept the menu at full width whenever any
    // notification existed.
    val isIdleInfoExpand = settings.idleTapMode == SmartIslandSettings.IdleTapModes.INFO &&
        (notifications.isEmpty() || infoPageActive)
    val expandedCardWidth = if (isIdleInfoExpand) {
        idleInfoMenuWidthDp(settings)
    } else {
        expandedWidth
    }
    val initialEstimatedHeight = remember(
        activeMode,
        notifications.isEmpty(),
        settings.idleTapMode,
        settings.idleInfoShowTime,
        settings.idleInfoShowDate,
        settings.idleInfoShowBattery,
        settings.idleInfoShowBluetooth
    ) {
        if (notifications.isEmpty()) {
            if (isIdleInfoExpand) {
                // Card width minus the menu column's 12dp start+end padding.
                idleInfoMenuHeightDp(settings, expandedCardWidth.value - 24f)
            } else {
                135.dp
            }
        } else {
            defaultEstimatedHeightForMode(activeMode)
        }
    }
    var expandedHeight by remember { mutableStateOf(initialEstimatedHeight) }

    LaunchedEffect(activeMode, notifications.isEmpty()) {
        if (!expanded) {
            expandedHeight = if (notifications.isEmpty()) {
                if (isIdleInfoExpand) {
                    idleInfoMenuHeightDp(settings, expandedCardWidth.value - 24f)
                } else {
                    135.dp
                }
            } else {
                defaultEstimatedHeightForMode(activeMode)
            }
        }
    }

    val compactGap = COMPACT_INDICATOR_GAP_DP.dp
    val isIdle = notifications.isEmpty() && !expanded
    val effectiveWidth = if (isIdle && settings.useCutoutSizeWhenIdle) settings.idleWidth else settings.width
    val effectiveHeight = if (isIdle && settings.useCutoutSizeWhenIdle) settings.idleHeight else settings.height
    val miniPillWidth = effectiveWidth.dp
    val circleSize = effectiveHeight.dp
    val hasCompanion = notifications.size >= 2
    val hasTertiary = notifications.size >= 3
    // Collapsed: one gap+circle per companion bubble. The tertiary circle is
    // drawn for 3+ notifications and needs its own gap+circle of window space
    // (mirrors groupWidthPx in SmartIslandOverlayService).
    val companionGroupWidth = when {
        !hasCompanion -> 0.dp
        expanded -> compactGap + miniPillWidth + if (hasTertiary) compactGap + circleSize else 0.dp
        else -> compactGap + circleSize + if (hasTertiary) compactGap + circleSize else 0.dp
    }
    val collapsedMainLeft = (screenCenter + settings.xOffset.dp - effectiveWidth.dp / 2f)
        .coerceIn(
            compactGap,
            (screenWidth - companionGroupWidth - compactGap).coerceAtLeast(compactGap)
        )
    // Collapsed offsets are SCREEN-anchored: target = desiredScreenX -
    // screenCenter. The service keeps the overlay window horizontally centered
    // (x = 0) in every state — expanded and the narrow collapsed window alike —
    // so the window center IS the screen center and every render site can apply
    // its target directly: renderedX = windowCenter + target = screenCenter +
    // target. Because the rendered path does not depend on which window is
    // active, the service's delayed narrow-window resize only changes the clip
    // bounds around the content and can never shift or teleport it (and no
    // per-frame window/compensation animation is needed, which is what made
    // the collapsed island shake before).
    // Screen-anchored collapsed X target. IDLE X DECOUPLING: the idle
    // punch-hole pill uses its OWN x (settings.idleXOffset, anchored to the
    // camera hole by the auto-detect) — NOT the wide island's xOffset.
    // Applying the wide island's x to the idle pill made every dismiss-to-idle
    // morph slide the tiny pill sideways from the expanded card's center to
    // the wide island's position, which read as "the idle pill comes from the
    // left and then snaps to the correct position". With the idle pill at its
    // own x the morph shrinks IN PLACE.
    val collapsedMainOffset = (if (isIdle) settings.idleXOffset else settings.xOffset).dp
    // Expanded card top offset. The base clears the status bar (and the
    // companion mini-pill when one exists); the wide-Y delta then applies the
    // precision-tuning "vertical offset" WITHOUT moving the window: the
    // window always sits at y = 0 (see SmartIslandOverlayService), so the
    // wide island's Y is decoupled from the idle punch-hole pill's Y and
    // changing one slider can never displace the other.
    val wideYDelta = (settings.yOffset - settings.idleYOffset).dp
    // FULL-BLEED WINDOW BASE: the content window covers the entire screen
    // (window y = 0). It previously started at idleYOffset, so a precision
    // yOffset SMALLER than idleYOffset pushed the collapsed pill above the
    // window's top frame and the system clipped its top even though that
    // screen strip was visible ("the top of it is cut even if it's not off
    // screen"). Every render site below adds this base back, so on-screen
    // positions are identical to the old window-at-idleYOffset geometry
    // while the pill now has unlimited headroom above it.
    val windowTopBase = settings.idleYOffset.dp
    val expandedTopOffset = (if (hasCompanion) {
        statusBarHeight.dp.coerceAtLeast(circleSize + compactGap)
    } else {
        statusBarHeight.dp
    }) + wideYDelta
    // PRECISION POSITION, NORMAL ISLAND (collapsed): the collapsed WIDE pill
    // renders at window-y (0) + windowTopBase (idleYOffset) + this delta, so
    // its final screen y IS the precision-tuning "vertical offset" (yOffset)
    // itself — while the idle punch-hole pill stays at idleYOffset. Every island now follows its own
    // sliders in every state (the "precision position is not working with the
    // normal island" report), and the window itself still never moves, so no
    // window-position transient can ghost the morph.
    val collapsedWideYDelta = if (isIdle) 0.dp else wideYDelta
    val isIdleHiding = settings.hideWhenIdle && notifications.isEmpty()

    var isAutoHidden by remember { mutableStateOf(false) }
    var userInteractionTimestamp by remember { mutableStateOf(System.currentTimeMillis()) }

    // Reset auto-hide whenever active notifications change or selection changes
    LaunchedEffect(notifications.map { it.key }, selectedIndex) {
        isAutoHidden = false
        userInteractionTimestamp = System.currentTimeMillis()
    }

    // Auto-hide countdown timer when pill is collapsed and autoHidePill is enabled
    LaunchedEffect(expanded, settings.autoHidePill, settings.autoHideTimeoutSeconds, userInteractionTimestamp) {
        if (expanded || !settings.autoHidePill) {
            isAutoHidden = false
            return@LaunchedEffect
        }
        val timeoutMs = (settings.autoHideTimeoutSeconds.coerceAtLeast(1) * 1000L)
        kotlinx.coroutines.delay(timeoutMs)
        isAutoHidden = true
    }

    val isHiding = isIdleHiding || (settings.autoHidePill && isAutoHidden)

    // Morph targets: the size springs (sizeSpec/heightSpec) are reserved for
    // the expanded<->collapsed MORPH (initialState != targetState). When the
    // transition STAYS expanded and only the width/height TARGET changes — the
    // info-menu card switching between its content-hugging width and the
    // 0.95-screen-wide card, or the per-frame pager height interpolation —
    // the value must SNAP. Spring-chasing a retargeted width while the user
    // is mid-swipe animates the pager's viewport width UNDER THE FINGER: the
    // pager's pixel-offset math re-derives against a page size that changes
    // every frame, the page visually stalls ("freezes ~0.5s") and only snaps
    // into place after the spring settles. A snap happens in one frame at a
    // drag boundary, where the offset is ~0 and no gesture math is fighting it.
    val width by transition.animateDp(transitionSpec = {
        if (initialState == targetState) snap() else sizeSpec
    }, label = "islandWidth") {
        if (it) expandedCardWidth else if (isHiding) 0.dp else effectiveWidth.dp
    }
    val height by transition.animateDp(transitionSpec = {
        if (initialState == targetState) snap() else heightSpec
    }, label = "islandHeight") {
        if (it) expandedHeight else if (isHiding) 0.dp else effectiveHeight.dp
    }
    val yOffset by transition.animateDp(transitionSpec = {
        if (initialState == targetState) snap() else sizeSpec
    }, label = "islandYOffset") {
        if (it) expandedTopOffset else collapsedWideYDelta
    }
    val radius by transition.animateDp(transitionSpec = {
        if (initialState == targetState) snap() else sizeSpec
    }, label = "islandRadius") {
        if (it) 34.dp else if (isHiding) 0.dp else settings.cornerRadius.dp
    }
    // IDLE SHADOW: the tiny cutout-sized idle pill floats on the wallpaper,
    // where a 14dp elevation shadow reads as a dirty smudge on light
    // backgrounds ("a little shadow that looks bad in lighter backgrounds").
    // The idle pill now has NO shadow; the collapsed wide island keeps 14dp
    // and the expanded card 22dp. Animated inside the same transition with
    // the pill's own float spec so the shadow fades with the morph instead
    // of popping when the last notification dismisses.
    val pillShadowElevation by transition.animateFloat(
        transitionSpec = { sizeSpecFloat },
        label = "pillShadowElevation"
    ) {
        if (it) 22f else if (isIdle) 0f else 14f
    }
    val animatedXOffset by transition.animateDp(transitionSpec = { positionSpec }, label = "islandXOffset") {
        if (it) 0.dp else collapsedMainOffset
    }

    val collapsedAlpha by transition.animateFloat(
        transitionSpec = { alphaSpec },
        label = "collapsedAlpha"
    ) {
        if (it || isHiding) 0f else 1f
    }

    val expandedAlpha by transition.animateFloat(
        transitionSpec = { alphaSpec },
        label = "expandedAlpha"
    ) {
        if (it) 1f else 0f
    }

    val contentScale by transition.animateFloat(
        transitionSpec = { sizeSpecFloat },
        label = "contentScale"
    ) {
        if (it) 1f else 0.95f
    }

    val contentSlideY by transition.animateDp(
        transitionSpec = { sizeSpec },
        label = "contentSlideY"
    ) {
        if (it) 0.dp else (-6).dp
    }

    val safeWidth = width.coerceAtLeast(0.dp)
    val safeHeight = height.coerceAtLeast(0.dp)
    val safeRadius = radius.coerceAtLeast(0.dp)

    // Tactile spring scale bounce animation only when user switches between active notifications
    val switchScaleAnim = remember { androidx.compose.animation.core.Animatable(1f) }
    var isInitialComposition by remember { mutableStateOf(true) }
    var lastSelectedIndex by remember { mutableStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) {
        if (isInitialComposition) {
            isInitialComposition = false
            lastSelectedIndex = selectedIndex
            return@LaunchedEffect
        }
        if (lastSelectedIndex != selectedIndex) {
            lastSelectedIndex = selectedIndex
            switchScaleAnim.animateTo(
                targetValue = 0.92f,
                animationSpec = tween(40, easing = FastOutSlowInEasing)
            )
            switchScaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = 650f
                )
            )
        }
    }

    // Reverse "app shrinks back into the island" illusion: the service bumps
    // reappearTick when the island returns (app closed, home, unlock). The pill
    // then springs in from ~half size at the punch hole. Android provides no
    // API to animate another app's window exit, so this replay is the closest
    // supported effect (docs/BLUETOOTH_TOGGLE_AND_UI_NOTES.md).
    LaunchedEffect(reappearTick) {
        if (reappearTick > 0) {
            switchScaleAnim.snapTo(0.55f)
            switchScaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = 620f
                )
            )
        }
    }

    // Touch-catcher reveal taps: while the pill is UI-hidden (auto-hide or
    // hide-when-idle) a tap must awaken it (auto-hide) or toggle the island
    // (idle hide) — the same decision the in-Compose hidden tap target makes.
    // The catcher cannot read Compose state, so it just asks; the state lives
    // here.
    LaunchedEffect(pillRevealTick) {
        if (pillRevealTick > 0) {
            if (settings.autoHidePill && isAutoHidden) {
                isAutoHidden = false
                userInteractionTimestamp = System.currentTimeMillis()
            } else {
                currentOnToggle()
            }
        }
    }

    // Keep the service's touch-catcher in sync with the Compose-side hiding
    // state: while hidden the catcher must only forward reveal-taps.
    val currentOnPillUiHidden by rememberUpdatedState(onPillUiHiddenChanged)
    LaunchedEffect(isHiding) {
        currentOnPillUiHidden(isHiding)
    }

    // Dual Pill (Multi-Tasking Split Island) Detection:
    // When 2 or more notifications exist (e.g. Music + Notification/Timer/Call), split into Main Pill + Secondary Bubble
    val secondaryNotification = if (notifications.size >= 2) {
        notifications.firstOrNull { it.key != activeNotification?.key }
    } else null
    val secondaryIndex = if (secondaryNotification != null) {
        notifications.indexOfFirst { it.key == secondaryNotification.key }
    } else -1
    val tertiaryNotification = if (notifications.size >= 3) {
        notifications.firstOrNull {
            it.key != activeNotification?.key && it.key != secondaryNotification?.key
        }
    } else null
    val tertiaryIndex = if (tertiaryNotification != null) {
        notifications.indexOfFirst { it.key == tertiaryNotification.key }
    } else -1
    val isSplitMode = secondaryNotification != null
    // When expanded, the secondary bubble morphs into a full mini-pill beside the main pill
    val secondaryIsPill = expanded
    val showTertiaryBubble = tertiaryNotification != null

    // STATE-INDEPENDENT bubble targets: these four depend on split/hiding
    // state, not on the transition's target state — the compose-animation lint
    // (UnusedTransitionTargetStateParameter) rightly requires transition
    // children to consume the target state, so they stay plain animate*AsState
    // with the SAME matched specs as the pill's transition children.
    val secondaryAlpha by animateFloatAsState(
        targetValue = if (isSplitMode && !isHiding) 1f else 0f,
        animationSpec = alphaSpec,
        label = "secondaryAlpha"
    )
    val secondaryScale by animateFloatAsState(
        targetValue = if (isSplitMode && !isHiding) 1f else 0.3f,
        animationSpec = sizeSpecFloat,
        label = "secondaryScale"
    )
    val secondaryBubbleWidth by transition.animateDp(transitionSpec = { sizeSpec }, label = "secondaryBubbleWidth") {
        if (it) miniPillWidth else circleSize
    }
    val secondaryPillProgress = (miniPillWidth - circleSize).value.let { widthDelta ->
        if (widthDelta == 0f) {
            if (secondaryIsPill) 1f else 0f
        } else {
            ((secondaryBubbleWidth - circleSize).value / widthDelta).coerceIn(0f, 1f)
        }
    }
    val secondaryBubbleCorner by transition.animateDp(transitionSpec = { sizeSpec }, label = "secondaryBubbleCorner") {
        if (it) settings.cornerRadius.dp else circleSize / 2f
    }
    val tertiaryAlpha by transition.animateFloat(transitionSpec = { alphaSpec }, label = "tertiaryAlpha") {
        if (showTertiaryBubble && !it && !isHiding) 1f else 0f
    }
    val tertiaryScale by animateFloatAsState(
        targetValue = if (showTertiaryBubble && !isHiding) 1f else 0.3f,
        animationSpec = sizeSpecFloat,
        label = "tertiaryScale"
    )

    // Secondary bubble: circle right of the main pill when collapsed; when expanded
    // the next most important notification snaps to the middle (punch-hole position)
    // above the card — only that one stays visible, the others are hidden.
    // Companion bubbles are animated INSIDE the same updateTransition as the main
    // pill — one frame clock, one spring family (positionSpec/sizeSpec/alphaSpec
    // are the exact specs the pill uses) — so a multi-notification collapse moves
    // as ONE rigid group instead of N independently-sprung shapes chasing
    // slightly different settle times. This is the coherence the info-menu-only
    // collapse always had ("smooth with the notifications like it is when it's
    // just the info menu").
    val collapsedSecondaryOffset = collapsedMainLeft + effectiveWidth.dp + compactGap +
        circleSize / 2f - screenCenter
    val expandedSecondaryLeft = 0.dp
    val secondaryOffset by transition.animateDp(transitionSpec = { positionSpec }, label = "secondaryOffset") {
        if (!it) collapsedSecondaryOffset else expandedSecondaryLeft
    }

    // Bubbles are SIBLINGS of the pill Box (they don't inherit its
    // translationY), so they must carry the same collapsed wide-Y delta the
    // pill renders with — otherwise the precision-Y slider would leave the
    // bubbles floating at the idle band while the pill moves down.
    val collapsedGroupY by transition.animateDp(transitionSpec = { sizeSpec }, label = "collapsedGroupY") {
        // Bubbles are siblings of the pill Box, so they carry the full
        // window-local Y themselves: windowTopBase + the collapsed wide-Y
        // delta when collapsed, windowTopBase when expanded (the old
        // window-local 0 — the window no longer starts at idleYOffset).
        if (!it) windowTopBase + collapsedWideYDelta else windowTopBase
    }

    // Tertiary bubble: always a circle, right of the secondary circle when collapsed;
    // hidden while expanded (only the secondary stays next to the expanded card).
    val collapsedTertiaryOffset = collapsedMainLeft + effectiveWidth.dp + compactGap +
        circleSize + compactGap + circleSize / 2f - screenCenter
    val tertiaryOffset by animateDpAsState(
        targetValue = collapsedTertiaryOffset,
        animationSpec = positionSpec,
        label = "tertiaryOffset"
    )

    // Outer Box: Fills the entire WindowManager window bounds (which are padded for easy touch)
    val outerModifier = if (currentExpanded) {
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures {
                    if (currentIsInputActive) {
                        onReplyStateChanged(false)
                    }
                    currentOnToggle()
                }
            }
    } else {
        modifier.fillMaxSize()
    }

    Box(
        modifier = outerModifier,
        contentAlignment = Alignment.TopCenter
    ) {

        // Invisible touch target over the pill location when hiding, so tapping the area reveals the pill or opens shortcuts
        if (isHiding && !currentExpanded) {
            Box(
                modifier = Modifier
                    .width(effectiveWidth.dp)
                    .height(effectiveHeight.dp)
                    .graphicsLayer {
                        translationX = collapsedMainOffset.toPx()
                        // The hidden pill also sits at windowTopBase + the
                        // collapsed wide-Y delta when notifications are
                        // present (auto-hide); the reveal tap target must
                        // cover THAT band.
                        translationY = windowTopBase.toPx() + collapsedWideYDelta.toPx()
                    }
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (settings.autoHidePill && isAutoHidden) {
                                // First tap on auto-hidden pill: awaken and reveal the pill
                                isAutoHidden = false
                                userInteractionTimestamp = System.currentTimeMillis()
                            } else {
                                // Empty notifications idle hiding: expand favorite shortcuts
                                currentOnToggle()
                            }
                        }
                    }
            )
        }

        // Inner Box: The actual visible pill container, managing the black background shape and size animations
        Box(
            modifier = Modifier
                .width(safeWidth)
                .height(safeHeight)
                .graphicsLayer {
                    // Screen-anchored target; the window is always centered so
                    // renderedX = screenCenter + xOffset in every window state.
                    // pillDragOffsetPx carries the touch-catcher's drag on
                    // no-reflection devices (0 on reflection devices, where the
                    // in-Compose dragOffset owns the follow-the-finger).
                    translationX = animatedXOffset.toPx()
                    // windowTopBase re-anchors every state to the full-bleed
                    // window (y = 0): idle pill → idleYOffset, collapsed wide
                    // island → idleYOffset + (yOffset - idleYOffset) = yOffset,
                    // expanded card → idleYOffset + expandedTopOffset — the
                    // exact screen positions the old window-at-idleYOffset
                    // geometry produced, now without the top clipping.
                    translationY = windowTopBase.toPx() + yOffset.toPx() + dragOffset + pillDragOffsetPx
                    scaleX = switchScaleAnim.value
                    scaleY = switchScaleAnim.value
                }
                .then(
                    if (settings.enableShadow && !isHiding) {
                        Modifier.shadow(
                            elevation = pillShadowElevation.dp,
                            shape = RoundedCornerShape(safeRadius),
                            clip = false,
                            ambientColor = Color.Black,
                            spotColor = Color.Black
                        )
                    } else Modifier
                )
                .clip(RoundedCornerShape(safeRadius))
                .background(Color.Black.copy(alpha = settings.opacity))
                .pointerInput(displayMetrics.density, isInputActive) {
                    if (isInputActive) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // A child of the card (the pager page's clickable, any
                        // button) consumes the DOWN to own the tap. That must
                        // NOT eat this handler's swipes: the pager claims only
                        // horizontal movement and the seek bar only a deliberate
                        // hold-scrub, so vertical drags still arrive here
                        // unconsumed. The flag only silences the TAP branches so
                        // a tap is never fired twice (child click + pill tap).
                        val downConsumedByChild = down.isConsumed
                        userInteractionTimestamp = System.currentTimeMillis()
                        val pressTimeMs = System.currentTimeMillis()
                        var isHoldRegistered = false
                        var dragAccumulator = 0f
                        var isDragging = false
                        // Set when a swipe gesture FIRED an action (collapse,
                        // dismiss, floating window). The return-to-rest below
                        // is then a SNAP instead of the playful bouncy spring:
                        // a MediumBouncy overshoot dragged the pill below its
                        // rest position in the exact frames where the collapse
                        // morph was resizing the window, which read as a
                        // visual glitch on every swipe-up-to-collapse.
                        var firedSwipeAction = false

                        val holdJob = scope.launch {
                            kotlinx.coroutines.delay(HOLD_GESTURE_THRESHOLD_MS)
                            isHoldRegistered = true
                            triggerHapticVibration(context)
                        }

                        val pointerId = down.id
                        var isFirstEvent = true

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            val isDownEvent = isFirstEvent
                            isFirstEvent = false

                            if (change.changedToUp()) {
                                change.consume()
                                holdJob.cancel()
                                val totalElapsedMs = System.currentTimeMillis() - pressTimeMs
                                val swipeUpThreshold = -SWIPE_THRESHOLD_DP * displayMetrics.density
                                val swipeDownThreshold = SWIPE_THRESHOLD_DP * displayMetrics.density

                                if (currentExpanded) {
                                    if (isDragging && dragOffset < swipeUpThreshold) {
                                        val action = if (isHoldRegistered || totalElapsedMs >= HOLD_GESTURE_THRESHOLD_MS) {
                                            currentSettings.holdSwipeUpAction
                                        } else {
                                            currentSettings.swipeUpAction
                                        }
                                        when (action) {
                                            SmartIslandSettings.GestureActions.DISMISS_ALL -> {
                                                firedSwipeAction = true
                                                currentOnDismissAll()
                                            }
                                            SmartIslandSettings.GestureActions.DISMISS -> {
                                                firedSwipeAction = true
                                                currentOnDismiss()
                                            }
                                            SmartIslandSettings.GestureActions.COLLAPSE -> {
                                                firedSwipeAction = true
                                                currentOnToggle()
                                            }
                                            else -> Unit
                                        }
                                    } else if (isDragging && dragOffset > swipeDownThreshold) {
                                        when (currentSettings.swipeDownAction) {
                                            SmartIslandSettings.GestureActions.FLOATING_WINDOW -> {
                                                firedSwipeAction = true
                                                currentOnOpenFloatingWindow()
                                            }
                                            SmartIslandSettings.GestureActions.COLLAPSE -> {
                                                firedSwipeAction = true
                                                currentOnToggle()
                                            }
                                            else -> Unit
                                        }
                                    } else if (!isDragging || abs(dragOffset) < 10f) {
                                        if (!downConsumedByChild && !isHoldRegistered) {
                                            val currentNotification = currentNotifications.getOrNull(currentSafeIndex)
                                            if (currentNotification != null && !infoPageActive) {
                                                currentOnOpenNotification(currentNotification)
                                            } else if (infoPageActive) {
                                                // The info menu is displayed: the rows handle
                                                // their own taps; never collapse or open apps
                                                // from a tap on the menu itself.
                                            } else {
                                                SmartIslandRepositories.notificationRepository(context).resetTimer()
                                            }
                                        }
                                    }
                                } else {
                                    if (isDragging && dragOffset < swipeUpThreshold) {
                                        // COLLAPSED-pill dismiss: hold + pull up works on
                                        // the pill itself, not only on the expanded card —
                                        // the gesture must discard the notification from
                                        // wherever the user grabs it. Plain swipe-up
                                        // resolves to swipeUpAction, a registered hold to
                                        // holdSwipeUpAction. COLLAPSE is meaningless here
                                        // (already collapsed) and FLOATING_WINDOW needs the
                                        // card's content, so only the dismiss actions fire.
                                        val action = if (isHoldRegistered || totalElapsedMs >= HOLD_GESTURE_THRESHOLD_MS) {
                                            currentSettings.holdSwipeUpAction
                                        } else {
                                            currentSettings.swipeUpAction
                                        }
                                        when (action) {
                                            SmartIslandSettings.GestureActions.DISMISS_ALL -> {
                                                firedSwipeAction = true
                                                currentOnDismissAll()
                                            }
                                            SmartIslandSettings.GestureActions.DISMISS -> {
                                                firedSwipeAction = true
                                                currentOnDismiss()
                                            }
                                            else -> Unit
                                        }
                                    } else if (!isDragging || abs(dragOffset) < 10f) {
                                        if (!downConsumedByChild) {
                                            when (currentSettings.tapAction) {
                                                SmartIslandSettings.GestureActions.TOGGLE -> currentOnToggle()
                                                else -> Unit
                                            }
                                        }
                                    }
                                }
                                break
                            } else if (!isDownEvent && change.isConsumed) {
                                // A child claimed the pointer DURING the gesture
                                // (pager horizontal drag, seek-bar scrub): it owns
                                // this gesture now. The DOWN event is exempt — see
                                // downConsumedByChild above.
                                holdJob.cancel()
                                break
                            } else {
                                val dragAmount = change.positionChange().y
                                if (abs(dragAmount) > 0.5f) {
                                    isDragging = true
                                    // Tracked and consumed in BOTH states: the collapsed
                                    // pill owns its vertical drags too (it has no child
                                    // that wants them), so hold + swipe-up can fire from
                                    // the pill itself. The drag also feeds the visual
                                    // translation — the pill follows the finger and
                                    // springs back on release.
                                    change.consume()
                                    dragAccumulator += dragAmount
                                    dragOffset = dragAccumulator.coerceIn(
                                        -DRAG_MAX_OFFSET_DP * displayMetrics.density,
                                        DRAG_MAX_OFFSET_DP * displayMetrics.density
                                    )
                                }
                            }
                        }

                        holdJob.cancel()
                        if (dragOffset != 0f) {
                            if (firedSwipeAction) {
                                // A swipe fired collapse/dismiss/etc: snap to
                                // rest so the transition (window resize +
                                // width spring + content crossfade) runs from
                                // a settled pill with nothing else moving.
                                dragOffset = 0f
                            } else {
                                // Cancelled drag (below threshold, gesture
                                // consumed elsewhere, pointer lost): keep the
                                // playful bouncy return — nothing else is
                                // animating, so the bounce is pure delight.
                                scope.launch {
                                    androidx.compose.animation.core.Animatable(dragOffset).animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    ) {
                                        dragOffset = value
                                    }
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            // Collapsed content layer (pinned to fixed pill bounds at top-center, cancelling yOffset)
            if (collapsedAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .width(effectiveWidth.dp)
                        .height(effectiveHeight.dp)
                        .align(Alignment.TopCenter)
                        .graphicsLayer {
                            alpha = collapsedAlpha
                        }
                ) {
                    IslandCollapsedContent(
                        mode = activeMode,
                        notification = activeNotification,
                        collapsedAlpha = collapsedAlpha,
                        settings = settings
                    )
                }
            }

            // Expanded content layer — smoothly fade out while collapsing.
            // fillMaxWidth matches the ORIGINAL upstream mechanics exactly:
            // the card re-wraps with the animating pill width so the morph is
            // a true squeeze-into-the-pill (the behavior the original is
            // loved for). The previous requiredWidth(expandedWidth) pin kept
            // a full-width layer inside the shrinking pill, whose overflow
            // crop read as content sliding sideways mid-collapse on the
            // content-sized-window device class. Height re-measures during
            // the width spring are harmless: the expanded window is now
            // full-screen (MATCH_PARENT) on every device, so no window
            // relayout can chase the card height any more.
            if (expanded || expandedAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .graphicsLayer {
                            alpha = expandedAlpha
                            scaleX = contentScale
                            scaleY = contentScale
                            translationY = contentSlideY.toPx()
                        }
                ) {
                    IslandExpandedContent(
                        notifications = notifications,
                        launcherApps = launcherApps,
                        selectedIndex = selectedIndex,
                        onPageSelected = onPageSelected,
                        onOpenNotification = onOpenNotification,
                        onLaunchApp = onLaunchApp,
                        onCollapse = onToggleExpanded,
                        statusBarHeight = statusBarHeight.dp,
                        expandedWidth = expandedCardWidth,
                        // Each mode owns its natural height. The launcher already
                        // supplies its own loading height and must not impose that
                        // minimum on compact call or battery content.
                        onHeightMeasured = { expandedHeight = it },
                        settings = settings,
                        onReplyStateChanged = onReplyStateChanged,
                        onOpenIdleInfoItem = onOpenIdleInfoItem,
                        onInfoPageActive = { infoPageActive = it },
                        menuFeedback = menuFeedback
                    )
                }
            }
        }

        // Collapsed: secondary circle. Expanded with 2: the same item morphs
        // into a full-size pill. Expanded with 3+: it stays the circle on the right.
        if (secondaryAlpha > 0f && secondaryNotification != null) {
            Box(
                modifier = Modifier
                    .absoluteOffset {
                        IntOffset(
                            secondaryOffset.roundToPx(),
                            collapsedGroupY.roundToPx()
                        )
                    }
                    .width(secondaryBubbleWidth)
                    .height(circleSize)
                    .graphicsLayer {
                        alpha = secondaryAlpha
                        scaleX = secondaryScale * switchScaleAnim.value
                        scaleY = secondaryScale * switchScaleAnim.value
                    }
                    .then(
                        if (settings.enableShadow) {
                            Modifier.shadow(
                                elevation = 12.dp,
                                shape = RoundedCornerShape(secondaryBubbleCorner),
                                clip = false,
                                ambientColor = Color.Black,
                                spotColor = Color.Black
                            )
                        } else Modifier
                    )
                    .clip(RoundedCornerShape(secondaryBubbleCorner))
                    .background(Color.Black.copy(alpha = settings.opacity))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (secondaryIndex >= 0) {
                            onPageSelected(secondaryIndex)
                        }
                        if (!currentExpanded) {
                            currentOnToggle()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 1f - secondaryPillProgress },
                    contentAlignment = Alignment.Center
                ) {
                    SecondaryBubbleContent(
                        notification = secondaryNotification,
                        settings = settings
                    )
                }
                Box(
                    modifier = Modifier
                        .requiredWidth(miniPillWidth)
                        .height(circleSize)
                        .graphicsLayer { alpha = secondaryPillProgress },
                    contentAlignment = Alignment.Center
                ) {
                    IslandCollapsedContent(
                        mode = secondaryNotification.mode,
                        notification = secondaryNotification,
                        collapsedAlpha = 1f,
                        settings = settings
                    )
                }
            }
        }

        if (tertiaryAlpha > 0f && tertiaryNotification != null) {
            Box(
                modifier = Modifier
                    .absoluteOffset {
                        IntOffset(
                            tertiaryOffset.roundToPx(),
                            collapsedGroupY.roundToPx()
                        )
                    }
                    .width(circleSize)
                    .height(circleSize)
                    .graphicsLayer {
                        alpha = tertiaryAlpha
                        scaleX = tertiaryScale * switchScaleAnim.value
                        scaleY = tertiaryScale * switchScaleAnim.value
                    }
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = settings.opacity))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (tertiaryIndex >= 0) {
                            onPageSelected(tertiaryIndex)
                        }
                        if (!currentExpanded) {
                            currentOnToggle()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Icon-only content for the companion circle. The full collapsed
                // glyph (IslandCollapsedContent) also renders the right-slot
                // DATA text (timer countdown, battery %, call timer, …), which
                // is unreadable noise inside a circleSize bubble — the main
                // pill keeps the full glyph with data.
                SecondaryBubbleContent(
                    notification = tertiaryNotification,
                    settings = settings
                )
            }
        }
    }
}

@Composable
private fun SecondaryBubbleContent(
    notification: IslandNotification,
    settings: SmartIslandSettings
) {
    when (notification.mode) {
        IslandMode.Bluetooth -> {
            Image(
                painter = painterResource(id = com.agupta07505.smartisland.R.drawable.ic_bluetooth_device),
                contentDescription = "Bluetooth Device",
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
            )
        }
        IslandMode.Flashlight -> {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF59E0B).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.FlashlightOn,
                    contentDescription = "Flashlight",
                    tint = Color(0xFFFACC15),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        IslandMode.Hotspot -> {
            HotspotCollapsedGlyph(notification = notification, settings = settings)
        }
        IslandMode.Battery -> {
            BatteryCollapsedGlyph(notification = notification, settings = settings)
        }
        IslandMode.LiveActivity -> {
            LiveActivityCollapsedGlyph(notification = notification, settings = settings)
        }
        IslandMode.Navigation -> {
            NavigationCollapsedGlyph(notification = notification, settings = settings)
        }
        IslandMode.IncomingCall -> {
            val icon = notification.largeIcon ?: notification.icon
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                )
            } else {
                Icon(
                    Icons.Rounded.Call,
                    contentDescription = null,
                    tint = Color(settings.callColor),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        IslandMode.Music -> {
            val artwork = notification.largeIcon ?: notification.icon
            if (artwork != null) {
                Image(
                    bitmap = artwork.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(settings.musicVisualizerColor)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        IslandMode.ScreenRecording -> {
            ScreenRecordingCollapsedGlyph(settings = settings)
        }
        IslandMode.Timer -> {
            TimerCollapsedGlyph(notification = notification, settings = settings)
        }
        IslandMode.Stopwatch -> {
            StopwatchCollapsedGlyph(notification = notification, settings = settings)
        }
        IslandMode.Notification, IslandMode.DownloadUpload, IslandMode.Empty -> {
            NotificationGlyph(notification = notification, settings = settings)
        }
    }
}

// Animation specs
private const val EXPANDED_WIDTH_RATIO = 0.95f
private const val SWIPE_THRESHOLD_DP = 35f
private const val DRAG_MAX_OFFSET_DP = 100f
private const val COMPACT_INDICATOR_GAP_DP = 8f
private const val HOLD_GESTURE_THRESHOLD_MS = 300L

private fun triggerHapticVibration(context: android.content.Context) {
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            val vibrator = vm?.defaultVibrator
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(60L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                return
            }
        }
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        if (vibrator?.hasVibrator() == true) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(60L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(60L)
            }
        }
    }
}

internal enum class CompactNotificationShape { MiniPill, Circle }

internal fun defaultEstimatedHeightForMode(mode: IslandMode?): Dp {
    return when (mode) {
        IslandMode.Music -> 175.dp
        IslandMode.Notification -> 135.dp
        IslandMode.IncomingCall, IslandMode.Battery -> 115.dp
        IslandMode.LiveActivity, IslandMode.Navigation -> 180.dp
        IslandMode.DownloadUpload, IslandMode.Hotspot -> 160.dp
        IslandMode.Bluetooth, IslandMode.Flashlight, IslandMode.ScreenRecording,
        IslandMode.Timer, IslandMode.Stopwatch -> 115.dp
        IslandMode.Empty, null -> 135.dp
    }
}

internal fun compactNotificationShapes(
    notificationCount: Int,
    expanded: Boolean
): List<CompactNotificationShape> = when {
    notificationCount < 2 -> emptyList()
    !expanded -> listOf(CompactNotificationShape.Circle)
    notificationCount == 2 -> listOf(CompactNotificationShape.MiniPill)
    else -> listOf(CompactNotificationShape.MiniPill, CompactNotificationShape.Circle)
}
