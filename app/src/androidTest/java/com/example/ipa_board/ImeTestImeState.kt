package com.example.ipa_board

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals

/** Test-only synchronization for asynchronous IME settings; commands are issued once. */
internal object ImeTestImeState {
    fun enableAndSelect(context: Context, ime: String, shell: (String) -> String) {
        var enableOutput: String? = null
        var selectOutput: String? = null
        try {
            enableOutput = shell("ime enable $ime")
            await(context, ime, enabled = true)
            selectOutput = shell("ime set $ime")
            await(context, ime, enabled = true, selected = ime)
        } catch (error: Throwable) {
            // These two fixed commands report IME configuration only, never editor contents.
            error.addSuppressed(AssertionError("IME setup command output: enable=${enableOutput?.trim() ?: "not completed"}; " +
                "select=${selectOutput?.trim() ?: "not completed"}"))
            throw error
        }
    }

    fun restore(context: Context, ime: String, oldIme: String, wasEnabled: Boolean, shell: (String) -> String) {
        shell("ime set $oldIme")
        // Selection must finish before disabling the test IME, otherwise Android can pick a fallback.
        await(context, ime, selected = oldIme)
        if (!wasEnabled) shell("ime disable $ime")
        await(context, ime, enabled = wasEnabled, selected = oldIme)
    }

    fun await(context: Context, ime: String, enabled: Boolean? = null, selected: String? = null) {
        var actualEnabled = false
        var actualSelected: String? = null
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            actualEnabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
            actualSelected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if ((enabled == null || actualEnabled == enabled) && (selected == null || actualSelected == selected)) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        if (enabled != null) assertEquals("IME enable/disable must finish before the next fixture step", enabled, actualEnabled)
        if (selected != null) assertEquals("IME selection must finish before the next fixture step", selected, actualSelected)
    }
}

/** Read only the owned editor and IME state before cleanup changes focus or selection. */
internal fun addImeReadinessDiagnostics(error: Throwable, context: Context, ime: String,
                                      scenario: ActivityScenario<ImeTestEditorActivity>) {
    try {
        val lifecycle = scenario.state
        var editorState = ""
        scenario.onActivity {
            val manager = it.getSystemService(InputMethodManager::class.java)
            editorState = "windowFocus=${it.editor.hasWindowFocus()}, editorFocus=${it.editor.hasFocus()}, " +
                "activeEditor=${manager.isActive(it.editor)}, attached=${it.editor.isAttachedToWindow}, " +
                "shown=${it.editor.isShown}, lifecycle=$lifecycle"
        }
        val selected = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val enabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        error.addSuppressed(AssertionError("Test-owned editor readiness: $editorState; defaultIME=$selected; testIMEEnabled=$enabled"))
    } catch (diagnosticError: Throwable) {
        error.addSuppressed(diagnosticError)
    }
}

/** Attempt every restoration step and preserve the original assertion as the primary failure. */
internal fun cleanupImeFixture(primaryFailure: Throwable?, vararg steps: () -> Unit) {
    val failures = mutableListOf<Throwable>()
    steps.forEach { step -> try { step() } catch (error: Throwable) { failures += error } }
    if (primaryFailure != null) failures.forEach(primaryFailure::addSuppressed)
    else if (failures.isNotEmpty()) {
        val first = failures.first()
        failures.drop(1).forEach(first::addSuppressed)
        throw first
    }
}
