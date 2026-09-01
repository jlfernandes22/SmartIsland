/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland

import android.content.Context
import android.app.Application
import com.agupta07505.smartisland.util.CrashCapture
import com.agupta07505.smartisland.util.CrashGuard
import com.agupta07505.smartisland.util.ExitInfoRecorder
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SmartIslandApp : Application() {

    override fun attachBaseContext(base: Context) {
        // Earliest survivable point: BEFORE super (Hilt) and BEFORE the
        // ContentProviders run — anything that throws from process start
        // through provider init now lands in the persisted crash report.
        // Round U installed here-late (onCreate) and a crash before that
        // point produced neither report nor card.
        CrashCapture.install(base)
        super.attachBaseContext(base)
        CrashGuard.recordHeartbeat(base, "app-attach")
    }

    override fun onCreate() {
        super.onCreate()
        // (Idempotent — attachBaseContext already installed it; kept here so
        // a future refactor of attachBaseContext cannot silently un-arm it.
        // Round W: also detects a foreign default-handler swap and re-chains
        // ours on top — the live device showed deaths with no persisted
        // stack, and a silent handler replacement is one possible cause.)
        CrashCapture.install(this)
        CrashCapture.ensureInstalled(this)
        CrashGuard.recordHeartbeat(this, "app-create")
        // Read the system's own death ledger (native crashes, ANRs, system
        // kills — everything the UncaughtExceptionHandler can never see) and
        // persist an unacknowledged death as a copyable report for the
        // home-screen crash card.
        try {
            ExitInfoRecorder.inspectOnLaunch(this)
        } catch (_: Throwable) {
            // A stripped ROM must degrade to "no death report", never crash.
        }
        bypassHiddenApis()
    }

    private fun bypassHiddenApis() {
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                arrayOf<Class<*>>().javaClass
            )

            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as java.lang.reflect.Method
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf(arrayOf<String>().javaClass)
            ) as java.lang.reflect.Method

            val vmRuntime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(vmRuntime, arrayOf("L") as Any)
            android.util.Log.d("SmartIslandApp", "Successfully bypassed Hidden API restrictions (unsealed reflection)")
        } catch (e: Exception) {
            android.util.Log.e("SmartIslandApp", "Failed to bypass Hidden API restrictions", e)
        }
    }
}
