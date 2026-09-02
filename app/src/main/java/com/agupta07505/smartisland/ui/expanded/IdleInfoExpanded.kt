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
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.data.SmartIslandSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class IdleDeviceState(
    val timeText: String,
    val dateWeekday: String,
    val dateText: String,
    val batteryText: String,
    val batteryCharging: Boolean,
    val bluetoothText: String,
    val bluetoothOn: Boolean
)

// Height clamp shared with the measured-height reporting in
// IslandExpandedContent: the estimate must use the SAME clamp as the real
// measurement or the first measure would move the card after it opened.
internal val IdleInfoMinHeight = 56.dp
internal const val IdleInfoMaxHeightDp = 250f

/**
 * Deterministic natural height of the idle info menu for [settings] inside an
 * [availableWidthDp]-wide card (the expanded card's inner width). Mirrors the
 * IdleInfoExpanded layout exactly — 8dp vertical padding, 44dp tiles, 8dp
 * column gap, 6dp row gap — so IslandOverlayView can initialize the expanded
 * card height with the SAME value the first real measurement will report.
 *
 * When the estimate equals the measured height the card never changes size
 * after the menu opens, which is what keeps the icon grid perfectly centered
 * with zero post-settle movement: any late height delta resizes the overlay
 * window and visibly nudges the content at the end of the settle.
 */
fun idleInfoMenuHeightDp(settings: SmartIslandSettings, availableWidthDp: Float): Dp {
    val tileEnabled = listOf(
        settings.idleInfoShowTime,
        settings.idleInfoShowDate,
        settings.idleInfoShowBattery,
        settings.idleInfoShowBluetooth
    )
    val tileCount = tileEnabled.count { it }
    if (tileCount == 0) {
        // "All info items are disabled" one-line fallback layout.
        return 32.dp.coerceIn(IdleInfoMinHeight, IdleInfoMaxHeightDp.dp)
    }
    val tileSize = 44f
    val columnGap = 8f
    // Greedy FlowRow wrap: n tiles of 44dp + (n-1) gaps fit into the width.
    val maxPerRow = ((availableWidthDp + columnGap) / (tileSize + columnGap)).toInt().coerceAtLeast(1)
    val rows = ((tileCount + maxPerRow - 1) / maxPerRow).coerceAtLeast(1)
    val height = rows * tileSize + (rows - 1) * 6f + 16f
    return height.dp.coerceIn(IdleInfoMinHeight, IdleInfoMaxHeightDp.dp)
}

/**
 * Natural width of the idle info menu card: the tiles' intrinsic extent plus
 * the column paddings, so the card hugs its content instead of stretching to
 * the 0.95-screen-width band the notification pages use (which read as an
 * empty black slab around a tiny strip of icons).
 *
 * 8dp of slack is added on top of the exact intrinsic width: a FlowRow that
 * fits EXACTLY can still wrap when density rounding costs a single pixel,
 * and a wrapped second row would desync the height estimate (the exact
 * post-settle-jump class of bug the centering fixes killed). With the slack
 * the row can never wrap, so estimate == measurement stays byte-exact.
 */
fun idleInfoMenuWidthDp(settings: SmartIslandSettings): Dp {
    val tileCount = listOf(
        settings.idleInfoShowTime,
        settings.idleInfoShowDate,
        settings.idleInfoShowBattery,
        settings.idleInfoShowBluetooth
    ).count { it }
    if (tileCount == 0) {
        // "All info items are disabled" one-line fallback text.
        return 160.dp
    }
    return (tileCount * 44f + (tileCount - 1) * 8f + 24f + 8f).dp
}

// Minimal palette: icons rest in muted grey and light up with a functional
// accent only while the radio is actually on. White-on-black, no badges, no
// labels — the menu reads as a quiet strip of glyphs.
private val Muted = Color(0xFF8E99A4)
private val AccentOn = Color(0xFF10B981)
private val AccentBluetooth = Color(0xFF2563EB)
private val AccentTime = Color(0xFF38BDF8)

// Tile geometry: everything lives on a 44dp grid so four enabled items still
// form a single icon row inside the expanded card (4*44 + 3*8 = 200dp).
private val TileSize = 44.dp
private val TileCorner = 14.dp
private val TileGap = 8.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdleInfoExpanded(
    settings: SmartIslandSettings,
    onItemClick: (String) -> Unit = {},
    feedback: String? = null
) {
    val context = LocalContext.current
    // Battery/Bluetooth reads are binder + reflection probes; run them on IO
    // so the 1s menu refresh never janks the overlay's main thread.
    val state by produceState(
        initialValue = IdleDeviceState("", "", "", "", false, "", false)
    ) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                readDeviceState(context)
            }
            delay(1000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Icon-first grid: one tile per enabled info item, no text rows. FlowRow
        // only wraps on very narrow cards; with every item enabled the tiles
        // still fit a single line on the 0.95 screen-width card.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TileGap, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (settings.idleInfoShowTime) {
                TimeTile(timeText = state.timeText.ifEmpty { "--:--" }) {
                    onItemClick(IDLE_ITEM_TIME)
                }
            }
            if (settings.idleInfoShowDate) {
                DateTile(
                    dateWeekday = state.dateWeekday.ifEmpty { "---" },
                    dateText = state.dateText.ifEmpty { "-- ---" }
                ) {
                    onItemClick(IDLE_ITEM_DATE)
                }
            }
            if (settings.idleInfoShowBattery) {
                // Text-only battery tile: the percentage IS the content. The
                // material glyph and the micro progress bar were removed —
                // the menu's design contract is a quiet strip of glyphs and
                // the battery number carries more information than either
                // decoration. Charging state reads through the color fade
                // (white → charging green), nothing else.
                val batteryPct = state.batteryText.substringBefore(" ")
                PercentTile(
                    text = batteryPct.ifEmpty { "--" },
                    charging = state.batteryCharging
                ) {
                    onItemClick(IDLE_ITEM_BATTERY)
                }
            }
            if (settings.idleInfoShowBluetooth) {
                ToggleTile(
                    icon = if (state.bluetoothOn) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth,
                    label = "Bluetooth",
                    on = state.bluetoothOn,
                    accent = AccentBluetooth
                ) {
                    onItemClick(IDLE_ITEM_BLUETOOTH)
                }
            }
        }

        if (!settings.idleInfoShowTime &&
            !settings.idleInfoShowDate &&
            !settings.idleInfoShowBattery &&
            !settings.idleInfoShowBluetooth
        ) {
            Text(
                text = "All info items are disabled",
                color = Muted,
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }

        // In-island action feedback (e.g. "Bluetooth on", "Hotspot on"):
        // the test device suppresses Toasts for this app, so results are shown
        // here instead. Rendered last so the tiles never shift position.
        if (feedback != null) {
            Text(
                text = feedback,
                color = AccentTime,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
            )
        }
    }
}

const val IDLE_ITEM_TIME = "time"
const val IDLE_ITEM_DATE = "date"
const val IDLE_ITEM_BATTERY = "battery"
const val IDLE_ITEM_BLUETOOTH = "bluetooth"

/**
 * Clock tile: the HH:mm readout IS the content — the only tile that is pure
 * text. Tap opens the clock app (service-side behavior, unchanged).
 */
@Composable
private fun TimeTile(timeText: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(TileSize)
            .height(TileSize)
            .clip(RoundedCornerShape(TileCorner))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = timeText,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Date tile: two-line readout (weekday / day-month) on the same 44dp grid.
 * Weekday carries the accent so the tile reads as a sibling of the clock
 * tile, not a second battery percentage. Tap opens the calendar app
 * (service-side behavior) while the clock tile keeps opening the clock app.
 */
@Composable
private fun DateTile(dateWeekday: String, dateText: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(TileSize)
            .height(TileSize)
            .clip(RoundedCornerShape(TileCorner))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = dateWeekday,
                color = AccentTime,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Text(
                text = dateText,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Battery tile: a single percentage readout on the same 44dp grid as the
 * clock tile. No glyph, no bar — the charging state is the color transition.
 */
@Composable
private fun PercentTile(
    text: String,
    charging: Boolean,
    onClick: () -> Unit
) {
    // Charging lights the number the way the toggle tiles light their glyphs —
    // a 350ms fade instead of a snap, matching the rest of the menu.
    val animatedTint by animateColorAsState(
        targetValue = if (charging) AccentOn else Color.White,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
        label = "batteryPctTint"
    )
    Box(
        modifier = Modifier
            .width(TileSize)
            .height(TileSize)
            .clip(RoundedCornerShape(TileCorner))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = animatedTint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Toggle tile: a single glyph on a 44dp tile. Off = muted icon on clear
 * glass; on = accent icon on a faint accent wash. The wash + icon color is
 * the whole state display — readable at a glance, silent when idle.
 */
@Composable
private fun ToggleTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    // Off → on fades the wash and lights the glyph the way the charging
    // island fades its progress bar — state changes are transitions, not
    // snaps.
    val animatedTint by animateColorAsState(
        targetValue = if (on) accent else Muted,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
        label = "toggleTileTint"
    )
    val animatedBackground by animateColorAsState(
        targetValue = if (on) accent.copy(alpha = 0.16f) else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
        label = "toggleTileBackground"
    )
    Box(
        modifier = Modifier
            .width(TileSize)
            .height(TileSize)
            .clip(RoundedCornerShape(TileCorner))
            .background(animatedBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = animatedTint,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun readDeviceState(context: Context): IdleDeviceState {
    val now = System.currentTimeMillis()
    val timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
    val dateWeekday = SimpleDateFormat("EEE", Locale.getDefault())
        .format(Date(now)).uppercase(Locale.getDefault())
    val dateText = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(now))

    var batteryText = "--"
    var batteryCharging = false
    runCatching {
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                null,
                android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level >= 0 && scale > 0) {
            val pct = (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100)
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            batteryText = "$pct%"
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
        // Best-effort device count: bondedDevices needs BLUETOOTH_CONNECT on
        // S+ (older releases hold the install-time BLUETOOTH permission).
        // The count is skipped when the grant is missing — the tile falls
        // back to a plain "On" readout instead of burning a SecurityException.
        val canReadBondedDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (canReadBondedDevices) {
            runCatching {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val bonded = adapter?.bondedDevices
                if (!bonded.isNullOrEmpty()) {
                    bluetoothText = "On • ${bonded.size} device${if (bonded.size == 1) "" else "s"}"
                }
            }
        }
    }

    return IdleDeviceState(
        timeText = timeText,
        dateWeekday = dateWeekday,
        dateText = dateText,
        batteryText = batteryText,
        batteryCharging = batteryCharging,
        bluetoothText = bluetoothText,
        bluetoothOn = bluetoothOn
    )
}
