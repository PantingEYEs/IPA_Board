package com.example.ipa_board.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.example.ipa_board.SettingsConstants

/** DIAGNOSTICS: fixed events to Logcat and the bounded local log; no telemetry or input content. */
object AppDiagnostics {
    // DIAGNOSTICS: single build-time removal gate; false also hides the optional settings control.
    const val LOGGING_AVAILABLE = true
    const val TAG = "IPABoardDiagnostics"
    private const val LOG_FILE_NAME = "diagnostics.log"
    private const val MAX_LOG_SIZE_BYTES = 512 * 1024L
    @Volatile private var appContext: Context? = null

    val isEnabled: Boolean get() = reporter.enabled

    private val reporter = DiagnosticReporter(SystemClock::elapsedRealtime) { event ->
        if (event.outcome == DiagnosticOutcome.FAILURE) Log.e(TAG, event.line()) else Log.d(TAG, event.line())
        appendLogFile(event)
    }

    fun configure(context: Context) {
        appContext = context.applicationContext
        try { configure(DebugDiagnosticsSettings.preferences(context)) }
        catch (_: Exception) { reporter.enabled = false }
    }
    fun configure(preferences: SharedPreferences) { reporter.enabled = LOGGING_AVAILABLE && DebugDiagnosticsSettings.enabled(preferences) }
    /** Isolated workers use the explicit request snapshot, never a stale cross-process preference read. */
    fun configure(data: Bundle) { reporter.enabled = LOGGING_AVAILABLE && data.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED, false) }

    fun <T> atStage(component: DiagnosticComponent, stage: DiagnosticStage, operation: () -> T): T =
        reporter.atStage(component, stage, operation)
    fun <T> measure(component: DiagnosticComponent, stage: DiagnosticStage, operation: () -> T): T =
        reporter.measure(component, stage, operation)
    fun failure(component: DiagnosticComponent, stage: DiagnosticStage, error: Throwable) = reporter.failure(component, stage, error)
    fun signal(component: DiagnosticComponent, stage: DiagnosticStage, kind: DiagnosticKind) = reporter.signal(component, stage, kind)

    fun trace(component: DiagnosticComponent, stage: DiagnosticStage) {
        if (reporter.enabled) {
            reporter.trace(component, stage)
        }
    }

    fun getLogFileSize(context: Context): Long {
        val file = java.io.File(context.filesDir, LOG_FILE_NAME)
        return if (file.exists()) file.length() else 0L
    }

    @Synchronized fun clearLogFile(context: Context) {
        try {
            val file = java.io.File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    fun exportLogFile(context: Context, uri: android.net.Uri): Boolean {
        return try {
            val file = java.io.File(context.filesDir, LOG_FILE_NAME)
            if (!file.exists()) return false
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) { false }
    }

    @Synchronized private fun appendLogFile(event: DiagnosticEvent) {
        val ctx = appContext ?: return
        try {
            val file = java.io.File(ctx.filesDir, LOG_FILE_NAME)
            if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                val content = file.readLines()
                val kept = content.takeLast((content.size / 2).coerceAtLeast(1))
                file.writeText(kept.joinToString("\n") + "\n")
            }
            file.appendText("${event.line()}\n")
        } catch (_: Exception) {}
    }
}

/** DIAGNOSTICS: removable preference + IPC envelope. Off for both debug and release until opted in. */
object DebugDiagnosticsSettings {
    const val KEY_ENABLED = "debug_diagnostics_enabled"
    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
    fun enabled(preferences: SharedPreferences): Boolean = try { preferences.getBoolean(KEY_ENABLED, false) }
        catch (_: ClassCastException) { false }
    fun setEnabled(context: Context, value: Boolean) {
        val preferences = preferences(context)
        preferences.edit { putBoolean(KEY_ENABLED, value) }
        AppDiagnostics.configure(preferences)
    }
    fun writeTo(bundle: Bundle, preferences: SharedPreferences) { bundle.putBoolean(KEY_ENABLED, enabled(preferences)) }
}
