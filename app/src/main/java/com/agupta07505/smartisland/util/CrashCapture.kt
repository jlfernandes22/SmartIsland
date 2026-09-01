/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.agupta07505.smartisland.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PERSISTS the last uncaught crash to app-private storage before the process
 * dies, so a user who cannot attach `adb logcat` can still hand over the
 * stack (Settings → Permissions Center → "Last crash detected" card copies
 * it to the clipboard; the overlay crash-loop reports so far have all been
 * stack-less and therefore unfixable).
 *
 * The handler CHAINS to the previous default handler: crash dialog, system
 * logging, Firebase-style collectors — everything keeps working. The only
 * added behavior is the file write, which is itself fully guarded: a crash
 * inside the crash writer must never mask the original crash.
 *
 * Re-throwing is NOT our call to make — the chained handler owns process
 * death. The file is overwritten on every crash (last crash wins); "Dismiss"
 * in the UI deletes it.
 */
object CrashCapture {

    private const val FILE_NAME = "crash-last.txt"

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        // Deliberately NOT context.applicationContext: install() runs from
        // Application.attachBaseContext, before the Application object is
        // fully registered — application-context resolution there is
        // implementation-defined. The passed context resolves filesDir
        // directly and safely at every call site.
        val appContext = context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Latch crash-loop safe mode FIRST (rolling crash-times list):
                // even if the report write below fails on a wedged filesystem,
                // the next launch still knows the app is crash-looping.
                CrashGuard.markCrash(appContext, CrashGuard.lastHeartbeat(appContext))
            } catch (_: Throwable) {
                // Never let the evidence-gatherer mask the evidence.
            }
            try {
                writeCrashFile(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Never let the evidence-gatherer mask the evidence.
            }
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                // And never mask a failing previous handler either: at this
                // point the runtime is dying anyway; die quietly.
            }
        }
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(context.filesDir, FILE_NAME)
        val header = buildString {
            append("SmartIsland crash")
            append(" — ").append(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            )
            append(" — version ").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")")
            append(" — SDK ").append(Build.VERSION.SDK_INT)
            append(" — device ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append('\n')
            append("thread: ").append(thread.name).append('\n')
            CrashGuard.lastHeartbeatRecord(context)?.let {
                append("last heartbeat: ").append(it.replace("|", " @ ")).append('\n')
            }
            append('\n')
        }
        val stack = runCatching { Log.getStackTraceString(throwable) }
            .getOrDefault(throwable.toString())
        file.parentFile?.mkdirs()
        file.writeText(header + stack)
    }

    /** The persisted crash report, or null when the app has not crashed. */
    fun lastCrashReport(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** User-visible acknowledgment ("Dismiss" in the crash card). */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
