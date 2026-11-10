package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test

/** Real keyboard actions are restricted to the protected regression runner's synthetic editor. */
class TerraPinyinImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val prefs get() = EngineSettings.preferences(context)
    private val driver = ImeTestDriver()

    @Test fun inlineToneKeysAndSchemeChangesPreservePendingTextUntilCandidateSelection() = withKeyboard(true) { scenario ->
        key(1, 1); key(1, 2); key(2, 3)
        awaitEditor(scenario, "ma<", 3, 0, 3)
        setScheme(PinyinScheme.LUNA)
        awaitEditor(scenario, "ma<", 3, 0, 3)
        setScheme(PinyinScheme.TERRA)
        awaitEditor(scenario, "ma<", 3, 0, 3)
        key(3, 3)
        driver.select(scenario, "马", "马")
    }

    @Test fun nonInlineLongPressToneBackspaceAndSchemeChangesNeverWriteRawTextToTheEditor() = withKeyboard(false) { scenario ->
        key(1, 1); key(1, 2)
        key(2, 3, AccessibilityNodeInfo.ACTION_LONG_CLICK) // The test-owned long press types fourth tone.
        awaitPreview("ma\\")
        awaitEditor(scenario, "", 0)
        key(3, 1)
        awaitPreview("ma")
        key(2, 3)
        awaitPreview("ma<")
        setScheme(PinyinScheme.LUNA)
        awaitPreview("ma<")
        awaitEditor(scenario, "", 0)
        setScheme(PinyinScheme.TERRA)
        awaitPreview("ma<")
        awaitEditor(scenario, "", 0)
        key(3, 3)
        driver.select(scenario, "马", "马")
    }

    private fun setScheme(scheme: PinyinScheme) {
        instrumentation.runOnMainSync {
            assertTrue(prefs.edit().putString(PinyinSettings.KEY_SCHEME, scheme.schemaId).commit())
        }
        instrumentation.waitForIdleSync()
    }

    private fun key(row: Int, column: Int, action: Int = AccessibilityNodeInfo.ACTION_CLICK) = driver.click(action) {
        it.contentDescription?.toString()?.startsWith("Row $row, key $column:") == true
    }

    private fun awaitPreview(raw: String) {
        driver.awaitNode {
            it.contentDescription?.toString()?.endsWith("，点按原样确认") == true &&
                it.text?.toString() == raw && it.isVisibleToUser
        }
    }

    private fun awaitEditor(scenario: ActivityScenario<ImeTestEditorActivity>, text: String, caret: Int,
                            composingStart: Int = -1, composingEnd: Int = -1) {
        val expected = listOf(text, caret, caret, composingStart, composingEnd)
        var actual: List<Any>? = null
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            scenario.onActivity {
                actual = listOf(it.editor.text.toString(), it.editor.selectionStart, it.editor.selectionEnd,
                    BaseInputConnection.getComposingSpanStart(it.editor.text), BaseInputConnection.getComposingSpanEnd(it.editor.text))
            }
            if (actual == expected) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, actual)
    }

    private fun withKeyboard(inline: Boolean, body: (ActivityScenario<ImeTestEditorActivity>) -> Unit) {
        val before = prefs.all.toMap()
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val wasEnabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var page: String? = null
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        try {
            instrumentation.runOnMainSync {
                assertTrue(prefs.edit().apply {
                    remove(RemoteKeyboardMode.KEY_SNAPSHOT)
                    remove(SettingsConstants.KEY_SHIFT_SHORTCUTS)
                    remove(SettingsConstants.KEY_CTRL_SHORTCUTS)
                    putBoolean(SettingsConstants.KEY_INLINE_PREEDIT_ENABLED, inline)
                    putBoolean(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, false)
                    putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, false)
                    putString(PinyinSettings.KEY_SCHEME, PinyinScheme.TERRA.schemaId)
                    putBoolean(PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES, false)
                    EngineFeature.entries.forEach {
                        putBoolean(it.key, it == EngineFeature.CHINESE_CONVERSION || it == EngineFeature.SCRIPT_CONVERSION)
                    }
                }.commit())
                val group = PageGroupManager.create(context, "Owned Terra keyboard")
                val layout = KeyboardLayout("Owned Terra keyboard", listOf(
                    RowLayout(1f, "man iho".filter { it != ' ' }.map { KeySlot(1f, it.toString(), textBehavior = TextBehavior.AUTO) }),
                    RowLayout(1f, listOf(KeySlot(1f, "-", textBehavior = TextBehavior.AUTO),
                        KeySlot(1f, "/", textBehavior = TextBehavior.AUTO),
                        KeySlot(1f, "<", textBehavior = TextBehavior.AUTO, longPressText = "\\"),
                        KeySlot(1f, "\\", textBehavior = TextBehavior.AUTO))),
                    RowLayout(1f, listOf(KeySlot(1f, action = KeyAction.BACKSPACE),
                        KeySlot(1f, action = KeyAction.LEFT), KeySlot(1f, action = KeyAction.CANDIDATES)))))
                page = LayoutFileManager.createLayout(context, layout.name, layout)
                PageGroupManager.addPages(context, group, listOf(requireNotNull(page)))
                PageGroupManager.setAppearance(context, group, GroupAppearance(heightDp = 200))
                PageGroupManager.select(context, group)
                PageGroupManager.selectPage(context, requireNotNull(page))
            }
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            shell("ime enable $ime")
            shell("ime set $ime")
            assertEquals(ime, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            val owned = ActivityScenario.launch(ImeTestEditorActivity::class.java)
            scenario = owned
            driver.awaitKeyboard(owned) { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
            awaitEditor(owned, "", 0)
            body(owned)
        } finally {
            try { scenario?.close() } finally {
                try {
                    shell("ime set $oldIme")
                    if (!wasEnabled) shell("ime disable $ime")
                    assertEquals(oldIme, Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
                    assertEquals(wasEnabled, context.getSystemService(InputMethodManager::class.java)
                        .enabledInputMethodList.any { it.id == ime })
                } finally {
                    try { page?.let { LayoutFileManager.deleteLayout(context, it) } } finally {
                        try {
                            instrumentation.runOnMainSync {
                                assertTrue(prefs.edit().clear().apply {
                                    before.forEach { (key, value) -> restoreValue(key, value) }
                                }.commit())
                            }
                        } finally { ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags } }
                    }
                }
            }
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
            null -> remove(key)
        }
    }
}
