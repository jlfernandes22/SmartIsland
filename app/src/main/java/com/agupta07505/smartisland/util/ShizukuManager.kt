/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.agupta07505.smartisland.service.SmartIslandNotificationListenerService
import com.agupta07505.smartisland.service.SmartIslandOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuManager {

    /**
     * Checks if the Shizuku app is installed on the device.
     * 100% crash proof against Throwable (including unit test stubs).
     */
    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Safely checks if the Shizuku service binder is currently alive and responsive.
     * Catches any Throwable (DeadObjectException, ExceptionInInitializerError, RemoteException).
     */
    fun isBinderAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Safely checks if Shizuku API permission has been granted to Smart Island.
     */
    fun hasPermission(): Boolean {
        if (!isBinderAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Safely requests Shizuku permission. Does not crash if Shizuku service is dead.
     */
    fun requestPermission(requestCode: Int = 1001) {
        if (!isBinderAvailable()) return
        try {
            Shizuku.requestPermission(requestCode)
        } catch (t: Throwable) {
            android.util.Log.e("ShizukuManager", "Failed to request Shizuku permission", t)
        }
    }

    private fun runShizukuCommands(commands: List<String>): Result<String> {
        if (!hasPermission()) {
            return Result.failure(IllegalStateException("Shizuku permission not granted or service binder offline."))
        }
        return runCatching {
            val fullScript = commands.joinToString("; ")
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", fullScript), null, null) as Process

            // Drain stderr on a helper thread: if the remote process fills its
            // stderr pipe while this thread blocks reading stdout (or vice
            // versa) the command deadlocks forever — which would leave the
            // island window stuck with FLAG_NOT_TOUCHABLE during a toggle.
            val errorBuilder = java.lang.StringBuffer()
            val errorDrainer = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                }.getOrNull()?.let { errorBuilder.append(it) }
            }.apply { isDaemon = true; start() }

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            errorDrainer.join(5000L)
            val error = errorBuilder.toString()
            val exitCode = process.waitFor()

            if (exitCode == 0 || output.isNotBlank() || error.isBlank()) {
                "Permissions auto-granted successfully via Shizuku."
            } else {
                throw RuntimeException("Shizuku command error (exit $exitCode): $error $output")
            }
        }
    }

    internal fun mergeColonSeparated(currentList: String, newEntry: String): String {
        val list = currentList.split(':').filter { it.isNotBlank() }.toMutableList()
        if (!list.contains(newEntry)) {
            list.add(newEntry)
        }
        return list.joinToString(":")
    }

    internal fun getMergedAccessibilityServices(context: Context, serviceComponent: String): String {
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        return mergeColonSeparated(current, serviceComponent)
    }

    internal fun getMergedNotificationListeners(context: Context, listenerComponent: String): String {
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        return mergeColonSeparated(current, listenerComponent)
    }

    /**
     * Executes ADB shell commands via Shizuku process on IO dispatcher to auto-grant:
     * - Allow restricted settings (Android 13+)
     * - Usage Access / Usage Stats (GET_USAGE_STATS)
     * - Accessibility Service & System Alert Window (preserving existing accessibility services)
     * - Notification Listener Access (preserving existing notification listeners)
     * - Battery Optimization whitelist
     */
    suspend fun autoGrantAllPermissions(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val accessibilityClass = "$pkg/${SmartIslandOverlayService::class.java.name}"
        val notificationClass = "$pkg/${SmartIslandNotificationListenerService::class.java.name}"
        val mergedAccessibilityServices = getMergedAccessibilityServices(context, accessibilityClass)
        val mergedNotificationListeners = getMergedNotificationListeners(context, notificationClass)

        val commands = listOf(
            "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow",
            "appops set $pkg GET_USAGE_STATS allow",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg BIND_ACCESSIBILITY_SERVICE allow",
            "appops set $pkg POST_NOTIFICATION allow",
            "appops set $pkg AUTO_START allow",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "settings put secure enabled_accessibility_services $mergedAccessibilityServices",
            "settings put secure accessibility_enabled 1",
            "cmd notification allow_listener $notificationClass",
            "settings put secure enabled_notification_listeners $mergedNotificationListeners",
            "am set-standby-bucket $pkg active",
            "dumpsys deviceidle whitelist +$pkg"
        )
        runShizukuCommands(commands)
    }

    /**
     * Grants OEM Autostart and disables background kill / app standby restrictions via Shizuku.
     */
    suspend fun grantOemAutostartAndKillProtection(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val commands = listOf(
            "appops set $pkg AUTO_START allow",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "am set-standby-bucket $pkg active",
            "dumpsys deviceidle whitelist +$pkg"
        )
        runShizukuCommands(commands)
    }

    /**
     * Grants Notification Listener permission via Shizuku without overwriting other active listeners.
     */
    suspend fun grantNotificationListener(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val notificationClass = "$pkg/${SmartIslandNotificationListenerService::class.java.name}"
        val mergedNotificationListeners = getMergedNotificationListeners(context, notificationClass)
        val commands = listOf(
            "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow",
            "cmd notification allow_listener $notificationClass",
            "settings put secure enabled_notification_listeners $mergedNotificationListeners"
        )
        runShizukuCommands(commands)
    }

    /**
     * Grants Accessibility service permission via Shizuku without overwriting other active accessibility services.
     */
    suspend fun grantAccessibility(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val accessibilityClass = "$pkg/${SmartIslandOverlayService::class.java.name}"
        val mergedAccessibilityServices = getMergedAccessibilityServices(context, accessibilityClass)
        val commands = listOf(
            "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow",
            "appops set $pkg BIND_ACCESSIBILITY_SERVICE allow",
            "settings put secure enabled_accessibility_services $mergedAccessibilityServices",
            "settings put secure accessibility_enabled 1"
        )
        runShizukuCommands(commands)
    }

    /**
     * Toggles Bluetooth with shell-level privileges via Shizuku.
     *
     * On Android 12+ a normal app cannot call BluetoothAdapter.enable()/disable()
     * (they return false even with BLUETOOTH_CONNECT granted), but the `shell`
     * uid holds BLUETOOTH_PRIVILEGED. Shizuku therefore dispatches the toggle
     * through the hidden BluetoothManagerService shell commands:
     *   1. `cmd bluetooth_manager enable|disable` (Android 11+ shell command)
     *   2. `svc bluetooth enable|disable` (older, still present on current ROMs)
     * `||` tries the second only when the first fails. No dialogs, no settings
     * pages, no shade pull-down — the overlay island stays untouched.
     *
     * @return success when the command chain exited cleanly. Callers MUST still
     * verify the actual state change via Settings.Global "bluetooth_on".
     */
    suspend fun toggleBluetooth(enable: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isBinderAvailable() || !hasPermission()) {
            return@withContext Result.failure(
                IllegalStateException("Shizuku binder offline or API permission not granted")
            )
        }
        val action = if (enable) "enable" else "disable"
        runShizukuCommands(
            listOf("cmd bluetooth_manager $action || svc bluetooth $action")
        ).map { true }
    }

    /**
     * Toggles a tethering type with shell-level privileges via Shizuku.
     *
     * Uses the ConnectivityService shell interface (Android 13+):
     *   `cmd connectivity tethering <kind> <enable|disable>`
     * where kind is one of "wifi", "usb" or "bluetooth". `shell` uid holds the
     * NETWORK_SETTINGS/TETHER_PRIVILEGED-like permissions the tethering shell
     * command checks, so this works where a normal app's TetheringManager calls
     * would need a full Settings page round-trip.
     *
     * Best-effort by design: some OEM builds may not expose the shell command,
     * in which case the returned Result fails and the caller shows in-menu
     * feedback (no dialogs, no settings pages — the island stays untouched).
     *
     * @param kind "wifi" (Wi-Fi hotspot), "usb" (USB tethering) or "bluetooth"
     *        (Bluetooth tethering).
     * @return success when the command exited cleanly. Callers SHOULD still
     *         verify the actual state change with whatever reader is available.
     */
    suspend fun toggleTethering(kind: String, enable: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            if (!isBinderAvailable() || !hasPermission()) {
                return@withContext Result.failure(
                    IllegalStateException("Shizuku binder offline or API permission not granted")
                )
            }
            require(kind in TETHERING_KINDS) { "Unknown tethering kind: $kind" }
            val action = if (enable) "enable" else "disable"
            runShizukuCommands(
                listOf("cmd connectivity tethering $kind $action")
            ).map { true }
        }

    /** Valid [toggleTethering] kinds, mirroring the shell command's types. */
    val TETHERING_KINDS = setOf("wifi", "usb", "bluetooth")
}
