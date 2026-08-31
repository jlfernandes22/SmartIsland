/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Settings
import com.agupta07505.smartisland.BuildConfig
import com.agupta07505.smartisland.service.SmartIslandNotificationListenerService
import com.agupta07505.smartisland.service.SmartIslandOverlayService
import com.agupta07505.smartisland.shizuku.ITetheringUserService
import com.agupta07505.smartisland.shizuku.TetheringShizukuService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShizukuManager {

    /**
     * Packages that can serve the Shizuku API. Besides the official Shizuku
     * manager app (`moe.shizuku.privileged.api`), [Sui](https://github.com/RikkaApps/Sui)
     * (`rikka.sui`) implements the exact same binder contract for rooted
     * devices — hard-coding the Shizuku package name alone made every
     * Shizuku-powered feature report "Not Installed" for Sui users even
     * while the binder was fully alive (their OEM-autostart path worked,
     * proving the binder itself was fine).
     */
    private val SHIZUKU_PROVIDER_PACKAGES = listOf(
        "moe.shizuku.privileged.api",
        "rikka.sui"
    )

    /**
     * Checks whether a Shizuku-compatible provider is usable: a LIVE BINDER
     * is the definitive answer (Sui users pass here with no Shizuku app
     * installed), otherwise either provider package being visible counts.
     * 100% crash proof against Throwable.
     */
    fun isInstalled(context: Context): Boolean {
        if (isBinderAvailable()) return true
        return try {
            SHIZUKU_PROVIDER_PACKAGES.any { pkg ->
                runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }
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

    /** Raw result of one Shizuku-exec'd shell script. */
    private class ShizukuShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    /**
     * Spawns one `sh -c [script]` process inside the Shizuku server (uid
     * 2000) and collects its streams. Shared by both runners below.
     *
     * Stderr is drained on a helper thread: if the remote process fills its
     * stderr pipe while this thread blocks reading stdout (or vice versa) the
     * command deadlocks forever — which would leave the island window stuck
     * with FLAG_NOT_TOUCHABLE during a toggle.
     */
    private fun shizukuExec(script: String): ShizukuShellResult {
        val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }

        val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", script), null, null) as Process

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
        return ShizukuShellResult(exitCode, output, error)
    }

    /**
     * The platform's tethered-interface list, read THROUGH the bound user
     * service (shell uid, no hidden-API restrictions). This is the
     * authoritative hotspot state the in-app readers cannot get: app-process
     * TetheringManager reflection is blocked on modern Android and the
     * legacy WifiManager fallback fails with it, which used to leave the
     * info menu stuck on "Tap to toggle" AND made every tap compute
     * "turn ON" as the target direction (before == null) — so a tap while
     * the hotspot was UP never turned it OFF.
     *
     * @return the pipe-joined lowercase iface list ("" = definitively
     *         nothing tethering), or null when there is no live user service
     *         answer and the caller should fall back to its own readers.
     */
    suspend fun tetheredIfacesViaUserService(): String? = withContext(Dispatchers.IO) {
        if (!isBinderAvailable() || !hasPermission()) return@withContext null
        val service = tetheringUserService() ?: return@withContext null
        try {
            withTimeoutOrNull(TETHERED_IFACES_TIMEOUT_MS) { service.getTetheredIfaces() }
        } catch (t: Throwable) {
            // Dead binder mid-read: drop the cache so the next call rebinds.
            android.util.Log.w("ShizukuManager", "getTetheredIfaces failed", t)
            tetheringServiceBinder = null
            null
        }
    }

    private fun runShizukuCommands(commands: List<String>): Result<String> {
        if (!hasPermission()) {
            return Result.failure(IllegalStateException("Shizuku permission not granted or service binder offline."))
        }
        return runCatching {
            val fullScript = commands.joinToString("; ")
            val result = shizukuExec(fullScript)

            if (result.exitCode == 0 || result.output.isNotBlank() || result.error.isBlank()) {
                "Permissions auto-granted successfully via Shizuku."
            } else {
                throw RuntimeException(
                    "Shizuku command error (exit ${result.exitCode}): ${result.error} ${result.output}"
                )
            }
        }
    }

    /**
     * Strict single-command runner for TOGGLES: success requires the remote
     * command to exit 0. Unlike the permission-grant batch above — whose
     * chatty-but-benign output must be tolerated — a `cmd` call that fails
     * prints its error on stdout/stderr and exits non-zero, and treating
     * that chatter as success would make a failed toggle believe itself.
     */
    private fun runShizukuCommandStrict(command: String): Result<Unit> {
        if (!hasPermission()) {
            return Result.failure(IllegalStateException("Shizuku permission not granted or service binder offline."))
        }
        return runCatching {
            val result = shizukuExec(command)
            if (result.exitCode != 0) {
                val detail = listOf(result.error.trim(), result.output.trim())
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                throw RuntimeException(
                    if (detail.isBlank()) "exit ${result.exitCode}" else "exit ${result.exitCode}: $detail"
                )
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
     * uid holds BLUETOOTH_PRIVILEGED. Three mechanisms are tried in order —
     * the first success wins, and the failure reason of the LAST attempt is
     * carried back so the menu can surface the exact failing stage:
     *   1. [TetheringShizukuService.setBluetoothEnabled] — BluetoothAdapter
     *      called directly in the shell-uid user service over a stable binder
     *      method (the same mechanism `svc bluetooth` uses internally, but
     *      without spawning a shell; immune to shell-command changes).
     *   2. `cmd bluetooth_manager enable|disable` (Android 11+ shell command).
     *   3. `svc bluetooth enable|disable` (older entry point, still present
     *      on current ROMs).
     *
     * No dialogs, no settings pages, no shade pull-down — the overlay island
     * stays untouched.
     *
     * @return success when a mechanism dispatched the request and reported
     * success. Callers MUST still verify the actual state change via
     * Settings.Global "bluetooth_on".
     */
    suspend fun toggleBluetooth(enable: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!isBinderAvailable() || !hasPermission()) {
            return@withContext Result.failure(
                IllegalStateException("Shizuku unavailable")
            )
        }
        val action = if (enable) "enable" else "disable"

        // Plan A — BluetoothAdapter in the shell-uid user service.
        val adapterResult = runCatching {
            tetheringUserService()?.setBluetoothEnabled(enable)
        }.onFailure { error ->
            android.util.Log.w("ShizukuManager", "Bluetooth user service call failed", error)
            // Dead binder: drop the cache so the next toggle rebinds fresh.
            tetheringServiceBinder = null
        }.getOrNull()
        if (adapterResult == true) return@withContext Result.success(true)

        // Plan B — hidden BluetoothManagerService shell command (Android 11+).
        val viaCmd = runShizukuCommandStrict("cmd bluetooth_manager $action")
        if (viaCmd.isSuccess) return@withContext Result.success(true)

        // Plan C — legacy svc entry point.
        val viaSvc = runShizukuCommandStrict("svc bluetooth $action")
        if (viaSvc.isSuccess) return@withContext Result.success(true)

        Result.failure(
            IllegalStateException(
                viaSvc.exceptionOrNull()?.message
                    ?: viaCmd.exceptionOrNull()?.message
                    ?: if (adapterResult == false) "adapter refused" else "user service unreachable"
            )
        )
    }

    /**
     * Hides or restores the status bar's clock, system icons and notification
     * icons, so the island can take the status bar's place.
     *
     * Mechanism: the platform's StatusBarShellCommand
     * (`cmd statusbar send-disable-flag <flags>`) — a shell-only entry point
     * (the disable flags it writes are guarded by system permissions normal
     * apps do not hold), so it is dispatched through Shizuku. `~`-prefixed
     * flags REMOVE the matching bit, which is how the icons are restored
     * without touching any other disable state the system may hold.
     *
     * The platform clears every caller's disable flags on reboot / SystemUI
     * restart; the saved preference is re-applied on boot by AutostartReceiver
     * once Shizuku is reachable.
     *
     * @param hide true hides the three groups, false restores them.
     */
    suspend fun sendStatusBarDisableFlags(hide: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isBinderAvailable() || !hasPermission()) {
            return@withContext Result.failure(
                IllegalStateException("Shizuku unavailable")
            )
        }
        val negate = if (hide) "" else "~"
        val args = listOf("system-icons", "clock", "notification-icons")
            .joinToString(" ") { "$negate$it" }
        runShizukuCommandStrict("cmd statusbar send-disable-flag $args")
    }

    /**
     * Toggles a tethering type with shell-level privileges.
     *
     * WHY NOT `cmd connectivity tethering` (the old mechanism): that shell
     * command does not exist. AOSP's ConnectivityService shell interface is
     * airplane-mode / firewall chains / package networking only — the command
     * printed its help text and exited, so the hotspot never changed state on
     * the device.
     *
     * Plan A — TetheringManager inside a Shizuku USER SERVICE (see
     * [TetheringShizukuService]): the service runs in the Shizuku server
     * process (uid 2000, shell), and the shell uid holds TETHER_PRIVILEGED
     * (the platform Shell app requests it and the permission is
     * privapp-whitelisted for com.android.shell). TetheringManager
     * .startTethering is the exact path the Settings app uses, so the Wi-Fi
     * hotspot starts with the user's SAVED SSID/password config. The returned
     * result code is authoritative: startTethering only answers
     * TETHER_ERROR_NO_ERROR through StartTetheringCallback once the soft-AP
     * actually reached WIFI_AP_STATE_ENABLED.
     *
     * Plan B — exec fallback when the user service is unreachable:
     *   wifi: `cmd wifi stop-softap` for the OFF direction only — the ON
     *         direction needs start-softap with an EXPLICIT ssid/passphrase,
     *         which would hijack the user's saved hotspot config, so it is
     *         deliberately not used.
     *
     * Only "wifi" exists: the USB and Bluetooth tethering rows were removed
     * from the info menu entirely.
     *
     * No dialogs, no settings pages, no shade pull-down — the overlay island
     * stays untouched. The result code IS the verification (both directions
     * confirm platform-side before answering), so callers can surface it
     * directly as in-menu feedback.
     *
     * @param kind "wifi" (Wi-Fi hotspot).
     * @return success when a mechanism dispatched the request AND the
     *         mechanism itself reported success. Failure carries the reason.
     */
    suspend fun toggleTethering(kind: String, enable: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            if (!isBinderAvailable() || !hasPermission()) {
                return@withContext Result.failure(
                    IllegalStateException("Shizuku unavailable")
                )
            }
            require(kind in TETHERING_KINDS) { "Unknown tethering kind: $kind" }
            val type = TETHERING_TYPES.getValue(kind)

            // Plan A: TetheringManager in the shell-uid user service.
            val userServiceCode = runCatching {
                tetheringServiceSetTethering(type, enable)
            }.getOrElse { error ->
                android.util.Log.w("ShizukuManager", "Tethering user service call failed", error)
                null
            }
            if (userServiceCode != null &&
                userServiceCode != TetheringShizukuService.ERR_UNAVAILABLE
            ) {
                // Authoritative answer from the platform path.
                return@withContext if (userServiceCode == TetheringShizukuService.TETHER_ERROR_NO_ERROR) {
                    Result.success(true)
                } else {
                    Result.failure(
                        IllegalStateException(tetheringErrorText(userServiceCode, kind))
                    )
                }
            }
            // userServiceCode == null (service unreachable) or ERR_UNAVAILABLE
            // (service-side infra failure): try the shell fallbacks below.

            // Plan B: exec fallback when the user service is unreachable.
            val commands = when (kind) {
                "wifi" -> if (enable) {
                    emptyList()
                } else {
                    listOf("cmd wifi stop-softap")
                }
                else -> emptyList()
            }
            if (commands.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("no mechanism for $kind (user service unreachable)")
                )
            }
            runShizukuCommands(commands).map { true }
        }

    /**
     * Dispatches setTethering through the bound user service.
     *
     * @return the platform/service result code, or null when the service
     *         could not be reached at all (caller falls back to exec).
     */
    private suspend fun tetheringServiceSetTethering(type: Int, enable: Boolean): Int? {
        val service = tetheringUserService() ?: return null
        return try {
            service.setTethering(type, enable)
        } catch (t: Throwable) {
            // DeadObject: the user service process was killed between calls.
            // Drop the cached binder so the next toggle rebinds fresh.
            android.util.Log.w("ShizukuManager", "Tethering user service binder dead", t)
            tetheringServiceBinder = null
            null
        }
    }

    /** Bound user service or null (never blocks long — binder is cached). */
    private suspend fun tetheringUserService(): ITetheringUserService? {
        val binder = tetheringServiceBinder() ?: return null
        return try {
            ITetheringUserService.Stub.asInterface(binder)
        } catch (t: Throwable) {
            tetheringServiceBinder = null
            null
        }
    }

    /**
     * Binds Smart Island's Shizuku user service once and caches its binder.
     * The service class runs inside the Shizuku server process; the version
     * in [UserServiceArgs] makes Shizuku restart it whenever the app updates
     * the class.
     *
     * Concurrent callers (the info menu's 1s state poll racing the menu-open
     * warmup, or a toggle arriving mid-bind) are serialized through
     * [bindMutex] so they share one bind instead of registering competing
     * service connections.
     */
    private suspend fun tetheringServiceBinder(): IBinder? {
        tetheringServiceBinder?.let { return it }
        return bindMutex.withLock {
            tetheringServiceBinder?.let { return it }
            bindTetheringServiceBinder(USER_SERVICE_BIND_TIMEOUT_MS)
                // A full-window timeout usually means the server was still
                // spinning the service process up; it is alive moments later
                // and the immediate retry answers instantly. The retry is
                // deliberately shorter so a toggle never stacks two long
                // waits back to back.
                ?: bindTetheringServiceBinder(USER_SERVICE_BIND_RETRY_MS)
        }
    }

    private suspend fun bindTetheringServiceBinder(timeoutMs: Long): IBinder? {
        val bound = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (service != null && continuation.isActive) {
                            tetheringServiceBinder = service
                            continuation.resume(service)
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        // The user service process died; force a rebind next time.
                        tetheringServiceBinder = null
                    }
                }
                tetheringServiceConnection = connection
                val args = Shizuku.UserServiceArgs(
                    ComponentName(BuildConfig.APPLICATION_ID, TetheringShizukuService::class.java.getName())
                )
                    .processNameSuffix("tethering")
                    // Stable tag: the service identity must survive R8 renaming
                    // in release builds (the server keys the service by tag).
                    .tag("tethering")
                    .version(USER_SERVICE_VERSION)
                try {
                    Shizuku.bindUserService(args, connection)
                } catch (t: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(t)
                    }
                }
            }
        }
        return bound
    }

    /** Serializes user-service binds (poll vs warmup vs toggle). */
    private val bindMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Pre-binds the tethering user service so the first hotspot tap dispatches
     * immediately instead of paying the cold-start bind latency. Fire-and-
     * forget: callers launch this on a background dispatcher when the info
     * menu opens; failures are harmless (the toggle rebinds on demand).
     */
    suspend fun warmUpTetheringUserService() {
        withContext(Dispatchers.IO) {
            runCatching { tetheringServiceBinder() }
                .onFailure {
                    android.util.Log.w("ShizukuManager", "Tethering service warmup failed", it)
                }
        }
    }

    /** Valid [toggleTethering] kinds. Only the Wi-Fi hotspot remains. */
    val TETHERING_KINDS = setOf("wifi")

    /**
     * Short, menu-ready text for a failed tethering dispatch. The overlay
     * shows this verbatim behind "Couldn't toggle X", so the exact failure
     * stage is visible on-device without logcat:
     *  - our negative local codes (service-side infra failures), and
     *  - the platform's positive TETHER_ERROR_* codes (platform refusals).
     */
    fun tetheringErrorText(code: Int, kind: String): String = when (code) {
        TetheringShizukuService.ERR_UNAVAILABLE -> "Shizuku service error"
        TetheringShizukuService.ERR_TIMEOUT -> "no answer (timeout)"
        TetheringShizukuService.ERR_STATE_UNCHANGED -> "state did not change"
        // TetheringManager.TETHER_ERROR_* (positive, stable since API 30).
        3 -> "unsupported by system"
        5 -> "system internal error"
        6 -> "missing TETHER_PRIVILEGED"
        9 -> "provisioning refused"
        else -> "system refused (code $code)"
    }.let { text -> if (kind.isBlank()) text else "$text [$kind]" }

    /** kinds → TetheringManager.TETHERING_* types (stable since API 30). */
    private val TETHERING_TYPES = mapOf(
        "wifi" to TetheringShizukuService.TETHERING_WIFI
    )

    /** Cached user-service binder; null until first bind / after death. */
    @Volatile
    private var tetheringServiceBinder: IBinder? = null

    /** Connection kept alive for the cached binder (passive afterwards). */
    private var tetheringServiceConnection: ServiceConnection? = null

    /** Bump whenever [TetheringShizukuService] or its AIDL changes shape. */
    private const val USER_SERVICE_VERSION = 4
    private const val USER_SERVICE_BIND_TIMEOUT_MS = 9000L
    private const val USER_SERVICE_BIND_RETRY_MS = 4000L
    private const val TETHERED_IFACES_TIMEOUT_MS = 2000L
}
