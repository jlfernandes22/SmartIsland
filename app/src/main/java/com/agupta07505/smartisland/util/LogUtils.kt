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
