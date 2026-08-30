/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui.expanded

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (settings.idleInfoShowTime) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onItemClick(IDLE_ITEM_TIME) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoIconBadge(icon = Icons.Rounded.Schedule, color = Color(0xFF38BDF8))
                Column {
                    Text(
                        text = state.timeText,
                        color = Color.White,
                        fontSize = 26.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = state.dateText,
                        color = Color(0xFFB7C0CA),
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (settings.idleInfoShowBattery) {
            IdleInfoRow(
                icon = Icons.Rounded.BatteryChargingFull,
                iconColor = if (state.batteryCharging) Color(0xFF10B981) else Color(0xFF94A3B8),
                label = "Battery",
                value = state.batteryText,
                onClick = { onItemClick(IDLE_ITEM_BATTERY) }
            )
        }

        if (settings.idleInfoShowBluetooth) {
            IdleInfoRow(
                icon = if (state.bluetoothOn) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
                iconColor = if (state.bluetoothOn) Color(0xFF2563EB) else Color(0xFF94A3B8),
                label = "Bluetooth",
                value = state.bluetoothText,
                onClick = { onItemClick(IDLE_ITEM_BLUETOOTH) }
            )
        }

        if (settings.idleInfoShowHotspot) {
            IdleInfoRow(
                icon = Icons.Rounded.WifiTethering,
                iconColor = if (state.hotspotText.startsWith("On")) Color(0xFFF59E0B) else Color(0xFF94A3B8),
                label = "Hotspot",
                value = state.hotspotText,
                onClick = { onItemClick(IDLE_ITEM_HOTSPOT) }
            )
        }

        if (settings.idleInfoShowUsbTethering) {
            IdleInfoRow(
                icon = Icons.Rounded.Usb,
                iconColor = if (state.usbTetheringText.startsWith("On")) Color(0xFF10B981) else Color(0xFF94A3B8),
                label = "USB Tethering",
                value = state.usbTetheringText,
                onClick = { onItemClick(IDLE_ITEM_USB_TETHERING) }
            )
        }

        if (settings.idleInfoShowBtTethering) {
            IdleInfoRow(
                icon = if (state.bluetoothOn) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
                iconColor = if (state.btTetheringText.startsWith("On")) Color(0xFF2563EB) else Color(0xFF94A3B8),
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
                color = Color(0xFFB7C0CA),
                fontSize = 13.sp
            )
        }

        // In-island action feedback (e.g. "Bluetooth on", "Turning Bluetooth…"):
        // the test device suppresses Toasts for this app, so results are shown
        // here instead. Rendered last so the rows never shift position.
        if (feedback != null) {
            Text(
                text = feedback,
                color = Color(0xFF67E8F9),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.animateContentSize()
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

@Composable
private fun InfoIconBadge(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(19.dp)
        )
    }
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
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InfoIconBadge(icon = icon, color = iconColor)
        Text(
            text = label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = Color(0xFFD5DAE0),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
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
        val active = HotspotUtil.isHotspotActive(context)
        when (active) {
            true -> hotspotText = "On"
            null -> hotspotText = TAP_TO_TOGGLE
            false -> hotspotText = "Off"
        }
    }

    // USB tethering brings up the rndis0/usb0 interface — a permission-free,
    // reliable read. When the probe cannot run at all, show "Tap to toggle".
    val usbTetheringText = runCatching {
        val up = java.net.NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.any { (it.name == "rndis0" || it.name == "usb0") && it.isUp } == true
        if (up) "On" else "Off"
    }.getOrDefault(TAP_TO_TOGGLE)

    // Bluetooth tethering (PAN) state is a hidden, permission-guarded API with
    // no permission-free read — show the best available state instead.
    val btTetheringText = TAP_TO_TOGGLE

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