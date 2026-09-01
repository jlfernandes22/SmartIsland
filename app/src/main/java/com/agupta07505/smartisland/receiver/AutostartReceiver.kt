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
import com.agupta07505.smartisland.util.CrashGuard
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

            // ROUND-V CRASH-LOOP BREAKER: while safe mode is latched, the
            // auto-grant batch (secure-settings writes that re-arm the
            // very services suspected of re-crashing the process) must not
            // run. The status-bar flag re-apply below is equally deferred —
            // the user re-enables everything by exiting safe mode.
            if (CrashGuard.isSafeMode(context)) return@runCatchingLogged

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    runSuspendCatchingLogged(
                        "AutostartReceiver",
                        "Failed handling autostart broadcast"
                    ) {
                        val settings = settingsRepository.settings.first()
                        // PERMISSION GRANTS PERSIST in Settings.Secure — they
                        // never need re-applying, and the full autoGrant batch
                        // (blocking shell inside a goAsync budget + repeated
                        // secure-settings writes that re-arm accessibility
                        // rebinds) must NOT run on every unlock. It previously
                        // fired on USER_PRESENT too: the moment the Shizuku
                        // permission existed, EVERY unlock re-ran the whole
                        // one-tap batch — an ANR vector and a crash-loop
                        // amplifier. Re-apply only on events that genuinely
                        // reset state: boot (and locked boot) and package
                        // update (restricted-settings re-freeze).
                        val isStateReset = action == Intent.ACTION_BOOT_COMPLETED ||
                            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                            action == Intent.ACTION_MY_PACKAGE_REPLACED
                        if (isStateReset && settings.enabled && ShizukuManager.hasPermission()) {
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
