/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.util

import android.util.Log
import com.agupta07505.smartisland.BuildConfig
import kotlinx.coroutines.CancellationException

inline fun <T> runCatchingLogged(tag: String, message: String = "Operation failed", block: () -> T): T? {
    return try {
        block()
    } catch (e: CancellationException) {
        // Never swallow cancellation — doing so breaks structured concurrency
        // whenever this helper wraps suspending calls inside coroutines.
        throw e
    } catch (e: Throwable) {
        // Throwable, not Exception: reflection/Compose failures surface as
        // Error subclasses (ExceptionInInitializerError, NoClassDefFoundError,
        // InternalError) that used to escape every "guarded" site and kill
        // the whole process — for an overlay service that meant a crash LOOP
        // (every system rebind re-crashed). A service that degrades to
        // "island missing" beats one that takes the app down with it.
        try {
            if (BuildConfig.DEBUG) {
                Log.e(tag, message, e)
            }
        } catch (_: Throwable) { /* Unit test JVM stub */ }
        null
    }
}

suspend inline fun <T> runSuspendCatchingLogged(
    tag: String,
    message: String = "Operation failed",
    crossinline block: suspend () -> T
): T? {
    return try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        // See runCatchingLogged: Errors must degrade, never crash the process.
        try {
            if (BuildConfig.DEBUG) {
                Log.e(tag, message, error)
            }
        } catch (_: Throwable) {
            // Unit-test Android stubs can throw from Log.
        }
        null
    }
}

/**
 * ROUND-X: the persisted crash reports from the live device (OnePlus CPH2581,
 * SDK 37) repeatedly stopped at the framework boundary —
 * "RuntimeException: Unable to create service ..." with NO "Caused by"
 * section and therefore no root exception, i.e. no diagnosis. Whether the
 * platform clipped the chain or the chain was never there, the fix is the
 * same: the report writer must not TRUST getStackTraceString alone. This
 * reconstruction walks the cause chain explicitly (identity-set guarded
 * against cycles) and formats every level with its own frames, so whatever
 * the platform logger does, the persisted report carries the complete
 * root-cause chain.
 */
fun Throwable.fullStackTrace(): String {
    return buildString {
        var current: Throwable? = this@fullStackTrace
        val seen = HashSet<Throwable>()
        var level = 0
        while (current != null && seen.add(current)) {
            if (level > 0) append("\nCaused by: ")
            append(current.javaClass.name)
            val message = runCatching { current.message }.getOrNull()
            if (!message.isNullOrBlank()) append(": ").append(message)
            val frames = runCatching { current.stackTrace }.getOrNull()
            if (frames != null && frames.isNotEmpty()) {
                for (frame in frames) append("\n        at ").append(frame.toString())
            } else {
                append("\n        <no stack frames available>")
            }
            current = runCatching { current.cause }.getOrNull()
            level++
        }
    }
}

/** The innermost throwable of a chain — the actual root cause. */
fun Throwable.rootCause(): Throwable {
    var current: Throwable = this
    val seen = HashSet<Throwable>().apply { add(this@rootCause) }
    while (true) {
        val next = runCatching { current.cause }.getOrNull() ?: break
        if (!seen.add(next)) break // cycle — stop at the last unique node
        current = next
    }
    return current
}
