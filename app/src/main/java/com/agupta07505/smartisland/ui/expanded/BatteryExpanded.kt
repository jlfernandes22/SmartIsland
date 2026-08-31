/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui.expanded

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.data.SmartIslandSettings
import com.agupta07505.smartisland.model.IslandNotification
import com.agupta07505.smartisland.ui.components.DottedRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Snapshot of the live battery hardware state, refreshed once a second on the
 * IO dispatcher while the battery island is on screen.
 *
 * [watts] is the instantaneous charging power (voltage × current), [tempC]
 * the pack temperature and [timeText] a time-to-full estimate built ONLY from
 * real data: the platform's own [BatteryManager.computeChargeTimeRemaining]
 * on P+, else a charge-counter ÷ live-current integration — never the linear
 * guess the old UI showed when no estimate existed.
 */
private data class ChargingStats(
    val charging: Boolean,
    val fullyCharged: Boolean,
    val watts: Int?,
    val tempC: Int?,
    val timeText: String?
)

@Composable
fun BatteryExpanded(
    notification: IslandNotification,
    bottomPadding: Dp,
    settings: SmartIslandSettings
) {
    val context = LocalContext.current
    val pctText = notification.text?.replace("%", "")?.trim() ?: "49"
    val pct = pctText.toFloatOrNull() ?: 49f
    val progress = (pct / 100f).coerceIn(0f, 1f)

    val titleLower = notification.title.lowercase()
    val isBatterySaver = titleLower.contains("saver") || notification.category == "battery_saver"
    val isLowBattery = titleLower.contains("low") || notification.category == "battery_low" || pct <= 20f

    // Live hardware readout on IO — the previous implementation ran a binder
    // call (computeChargeTimeRemaining) inside remember{} on the MAIN thread
    // every time the percentage changed.
    val stats by produceState<ChargingStats?>(initialValue = null) {
        while (true) {
            value = withContext(Dispatchers.IO) { readChargingStats(context) }
            delay(1000L)
        }
    }

    val batteryColor = when {
        isLowBattery -> Color(0xFFEF4444)
        isBatterySaver -> Color(0xFFF59E0B)
        else -> Color(settings.batteryColor)
    }
    val midBatteryColor = lerp(batteryColor, Color.White, 0.35f)
    val lightestBatteryColor = lerp(batteryColor, Color.White, 0.65f)

    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        animatedProgress.animateTo(
            targetValue = progress,
            animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing)
        )
    }

    val flowTransition = rememberInfiniteTransition(label = "electricFlow")
    val flowOffset by flowTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flowOffset"
    )

    val rotationAngle by flowTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dottedRingRotation"
    )

    val timeText = remember(pct, isBatterySaver, isLowBattery, notification.title, stats) {
        when {
            isBatterySaver -> "Power Saver active"
            isLowBattery -> "Connect charger"
            stats?.fullyCharged == true -> buildStatsLine(stats, fullyCharged = true)
            stats?.charging == true -> buildStatsLine(stats, fullyCharged = false)
            notification.title.equals("Fully Charged", ignoreCase = true) -> "Full"
            else -> "Charging…"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(start = 18.dp, top = 20.dp, end = 18.dp, bottom = bottomPadding)
            .heightIn(min = 72.dp, max = 110.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier.size(46.dp),
                contentAlignment = Alignment.Center
            ) {
                DottedRing(
                    progress = progress,
                    rotationAngle = rotationAngle,
                    modifier = Modifier.size(44.dp),
                    color = batteryColor
                )

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(batteryColor.copy(alpha = 0.12f), shape = CircleShape)
                        .border(1.2.dp, batteryColor.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isBatterySaver -> {
                            Icon(
                                Icons.Rounded.BatterySaver,
                                contentDescription = null,
                                tint = batteryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        isLowBattery -> {
                            Icon(
                                Icons.Rounded.BatteryAlert,
                                contentDescription = null,
                                tint = batteryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        else -> {
                            Icon(
                                Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = batteryColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Column {
                Text(
                    text = notification.title.takeIf { it.isNotBlank() } ?: if (isBatterySaver) "Battery Saver ON" else if (isLowBattery) "Low Battery" else "Charging",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Text(
                    text = "${pct.toInt()}%",
                    color = batteryColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    lineHeight = 22.sp
                )
                Text(
                    text = timeText,
                    color = Color(0xFF98A2B3),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(32.dp)
                    .border(1.5.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                    .background(Color(0x1AFFFFFF), RoundedCornerShape(8.dp))
                    .padding(2.5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedProgress.value)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    batteryColor,
                                    midBatteryColor,
                                    lightestBatteryColor,
                                    midBatteryColor,
                                    batteryColor
                                ),
                                startX = flowOffset,
                                endX = flowOffset + 300f,
                                tileMode = TileMode.Repeated
                            ),
                            shape = RoundedCornerShape(6.dp)
                        )
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent),
                                startY = 0f,
                                endY = 40f
                            ),
                            shape = RoundedCornerShape(6.dp)
                        )
                )
            }
            Spacer(modifier = Modifier.width(3.dp))
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(12.dp)
                    .background(Color(0x66FFFFFF), shape = RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
            )
        }
    }
}

/**
 * Third line of the charging island: "⚡18 W fast • 42 m until full • 31°C",
 * trimmed to the longest part that fits one line (temperature drops first,
 * then the estimate — the wattage readout is the piece worth keeping).
 */
private fun buildStatsLine(stats: ChargingStats?, fullyCharged: Boolean): String {
    if (stats == null) return if (fullyCharged) "Full" else "Charging…"
    if (fullyCharged) {
        return listOfNotNull("Full", stats.tempC?.let { "$it°C" }).joinToString(" • ")
    }
    val wattPart = stats.watts?.let { w ->
        when {
            w >= 20 -> "⚡$w W fast"
            w >= 8 -> "⚡$w W"
            else -> "⚡$w W slow"
        }
    }
    val timePart = stats.timeText
    val tempPart = stats.tempC?.let { "$it°C" }
    val parts = listOfNotNull(wattPart, timePart, tempPart)
    var line = parts.joinToString(" • ")
    if (line.length > 34 && tempPart != null) {
        line = parts.filter { it !== tempPart }.joinToString(" • ")
    }
    return line.ifEmpty { "Charging…" }
}

/**
 * One-second hardware snapshot. Everything is best-effort: any failure or
 * implausible value simply drops that piece of the readout instead of
 * showing garbage.
 */
private fun readChargingStats(context: Context): ChargingStats? {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        ?: return null
    val sticky = stickyBatteryIntent(context) ?: return null
    val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val pct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt().coerceIn(0, 100) else -1
    val tempDeci = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val tempC = if (tempDeci != Int.MIN_VALUE) (tempDeci / 10).takeIf { it in 0..90 } else null

    val fullyCharged = status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0
    if (!charging) return ChargingStats(false, fullyCharged, null, tempC, null)

    // Live charging power. EXTRA_VOLTAGE is millivolts but a few OEMs report
    // microvolts — normalize before the math; CURRENT_NOW is microamps and
    // some devices invert its sign, so the magnitude is what is used here.
    val mvRaw = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).toLong()
    val mv = if (mvRaw > 100_000) mvRaw / 1000 else mvRaw
    val ua = runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
        .getOrDefault(0L)
    val watts = if (mv in 1_000..100_000 && ua != 0L) {
        ((mv * abs(ua)) / 1_000_000_000L).toInt()
    } else {
        null
    }

    val timeText = when {
        fullyCharged -> "Full"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            val ms = runCatching { bm.computeChargeTimeRemaining() }.getOrDefault(-1L)
            if (ms > 0) formatRemaining(ms) else estimateFromChargeCounter(bm, pct)
        }
        else -> estimateFromChargeCounter(bm, pct)
    }

    return ChargingStats(true, fullyCharged, watts?.takeIf { it in 1..300 }, tempC, timeText)
}

/**
 * Below-P fallback for the time-to-full estimate: remaining charge (derived
 * from the real charge counter and the reported percentage) divided by the
 * live current — real measurements instead of the old linear guess. Returns
 * null (→ the UI just says "Charging…") whenever the numbers are missing or
 * the result would be absurd.
 */
private fun estimateFromChargeCounter(bm: BatteryManager, pct: Int): String? {
    if (pct !in 1..99) return null
    val chargeUah = runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }
        .getOrDefault(0L)
    val ua = runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
        .getOrDefault(0L)
    if (chargeUah <= 0 || ua <= 0) return null
    val remainingUah = chargeUah * 100L / pct
    val ms = (remainingUah.toDouble() / ua.toDouble() * 3_600_000.0).toLong()
    if (ms < 60_000L || ms > 12L * 3_600_000L) return null
    return "~" + formatRemaining(ms)
}

private fun formatRemaining(ms: Long): String {
    val totalMins = (ms / 60_000L).coerceAtLeast(1)
    val h = totalMins / 60
    val m = totalMins % 60
    return if (h > 0) "$h h $m m until full" else "$m m until full"
}

/** Sticky ACTION_BATTERY_CHANGED query without registering a receiver. */
private fun stickyBatteryIntent(context: Context): Intent? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_EXPORTED
        )
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
}.getOrNull()
