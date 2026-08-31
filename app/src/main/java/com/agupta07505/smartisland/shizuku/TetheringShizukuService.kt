/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Smart Island's Shizuku user service. This class runs inside the Shizuku
 * server process (uid 2000, "shell") — NOT in the app process — which is the
 * only way a non-root device can change tethering state:
 *
 *  1. `cmd connectivity tethering <kind> <enable|disable>` does NOT exist in
 *     AOSP's ConnectivityService (its shell command set is airplane-mode,
 *     firewall chains, package networking — nothing tethering-related). The
 *     old command just printed its help text and exited non-zero.
 *  2. TetheringService.startTethering/stopTethering enforce
 *     TETHER_PRIVILEGED (signature|privileged). The platform Shell app
 *     requests exactly that permission in its manifest (and the permission is
 *     privapp-whitelisted for com.android.shell), so the shell uid holds it —
 *     the same trick that makes `cmd bluetooth_manager enable` work for the
 *     Bluetooth toggle.
 *
 * Only the Wi-Fi hotspot is dispatched here: the USB and Bluetooth tethering
 * rows were removed from the info menu entirely (the USB path in particular
 * could crash the system UI by switching USB gadget functions mid-session).
 *
 * TetheringManager itself is a @SystemApi class (not on the app compile
 * classpath), so every call here goes through reflection. Reflection is safe
 * in this process: the Shizuku server is started from `app_process` via
 * adb/root and is not subject to the hidden-API enforcement that applies to
 * Zygote-forked app processes.
 */
class TetheringShizukuService(private val context: Context) : ITetheringUserService.Stub() {

    /**
     * Context whose opPackageName is "com.android.shell". TetheringService
     * checks that the callerPkg TetheringManager sends belongs to the calling
     * binder uid (checkPackageNameMatchesUid); the binder uid here is 2000,
     * whose only package is com.android.shell. Whatever context Shizuku hands
     * us names the APP's package, so we must re-create the context for the
     * shell package or every tethering call is rejected with
     * TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION.
     */
    private val shellContext: Context? by lazy {
        runCatching {
            val base = context.applicationContext ?: context
            base.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.onFailure {
            Log.e(TAG, "createPackageContext($SHELL_PACKAGE) failed", it)
        }.getOrNull()
    }

    override fun setTethering(type: Int, enable: Boolean): Int {
        if (type != TETHERING_WIFI) return ERR_UNAVAILABLE
        val manager = tetheringManager() ?: return ERR_UNAVAILABLE
        return runCatching {
            // Idempotency: the platform's answer for a no-op start/stop can be
            // a refusal (already-tethered / nothing-tethered states are not
            // always NO_ERROR through every ROM's startTethering path). The
            // authoritative iface list decides first.
            val currentlyUp = softApTetheredNow(manager)
            if (enable == currentlyUp) return TETHER_ERROR_NO_ERROR
            if (enable) startTethering(manager, type) else stopTethering(manager, type)
        }.getOrElse {
            Log.e(TAG, "setTethering($type, $enable) failed", it)
            ERR_UNAVAILABLE
        }
    }

    /**
     * The platform's own tethered-interface list, read in the shell-uid
     * process where hidden-API enforcement does not apply (the app process
     * gets reflection-blocked on the same read — see HotspotUtil). The app
     * uses this to VERIFY that a toggle actually changed the tethering state
     * and to display the live hotspot state in the info menu.
     *
     * Returns null (never an empty string) when there is NO authoritative
     * answer — the TetheringManager is unreachable — so the app knows to
     * fall back to its own readers instead of reading "" as "definitively
     * nothing is tethering".
     */
    override fun getTetheredIfaces(): String? {
        val manager = tetheringManager() ?: return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val ifaces = manager.javaClass
                .getMethod("getTetheredIfaces")
                .invoke(manager) as? Array<String>
            ifaces?.joinToString("|") { it.lowercase() } ?: ""
        }.getOrElse {
            Log.e(TAG, "getTetheredIfaces failed", it)
            null
        }
    }

    /**
     * Toggles the Bluetooth radio in the shell-uid process. The adapter calls
     * are public API (deprecated since API 33, but still dispatched and the
     * only direct mechanism for a privileged caller); hidden-API enforcement
     * does not apply in this process anyway.
     *
     * enable()/disable() return false both when the request is REFUSED and
     * when the radio is ALREADY in the requested state, so a false answer is
     * disambiguated through the permission-free Settings.Global "bluetooth_on"
     * read (the same switch the app polls): already-in-target-state counts as
     * success so the caller's own verification never races a no-op.
     */
    // Lint wants a runtime BLUETOOTH_PRIVILEGED/CONNECT check, but this code
    // never runs in the app: the user service executes inside Shizuku's
    // shell-uid process, which the platform grants the privileged Bluetooth
    // permissions. A SecurityException would additionally be caught by the
    // runCatching below and reported as a plain false to the caller.
    @SuppressLint("MissingPermission")
    override fun setBluetoothEnabled(enable: Boolean): Boolean {
        return runCatching {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                ?: return false
            val accepted = if (enable) adapter.enable() else adapter.disable()
            accepted || isBluetoothOn() == enable
        }.getOrElse {
            Log.e(TAG, "setBluetoothEnabled($enable) failed", it)
            false
        }
    }

    /** Permission-free Bluetooth state read (Settings.Global "bluetooth_on"). */
    private fun isBluetoothOn(): Boolean = runCatching {
        android.provider.Settings.Global.getInt(
            context.contentResolver,
            "bluetooth_on",
            0
        ) != 0
    }.getOrDefault(false)

    override fun exit() {
        System.exit(0)
    }

    // ------------------------------------------------------------------ //
    // TetheringManager plumbing (all reflective)                          //
    // ------------------------------------------------------------------ //

    private fun tetheringManager(): Any? {
        val ctx = shellContext ?: return null
        return runCatching {
            // Context.TETHERING_SERVICE — the literal avoids a compile-time
            // reference to an API-30 constant from this process-agnostic class.
            ctx.getSystemService("tethering")
        }.onFailure {
            Log.e(TAG, "getSystemService(tethering) failed", it)
        }.getOrNull().also {
            if (it == null) Log.e(TAG, "TetheringManager unavailable")
        }
    }

    /**
     * TETHERING_WIFI start. The platform answers through the callback, so each
     * attempt blocks on a latch and translates onTetheringFailed(resultCode)
     * into the return value.
     *
     * Provisioning: several carriers (and several ROM builds) require a
     * tethering entitlement check before the soft-AP may start. startTethering
     * called WITHOUT the provisioning UI then refuses the request with
     * TETHER_ERROR_PROVISIONING_FAILED (14) — the "system refused (code 14)
     * [wifi]" the info menu reported — even though the same toggle from the
     * system Settings works, because Settings runs the check WITH its UI.
     *
     * The dispatch therefore mirrors Settings, then goes further:
     *  1. the 4-arg startTethering(type, showProvisioningUi=false, …),
     *  2. on failure the same call with showProvisioningUi=true — the platform
     *     runs the carrier approval flow (it may briefly show a system
     *     dialog/app; that is exactly what the stock toggle does). The latch
     *     is generous: reading the dialog, tapping through, the carrier app
     *     cold start — all inside this window still answer through the
     *     callback.
     *  3. if the callback refused/timed out, the platform's tethered-iface
     *     list is polled — an approval that lands LATE is still a success.
     *  4. an EXPLICIT provisioning refusal (the flow itself ended with an
     *     error, e.g. the ROM's entitlement app fails headlessly) gets one
     *     last resort: startSoftAp with the user's SAVED hotspot config read
     *     through the shell-uid WifiManager (NETWORK_SETTINGS parity with the
     *     `cmd wifi` commands) — the SSID/password are preserved exactly and
     *     the tethering-layer entitlement check is bypassed. Verified by
     *     polling WIFI_AP_STATE_ENABLED.
     *  5. a latch TIMEOUT (flow may still be running) never triggers the
     *     direct start — it reports ERR_PROVISIONING_PENDING so the app-side
     *     late verifier can catch the approval.
     *  6. ROMs that only expose the legacy 3-arg overload fall back to it.
     */
    private fun startTethering(manager: Any, type: Int): Int {
        val callbackClass = Class.forName("$MANAGER_CLASS\$StartTetheringCallback")
        val managerClass = manager.javaClass
        val fourArg = runCatching {
            managerClass.getMethod(
                "startTethering",
                Integer.TYPE,
                java.lang.Boolean.TYPE,
                Executor::class.java,
                callbackClass
            )
        }.getOrNull()
        val threeArg = if (fourArg == null) {
            runCatching {
                managerClass.getMethod("startTethering", Integer.TYPE, Executor::class.java, callbackClass)
            }.getOrNull()
        } else {
            null
        }
        if (fourArg == null && threeArg == null) {
            Log.e(TAG, "No startTethering overload found on $managerClass")
            return ERR_UNAVAILABLE
        }

        var firstFailure: Int
        if (fourArg != null) {
            val noUi = dispatchStartTethering(manager, fourArg, type, showProvisioningUi = false, timeoutMs = START_TIMEOUT_MS)
            if (noUi == TETHER_ERROR_NO_ERROR) return noUi
            firstFailure = noUi
            Log.d(TAG, "startTethering(no-UI) -> $noUi, retrying with provisioning UI")
            val withUi = dispatchStartTethering(manager, fourArg, type, showProvisioningUi = true, timeoutMs = PROVISIONING_TIMEOUT_MS)
            if (withUi == TETHER_ERROR_NO_ERROR) return withUi
            // The approval screen may still be up when the latch expires; the
            // callback only fires after the whole flow finishes. Poll the
            // platform's authoritative iface list before reporting failure.
            if (softApAppeared(manager, PROVISIONING_VERIFY_MS)) return TETHER_ERROR_NO_ERROR
            if (withUi == ERR_TIMEOUT) {
                // No callback at all: the provisioning flow may still be
                // running. DO NOT direct-start on top of it — report pending
                // and let the app-side late verifier watch for the approval.
                return ERR_PROVISIONING_PENDING
            }
            // EXPLICIT refusal: the entitlement flow ended and refused. Last
            // resort: direct-start the soft AP with the SAVED config.
            if (directStartSoftApWithSavedConfig()) return TETHER_ERROR_NO_ERROR
        } else {
            val code = dispatchStartTethering(manager, threeArg!!, type, showProvisioningUi = null, timeoutMs = START_TIMEOUT_MS)
            if (code == TETHER_ERROR_NO_ERROR) return code
            firstFailure = code
        }
        return firstFailure
    }

    /** One startTethering attempt: reflective dispatch + bounded latch. */
    private fun dispatchStartTethering(
        manager: Any,
        method: Method,
        type: Int,
        showProvisioningUi: Boolean?,
        timeoutMs: Long
    ): Int {
        val latch = CountDownLatch(1)
        var result = ERR_TIMEOUT
        val callbackClass = method.parameterTypes.last()
        val callback = java.lang.reflect.Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass)
        ) { _, invoked, args ->
            when (invoked.name) {
                "onTetheringStarted" -> {
                    result = TETHER_ERROR_NO_ERROR
                    latch.countDown()
                }
                "onTetheringFailed" -> {
                    result = (args?.getOrNull(0) as? Int) ?: ERR_UNAVAILABLE
                    latch.countDown()
                }
            }
            null
        }
        runCatching {
            if (showProvisioningUi == null) {
                method.invoke(manager, type, DIRECT_EXECUTOR, callback)
            } else {
                method.invoke(manager, type, showProvisioningUi, DIRECT_EXECUTOR, callback)
            }
        }.onFailure {
            Log.e(TAG, "startTethering dispatch failed", it)
            return ERR_UNAVAILABLE
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        Log.d(TAG, "startTethering(type=$type, ui=$showProvisioningUi) -> $result")
        return result
    }

    /** True when a soft-AP interface shows up in the platform's tethered list. */
    private fun softApAppeared(manager: Any, verifyMs: Long = PROVISIONING_VERIFY_MS): Boolean {
        val kindPrefixes = arrayOf("ap", "swlan", "wlan")
        val deadline = System.currentTimeMillis() + verifyMs
        while (System.currentTimeMillis() < deadline) {
            if (tetheredIfacesOfType(manager, kindPrefixes)) return true
            runCatching { Thread.sleep(400L) }
        }
        return false
    }

    /** Authoritative "is a soft-AP iface tethered RIGHT NOW" read (single shot). */
    private fun softApTetheredNow(manager: Any): Boolean =
        tetheredIfacesOfType(manager, arrayOf("ap", "swlan", "wlan"))

    /** WifiManager.WIFI_AP_STATE_ENABLED (stable since API 11). */
    private val WIFI_AP_STATE_ENABLED = 13

    /** WifiManager.WIFI_AP_STATE_FAILED (stable since API 11). */
    private val WIFI_AP_STATE_FAILED = 14

    /**
     * Last-resort ON path when the tethering layer's entitlement flow refuses
     * outright: read the user's SAVED hotspot configuration through the
     * shell-uid WifiManager (shell holds NETWORK_SETTINGS — the same grant
     * that makes every `cmd wifi` subcommand work from adb) and start the
     * soft AP DIRECTLY with it. The saved SSID/password/security are used
     * verbatim — nothing is hijacked — and the tethering-module entitlement
     * check is simply not involved. Verified by polling the AP state.
     */
    private fun directStartSoftApWithSavedConfig(): Boolean {
        val ctx = shellContext ?: return false
        return runCatching {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: return false
            val config = runCatching {
                wm.javaClass.getMethod("getSoftApConfiguration").invoke(wm)
            }.getOrElse {
                Log.e(TAG, "getSoftApConfiguration failed", it)
                return false
            } ?: run {
                Log.w(TAG, "getSoftApConfiguration returned null; no saved hotspot config")
                return false
            }
            runCatching {
                wm.javaClass.getMethod("startSoftAp", config.javaClass).invoke(wm, config)
            }.getOrElse {
                Log.e(TAG, "startSoftAp(saved config) failed", it)
                return false
            }
            Log.d(TAG, "startSoftAp(saved config) dispatched; polling AP state")
            val deadline = System.currentTimeMillis() + SOFT_AP_DIRECT_VERIFY_MS
            while (System.currentTimeMillis() < deadline) {
                val state = runCatching {
                    wm.javaClass.getMethod("getWifiApState").invoke(wm) as? Int
                }.getOrNull()
                if (state == WIFI_AP_STATE_ENABLED) return true
                if (state == WIFI_AP_STATE_FAILED) return false
                runCatching { Thread.sleep(500L) }
            }
            false
        }.getOrElse {
            Log.e(TAG, "directStartSoftApWithSavedConfig failed", it)
            false
        }
    }

    /**
     * TETHERING_WIFI stop: stopTethering(int) is fire-and-forget on the
     * platform side (its result listener never reports to callers), so verify
     * by polling getTetheredIfaces() until the soft-AP interface disappears
     * from the platform's tethered list.
     */
    private fun stopTethering(manager: Any, type: Int): Int {
        val managerClass = manager.javaClass
        managerClass.getMethod("stopTethering", Integer.TYPE)
            .invoke(manager, type)

        val kindPrefixes = arrayOf("ap", "swlan", "wlan")
        val deadline = System.currentTimeMillis() + STOP_VERIFY_TIMEOUT_MS
        var stillUp = tetheredIfacesOfType(manager, kindPrefixes)
        while (stillUp && System.currentTimeMillis() < deadline) {
            Thread.sleep(200L)
            stillUp = tetheredIfacesOfType(manager, kindPrefixes)
        }
        Log.d(TAG, "stopTethering($type) stillUp=$stillUp")
        return if (stillUp) ERR_STATE_UNCHANGED else TETHER_ERROR_NO_ERROR
    }

    @Suppress("UNCHECKED_CAST")
    private fun tetheredIfacesOfType(manager: Any, prefixes: Array<String>): Boolean {
        return runCatching {
            val ifaces = manager.javaClass
                .getMethod("getTetheredIfaces")
                .invoke(manager) as? Array<String> ?: return false
            ifaces.any { iface ->
                val name = iface.lowercase()
                prefixes.any { name.startsWith(it) }
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "TetheringShizukuSvc"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val MANAGER_CLASS = "android.net.TetheringManager"

        // TetheringManager.TETHERING_WIFI constant (stable since API 30).
        const val TETHERING_WIFI = 0

        // Platform result code for "everything fine" (TETHER_ERROR_NO_ERROR).
        const val TETHER_ERROR_NO_ERROR = 0

        // Local failure codes (negative — never produced by the platform).
        const val ERR_UNAVAILABLE = -1
        const val ERR_TIMEOUT = -2
        const val ERR_STATE_UNCHANGED = -3

        // The provisioning flow never answered within its latch; it may still
        // be running (dialog up, approval pending). The caller should poll the
        // real state instead of treating this as a hard refusal.
        const val ERR_PROVISIONING_PENDING = -4

        // TetheringManager answers on a binder callback; the platform usually
        // answers within a second, but carrier provisioning checks can take
        // several. 8s covers the observed worst cases without hanging the
        // toggle tap forever.
        private const val START_TIMEOUT_MS = 8000L

        // The provisioning-UI attempt: the carrier approval flow (dialog read,
        // taps, carrier app spin-up) runs before the callback fires, so the
        // latch is generous; anything later is caught by the iface poll.
        private const val PROVISIONING_TIMEOUT_MS = 20_000L
        private const val PROVISIONING_VERIFY_MS = 15_000L
        private const val SOFT_AP_DIRECT_VERIFY_MS = 12_000L
        private const val STOP_VERIFY_TIMEOUT_MS = 8000L

        private val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
