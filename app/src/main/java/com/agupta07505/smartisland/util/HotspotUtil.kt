/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.widget.Toast

object HotspotUtil {

    // Resolved once: getMethod() is surprisingly expensive and this check runs
    // every second from the idle info menu's state poll.
    @Volatile
    private var wifiApStateMethod: java.lang.reflect.Method? = null

    // TetheringManager.getTetheredIfaces() reflection, resolved once (and
    // negative-cached — a blocked method must not be re-probed every second).
    @Volatile
    private var tetheredIfacesMethod: java.lang.reflect.Method? = null
    @Volatile
    private var tetheredIfacesBlocked = false

    /**
     * The platform's ACTUAL tethered-interface list via the hidden
     * TetheringManager system service (API 30+). Every interface in this list
     * is by definition actively tethering right now, so this reflects reality
     * for Wi-Fi hotspot, USB tethering and Bluetooth tethering alike — no
     * interface-name guessing needed. Returns null when the service or the
     * method is unavailable/blocked on this device.
     */
    private fun tetheredIfaces(context: Context): Set<String>? {
        if (tetheredIfacesBlocked) return null
        return runCatching {
            val manager = context.applicationContext.getSystemService("tethering")
                ?: return null
            val method = tetheredIfacesMethod ?: manager.javaClass
                .getMethod("getTetheredIfaces")
                .also { tetheredIfacesMethod = it }
            @Suppress("UNCHECKED_CAST")
            val result = method.invoke(manager) as? Array<String>
                ?: return null
            result.map { it.lowercase() }.toSet()
        }.getOrElse {
            // NoSuchMethodException / reflection blocks: remember, don't retry.
            tetheredIfacesBlocked = true
            null
        }
    }

    /** True when [ifaceName] belongs to the given tethering kind. */
    private fun ifaceMatchesKind(ifaceName: String, kind: String): Boolean = when (kind) {
        // SoftAP interfaces: ap0 (modern AOSP), wlan0/wlan1 (concurrent STA+AP
        // or older devices), swlan0 (some OEMs).
        "wifi" -> ifaceName.startsWith("ap") ||
            ifaceName.startsWith("swlan") ||
            ifaceName.startsWith("wlan")
        // USB gadget drivers: rndis0 (RNDIS), usb0 (f_ncm on many kernels),
        // ncm0 (NCM — the default on recent Pixels), eth* (NCM/ECM surfaced
        // as ethernet; in the TETHERED list it can only mean USB/eth tethering).
        "usb" -> ifaceName.startsWith("rndis") ||
            ifaceName.startsWith("usb") ||
            ifaceName.startsWith("ncm") ||
            ifaceName.startsWith("eth")
        // Bluetooth PAN bridge interface.
        "bluetooth" -> ifaceName.startsWith("bt-pan") || ifaceName.startsWith("btpan")
        else -> false
    }

    /**
     * TRUE tethering state for the idle info menu and the Shizuku toggle
     * verification, in decreasing reliability order:
     *  1. TetheringManager.getTetheredIfaces() — the platform's own list.
     *  2. Kind-specific fallbacks (legacy WifiManager reflection, the USB_STATE
     *     sticky broadcast, the rndis/usb/ncm interface probe).
     *  3. null — nothing on this device reflects the state; the UI shows
     *     "Tap to toggle" instead of a wrong On/Off.
     */
    fun isWifiTetheringActive(context: Context): Boolean? {
        tetheredIfaces(context)?.let { ifaces ->
            return ifaces.any { ifaceMatchesKind(it, "wifi") }
        }
        return isHotspotActive(context)
    }

    fun isUsbTetheringActive(context: Context): Boolean? {
        tetheredIfaces(context)?.let { ifaces ->
            return ifaces.any { ifaceMatchesKind(it, "usb") }
        }
        // USB_STATE sticky broadcast: the active USB gadget functions appear as
        // boolean extras ("rndis", "ncm"). Permission-free and set by the
        // platform whenever USB tethering's function is configured.
        runCatching {
            val filter = IntentFilter("android.hardware.usb.action.USB_STATE")
            val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(null, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.applicationContext.registerReceiver(null, filter)
            }
            if (sticky != null &&
                (sticky.getBooleanExtra("rndis", false) || sticky.getBooleanExtra("ncm", false))
            ) {
                return true
            }
        }
        // Interface probe: enabling USB tethering brings up the gadget
        // interface. Name prefixes only — rndis/usb/ncm interfaces cannot
        // exist unless the USB gadget created them.
        return runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                ?: return@runCatching null
            val up = interfaces.toList().any {
                it.isUp && ifaceMatchesKind(it.name.lowercase().substringBefore('#'), "usb") &&
                    !it.name.lowercase().startsWith("eth")
            }
            up
        }.getOrNull()
    }

    fun isBluetoothTetheringActive(context: Context): Boolean? {
        tetheredIfaces(context)?.let { ifaces ->
            return ifaces.any { ifaceMatchesKind(it, "bluetooth") }
        }
        // Bluetooth PAN state is a hidden, permission-guarded API with no
        // permission-free read — report "unknown" rather than guessing.
        return null
    }

    /**
     * Best-effort live tethering state detection via the legacy WifiManager API.
     * Returns true/false when readable, null when the platform blocks reflection
     * (Android 13+ hidden API restrictions).
     */
    fun isHotspotActive(context: Context): Boolean? {
        return runCatching {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: return null
            val method = wifiApStateMethod ?: wifiManager.javaClass
                .getMethod("getWifiApState")
                .also { wifiApStateMethod = it }
            val state = method.invoke(wifiManager) as? Int ?: return null
            // WIFI_AP_STATE_ENABLED = 13, WIFI_AP_STATE_ENABLING = 12
            state == 13 || state == 12
        }.getOrNull()
    }

    fun parseDeviceCount(title: String?, text: String?): Int {
        val fullText = "${title.orEmpty()} ${text.orEmpty()}"
        val lower = fullText.lowercase()

        if (lower.contains("no device") || lower.contains("0 device") || lower.contains("no connected") || lower.contains("0 connected")) {
            return 0
        }

        // Pattern 1: "1 device", "2 devices", "1 connected", "2 clients"
        val pattern1 = Regex("""\b(\d+)\s*(?:device|connected|client)s?\b""", RegexOption.IGNORE_CASE)
        pattern1.find(fullText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        // Pattern 2: "devices: 1", "connected: 2", "clients: 0"
        val pattern2 = Regex("""\b(?:devices?|connected|clients?)\s*[:=]?\s*(\d+)\b""", RegexOption.IGNORE_CASE)
        pattern2.find(fullText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        // Pattern 3: "1 connected device"
        val pattern3 = Regex("""\b(\d+)\s+connected\s+devices?\b""", RegexOption.IGNORE_CASE)
        pattern3.find(fullText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        return 0
    }

    /**
     * Opens the device's Hotspot & Tethering configuration page.
     * Uses contentIntent if available, otherwise attempts platform and OEM specific tethering intents.
     */
    fun openHotspotSettings(context: Context, contentIntent: PendingIntent? = null) {
        if (contentIntent != null) {
            val sent = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val options = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                    contentIntent.send(context, 0, null, null, null, null, options)
                } else {
                    try {
                        contentIntent.send(context, 0, null)
                    } catch (e: Exception) {
                        contentIntent.send()
                    }
                }
                true
            }.getOrDefault(false)
            if (sent) return
        }

        val intents = listOf(
            // 1. Android Standard WiFi Tethering Settings (API 30+)
            Intent("android.settings.WIFI_TETHER_SETTINGS"),
            // 2. Android Standard Tethering Settings (API 26+)
            Intent("android.settings.TETHER_SETTINGS"),
            // 3. Xiaomi / HyperOS / MIUI Component Tethering
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.wifi.tether.TetherSettings")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity")),
            Intent("miui.intent.action.TETHER_SETTINGS"),
            // 4. Samsung Hotspot Settings
            Intent("com.samsung.android.settings.WIFI_AP_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$WifiApSettingsActivity")),
            // 5. General Wireless / Network Settings Fallbacks
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            val launched = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return
        }

        Toast.makeText(context, "Opening Hotspot settings...", Toast.LENGTH_SHORT).show()
    }
}
