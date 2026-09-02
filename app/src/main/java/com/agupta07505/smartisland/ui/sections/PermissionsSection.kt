/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui.sections

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.agupta07505.smartisland.R
import com.agupta07505.smartisland.data.SmartIslandSettings
import com.agupta07505.smartisland.data.SmartIslandSettingsRepository
import com.agupta07505.smartisland.ui.PermissionCard
import com.agupta07505.smartisland.util.CrashCapture
import com.agupta07505.smartisland.util.OemAutostartUtil
import com.agupta07505.smartisland.util.ShizukuManager
import com.agupta07505.smartisland.util.safeStartActivity
import kotlinx.coroutines.launch

@Composable
fun PermissionsSection(
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    batteryIgnored: Boolean = false,
    settings: SmartIslandSettings,
    repository: SmartIslandSettingsRepository,
    onOverlayClick: () -> Unit,
    onNotificationClick: () -> Unit,
    onBatteryClick: () -> Unit,
    onRefreshPermissions: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // LAST CRASH: CrashCapture persists every uncaught exception to
    // filesDir/crash-last.txt before the process dies; if a report exists,
    // surface it here so a stack-less "the app crashes" message becomes an
    // actionable stack (copy → paste to the maintainer). Remains until the
    // user dismisses it — the file outlives process restarts by design.
    var isExecutingShizuku by remember { mutableStateOf(false) }
    var isOemAutostartEnabled by remember { mutableStateOf(batteryIgnored) }
    var isOverlayWarningDisabled by remember { mutableStateOf(false) }
    // Status Bar Icons card state (moved here from Color Studio — the switch
    // is Shizuku-powered system integration, not a color customization, and
    // users could not find it buried under the color pickers).
    var statusBarBusy by remember { mutableStateOf(false) }
    var statusBarStatus by remember { mutableStateOf<String?>(null) }
    var statusBarStatusIsError by remember { mutableStateOf(false) }

    // POST-REBOOT CONVERGENCE: the status bar's disable flags are volatile
    // (the platform clears them on every reboot / SystemUI restart), so with
    // "hide" saved the icons can still be visible after a boot whenever the
    // Shizuku server was not up at the moment the boot-time re-apply ran.
    // Opening the Permissions Center with "hide" saved re-sends the
    // idempotent hide command once and surfaces the outcome in the same
    // inline status line the manual switch uses — a failure (e.g. "Shizuku
    // unavailable") explains exactly why the icons are still visible, so the
    // toggle never looks stale without saying why. Runs once per section
    // visit (LaunchedEffect(Unit) semantics), never fights a manual toggle
    // (statusBarBusy gates the switch while it runs).
    LaunchedEffect(Unit) {
        if (!settings.statusBarIconsHidden) return@LaunchedEffect
        statusBarBusy = true
        statusBarStatus = null
        val result = ShizukuManager.sendStatusBarDisableFlags(hide = true)
        if (result.isSuccess) {
            statusBarStatusIsError = false
            statusBarStatus = "Status bar icons hidden"
        } else {
            statusBarStatusIsError = true
            val reason = result.exceptionOrNull()?.message
            statusBarStatus = if (reason.isNullOrBlank()) {
                "Couldn't re-hide the icons"
            } else {
                "Couldn't re-hide the icons — $reason"
            }
        }
        statusBarBusy = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Last-crash report card — only rendered when CrashCapture has a
        // persisted uncaught exception. Copy puts the full stack on the
        // clipboard; dismiss deletes the file.
        var crashReport by remember { mutableStateOf(CrashCapture.lastCrashReport(context)) }
        if (crashReport != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.BugReport,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.crash_card_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.crash_card_desc),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                val report = crashReport.orEmpty()
                                val clipboard = context.getSystemService(
                                    android.content.Context.CLIPBOARD_SERVICE
                                ) as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                        "SmartIsland crash log",
                                        report
                                    )
                                )
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.crash_copied),
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.crash_copy),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    TextButton(onClick = {
                        CrashCapture.clear(context)
                        crashReport = null
                    }) {
                        Text(
                            text = stringResource(R.string.crash_dismiss),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Shizuku 1-Tap Auto Setup Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FlashOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.shizuku_card_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val shizukuStateText = when {
                                // Binder-first: Sui (rikka.sui) users have a
                                // fully working API with NO Shizuku package
                                // installed, so the binder — not a package
                                // lookup — is the source of truth. With the
                                // old order this card read "Not Installed"
                                // forever for Sui users while every Shizuku
                                // feature actually worked.
                                ShizukuManager.isBinderAvailable() && ShizukuManager.hasPermission() -> "Ready to Auto-Grant"
                                ShizukuManager.isBinderAvailable() -> "Permission Required"
                                ShizukuManager.isInstalled(context) -> "Shizuku Not Running"
                                else -> "Not Installed"
                            }
                            Text(
                                text = shizukuStateText,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (ShizukuManager.isBinderAvailable() && ShizukuManager.hasPermission()) Color(0xFF0F9F6E) else MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        enabled = !isExecutingShizuku,
                        onClick = {
                            when {
                                // Binder-first (Sui-compatible): the binder is
                                // the API — package detection is only needed
                                // to tell a stopped provider from a missing
                                // one in the error paths.
                                ShizukuManager.isBinderAvailable() && !ShizukuManager.hasPermission() -> {
                                    ShizukuManager.requestPermission()
                                }
                                ShizukuManager.isBinderAvailable() -> {
                                    isExecutingShizuku = true
                                    scope.launch {
                                        val result = ShizukuManager.autoGrantAllPermissions(context)
                                        isExecutingShizuku = false
                                        result.onSuccess { msg ->
                                            Toast.makeText(context, context.getString(R.string.shizuku_success), Toast.LENGTH_LONG).show()
                                            isOemAutostartEnabled = true
                                            isOverlayWarningDisabled = true
                                            onRefreshPermissions()
                                        }.onFailure { err ->
                                            Toast.makeText(context, context.getString(R.string.shizuku_failed, err.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                                ShizukuManager.isInstalled(context) -> {
                                    Toast.makeText(context, context.getString(R.string.shizuku_installed_not_running), Toast.LENGTH_LONG).show()
                                }
                                else -> {
                                    Toast.makeText(context, context.getString(R.string.shizuku_not_installed), Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (isExecutingShizuku) stringResource(R.string.shizuku_btn_running) else stringResource(R.string.shizuku_btn_run),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.shizuku_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        // Required Permission 1: Accessibility
        PermissionCard(
            title = stringResource(R.string.perm_accessibility_title),
            description = stringResource(R.string.perm_accessibility_desc),
            granted = overlayGranted,
            buttonText = stringResource(R.string.btn_grant),
            onClick = onOverlayClick
        )

        // Required Permission 2: Notification Listener
        PermissionCard(
            title = stringResource(R.string.perm_notification_title),
            description = stringResource(R.string.perm_notification_desc),
            granted = notificationGranted,
            buttonText = stringResource(R.string.btn_grant),
            onClick = onNotificationClick
        )

        // Recommended Permission 3: Battery Optimization
        PermissionCard(
            title = stringResource(R.string.perm_battery_title),
            description = stringResource(R.string.perm_battery_desc),
            granted = batteryIgnored,
            buttonText = stringResource(R.string.btn_grant),
            onClick = onBatteryClick
        )

        // Overlay System Warning Card
        val warningIconColor = if (isOverlayWarningDisabled) Color(0xFF0F9F6E) else MaterialTheme.colorScheme.onSurfaceVariant
        val warningBgColor = if (isOverlayWarningDisabled) Color(0xFF0F9F6E).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(warningBgColor, shape = RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isOverlayWarningDisabled) Icons.Rounded.CheckCircle else Icons.Rounded.VisibilityOff,
                                contentDescription = null,
                                tint = warningIconColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Suppress System Overlay Warning",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isOverlayWarningDisabled) {
                                Spacer(Modifier.height(3.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF0F9F6E).copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Configured",
                                        color = Color(0xFF0F9F6E),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = {
                            isOverlayWarningDisabled = true
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, "android")
                            }
                            context.safeStartActivity(
                                intent,
                                "Cannot open app notification settings on this device."
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(if (isOverlayWarningDisabled) "Open" else "Hide Alert", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Opens Android system notification channels to hide the persistent \"Smart Island is displaying over other apps\" banner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        // OEM Autostart & Kill Protection Card
        val oemIconColor = if (isOemAutostartEnabled) Color(0xFF0F9F6E) else MaterialTheme.colorScheme.tertiary
        val oemBgColor = if (isOemAutostartEnabled) Color(0xFF0F9F6E).copy(alpha = 0.12f) else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(oemBgColor, shape = RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isOemAutostartEnabled) Icons.Rounded.CheckCircle else Icons.Rounded.Build,
                                contentDescription = null,
                                tint = oemIconColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "OEM Autostart & Kill Protection",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isOemAutostartEnabled) {
                                Spacer(Modifier.height(3.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF0F9F6E).copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Configured",
                                        color = Color(0xFF0F9F6E),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (ShizukuManager.hasPermission() && !isOemAutostartEnabled) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val result = ShizukuManager.grantOemAutostartAndKillProtection(context)
                                        result.onSuccess {
                                            isOemAutostartEnabled = true
                                            Toast.makeText(context, "OEM autostart granted via Shizuku!", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            Toast.makeText(context, "Error: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text("Shizuku", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                isOemAutostartEnabled = true
                                OemAutostartUtil.openAutostartSettings(context)
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Fix Kills", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "On Xiaomi/HyperOS, Samsung OneUI, OPPO ColorOS, and Vivo OriginOS, enable Autostart to prevent custom OEM task killers from terminating Smart Island.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        // Card: Status Bar Icons — hides the clock, system icons and
        // notification icons (cmd statusbar send-disable-flag via Shizuku) so
        // the island can take the status bar's place. Lives in the Permissions
        // Center because it is Shizuku-powered system integration (it used to
        // sit at the bottom of Color Studio where nobody could find it). The
        // command is strict (exit-code checked): when it fails the switch
        // stays untouched and the exact failure reason is shown inline below.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.statusbar_icons_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.statusbar_icons_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = settings.statusBarIconsHidden,
                        enabled = !statusBarBusy,
                        onCheckedChange = { hide ->
                            if (statusBarBusy) return@Switch
                            scope.launch {
                                statusBarBusy = true
                                statusBarStatus = null
                                val result = ShizukuManager.sendStatusBarDisableFlags(hide)
                                if (result.isSuccess) {
                                    repository.setStatusBarIconsHidden(hide)
                                    statusBarStatusIsError = false
                                    statusBarStatus =
                                        if (hide) "Status bar icons hidden" else "Status bar icons restored"
                                } else {
                                    statusBarStatusIsError = true
                                    val reason = result.exceptionOrNull()?.message
                                    val action = if (hide) "hide" else "restore"
                                    statusBarStatus = if (reason.isNullOrBlank()) {
                                        "Couldn't $action the icons"
                                    } else {
                                        "Couldn't $action the icons — $reason"
                                    }
                                }
                                statusBarBusy = false
                            }
                        }
                    )
                }
                if (statusBarStatus != null) {
                    Text(
                        text = statusBarStatus.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusBarStatusIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        }
    }
}
