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
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.RemoteKeyboardMode
import org.junit.Assert.*
import org.junit.Test

/** Real IME actions in a synthetic editor; device runs require regression.py's external snapshot. */
class PreeditModeImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val driver = ImeTestDriver()
    private val context get() = instrumentation.targetContext
    private val prefs get() = EngineSettings.preferences(context)

    private data class EditorState(val text: String, val start: Int, val end: Int,
        val composingStart: Int, val composingEnd: Int)

    @Test fun boundModeKeyDefersPinyinUntilRealCandidateSelectionAndCanReturnToInline() = withKeyboard { scenario ->
        modeState(inline = true)
        key(2, 1)
        modeState(inline = false)

        typeDeferred("ling")
        editor(scenario, SENTENCE, 0)
        key(2, 3)
        // Select an explicit compatible Rime candidate from the expanded list, without a rank assumption.
        // The driver retries rejected stale nodes only; an accepted selection is never submitted twice.
        driver.click { it.contentDescription?.toString() == "零" && it.isClickable }
        editor(scenario, "零$SENTENCE", 1)
        noPreview()

        key(2, 1)
        modeState(inline = true)
        key(1, 5)
        editor(scenario, "零a$SENTENCE", 2, composingStart = 1, composingEnd = 2)
        noPreview()
        key(2, 2)
        editor(scenario, "零a$SENTENCE", 2)
    }

    @Test fun longPressAndToolbarSwitchesConfirmPendingTextOnceAndVirtualEditingDoesNotTouchTheHost() = withKeyboard { scenario ->
        key(2, 5, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        modeState(inline = false)
        typeDeferred("li")
        key(2, 6)
        preview("li", caret = 1)
        key(2, 7)
        preview("i", caret = 0)
        editor(scenario, SENTENCE, 0)

        clickPreview("i")
        editor(scenario, "i$SENTENCE", 1)
        noPreview()
        key(2, 5, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        modeState(inline = true)
        key(2, 5)
        editor(scenario, "im$SENTENCE", 2, composingStart = 1, composingEnd = 2)

        // Switching away from inline confirms the text already present, without reinserting it.
        key(2, 5, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        modeState(inline = false)
        editor(scenario, "im$SENTENCE", 2)
        typeDeferred("a")
        editor(scenario, "im$SENTENCE", 2)
        driver.click { it.contentDescription?.toString() == "预编辑模式" }
        modeState(inline = true)
        editor(scenario, "ima$SENTENCE", 3)
        noPreview()
    }

    @Test fun shiftShortcutConsumesItsKeyAndExternalSelectionCancelsOnlyTheDeferredBuffer() = withKeyboard { scenario ->
        shiftToggle()
        modeState(inline = false)
        typeDeferred("li")
        editor(scenario, SENTENCE, 0)

        scenario.onActivity { it.editor.setSelection(it.editor.length()) }
        noPreview()
        editor(scenario, SENTENCE, SENTENCE.length)
        typeDeferred("g")
        editor(scenario, SENTENCE, SENTENCE.length)
        clickPreview("g")
        editor(scenario, "${SENTENCE}g", SENTENCE.length + 1)
        noPreview()

        shiftToggle()
        modeState(inline = true)
        key(1, 5)
        editor(scenario, "${SENTENCE}ga", SENTENCE.length + 2,
            composingStart = SENTENCE.length + 1, composingEnd = SENTENCE.length + 2)
    }

    private fun isKey(node: AccessibilityNodeInfo, row: Int, column: Int) =
        node.contentDescription?.toString()?.startsWith("Row $row, key $column:") == true

    private fun key(row: Int, column: Int, action: Int = AccessibilityNodeInfo.ACTION_CLICK) =
        driver.click(action) { isKey(it, row, column) }

    private fun shiftToggle() {
        key(2, 4)
        key(2, 5)
        assertFalse("The mode shortcut must consume temporary Shift",
            driver.awaitNode { isKey(it, 2, 4) && !it.isSelected }.isSelected)
    }

    private fun modeState(inline: Boolean) {
        val node = driver.awaitNode {
            it.contentDescription?.toString() == "预编辑模式" &&
                it.stateDescription?.toString() == if (inline) "内联" else "非内联"
        }
        assertEquals(!inline, node.isSelected)
        assertEquals("The chosen mode must be saved", inline,
            prefs.getBoolean(SettingsConstants.KEY_INLINE_PREEDIT_ENABLED, true))
    }

    private fun isPreview(node: AccessibilityNodeInfo) =
        node.contentDescription?.toString()?.endsWith("，点按原样确认") == true

    private fun preview(text: String, caret: Int = text.codePointCount(0, text.length)) {
        driver.awaitNode { isPreview(it) && it.text?.toString() == text &&
            it.stateDescription?.toString() == "光标在第 $caret 个字符后" && it.isVisibleToUser }
    }

    private fun noPreview() = driver.awaitAbsent(::isPreview)

    private fun clickPreview(text: String) = driver.click { isPreview(it) && it.text?.toString() == text }

    private fun typeDeferred(text: String) {
        var pending = ""
        for (character in text) {
            val column = "linga".indexOf(character) + 1
            require(column > 0)
            key(1, column)
            pending += character
            preview(pending)
        }
    }

    private fun editor(scenario: ActivityScenario<ImeTestEditorActivity>, text: String, caret: Int,
        composingStart: Int = -1, composingEnd: Int = -1) {
        val expected = EditorState(text, caret, caret, composingStart, composingEnd)
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var actual: EditorState? = null
        do {
            scenario.onActivity {
                actual = EditorState(it.editor.text.toString(), it.editor.selectionStart, it.editor.selectionEnd,
                    BaseInputConnection.getComposingSpanStart(it.editor.text), BaseInputConnection.getComposingSpanEnd(it.editor.text))
            }
            if (actual == expected) return
            SystemClock.sleep(40L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("An accepted action must affect only the intended editor range", expected, actual)
    }

    private fun withKeyboard(body: (ActivityScenario<ImeTestEditorActivity>) -> Unit) {
        val groups = PageGroupTestState(context)
        val keys = EngineFeature.entries.map { it.key } + listOf(RemoteKeyboardMode.KEY_SNAPSHOT,
            MultifunctionBarSettings.KEY_BINDINGS, SettingsConstants.KEY_INLINE_PREEDIT_ENABLED,
            SettingsConstants.KEY_SHIFT_SHORTCUTS, SettingsConstants.KEY_CTRL_SHORTCUTS,
            SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, SettingsConstants.KEY_QUICK_PASTE_ENABLED)
        val before = keys.associateWith { prefs.all[it] }
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val wasEnabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val flags = ui.serviceInfo.flags
        var page: String? = null
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        try {
            instrumentation.runOnMainSync {
                assertTrue(prefs.edit().apply {
                    remove(RemoteKeyboardMode.KEY_SNAPSHOT)
                    remove(MultifunctionBarSettings.KEY_BINDINGS)
                    remove(SettingsConstants.KEY_INLINE_PREEDIT_ENABLED)
                    remove(SettingsConstants.KEY_CTRL_SHORTCUTS)
                    putString(SettingsConstants.KEY_SHIFT_SHORTCUTS,
                        SettingsConstants.serializeShortcutsJson(mapOf("m" to KeyAction.PREEDIT_MODE)))
                    putBoolean(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, false)
                    putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, false)
                    EngineFeature.entries.forEach { putBoolean(it.key, it == EngineFeature.CHINESE_CONVERSION) }
                }.commit())
                MultifunctionBarSettings.save(context, MultifunctionBarSlot.LEFT_FIRST, MultifunctionBarBinding(KeyAction.PREEDIT_MODE))
            }
            ui.serviceInfo = ui.serviceInfo.apply { this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            val group = PageGroupManager.create(context, "Preedit mode fixture")
            val layout = KeyboardLayout("Preedit mode fixture", listOf(
                RowLayout(1f, "linga".map { KeySlot(1f, it.toString(), textBehavior = TextBehavior.AUTO) }),
                RowLayout(1f, listOf(KeySlot(1f, action = KeyAction.PREEDIT_MODE),
                    KeySlot(1f, action = KeyAction.ENTER), KeySlot(1f, action = KeyAction.CANDIDATES),
                    KeySlot(1f, action = KeyAction.SHIFT),
                    KeySlot(1f, "m", textBehavior = TextBehavior.AUTO, longPressAction = KeyAction.PREEDIT_MODE),
                    KeySlot(1f, action = KeyAction.LEFT), KeySlot(1f, action = KeyAction.BACKSPACE)))))
            page = LayoutFileManager.createLayout(context, layout.name, layout)
            PageGroupManager.addPages(context, group, listOf(page))
            PageGroupManager.setAppearance(context, group, GroupAppearance(heightDp = 200))
            PageGroupManager.select(context, group)
            PageGroupManager.selectPage(context, page)
            shell("ime enable $ime")
            shell("ime set $ime")
            val owned = ActivityScenario.launch(ImeTestEditorActivity::class.java)
            scenario = owned
            driver.awaitKeyboard(owned) { isKey(it, 1, 1) }
            // Synthetic text only: this is not a clipboard operation or a claim about Gemini's trigger.
            owned.onActivity { it.editor.setText(SENTENCE); it.editor.setSelection(0) }
            instrumentation.waitForIdleSync()
            editor(owned, SENTENCE, 0)
            modeState(inline = true)
            body(owned)
        } finally {
            try { scenario?.close() } finally {
                try { shell("ime set $oldIme"); if (!wasEnabled) shell("ime disable $ime") } finally {
                    try { page?.let { LayoutFileManager.deleteLayout(context, it) } } finally {
                        try { groups.close() } finally {
                            try { assertTrue(prefs.edit().apply { before.forEach { (key, value) -> restoreValue(key, value) } }.commit()) }
                            finally { ui.serviceInfo = ui.serviceInfo.apply { this.flags = flags } }
                        }
                    }
                }
            }
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            else -> error("Unexpected preedit fixture preference type: $key")
        }
    }

    private companion object { const val SENTENCE = "一二三四五六七八九十一二三" }
}
