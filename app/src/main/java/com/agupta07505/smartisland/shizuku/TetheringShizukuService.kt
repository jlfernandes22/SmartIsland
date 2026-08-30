/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.shizuku

import android.content.Context
import android.util.Log
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
     * uses this to VERIFY that a toggle actually changed the tethering state;
     * a null (empty) answer tells it to fall back to its own readers.
     */
    override fun getTetheredIfaces(): String {
        val manager = tetheringManager() ?: return ""
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val ifaces = manager.javaClass
                .getMethod("getTetheredIfaces")
                .invoke(manager) as? Array<String>
            ifaces?.joinToString("|") { it.lowercase() } ?: ""
        }.getOrElse {
            Log.e(TAG, "getTetheredIfaces failed", it)
            ""
        }
    }

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
     * TETHERING_WIFI start: startTethering(int, Executor,
     * StartTetheringCallback) answers through the callback, so block on a
     * latch and translate onTetheringFailed(resultCode) into the return value.
     */
    private fun startTethering(manager: Any, type: Int): Int {
        val managerClass = manager.javaClass
        val callbackClass = Class.forName("$MANAGER_CLASS\$StartTetheringCallback")
        val start = managerClass.getMethod(
            "startTethering",
            Integer.TYPE,
            Executor::class.java,
            callbackClass
        )
        val latch = CountDownLatch(1)
        var result = ERR_TIMEOUT
        val callback = java.lang.reflect.Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass)
        ) { _, method, args ->
            when (method.name) {
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
        start.invoke(manager, type, DIRECT_EXECUTOR, callback)
        latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        Log.d(TAG, "startTethering($type) -> $result")
        return result
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

        // startTethering answers on a binder callback; the platform usually
        // answers within a second, but carrier provisioning checks can take
        // several. 8s covers the observed worst cases without hanging the
        // toggle tap forever.
        private const val START_TIMEOUT_MS = 8000L
        private const val STOP_VERIFY_TIMEOUT_MS = 4000L

        private val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
