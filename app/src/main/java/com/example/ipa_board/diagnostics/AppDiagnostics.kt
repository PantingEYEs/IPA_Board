package com.example.ipa_board.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.example.ipa_board.SettingsConstants

/** DIAGNOSTICS: the only Logcat adapter. No files, telemetry, free-text messages or stack traces. */
object AppDiagnostics {
    // DIAGNOSTICS: single build-time removal gate; false also hides the optional settings control.
    const val LOGGING_AVAILABLE = true
    const val TAG = "IPABoardDiagnostics"
    private val reporter = DiagnosticReporter(SystemClock::elapsedRealtime) { event ->
        if (event.outcome == DiagnosticOutcome.FAILURE) Log.e(TAG, event.line()) else Log.d(TAG, event.line())
    }
    fun configure(context: Context) {
        try { configure(DebugDiagnosticsSettings.preferences(context)) }
        catch (_: Exception) { reporter.enabled = false }
    }
    fun configure(preferences: SharedPreferences) { reporter.enabled = LOGGING_AVAILABLE && DebugDiagnosticsSettings.enabled(preferences) }
    /** Isolated workers use the explicit request snapshot, never a stale cross-process preference read. */
    fun configure(data: Bundle) { reporter.enabled = LOGGING_AVAILABLE && data.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED, false) }
    fun <T> atStage(component: DiagnosticComponent, stage: DiagnosticStage, operation: () -> T): T =
        reporter.atStage(component, stage, operation)
    fun failure(component: DiagnosticComponent, stage: DiagnosticStage, error: Throwable) = reporter.failure(component, stage, error)
    fun signal(component: DiagnosticComponent, stage: DiagnosticStage, kind: DiagnosticKind) = reporter.signal(component, stage, kind)
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
