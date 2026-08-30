/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui.expanded

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.data.SmartIslandSettings
import com.agupta07505.smartisland.util.HotspotUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class IdleDeviceState(
    val timeText: String,
    val dateText: String,
    val batteryText: String,
    val batteryCharging: Boolean,
    val bluetoothText: String,
    val bluetoothOn: Boolean,
    val hotspotText: String,
    val usbTetheringText: String,
    val btTetheringText: String
)

// Minimal palette: one muted resting tone, functional accents only when a
// radio/tether is actually on. Everything else is white-on-black with lots of
// negative space — the menu reads as a quiet list, not a dashboard.
private val Muted = Color(0xFF94A3B8)
private val MutedText = Color(0xFF9AA4AF)
private val AccentTime = Color(0xFF38BDF8)
private val AccentOn = Color(0xFF10B981)
private val AccentBluetooth = Color(0xFF2563EB)
private val AccentHotspot = Color(0xFFF59E0B)

@Composable
fun IdleInfoExpanded(
    settings: SmartIslandSettings,
    onItemClick: (String) -> Unit = {},
    feedback: String? = null
) {
    val context = LocalContext.current
    // Battery/BT/hotspot/tethering reads are binder + reflection + interface
    // probes; run them on IO so the 1s menu refresh never janks the overlay's
    // main thread.
    val state by produceState(
        initialValue = IdleDeviceState("", "", "", false, "", false, "", "", "")
    ) {
        while (true) {
            value = withContext(Dispatchers.IO) { readDeviceState(context) }
            delay(1000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (settings.idleInfoShowTime) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onItemClick(IDLE_ITEM_TIME) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoIcon(icon = Icons.Rounded.Schedule, color = AccentTime)
                Column {
                    Text(
                        text = state.timeText,
                        color = Color.White,
                        fontSize = 22.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.dateText,
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        if (settings.idleInfoShowBattery) {
            IdleInfoRow(
                icon = Icons.Rounded.BatteryChargingFull,
                iconColor = if (state.batteryCharging) AccentOn else Muted,
                label = "Battery",
                value = state.batteryText,
                onClick = { onItemClick(IDLE_ITEM_BATTERY) }
            )
        }

        if (settings.idleInfoShowBluetooth) {
            IdleInfoRow(
                icon = if (state.bluetoothOn) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
                iconColor = if (state.bluetoothOn) AccentBluetooth else Muted,
                label = "Bluetooth",
                value = state.bluetoothText,
                onClick = { onItemClick(IDLE_ITEM_BLUETOOTH) }
            )
        }

        if (settings.idleInfoShowHotspot) {
            IdleInfoRow(
                icon = Icons.Rounded.WifiTethering,
                iconColor = if (state.hotspotText == "On") AccentHotspot else Muted,
                label = "Hotspot",
                value = state.hotspotText,
                onClick = { onItemClick(IDLE_ITEM_HOTSPOT) }
            )
        }

        if (settings.idleInfoShowUsbTethering) {
            IdleInfoRow(
                icon = Icons.Rounded.Usb,
                iconColor = if (state.usbTetheringText == "On") AccentOn else Muted,
                label = "USB Tethering",
                value = state.usbTetheringText,
                onClick = { onItemClick(IDLE_ITEM_USB_TETHERING) }
            )
        }

        if (settings.idleInfoShowBtTethering) {
            IdleInfoRow(
                icon = if (state.bluetoothOn) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
                iconColor = if (state.btTetheringText == "On") AccentBluetooth else Muted,
                label = "Bluetooth Tethering",
                value = state.btTetheringText,
                onClick = { onItemClick(IDLE_ITEM_BT_TETHERING) }
            )
        }

        if (!settings.idleInfoShowTime &&
            !settings.idleInfoShowBattery &&
            !settings.idleInfoShowBluetooth &&
            !settings.idleInfoShowHotspot &&
            !settings.idleInfoShowUsbTethering &&
            !settings.idleInfoShowBtTethering
        ) {
            Text(
                text = "All info items are disabled",
                color = Muted,
                fontSize = 11.sp
            )
        }

        // In-island action feedback (e.g. "Bluetooth on", "Turning Bluetooth…"):
        // the test device suppresses Toasts for this app, so results are shown
        // here instead. Rendered last so the rows never shift position.
        if (feedback != null) {
            Text(
                text = feedback,
                color = AccentTime,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .animateContentSize()
            )
        }
    }
}

const val IDLE_ITEM_TIME = "time"
const val IDLE_ITEM_BATTERY = "battery"
const val IDLE_ITEM_BLUETOOTH = "bluetooth"
const val IDLE_ITEM_HOTSPOT = "hotspot"
const val IDLE_ITEM_USB_TETHERING = "usb_tethering"
const val IDLE_ITEM_BT_TETHERING = "bt_tethering"

/** Row value shown when a tethering state cannot be read on this device. */
private const val TAP_TO_TOGGLE = "Tap to toggle"

/**
 * Minimal row icon: a plain 18dp glyph with no badge box behind it. Color is
 * the only state signal (muted when off, accent when on), which keeps the
 * menu calm while the on/off state stays readable at a glance.
 */
@Composable
private fun InfoIcon(icon: ImageVector, color: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun IdleInfoRow(
    icon: ImageVector,
    iconColor: Color,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InfoIcon(icon = icon, color = iconColor)
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = MutedText,
            fontSize = 12.sp
        )
    }
}

private fun readDeviceState(context: Context): IdleDeviceState {
    val now = System.currentTimeMillis()
    val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
    val dateText = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(now))

    var batteryText = "--"
    var batteryCharging = false
    runCatching {
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level >= 0 && scale > 0) {
            val pct = (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            batteryText = if (batteryCharging) "$pct% • Charging" else "$pct%"
        }
    }

    var bluetoothText = "Off"
    var bluetoothOn = false
    // Permission-free adapter state: the system tracks it in Settings.Global.
    // (BluetoothAdapter.isEnabled needs BLUETOOTH_CONNECT which may be denied.)
    bluetoothOn = runCatching {
        android.provider.Settings.Global.getInt(
            context.contentResolver,
            "bluetooth_on",
            0
        ) != 0
    }.getOrDefault(false)
    if (bluetoothOn) {
        bluetoothText = "On"
        // Best-effort device count: bondedDevices needs BLUETOOTH_CONNECT.
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val bonded = adapter?.bondedDevices
            if (!bonded.isNullOrEmpty()) {
                bluetoothText = "On • ${bonded.size} device${if (bonded.size == 1) "" else "s"}"
            }
        }
    }

    var hotspotText = "Off"
    runCatching {
        // Layered reader: TetheringManager.getTetheredIfaces (the platform's
        // own tethered list) → legacy WifiManager reflection → unknown.
        val active = HotspotUtil.isWifiTetheringActive(context)
        when (active) {
            true -> hotspotText = "On"
            null -> hotspotText = TAP_TO_TOGGLE
            false -> hotspotText = "Off"
        }
    }

    // Same layered reader: TetheredIfaces → USB_STATE sticky broadcast →
    // gadget-interface probe (rndis/usb/ncm). Unknown only when the device
    // offers none of these.
    val usbTetheringText = when (HotspotUtil.isUsbTetheringActive(context)) {
        true -> "On"
        null -> TAP_TO_TOGGLE
        false -> "Off"
    }

    // Bluetooth PAN: TetheredIfaces when the platform reports it, otherwise
    // the honest "unknown" instead of a wrong On/Off.
    val btTetheringText = when (HotspotUtil.isBluetoothTetheringActive(context)) {
        true -> "On"
        null -> TAP_TO_TOGGLE
        false -> "Off"
    }

    return IdleDeviceState(
        timeText = timeText,
        dateText = dateText,
        batteryText = batteryText,
        batteryCharging = batteryCharging,
        bluetoothText = bluetoothText,
        bluetoothOn = bluetoothOn,
        hotspotText = hotspotText,
        usbTetheringText = usbTetheringText,
        btTetheringText = btTetheringText
    )
}
