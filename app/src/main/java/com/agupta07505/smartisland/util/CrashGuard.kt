/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's black-box flight recorder + crash-loop breaker.
 *
 * Round V hardening: the Round-U capture (CrashCapture) only sees JAVA
 * exceptions. Device reports kept coming back "it still crashes and the
 * Last crash detected card does not appear" — the signature of deaths the
 * UncaughtExceptionHandler can NEVER observe: native signals (SIGSEGV /
 * SIGABRT in binder or graphics code), ANR kills (main thread blocked, the
 * system shoots the process), watchdog / excessive-resource kills, or a
 * loop so fast the user can never reach Permissions Center to read the
 * card. This object closes that gap with three independent mechanisms:
 *
 * 1. HEARTBEAT — every lifecycle phase (app create, activity open, overlay
 *    connected, listener connected) stamps a tiny file. Whatever kills the
 *    process, the file survives and says what the app was doing when it
 *    died; the crash report merges it in ("died while: overlay-connected").
 *
 * 2. CRASH LATCH — every recorded Java crash also appends to a rolling
 *    crash-times list. When 2+ crashes land within the window, the
 *    safe-mode flag is written: the next launch (and every service bind)
 *    sees it, auto-start surfaces stay DOWN and the loop is broken.
 *
 * 3. SAFE MODE — services/autostart check [isSafeMode] before doing
 *    anything, so whatever re-arms the crash cannot run again until the
 *    user consciously presses "Exit safe mode" in the app. The crash
 *    evidence is deliberately NOT deleted by exiting safe mode.
 *
 * All writes are last-wins tiny files in filesDir — no locks, no
 * coroutines, safe to call from any thread including a dying one.
 */
object CrashGuard {

    private const val HEARTBEAT_FILE = "heartbeat.txt"
    private const val CRASH_TIMES_FILE = "crash-times.txt"
    private const val SAFE_MODE_FILE = "safe-mode.flag"
    private const val BOUNDARY_FILE = "boundary-crash.txt"

    /** Rolling window in which 2+ crashes latch safe mode. */
    private const val SAFE_MODE_WINDOW_MS = 15 * 60 * 1000L

    /** Keep the heartbeat file from churning on every repeat event. */
    private const val HEARTBEAT_MIN_INTERVAL_MS = 20_000L

    /** Max crash timestamps retained in the rolling list. */
    private const val CRASH_HISTORY_MAX = 8

    @Volatile private var lastHeartbeatPhase: String? = null
    @Volatile private var lastHeartbeatAt: Long = 0L

    /**
     * Stamp the current lifecycle phase. Called from app/Activity/Service
     * lifecycle transitions only — phase changes are cheap; per-event calls
     * would be disk churn.
     */
    fun recordHeartbeat(context: Context, phase: String) {
        val now = System.currentTimeMillis()
        val samePhase = lastHeartbeatPhase == phase
        lastHeartbeatPhase = phase
        if (samePhase && now - lastHeartbeatAt < HEARTBEAT_MIN_INTERVAL_MS) return
        lastHeartbeatAt = now
        runCatching {
            File(context.filesDir, HEARTBEAT_FILE)
                .writeText("$now|$phase")
        }
    }

    /** The last stamped phase (memory first, file fallback), or null. */
    fun lastHeartbeat(context: Context): String? {
        lastHeartbeatPhase?.let { return it }
        val text = lastHeartbeatRecord(context) ?: return null
        return text.substringAfter('|', "").ifEmpty { null }
    }

    /** The raw heartbeat record ("epoch|phase"), for report headers. */
    fun lastHeartbeatRecord(context: Context): String? {
        val file = File(context.filesDir, HEARTBEAT_FILE)
        return runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
    }

    /**
     * Called from the uncaught-exception handler BEFORE the report write:
     * appends the timestamp to the rolling list and latches safe mode when
     * the recent-crash threshold trips. Fully no-throw by contract — this
     * runs in a dying process and must never mask the original crash.
     */
    fun markCrash(context: Context, phase: String?) {
        runCatching {
            val now = System.currentTimeMillis()
            val file = File(context.filesDir, CRASH_TIMES_FILE)
            val previous = runCatching {
                file.takeIf { it.exists() }?.readText().orEmpty()
            }.getOrNull().orEmpty()
            val stamps = previous.split('\n')
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it > now - 24 * 60 * 60 * 1000L } // drop stale days-old entries
                .toMutableList()
            stamps.add(now)
            while (stamps.size > CRASH_HISTORY_MAX) stamps.removeAt(0)
            file.writeText(stamps.joinToString("\n"))

            val recent = stamps.count { it > now - SAFE_MODE_WINDOW_MS }
            if (recent >= 2) {
                File(context.filesDir, SAFE_MODE_FILE)
                    .writeText("$now|$phase")
            }
        }
    }

    /**
     * Persisted evidence for a crash we caught AT A BOUNDARY (around
     * super.onCreate, around repository resolution, around any framework
     * call we refuse to let kill the process). Round W: the live device
     * (OnePlus CPH2581, SDK 37) kept dying between app-create and
     * activity-create WITHOUT the uncaught handler ever persisting a Java
     * stack — so the boundaries themselves now capture what dies inside
     * them. Written to filesDir AND external files dir: if private storage
     * is the sick organ, the external copy still lands.
     */
    fun recordBoundaryCrash(context: Context, where: String, throwable: Throwable) {
        runCatching {
            val header = buildString {
                append("Boundary crash caught at: ").append(where)
                append(" — ").append(formatTime(System.currentTimeMillis()))
                append(" — thread ").append(Thread.currentThread().name)
                append('\n')
                lastHeartbeatRecord(context)?.let {
                    append("last heartbeat: ").append(it.replace("|", " @ ")).append('\n')
                }
                append('\n')
            }
            val stack = android.util.Log.getStackTraceString(throwable)
            File(context.filesDir, BOUNDARY_FILE)
                .writeText(header + stack)
            context.getExternalFilesDir(null)?.let { dir ->
                runCatching { File(dir, BOUNDARY_FILE).writeText(header + stack) }
            }
        }
        // A boundary catch IS a crash for the loop breaker: two of these in
        // the window must latch safe mode exactly like an uncaught one.
        markCrash(context, "boundary:$where")
    }

    /** True while the crash-loop breaker is engaged. */
    fun isSafeMode(context: Context): Boolean {
        return runCatching {
            File(context.filesDir, SAFE_MODE_FILE).exists()
        }.getOrDefault(false)
    }

    /** When safe mode was latched (epoch ms), or null. */
    fun safeModeSince(context: Context): Long? {
        return runCatching {
            File(context.filesDir, SAFE_MODE_FILE)
                .takeIf { it.exists() }
                ?.readText()
                ?.substringBefore('|')
                ?.trim()
                ?.toLongOrNull()
        }.getOrNull()
    }

    /** User's explicit "Exit safe mode" — clears the latch AND the crash history. */
    fun exitSafeMode(context: Context) {
        runCatching {
            File(context.filesDir, SAFE_MODE_FILE).delete()
            File(context.filesDir, CRASH_TIMES_FILE).delete()
        }
    }

    /** Human-readable stamp helper shared by the report builders. */
    internal fun formatTime(epochMs: Long): String {
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))
        }.getOrDefault(epochMs.toString())
    }

    /** Merges both evidence sources + safe-mode state into one card text. */
    fun buildLaunchCrashReport(context: Context): String? {
        val parts = mutableListOf<String>()
        File(context.filesDir, BOUNDARY_FILE)
            .takeIf { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.let { parts.add(it) }
        ExitInfoRecorder.lastReport(context)?.let { parts.add(it) }
        CrashCapture.lastCrashReport(context)?.let { parts.add(it) }
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n———\n\n")
    }

    /** User-visible acknowledgment — drops the boundary evidence too. */
    fun clearEvidence(context: Context) {
        runCatching { File(context.filesDir, BOUNDARY_FILE).delete() }
        context.getExternalFilesDir(null)?.let { dir ->
            runCatching { File(dir, BOUNDARY_FILE).delete() }
        }
    }
}
