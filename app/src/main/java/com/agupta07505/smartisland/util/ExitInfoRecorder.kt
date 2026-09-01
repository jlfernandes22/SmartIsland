/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.agupta07505.smartisland.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Reads the system's OWN death ledger for this package —
 * [ApplicationExitInfo] (API 30+, our floor device is API 33) — at every
 * app start and turns abnormal process deaths into a persisted, copyable
 * report.
 *
 * WHY THIS EXISTS: CrashCapture only fires for uncaught JAVA exceptions.
 * The user's Round-V report ("still crashes, card does not appear") is the
 * signature of everything else:
 *  - REASON_SIGNALED    → native crash (SIGSEGV/SIGABRT) — no Java handler runs
 *  - REASON_ANR         → main thread blocked; the system shoots the process
 *  - REASON_LOW_MEMORY  → kernel OOM kill
 *  - REASON_EXCESSIVE_RESOURCE_USAGE → watchdog kill (hot overlay loop!)
 *  - REASON_INITIALIZATION_FAILURE → early process death
 * None of these write crash-last.txt, so the card never appears. The system
 * ledger, however, records ALL of them — with the native/ANR trace attached
 * on API 31+ — and it survives process death and app upgrades.
 *
 * Acknowledgment model: the newest abnormal record is reported until the
 * user dismisses the card (which stores that record's timestamp in
 * exit-seen.txt) — old acknowledged deaths never resurface.
 */
object ExitInfoRecorder {

    private const val REPORT_FILE = "exit-info-last.txt"
    private const val SEEN_FILE = "exit-seen.txt"

    /** Abnormal death reasons we surface; user/swipe/system-cleanup reasons are noise. */
    private fun isAbnormal(reason: Int): Boolean {
        return reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_ANR ||
            reason == ApplicationExitInfo.REASON_SIGNALED ||
            reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
            reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
    }

    private fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java crash (uncaught exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE crash (SIGSEGV/SIGABRT)"
        ApplicationExitInfo.REASON_ANR -> "ANR — app not responding (main thread blocked)"
        ApplicationExitInfo.REASON_SIGNALED -> "Killed by signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed by the kernel (out of memory)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            "Killed by the system (excessive resource usage)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
        else -> "Unknown reason ($reason)"
    }

    /**
     * Runs at app start (before the UI): inspects the ledger, and if the
     * newest abnormal death is not yet acknowledged, writes the merged
     * report to exit-info-last.txt. One binder call, fully guarded — a
     * stripped-down ROM must degrade to "no report", never to a crash.
     */
    fun inspectOnLaunch(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appContext = context.applicationContext
        runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return
            val records = am.getHistoricalProcessExitReasons(appContext.packageName, 0, 8)
            val newest = records.firstOrNull { isAbnormal(it.reason) } ?: return

            val seenEpoch = readSeenEpoch(appContext)
            if (seenEpoch != null && seenEpoch >= newest.timestamp) return

            appContext.filesDir.resolve(REPORT_FILE)
                .writeText(buildReport(appContext, newest))
        }
    }

    private fun readSeenEpoch(context: Context): Long? {
        val file = File(context.filesDir, SEEN_FILE)
        if (!file.exists()) return null
        return runCatching { file.readText().trim().toLongOrNull() }.getOrNull()
    }

    private fun buildReport(context: Context, record: ApplicationExitInfo): String {
        return buildString {
            append("Process death detected — ")
            append(reasonLabel(record.reason))
            append('\n')
            append("when: ").append(CrashGuard.formatTime(record.timestamp)).append('\n')
            append("version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")")
                .append(" — SDK ").append(Build.VERSION.SDK_INT)
                .append(" — device ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n')
            append("process: ").append(record.processName ?: "?")
            if (record.reason == ApplicationExitInfo.REASON_SIGNALED ||
                record.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            ) {
                // wait-status encoding: low 7 bits carry the terminating signal
                append(" — status ").append(record.status)
                    .append(" (signal ").append(record.status and 0x7f).append(")")
            }
            append('\n')
            record.description?.takeIf { it.isNotBlank() }?.let {
                append("description: ").append(it).append('\n')
            }
            CrashGuard.lastHeartbeatRecord(context)?.let {
                append("last heartbeat: ").append(it.replace('|', " @ ")).append('\n')
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                readTrace(record)?.let { trace ->
                    append("\n--- system trace (truncated) ---\n")
                    append(trace)
                    append('\n')
                }
            }
        }
    }

    /** The system-attached tombstone/ANR trace, capped to keep the card readable. */
    private fun readTrace(record: ApplicationExitInfo): String? {
        return runCatching {
            val stream = record.traceInputStream ?: return@runCatching null
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (total < 64 * 1024) {
                val read = stream.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                total += read
            }
            stream.close()
            buffer.toString("UTF-8")
        }.getOrNull()
    }

    /** The persisted exit report, or null. */
    fun lastReport(context: Context): String? {
        val file = File(context.filesDir, REPORT_FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** Card "Dismiss": acknowledges the reported death and drops the file. */
    fun acknowledgeAndClear(context: Context) {
        runCatching {
            val file = File(context.filesDir, REPORT_FILE)
            // Remember which death we just showed so it never resurfaces.
            val headerWhen = file.takeIf { it.exists() }?.readText()
                ?.lineSequence()
                ?.firstOrNull { it.startsWith("when: ") }
            val epoch = headerWhen?.let { parseReportEpoch(it) }
            if (epoch != null) {
                File(context.filesDir, SEEN_FILE).writeText(epoch.toString())
            }
            file.delete()
        }
    }

    private fun parseReportEpoch(line: String): Long? {
        // "when: yyyy-MM-dd HH:mm:ss" — parse back through the same formatter.
        return runCatching {
            val text = line.removePrefix("when: ").trim()
            val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            val date = format.parse(text) ?: return@runCatching null
            date.time
        }.getOrNull()
    }
}
