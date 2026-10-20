package com.example.ipa_board

import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.diagnostics.AppDiagnostics
import com.example.ipa_board.diagnostics.DebugDiagnosticsSettings
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** Fixed lifecycle events from this fixture only. Private data is restored even after an assertion fails. */
internal class ImeLifecycleTrace : TestWatcher() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = DebugDiagnosticsSettings.preferences(context)
    private val file = File(context.filesDir, "diagnostics.log")
    private var original: ByteArray? = null
    private var setting: Any? = null
    override fun starting(description: Description) {
        original = if (file.exists()) file.readBytes() else null
        setting = preferences.all[DebugDiagnosticsSettings.KEY_ENABLED]
        AppDiagnostics.clearLogFile(context)
        AppDiagnostics.configure(context)
        DebugDiagnosticsSettings.setEnabled(context, true)
    }
    override fun failed(error: Throwable, description: Description) {
        val events = if (file.isFile) file.readLines().filter { it.matches(Regex("IME/[A-Z_]+/READY")) }.takeLast(40) else emptyList()
        error.addSuppressed(AssertionError("Test-owned IME lifecycle: $events"))
    }
    override fun finished(description: Description) {
        try {
            preferences.edit().apply {
                when (val value = setting) {
                    is Boolean -> putBoolean(DebugDiagnosticsSettings.KEY_ENABLED, value)
                    is String -> putString(DebugDiagnosticsSettings.KEY_ENABLED, value)
                    is Int -> putInt(DebugDiagnosticsSettings.KEY_ENABLED, value)
                    is Long -> putLong(DebugDiagnosticsSettings.KEY_ENABLED, value)
                    is Float -> putFloat(DebugDiagnosticsSettings.KEY_ENABLED, value)
                    null -> remove(DebugDiagnosticsSettings.KEY_ENABLED)
                    else -> error("Unsupported diagnostics preference fixture type")
                }
            }.commit()
            AppDiagnostics.configure(context)
        } finally {
            val bytes = original
            if (bytes == null) file.delete() else file.writeBytes(bytes)
        }
    }
}
