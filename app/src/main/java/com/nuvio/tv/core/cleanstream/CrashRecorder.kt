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

    private const val SENT_PREFS = "clean_crash_reports"
    private const val KEY_SENT = "sent_files"

    /** Call once from Application.onCreate, after other crash handlers are set up. */
    fun install(context: Context) {
        appContext = context.applicationContext
        CleanStreamSettings.init(context)
        // Reports from a crash are sent on the next start (a crashing app cannot send reliably)
        sendPendingInBackground()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write("crash", "Crash on thread ${thread.name}", error) }
            // Let Android (and any crash reporter) handle the crash as before
            previous?.uncaughtException(thread, error)
        }
    }

    /** Record an error Nuvio Clean caught (the app keeps running), and send it if the viewer chose to. */
    fun recordHandled(where: String, error: Throwable) {
        Log.w(TAG, "Nuvio Clean error in $where", error)
        runCatching { write("error", "Handled error in $where", error) }
        sendPendingInBackground()
    }

    /**
     * Send reports not sent yet, when "Send crash reports" is on. Each report
     * is sent once; nothing is sent when the setting is off.
     */
    fun sendPendingInBackground() {
        val context = appContext ?: return
        if (!CleanStreamSettings.current.sendCrashReports) return
        Thread {
            runCatching {
                val prefs = context.getSharedPreferences(SENT_PREFS, Context.MODE_PRIVATE)
                val sent = prefs.getStringSet(KEY_SENT, emptySet()).orEmpty().toMutableSet()
                val dir = File(context.cacheDir, "autosync-debug")
                val reports = dir.listFiles { f -> f.name.startsWith("autosync-crash-") || f.name.startsWith("autosync-error-") }
                    .orEmpty()
                    .filter { it.name !in sent }
                    .sortedBy { it.name }
                for (file in reports) {
                    val kind = if (file.name.startsWith("autosync-crash-")) "crash" else "error"
                    if (CleanStreamClient.sendReportNow(kind, file.readText())) sent += file.name else break
                }
                // Forget names of reports that no longer exist
                val existing = dir.list()?.toSet().orEmpty()
                prefs.edit().putStringSet(KEY_SENT, sent.filter { it in existing }.toSet()).apply()
            }.onFailure { Log.w(TAG, "Could not send reports: ${it.message}") }
        }.apply { name = "clean-crash-reports"; isDaemon = true }.start()
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
