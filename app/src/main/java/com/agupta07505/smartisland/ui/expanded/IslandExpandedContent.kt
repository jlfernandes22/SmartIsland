/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.agupta07505.smartisland.ui.expanded

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.model.IslandMode
import com.agupta07505.smartisland.model.IslandNotification
import com.agupta07505.smartisland.data.SmartIslandSettings
import com.agupta07505.smartisland.data.LaunchableApp
import androidx.core.graphics.drawable.toBitmap

@Composable
fun IslandExpandedContent(
    notifications: List<IslandNotification>,
    launcherApps: List<LaunchableApp>?,
    selectedIndex: Int,
    onPageSelected: (Int) -> Unit,
    onOpenNotification: (IslandNotification) -> Unit,
    onLaunchApp: (String) -> Unit,
    onCollapse: () -> Unit,
    statusBarHeight: Dp,
    onHeightMeasured: (Dp) -> Unit,
    settings: SmartIslandSettings,
    modifier: Modifier = Modifier,
    onReplyStateChanged: (Boolean) -> Unit = {},
    onOpenIdleInfoItem: (String) -> Unit = {},
    onInfoPageActive: (Boolean) -> Unit = {},
    menuFeedback: String? = null
) {
    if (notifications.isEmpty()) {
        val density = LocalDensity.current
        Box(
            modifier = modifier
                .fillMaxWidth()
                .wrapContentHeight(unbounded = true)
                .onSizeChanged {
                    val measuredHeight = with(density) { it.height.toDp() }
                    if (measuredHeight > 0.dp) {
                        // Same clamp bounds as idleInfoMenuHeightDp's estimate:
                        // estimate == measurement is what keeps the menu from
                        // moving after it settles (see IdleInfoExpanded.kt).
                        val clamped = measuredHeight.coerceIn(IdleInfoMinHeight, IdleInfoMaxHeightDp.dp)
                        onHeightMeasured(clamped)
                    }
                }
        ) {
            EmptyExpanded(
                settings = settings,
                apps = launcherApps,
                onLaunchApp = onLaunchApp,
                onOpenIdleInfoItem = onOpenIdleInfoItem
            )
        }
        return
    }

    val density = LocalDensity.current
    var pageHeights by remember { mutableStateOf(emptyMap<String, Dp>()) }

    // When the idle tap mode is "Info Menu", an extra info page is prepended to the
    // pager so the info menu stays reachable even while notifications are active.
    val showInfoPage = settings.idleTapMode == com.agupta07505.smartisland.data.SmartIslandSettings.IdleTapModes.INFO
    val infoPageIndex = 0
    val pagerOffset = if (showInfoPage) 1 else 0
    val pageCount = notifications.size + pagerOffset
    var infoPageHeight by remember { mutableStateOf<Dp?>(null) }
    val scope = rememberCoroutineScope()

    // Clean up stale keys not present in notifications
    val activeKeys = remember(notifications) { notifications.map { it.key }.toSet() }
    LaunchedEffect(activeKeys) {
        pageHeights = pageHeights.filterKeys { it in activeKeys }
    }

    val pagerState = rememberPagerState(
        initialPage = (selectedIndex.coerceIn(0, notifications.lastIndex)) + pagerOffset,
        pageCount = { pageCount }
    )

    // Tracks the page the pager itself last settled on. When a selectedIndex change
    // matches it, the change is just the pager reporting its own scroll (user swipe)
    // and must not be re-animated; any other change (bubble tap, auto-expand) is an
    // external selection that wins and animates the pager into place.
    var lastPagerSettledIndex by remember { mutableStateOf(-1) }

    LaunchedEffect(selectedIndex) {
        val targetPage = selectedIndex.coerceIn(0, notifications.lastIndex) + pagerOffset
        if (targetPage in 0 until pageCount &&
            pagerState.currentPage != targetPage &&
            targetPage != lastPagerSettledIndex
        ) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    // Sync settled page updates back to caller ONLY when scroll has settled.
    // The info page (page 0) does not map to a notification.
    LaunchedEffect(pagerState.settledPage) {
        val settled = pagerState.settledPage
        if (settled in 0 until pageCount) {
            lastPagerSettledIndex = settled
            val notificationIndex = settled - pagerOffset
            if (notificationIndex in notifications.indices) {
                onPageSelected(notificationIndex)
            }
        }
    }

    // Safety net: Ensure pager never stays stuck at a non-zero offset fraction when scroll finishes
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && pagerState.currentPageOffsetFraction != 0f) {
            val targetPage = pagerState.settledPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            if (targetPage in 0 until pageCount) {
                pagerState.animateScrollToPage(targetPage)
            }
        }
    }

    // Tell the caller whether the info menu page is the one being displayed,
    // so overlay taps on it don't open the selected notification's app.
    LaunchedEffect(pagerState.currentPage) {
        onInfoPageActive(showInfoPage && pagerState.currentPage == infoPageIndex)
    }
    LaunchedEffect(Unit) {
        onInfoPageActive(showInfoPage && pagerState.currentPage == infoPageIndex)
    }

    val bottomPadding = 16.dp

    Column(modifier = modifier.fillMaxWidth().wrapContentHeight()) {

        // Interpolate height between pages based on swipe progress
        // FIX: Clamp battery/call heights to match music/notification style to avoid
        // glitch where battery/call expand more (taller) than other modes.
        val currentPage = pagerState.currentPage
        val offsetFraction = pagerState.currentPageOffsetFraction

        fun clampHeightForMode(notif: IslandNotification?, height: Dp): Dp {
            return when (notif?.mode) {
                IslandMode.Battery, IslandMode.IncomingCall -> height.coerceIn(72.dp, 125.dp)
                IslandMode.Notification -> height.coerceIn(95.dp, 145.dp)
                IslandMode.Music -> height.coerceIn(115.dp, 180.dp)
                IslandMode.LiveActivity -> height.coerceIn(140.dp, 205.dp)
                IslandMode.Navigation -> height.coerceIn(135.dp, 195.dp)
                IslandMode.DownloadUpload -> height.coerceIn(120.dp, 195.dp)
                IslandMode.Hotspot -> height.coerceIn(120.dp, 195.dp)
                IslandMode.Bluetooth -> height.coerceIn(72.dp, 130.dp)
                IslandMode.Flashlight -> height.coerceIn(72.dp, 130.dp)
                IslandMode.ScreenRecording -> height.coerceIn(72.dp, 130.dp)
                IslandMode.Timer -> height.coerceIn(72.dp, 130.dp)
                IslandMode.Stopwatch -> height.coerceIn(72.dp, 130.dp)
                else -> height.coerceIn(80.dp, 160.dp)
            }
        }

        fun pageHeightFor(page: Int): Dp {
            if (showInfoPage && page == infoPageIndex) {
                return infoPageHeight?.coerceIn(IdleInfoMinHeight, IdleInfoMaxHeightDp.dp)
                    ?: com.agupta07505.smartisland.ui.defaultEstimatedHeightForMode(null)
            }
            val notification = notifications.getOrNull(page - pagerOffset) ?: return 135.dp
            val raw = pageHeights[notification.key]
            return raw?.let { clampHeightForMode(notification, it) }
                ?: com.agupta07505.smartisland.ui.defaultEstimatedHeightForMode(notification.mode)
        }

        val currentPageHeight = pageHeightFor(currentPage)
        val targetHeight = run {
            val nextPage = if (offsetFraction > 0f) {
                (currentPage + 1).coerceAtMost(pageCount - 1)
            } else if (offsetFraction < 0f) {
                (currentPage - 1).coerceAtLeast(0)
            } else {
                currentPage
            }
            val nextHeight = pageHeightFor(nextPage)
            val fraction = kotlin.math.abs(offsetFraction)
            (currentPageHeight + (nextHeight - currentPageHeight) * fraction).coerceIn(72.dp, 250.dp)
        }

        LaunchedEffect(targetHeight) {
            onHeightMeasured(targetHeight)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(targetHeight)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    // unbounded = true: pages measure at natural height even when parent Box has explicit height
                    .wrapContentHeight(unbounded = true)
            ) { page ->
                if (showInfoPage && page == infoPageIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .onSizeChanged { size ->
                                val heightDp = with(density) { size.height.toDp() }
                                if (infoPageHeight != heightDp) {
                                    infoPageHeight = heightDp
                                }
                            }
                    ) {
                        IdleInfoExpanded(
                            settings = settings,
                            onItemClick = onOpenIdleInfoItem,
                            feedback = menuFeedback
                        )
                    }
                } else {
                    val notification = notifications.getOrNull(page - pagerOffset)
                    if (notification != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .onSizeChanged { size ->
                                val heightDp = with(density) { size.height.toDp() }
                                if (pageHeights[notification.key] != heightDp) {
                                    pageHeights = pageHeights.toMutableMap().apply { put(notification.key, heightDp) }
                                }
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onOpenNotification(notification)
                            }
                    ) {
                        when (notification.mode) {
                            IslandMode.Notification -> NotificationExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onOpenNotification = { onOpenNotification(notification) },
                                onCollapse = onCollapse,
                                showActions = settings.showNotificationActions,
                                settings = settings,
                                onReplyStateChanged = onReplyStateChanged
                            )
                            IslandMode.IncomingCall -> IncomingCallExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onCollapse = onCollapse,
                                settings = settings
                            )
                            IslandMode.Music -> MusicExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                settings = settings
                            )
                            IslandMode.Battery -> BatteryExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                settings = settings
                            )
                            IslandMode.LiveActivity -> LiveActivityExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onOpenNotification = { onOpenNotification(notification) },
                                onCollapse = onCollapse,
                                settings = settings
                            )
                            IslandMode.Navigation -> NavigationExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onOpenNotification = { onOpenNotification(notification) },
                                onCollapse = onCollapse,
                                settings = settings
                            )
                            IslandMode.DownloadUpload -> DownloadExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onOpenNotification = { onOpenNotification(notification) },
                                onCollapse = onCollapse,
                                settings = settings
                            )
                            IslandMode.Hotspot -> HotspotExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onOpenNotification = { onOpenNotification(notification) },
                                onCollapse = onCollapse,
                                settings = settings
                            )
                            IslandMode.Bluetooth -> BluetoothExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onCollapse = onCollapse,
                                settings = settings
                            )
                            IslandMode.Flashlight -> FlashlightExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onCollapse = onCollapse,
                                settings = settings
                            )
                            IslandMode.ScreenRecording -> ScreenRecordingExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onCollapse = onCollapse,
                                settings = settings
                            )
                            IslandMode.Timer -> TimerExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onOpenNotification = { onOpenNotification(notification) },
                                onCollapse = onCollapse,
                                settings = settings
                            )
                            IslandMode.Stopwatch -> StopwatchExpanded(
                                notification = notification,
                                bottomPadding = bottomPadding,
                                onOpenNotification = { onOpenNotification(notification) },
                                onCollapse = onCollapse,
                                settings = settings
                            )
                            IslandMode.Empty -> EmptyExpanded(
                                settings = settings,
                                apps = launcherApps,
                                onLaunchApp = onLaunchApp,
                                onOpenIdleInfoItem = onOpenIdleInfoItem,
                                menuFeedback = menuFeedback
                            )
                        }
                    }
                }
            }
            }

            // Notification count dots at the bottom of the expanded card:
            // one dot per page (info menu page first when enabled), the current one highlighted.
            if (pageCount > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    (0 until pageCount).forEach { index ->
                        val isActive = index == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .size(if (isActive) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) Color.White else Color.White.copy(alpha = 0.35f)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (showInfoPage && index == infoPageIndex) {
                                        scope.launch { pagerState.animateScrollToPage(infoPageIndex) }
                                    } else {
                                        onPageSelected(index - pagerOffset)
                                    }
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyExpanded(
    settings: SmartIslandSettings,
    apps: List<LaunchableApp>?,
    onLaunchApp: (String) -> Unit,
    onOpenIdleInfoItem: (String) -> Unit,
    menuFeedback: String? = null
) {
    if (settings.idleTapMode == com.agupta07505.smartisland.data.SmartIslandSettings.IdleTapModes.INFO) {
        IdleInfoExpanded(
            settings = settings,
            onItemClick = onOpenIdleInfoItem,
            feedback = menuFeedback
        )
        return
    }

    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(unbounded = true)
            .padding(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        val loadedApps = apps
        val hasConfiguration = settings.shortcutPackages.isNotEmpty() || settings.showRecentApps

        if (loadedApps == null) {
            // Keep the configured launcher visually clean while PackageManager and
            // UsageStats are queried. In particular, do not flash the setup state.
            Spacer(Modifier.height(84.dp))
        } else if (loadedApps.isEmpty() && !hasConfiguration) {
            Text("Quick launch", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Choose shortcuts in the Smart Island app",
                color = Color(0xFFB7C0CA),
                fontSize = 13.sp
            )
            Text(
                "Open Smart Island settings",
                color = Color(0xFF67E8F9),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clickable { onLaunchApp(context.packageName) }
            )
        } else if (loadedApps.isEmpty()) {
            Text(
                "Selected apps are unavailable. Update App shortcuts in Smart Island.",
                color = Color(0xFFB7C0CA),
                fontSize = 13.sp
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                loadedApps.chunked(4).forEach { rowApps ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        rowApps.forEach { app ->
                            ShortcutApp(app = app, onClick = { onLaunchApp(app.packageName) })
                        }
                        repeat(4 - rowApps.size) { Box(Modifier.size(width = 64.dp, height = 1.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutApp(app: LaunchableApp, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName)
                .toBitmap(width = 96, height = 96)
                .asImageBitmap()
        }.getOrNull()
    }
    Column(
        modifier = Modifier
            .size(width = 64.dp, height = 76.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = app.label,
                modifier = Modifier.size(44.dp)
            )
        }
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}
