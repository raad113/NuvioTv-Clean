package com.nuvio.tv.core.cleanstream

import android.content.Context
import android.os.Build
import android.util.Log
import com.nuvio.tv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NuvioTv-Clean: keeps a short report of crashes and of errors that Nuvio Clean
 * caught, so a problem seen on a TV can be investigated afterwards.
 *
 * Reports go to the folder Reshaped's debug log page serves (Settings > Nuvio
 * Reshaped > AutoSync > debug log QR code), with names starting "autosync-"
 * so that page lists them. Only the newest few are kept. Reports contain the
 * app version, device model and the error's stack trace, nothing personal.
 */
object CrashRecorder {
    private const val TAG = "CrashRecorder"
    private const val MAX_REPORTS = 6

    @Volatile private var appContext: Context? = null

    /** Call once from Application.onCreate, after other crash handlers are set up. */
    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write("crash", "Crash on thread ${thread.name}", error) }
            // Let Android (and any crash reporter) handle the crash as before
            previous?.uncaughtException(thread, error)
        }
    }

    /** Record an error Nuvio Clean caught (the app keeps running). */
    fun recordHandled(where: String, error: Throwable) {
        Log.w(TAG, "Nuvio Clean error in $where", error)
        runCatching { write("error", "Handled error in $where", error) }
    }

    private fun write(kind: String, title: String, error: Throwable) {
        val context = appContext ?: return
        val dir = File(context.cacheDir, "autosync-debug").apply { mkdirs() }
        val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("Nuvio Clean $kind report")
            appendLine(title)
            appendLine("Time: $time")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(trace)
        }
        File(dir, "autosync-$kind-$time.txt").writeText(report)
        // Keep only the newest reports
        dir.listFiles { f -> f.name.startsWith("autosync-crash-") || f.name.startsWith("autosync-error-") }
            ?.sortedByDescending { it.name.substringAfterLast('-') + it.name }
            ?.drop(MAX_REPORTS)
            ?.forEach { it.delete() }
    }
}
