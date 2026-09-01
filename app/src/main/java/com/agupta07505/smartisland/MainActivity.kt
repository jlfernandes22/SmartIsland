/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.agupta07505.smartisland.data.INotificationRepository
import com.agupta07505.smartisland.data.SmartIslandSettingsRepository
import com.agupta07505.smartisland.ui.SmartIslandHomeScreen
import com.agupta07505.smartisland.ui.SmartIslandTheme
import com.agupta07505.smartisland.util.SystemServiceRecovery
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SmartIslandSettingsRepository
    @Inject lateinit var notificationRepository: INotificationRepository

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Bluetooth permission granted", Toast.LENGTH_SHORT).show()
        }
    }

    // Toggle-rows permission flow: the overlay launches MainActivity with
    // EXTRA_REQUEST_TOGGLE_PERMISSIONS when a hotspot / tethering row needs a
    // runtime grant (the same launch-an-extra pattern as the Bluetooth
    // request below). Only permissions the user has never rejected are
    // asked for — a previous rejection keeps the system dialog silent and
    // would only flash an empty screen.
    private val togglePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemServiceRecovery.requestRecovery(this)
        requestNotificationPermissionIfNeeded()

        if (intent?.getBooleanExtra(EXTRA_REQUEST_BLUETOOTH_PERMISSION, false) == true) {
            requestBluetoothPermissionIfNeeded()
        }
        if (intent?.getBooleanExtra(EXTRA_REQUEST_TOGGLE_PERMISSIONS, false) == true) {
            requestTogglePermissionsIfNeeded()
        }

        setContent {
            SmartIslandTheme {
                SmartIslandHomeScreen(
                    repository = settingsRepository,
                    notificationRepository = notificationRepository
                )
            }
        }
    }

    /**
     * POST_NOTIFICATIONS (API 33+): the app posts its own notifications —
     * the foreground-service status and the silent lock-screen unread
     * mirrors — and without this runtime grant the system silently drops
     * every one of them. Asked once on the first launch; rejections simply
     * keep the island running without its own notifications.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not: the island works either way */ }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_BLUETOOTH_PERMISSION, false)) {
            requestBluetoothPermissionIfNeeded()
        }
        if (intent.getBooleanExtra(EXTRA_REQUEST_TOGGLE_PERMISSIONS, false)) {
            requestTogglePermissionsIfNeeded()
        }
    }

    private fun requestBluetoothPermissionIfNeeded() {
        // BLUETOOTH_CONNECT exists only on S+; on older releases the legacy
        // install-time BLUETOOTH permission already covers adapter reads, so
        // asking would be both a no-op and a lint InlinedApi violation.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val granted = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /**
     * Runtime grants the hotspot / tethering toggle rows rely on for their
     * state reads (the toggles themselves are dispatched with shell-level
     * privileges through the Shizuku user service and need no app permission).
     * Only ever asks for permissions that are missing AND never rejected
     * (shouldShowRequestPermissionRationale is false for never-asked and
     * permanently-denied, but a permanently-denied request would be a silent
     * no-op — matching the "user has never rejected it" rule).
     */
    private fun requestTogglePermissionsIfNeeded() {
        val wanted = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            wanted.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            wanted.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val askNow = wanted.filter { perm ->
            checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(perm)
        }
        if (askNow.isNotEmpty()) {
            togglePermissionsLauncher.launch(askNow.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        SystemServiceRecovery.requestRecovery(this)
    }

    companion object {
        const val EXTRA_REQUEST_BLUETOOTH_PERMISSION = "request_bluetooth_permission"

        /** Overlay → MainActivity: ask for the missing toggle-row permissions. */
        const val EXTRA_REQUEST_TOGGLE_PERMISSIONS = "request_toggle_permissions"
    }
}