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
    private const val HANDLER_MARKER = "crash-handler-ran.txt"

    @Volatile private var installed = false

    /** Our live handler instance, kept so re-assertion can detect swaps. */
    @Volatile private var ourHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        if (installed && ourHandler != null) return
        installed = true
        // Deliberately NOT context.applicationContext: install() runs from
        // Application.attachBaseContext, before the Application object is
        // fully registered — application-context resolution there is
        // implementation-defined. The passed context resolves filesDir
        // directly and safely at every call site.
        val appContext = context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
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
        ourHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    /**
     * Round W: re-asserts that OUR handler is still the process default.
     * The live device produced system-ledger REASON_CRASH deaths with NO
     * persisted Java stack — one possible cause is a later-installed
     * component silently REPLACING the default handler (ours never runs,
     * no crash-last.txt). Called from app-create and activity-create; if a
     * swap is detected we re-chain on top of whoever swapped us.
     */
    fun ensureInstalled(context: Context) {
        if (ourHandler == null) {
            install(context)
            return
        }
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current !== ourHandler) {
            install(context) // chains on top of the current (foreign) handler
        }
    }

    private fun writeCrashFile(context: Context, thread: Thread, throwable: Throwable) {
        val file = File(context.filesDir, FILE_NAME)
        // ROUND-X: surface the ROOT cause in the header so one glance — and
        // one screenshot of the safe-mode card — carries the diagnosis even
        // if the stack below is long or the user's OCR flattens it.
        val root = runCatching { throwable.rootCause() }.getOrNull() ?: throwable
        val rootLine = buildString {
            append(runCatching { root.javaClass.simpleName }.getOrNull() ?: "Unknown")
            val msg = runCatching { root.message }.getOrNull()
            if (!msg.isNullOrBlank()) append(": ").append(msg.take(300))
        }
        // ROUND-Y: the root cause's TOP FRAMES go into the header too — the
        // CPH2581 reports kept arriving as flattened screenshots whose OCR
        // truncated the stack body; with the throw site up top, one
        // screenshot is enough to localize a crash.
        val rootFrames = runCatching { root.stackTrace }.getOrNull()
            ?.take(8)
            ?.joinToString("  |  ") { it.toString() }
            .orEmpty()
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
            append("root cause: ").append(rootLine).append('\n')
            if (rootFrames.isNotEmpty()) {
                append("root frames: ").append(rootFrames).append('\n')
            }
            append('\n')
        }
        // ROUND-X: full cause chain, reconstructed explicitly — the live
        // device produced stack-less / cause-less reports for weeks; never
        // again trust a single platform-formatting call for the evidence.
        val stack = runCatching { throwable.fullStackTrace() }
            .getOrElse {
                runCatching { Log.getStackTraceString(throwable) }
                    .getOrDefault(throwable.toString())
            }
        file.parentFile?.mkdirs()
        file.writeText(header + stack)
        // Belt-and-braces: if private storage is the sick organ on this
        // device (the live loop showed REASON_CRASH deaths with NO stack),
        // an external-files-dir copy may still land.
        runCatching {
            context.getExternalFilesDir(null)?.let { dir ->
                File(dir, FILE_NAME).writeText(header + stack)
            }
        }
        // Handler-ran marker: proves the uncaught handler executed for this
        // death even when every report write failed. Its ABSENCE on a
        // device is itself the diagnosis (handler never ran).
        runCatching {
            File(context.filesDir, HANDLER_MARKER).writeText(
                "handler ran — " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                    " — thread " + thread.name + "\n"
            )
        }
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
        runCatching { File(context.filesDir, HANDLER_MARKER).delete() }
        runCatching {
            context.getExternalFilesDir(null)?.let { dir ->
                File(dir, FILE_NAME).delete()
            }
        }
    }
}
