package com.example.ipa_board

import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.clipboard.ClipboardRepository
import com.example.ipa_board.diagnostics.AppDiagnostics
import com.example.ipa_board.diagnostics.DebugDiagnosticsSettings
import com.example.ipa_board.ime.ImeChromeView
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Synthetic ClipData only: these tests neither read nor write the system clipboard. */
class ClipboardStartupSyncTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun defaultOnImportsBeforeOpeningThePanelAndDoesNotReviveDeletedItems() = withFixture { f ->
        val clip = ClipData.newPlainText("test", "Synthetic startup ɑ😀")
        assertFalse(f.prefs.contains(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START))
        f.service.syncClipboardOnInputViewStart { clip }
        assertEquals(ImeChromeView.Panel.KEYBOARD, f.chrome.panel)
        val item = f.repository.getItems().single()
        assertEquals("Synthetic startup ɑ😀", item.text)
        assertFalse("Quick Paste is independently disabled in this fixture", f.prefs.getBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, true))
        f.service.syncClipboardOnInputViewStart { clip }
        assertEquals(listOf(item), f.repository.getItems())
        f.repository.deleteItem(item.id)
        f.service.syncClipboardOnInputViewStart { clip }
        assertTrue(f.repository.getItems().isEmpty())
        f.service.syncClipboardOnInputViewStart { ClipData.newPlainText("test", "Synthetic next copy") }
        assertEquals("Synthetic next copy", f.repository.getItems().single().text)
        assertFalse(f.log.exists())
    }

    @Test fun disabledStartupNeverReadsAndReenabledStartupImmediatelyUsesTheCurrentClip() = withFixture { f ->
        f.prefs.edit().putBoolean(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, false).commit()
        DebugDiagnosticsSettings.setEnabled(f.context, true)
        f.service.syncClipboardOnInputViewStart { error("Disabled startup must not read clipboard") }
        assertTrue(f.repository.getItems().isEmpty())
        assertFalse(f.log.exists())
        f.prefs.edit().putBoolean(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, true).commit()
        f.service.syncClipboardOnInputViewStart { ClipData.newPlainText("test", "Synthetic current copy") }
        assertEquals("Synthetic current copy", f.repository.getItems().single().text)
        assertEquals(1, f.log.readLines().size)
    }

    @Test fun everyEnabledAttemptHasOptionalDurationLoggingWithoutContent() = withFixture { f ->
        DebugDiagnosticsSettings.setEnabled(f.context, true)
        f.prefs.edit().putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, true).commit()
        val clip = ClipData.newPlainText("private synthetic label", "PrivateSyntheticText-你好😀")
        repeat(2) { f.service.syncClipboardOnInputViewStart { clip } }
        assertNotNull(f.field("quickPasteState"))
        f.service.syncClipboardOnInputViewStart { null }
        f.service.syncClipboardOnInputViewStart { throw SecurityException("PrivateSyntheticPermissionDetail") }
        val lines = f.log.readLines()
        assertEquals(4, lines.size)
        assertTrue(lines.all { it.matches(Regex("CLIPBOARD/STARTUP_SYNC/READY duration_ms=[0-9]+")) })
        assertFalse(f.log.readText().contains("PrivateSynthetic"))
        assertEquals("PrivateSyntheticText-你好😀", f.repository.getItems().single().text)
        DebugDiagnosticsSettings.setEnabled(f.context, false)
        f.service.syncClipboardOnInputViewStart { ClipData.newPlainText("test", "Synthetic logging-off copy") }
        assertTrue(f.repository.getItems().any { it.text == "Synthetic logging-off copy" })
        assertEquals(lines, f.log.readLines())
    }

    @Test fun settingsDefaultSaveReopenAndDiscardAreIndependentOfQuickPaste() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val keys = listOf(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, SettingsConstants.KEY_QUICK_PASTE_ENABLED,
            SettingsConstants.KEY_QUICK_PASTE_RETENTION_TYPE, SettingsConstants.KEY_QUICK_PASTE_RETENTION_SECONDS,
            SettingsConstants.KEY_QUICK_PASTE_USAGE_TYPE, SettingsConstants.KEY_QUICK_PASTE_USAGE_TIMES,
            SettingsConstants.KEY_SMS_OTP_AUTO_COPY_ENABLED, SettingsConstants.KEY_SMS_OTP_MODE)
        val before = keys.associateWith { prefs.all[it] }
        try {
            prefs.edit().remove(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START)
                .putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, false)
                .putBoolean(SettingsConstants.KEY_SMS_OTP_AUTO_COPY_ENABLED, false).commit()
            ActivityScenario.launch(ClipboardManagerActivity::class.java).use {
                onView(withId(R.id.sw_clipboard_sync_on_start)).check(matches(isChecked())).perform(click())
                onView(withId(R.id.btn_back)).perform(click())
            }
            assertFalse(prefs.contains(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START))
            ActivityScenario.launch(ClipboardManagerActivity::class.java).use {
                onView(withId(R.id.sw_clipboard_sync_on_start)).check(matches(isChecked())).perform(click())
                onView(withId(R.id.btn_save_clipboard_settings)).perform(scrollTo(), click())
            }
            assertFalse(prefs.getBoolean(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, true))
            ActivityScenario.launch(ClipboardManagerActivity::class.java).use {
                onView(withId(R.id.sw_clipboard_sync_on_start)).check(matches(not(isChecked()))).perform(click())
                onView(withId(R.id.btn_save_clipboard_settings)).perform(scrollTo(), click())
                assertTrue(prefs.getBoolean(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, false))
                assertFalse(prefs.getBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, true))
            }
        } finally {
            prefs.edit().apply { before.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                    else -> error("Unexpected clipboard preference type")
                }
            } }.commit()
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        instrumentation.runOnMainSync {
            Fixture(instrumentation.targetContext).use(test)
        }
    }

    private class Fixture(private val base: Context) : AutoCloseable {
        private val prefix = "clipboard-startup-${UUID.randomUUID()}"
        private val directory = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val prefs: SharedPreferences = base.getSharedPreferences(prefix, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        val repository = ClipboardRepository(prefsOverride = prefs)
        val service = IpaBoardService()
        val chrome = ImeChromeView(base)
        val log = File(directory, "diagnostics.log")
        init {
            prefs.edit().putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, false).commit()
            field("prefs", prefs)
            field("chrome", chrome)
            field("clipboardRepository\$delegate", lazyOf(repository))
            AppDiagnostics.configure(context)
        }
        fun field(name: String): Any? = IpaBoardService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
        private fun field(name: String, value: Any) {
            IpaBoardService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        override fun close() {
            (field("quickPasteHandler") as Handler).removeCallbacksAndMessages(null)
            AppDiagnostics.configure(base)
            base.deleteSharedPreferences(prefix)
            directory.deleteRecursively()
        }
    }
}
