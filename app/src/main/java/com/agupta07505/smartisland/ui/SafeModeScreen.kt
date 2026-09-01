/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.util.CrashCapture
import com.agupta07505.smartisland.util.CrashGuard
import com.agupta07505.smartisland.util.ExitInfoRecorder

/**
 * The crash-loop breaker UI — deliberately TINY.
 *
 * Round V's safe mode latched correctly but changed nothing the user could
 * see: MainActivity kept composing the full home screen, so whatever threw
 * on the affected device killed every launch ~1s in — including the safe
 * mode launches — and the persisted Java stack (crash-last.txt) could never
 * be copied because the app died before a tap could land.
 *
 * THIS screen composes nothing but text and buttons: no repositories, no
 * DataStore collection, no ViewModels, no overlay preview, no Shizuku, no
 * icon packs. If safe mode is latched, MainActivity renders ONLY this — so
 * the process stays alive indefinitely and the user can finally read and
 * copy the full crash evidence, then consciously exit the breaker.
 *
 * Exit behavior: two-tap confirm (the first tap swaps the label), because
 * exiting re-enables exactly the surfaces that were crashing.
 */
@Composable
fun SafeModeScreen(
    report: String?,
    safeModeSince: Long?,
    onExitSafeMode: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var evidence by remember { mutableStateOf(report) }
    var exitArmed by remember { mutableStateOf(false) }

    Surface(color = Color(0xFF0B0B10), modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Crash safe mode",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (safeModeSince != null) {
                    "Active since " + CrashGuard.formatTime(safeModeSince) +
                        " — the app crashed repeatedly within 15 minutes, so every " +
                        "heavy surface (overlay, listener, home studio) is disabled " +
                        "to break the loop."
                } else {
                    "Every heavy surface is disabled to break the crash loop."
                },
                color = Color(0xFF9CA3AF),
                fontSize = 14.sp,
                lineHeight = 19.sp
            )

            Surface(
                color = Color(0xFF17171F),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Crash evidence",
                        color = Color(0xFFE5E7EB),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (evidence.isNullOrBlank()) {
                        Text(
                            text = "No persisted report — the last death left no " +
                                "trace on disk. Exiting safe mode and reproducing " +
                                "the crash will regenerate it.",
                            color = Color(0xFF6B7280),
                            fontSize = 13.sp,
                            lineHeight = 17.sp
                        )
                    } else {
                        Text(
                            text = evidence.orEmpty(),
                            color = Color(0xFFD1D5DB),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val text = evidence.orEmpty()
                    runCatching {
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !evidence.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Copy report", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = {
                    if (exitArmed) {
                        onExitSafeMode()
                    } else {
                        exitArmed = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFDC2626),
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (exitArmed) {
                        "Tap again to confirm — this restarts the app with everything re-enabled"
                    } else {
                        "Exit safe mode"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )
            }

            TextButton(
                onClick = {
                    runCatching {
                        CrashCapture.clear(context)
                        ExitInfoRecorder.acknowledgeAndClear(context)
                        CrashGuard.clearEvidence(context)
                    }
                    evidence = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Dismiss evidence", color = Color(0xFF9CA3AF), fontSize = 14.sp)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Dismissing the evidence deletes it permanently — copy it " +
                    "first if you plan to report a bug.",
                color = Color(0xFF4B5563),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}
