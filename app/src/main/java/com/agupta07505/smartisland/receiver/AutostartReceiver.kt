/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agupta07505.smartisland.data.SmartIslandSettingsRepository
import com.agupta07505.smartisland.util.ShizukuManager
import com.agupta07505.smartisland.util.runCatchingLogged
import com.agupta07505.smartisland.util.runSuspendCatchingLogged
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AutostartReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SmartIslandSettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        runCatchingLogged("AutostartReceiver", "Autostart broadcast callback failed") {
            val action = intent.action ?: return@runCatchingLogged
            if (action != Intent.ACTION_BOOT_COMPLETED &&
                action != Intent.ACTION_MY_PACKAGE_REPLACED &&
                action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
                action != Intent.ACTION_USER_PRESENT
            ) return@runCatchingLogged

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    runSuspendCatchingLogged(
                        "AutostartReceiver",
                        "Failed handling autostart broadcast"
                    ) {
                        val settings = settingsRepository.settings.first()
                        if (settings.enabled && ShizukuManager.hasPermission()) {
                            ShizukuManager.autoGrantAllPermissions(context)
                        }
                        // Re-apply the status-bar icon hiding after boot /
                        // SystemUI restart: the platform clears every caller's
                        // disable flags whenever the status bar starts fresh,
                        // so the saved preference must be re-sent. Best-effort:
                        // it only runs while the Shizuku binder is reachable
                        // (started-on-boot via root, or once the user opens
                        // Shizuku — USER_PRESENT re-fires this receiver after
                        // unlock). The command is idempotent, so re-running it
                        // on every unlock that lands here is harmless, and a
                        // user-turned-off preference never re-hides anything.
                        if (settings.statusBarIconsHidden && ShizukuManager.hasPermission()) {
                            ShizukuManager.sendStatusBarDisableFlags(hide = true)
                        }
                    }
                } finally {
                    runCatchingLogged("AutostartReceiver", "goAsync finish failed") {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
