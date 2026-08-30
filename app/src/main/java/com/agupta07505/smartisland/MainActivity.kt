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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemServiceRecovery.requestRecovery(this)

        if (intent?.getBooleanExtra(EXTRA_REQUEST_BLUETOOTH_PERMISSION, false) == true) {
            requestBluetoothPermissionIfNeeded()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_BLUETOOTH_PERMISSION, false)) {
            requestBluetoothPermissionIfNeeded()
        }
    }

    private fun requestBluetoothPermissionIfNeeded() {
        val granted = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    override fun onResume() {
        super.onResume()
        SystemServiceRecovery.requestRecovery(this)
    }

    companion object {
        const val EXTRA_REQUEST_BLUETOOTH_PERMISSION = "request_bluetooth_permission"
    }
}