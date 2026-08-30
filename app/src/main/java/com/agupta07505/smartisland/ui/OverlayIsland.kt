/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.agupta07505.smartisland.data.AppShortcutProvider
import com.agupta07505.smartisland.data.LaunchableApp
import com.agupta07505.smartisland.model.IslandNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OverlayIsland(
    viewModel: IslandViewModel,
    statusBarHeight: Float,
    onOpenNotification: (IslandNotification) -> Unit,
    onLaunchApp: (String) -> Unit,
    onOpenFloatingWindow: () -> Unit,
    modifier: Modifier = Modifier,
    isFullWidth: Boolean = true,
    // Published by SmartIslandOverlayService: dp offset of the actual overlay
    // window center from the screen center (0f for full-width windows). Passed
    // down to IslandOverlayView, which subtracts it from every rendered
    // x-translation so the collapsed content stays anchored to the screen while
    // the narrow collapsed window resizes mid-animation.
    windowCenterOffsetFlow: StateFlow<Float> = MutableStateFlow(0f),
    onOpenIdleInfoItem: (String) -> Unit = {},
    onExpandedWindowContentSize: (Int, Int) -> Unit = { _, _ -> }
) {
    val settings by viewModel.settings.collectAsState()
    val expanded by viewModel.expanded.collectAsState()
    val notifications by viewModel.visibleNotifications.collectAsState()
    val selectedIndex by viewModel.selectedIndex.collectAsState()
    val isLocked by viewModel.isLocked.collectAsState()
    val isInputActive by viewModel.isInputActive.collectAsState()
    val menuFeedback by viewModel.menuFeedback.collectAsState()
    val reappearTick by viewModel.reappearTick.collectAsState()
    val windowCenterOffsetDp by windowCenterOffsetFlow.collectAsState()
    val context = LocalContext.current

    val isContentRedacted = isLocked && settings.lockScreenPrivacy == "AppIconOnly"
    val processedNotifications = remember(notifications, isContentRedacted) {
        if (isContentRedacted) {
            notifications.map { notif ->
                if (notif.mode == com.agupta07505.smartisland.model.IslandMode.Notification) {
                    notif.copy(
                        title = notif.appName,
                        text = "Contents hidden",
                        actionIntents = emptyList()
                    )
                } else {
                    notif
                }
            }
        } else {
            notifications
        }
    }

    val selectedApps = remember(settings.shortcutPackages) {
        AppShortcutProvider.selectedApps(context, settings.shortcutPackages)
    }
    val launcherApps by produceState<List<LaunchableApp>?>(
        initialValue = when {
            selectedApps.isNotEmpty() -> selectedApps
            settings.shortcutPackages.isEmpty() && !settings.showRecentApps -> emptyList()
            !AppShortcutProvider.hasUsageAccess(context) -> emptyList()
            else -> null
        },
        settings.shortcutPackages,
        settings.showRecentApps
    ) {
        value = withContext(Dispatchers.IO) {
            AppShortcutProvider.shortcuts(
                context = context,
                selectedPackages = settings.shortcutPackages,
                includeRecent = settings.showRecentApps
            )
        }
    }

    IslandOverlayView(
        settings = settings,
        expanded = expanded,
        notifications = processedNotifications,
        selectedIndex = selectedIndex,
        launcherApps = launcherApps,
        onPageSelected = { index -> viewModel.setSelectedNotificationIndex(index) },
        onOpenNotification = onOpenNotification,
        onLaunchApp = onLaunchApp,
        onToggleExpanded = { viewModel.toggleExpanded() },
        onDismissNotification = { viewModel.dismissCurrentNotification() },
        onDismissAllNotifications = { viewModel.dismissAllNotifications() },
        onOpenFloatingWindow = onOpenFloatingWindow,
        statusBarHeight = statusBarHeight,
        isInputActive = isInputActive,
        onReplyStateChanged = { viewModel.setInputActive(it) },
        isFullWidth = isFullWidth,
        windowCenterOffsetDp = windowCenterOffsetDp,
        onOpenIdleInfoItem = onOpenIdleInfoItem,
        menuFeedback = menuFeedback,
        reappearTick = reappearTick,
        onExpandedWindowContentSize = onExpandedWindowContentSize,
        modifier = modifier
    )
}
