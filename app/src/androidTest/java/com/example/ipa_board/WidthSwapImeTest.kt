package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import com.example.ipa_board.shortcuts.ReplacementSettings
import org.junit.Assert.*
import org.junit.Test

/** Real IME actions use only the protected runner's test-owned keyboard and editor. */
class WidthSwapImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val prefs get() = EngineSettings.preferences(context)
    private val driver = ImeTestDriver()
    private val widthDescription = "全角 / 半角转换"

    @Test fun singleConvertsOneActualCharacterIncludingKanaAndEmojiClustersThenTurnsOff() = withKeyboard { scenario ->
        var committed = ""
        for ((column, output) in listOf(1 to "ａbc", 2 to "ガa", 3 to "ｶﾞa", 4 to "1️⃣a", 5 to "👩🏽‍💻a")) {
            key(2, 1)
            state("单次")
            key(1, column)
            editor(scenario, committed + output, composingStart = committed.length)
            state("关闭")
            key(2, 2)
            committed += output
            editor(scenario, committed)
            state("关闭")
        }
    }

    @Test fun actualSpaceInputConsumesSingleWidthWithoutRewritingPendingText() = withKeyboard(native = false) { scenario ->
        key(2, 1)
        state("单次")
        key(3, 5)
        editor(scenario, "　")
        state("关闭")

        key(1, 1)
        editor(scenario, "　abc", composingStart = 1)
        key(2, 1)
        state("单次")
        editor(scenario, "　abc", composingStart = 1)
        // With every engine disabled, Space commits the existing spelling plus one actual space.
        key(3, 5)
        editor(scenario, "　abc　")
        state("关闭")
        key(1, 1)
        editor(scenario, "　abc　abc", composingStart = 5)
        key(2, 2)
        editor(scenario, "　abc　abc")
    }

    @Test fun lockedModeKeepsEarlierInputAndSurvivesConfirmationMovementHidingAndNewFields() = withKeyboard { scenario ->
        val original = "aＡｶﾞ😀"
        key(3, 4)
        editor(scenario, original, composingStart = 0)
        key(2, 1)
        state("单次")
        key(2, 1, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        state("锁定")
        editor(scenario, original, composingStart = 0)
        key(1, 1)
        editor(scenario, original + "ａｂｃ", composingStart = 0)
        state("锁定")
        key(2, 2)
        editor(scenario, original + "ａｂｃ")
        state("锁定")

        scenario.onActivity { it.editor.setSelection(0) }
        instrumentation.waitForIdleSync()
        state("锁定")
        scenario.onActivity {
            it.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.editor.windowToken, 0)
        }
        driver.awaitAbsent { it.contentDescription?.toString() == widthDescription && it.isVisibleToUser }
        driver.awaitKeyboard(scenario) { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
        state("锁定")

        scenario.onActivity { activity ->
            activity.editor = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT }
            activity.setContentView(activity.editor)
            activity.editor.requestFocus()
        }
        awaitActiveEditor(scenario)
        state("锁定")
        key(1, 1)
        editor(scenario, "ａｂｃ", composingStart = 0)
        key(2, 1)
        state("关闭")
        editor(scenario, "ａｂｃ", composingStart = 0)
        key(1, 1)
        editor(scenario, "ａｂｃabc", composingStart = 0)
        key(2, 2)
        editor(scenario, "ａｂｃabc")
    }

    @Test fun firstActivationConvertsTheSelectionAndLockingDoesNotReverseItAgain() = withKeyboard { scenario ->
        scenario.onActivity { it.editor.setText("前aＡｶﾞ😀後"); it.editor.setSelection(1, 7) }
        instrumentation.waitForIdleSync()
        key(2, 1)
        state("单次")
        editor(scenario, "前ａAガ😀後", caret = 1, selectionEnd = 6)
        key(2, 1)
        state("锁定")
        editor(scenario, "前ａAガ😀後", caret = 1, selectionEnd = 6)
        scenario.onActivity { it.editor.setSelection(it.editor.length()) }
        instrumentation.waitForIdleSync()
        state("锁定")
        key(1, 1)
        editor(scenario, "前ａAガ😀後ａｂｃ", composingStart = 7)
        key(2, 2)
        editor(scenario, "前ａAガ😀後ａｂｃ")
        state("锁定")
        key(2, 1)
        state("关闭")
        editor(scenario, "前ａAガ😀後ａｂｃ")
    }

    @Test fun deferredInputUsesActualTextAndVirtualCaretDoesNotUnlockOrWriteToTheHost() = withKeyboard(inline = false) { scenario ->
        key(1, 1)
        preview("abc")
        setState("锁定")
        preview("abc")
        key(1, 1)
        preview("abcａｂｃ")
        key(2, 3)
        key(2, 4)
        preview("abcａｃ")
        state("锁定")
        key(3, 3)
        preview("abcａｂBｃ")
        editor(scenario, "")
        key(2, 2)
        editor(scenario, "abcａｂBｃ")
        state("锁定")
    }

    @Test fun nativeCandidatesStayUnchangedAndBothPartialAndFullSelectionKeepLockedMode() = withKeyboard(native = true) { scenario ->
        key(3, 1)
        editor(scenario, "nihao123", composingStart = 0)
        driver.awaitNode { it.contentDescription?.toString() == "你好123" && it.isClickable }
        setState("锁定")
        editor(scenario, "nihao123", composingStart = 0)
        driver.select(scenario, "你好123", "你好123")
        state("锁定")

        setState("关闭")
        key(3, 2)
        setState("锁定")
        driver.select(scenario, "你好", "你好123你好shijie", expectedComposingStart = 7)
        state("锁定")
        driver.select(scenario, "世界", "你好123你好世界")
        state("锁定")
        key(1, 1)
        editor(scenario, "你好123你好世界ａｂｃ", composingStart = 9)
        // The converted spelling is actual input; native engines need not produce an ASCII candidate.
        key(2, 2)
        editor(scenario, "你好123你好世界ａｂｃ")
        state("锁定")
    }

    @Test fun remoteHostRejectionIsNotReportedBackAndRetryRequiresReenablingSingle() = withKeyboard { scenario ->
        var accept = false
        val attempts = mutableListOf<String>()
        scenario.onActivity { activity ->
            activity.editor = object : EditText(activity) {
                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                    val base = super.onCreateInputConnection(outAttrs) ?: return null
                    return object : InputConnectionWrapper(base, false) {
                        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                            attempts += text.toString()
                            return accept && super.commitText(text, newCursorPosition)
                        }
                    }
                }
            }.apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
            activity.setContentView(activity.editor)
            activity.editor.requestFocus()
        }
        awaitActiveEditor(scenario)
        key(2, 1)
        state("单次")
        key(1, 1)
        instrumentation.waitForIdleSync()
        editor(scenario, "")
        // The remote input connection acknowledges dispatch, not the host's commitText result.
        // A known synchronous false is covered by the controller test; this is the real Binder boundary.
        state("关闭")
        scenario.onActivity { assertEquals(listOf("ａbc"), attempts); accept = true }
        key(2, 1)
        state("单次")
        key(1, 1)
        editor(scenario, "ａbc")
        state("关闭")
        scenario.onActivity { assertEquals(listOf("ａbc", "ａbc"), attempts) }
    }

    private fun key(row: Int, column: Int, action: Int = AccessibilityNodeInfo.ACTION_CLICK) = driver.click(action) {
        it.contentDescription?.toString()?.startsWith("Row $row, key $column:") == true
    }

    private fun state(expected: String) {
        val node = driver.awaitNode { it.contentDescription?.toString() == widthDescription && it.stateDescription?.toString() == expected }
        assertEquals(expected != "关闭", node.isSelected)
    }

    private fun setState(expected: String) {
        repeat(3) {
            if (driver.awaitNode { it.contentDescription?.toString() == widthDescription }.stateDescription?.toString() == expected) return
            driver.click { it.contentDescription?.toString() == widthDescription }
            instrumentation.waitForIdleSync()
        }
        state(expected)
    }

    private fun preview(expected: String) {
        driver.awaitNode { it.contentDescription?.toString() == "$expected，点按原样确认" && it.text?.toString() == expected }
    }

    private fun editor(scenario: ActivityScenario<ImeTestEditorActivity>, expected: String, composingStart: Int = -1,
                       caret: Int = expected.length, selectionEnd: Int = caret) {
        val wanted = listOf(expected, caret, selectionEnd, composingStart, if (composingStart >= 0) expected.length else -1)
        var actual: List<Any>? = null
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            scenario.onActivity {
                actual = listOf(it.editor.text.toString(), it.editor.selectionStart, it.editor.selectionEnd,
                    BaseInputConnection.getComposingSpanStart(it.editor.text), BaseInputConnection.getComposingSpanEnd(it.editor.text))
            }
            if (actual == wanted) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(wanted, actual)
    }

    private fun awaitActiveEditor(scenario: ActivityScenario<ImeTestEditorActivity>) {
        var active = false
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            scenario.onActivity {
                active = it.editor.hasFocus() && it.getSystemService(InputMethodManager::class.java).isActive(it.editor)
            }
            if (active) break
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("The new test-owned editor must be the active input target", active)
        driver.awaitKeyboard(scenario) { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
        instrumentation.waitForIdleSync()
    }

    private fun withKeyboard(inline: Boolean = true, native: Boolean = false, body: (ActivityScenario<ImeTestEditorActivity>) -> Unit) {
        val before = prefs.all.toMap()
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val wasEnabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var page: String? = null
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        var keyboardReady = false
        var primaryFailure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                assertTrue(prefs.edit().apply {
                    remove(RemoteKeyboardMode.KEY_SNAPSHOT)
                    remove(SettingsConstants.KEY_SHIFT_SHORTCUTS)
                    remove(SettingsConstants.KEY_CTRL_SHORTCUTS)
                    remove(MultifunctionBarSettings.KEY_BINDINGS)
                    remove(ReplacementSettings.KEY)
                    putBoolean(SettingsConstants.KEY_INLINE_PREEDIT_ENABLED, inline)
                    putBoolean(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, false)
                    putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, false)
                    putString(PinyinSettings.KEY_SCHEME, PinyinScheme.LUNA.schemaId)
                    EngineFeature.entries.forEach { putBoolean(it.key, native && it in setOf(
                        EngineFeature.CHINESE_CONVERSION, EngineFeature.SCRIPT_CONVERSION, EngineFeature.SEGMENTATION)) }
                }.commit())
                val layout = KeyboardLayout("Owned width keyboard", listOf(
                    RowLayout(1f, listOf("abc", "ｶﾞa", "ガa", "1️⃣a", "👩🏽‍💻a").map { KeySlot(1f, it, textBehavior = TextBehavior.AUTO) }),
                    RowLayout(1f, listOf(KeySlot(1f, action = KeyAction.WIDTH_SWAP, longPressAction = KeyAction.WIDTH_SWAP),
                        KeySlot(1f, action = KeyAction.ENTER), KeySlot(1f, action = KeyAction.LEFT),
                        KeySlot(1f, action = KeyAction.BACKSPACE), KeySlot(1f, action = KeyAction.CANDIDATES))),
                    RowLayout(1f, listOf("nihao123", "nihaoshijie", "bＢ", "aＡｶﾞ😀", " ").map { KeySlot(1f, it, textBehavior = TextBehavior.AUTO) })))
                val group = PageGroupManager.create(context, layout.name)
                page = LayoutFileManager.createLayout(context, layout.name, layout)
                PageGroupManager.addPages(context, group, listOf(requireNotNull(page)))
                PageGroupManager.setAppearance(context, group, GroupAppearance(heightDp = 200))
                PageGroupManager.select(context, group)
                PageGroupManager.selectPage(context, requireNotNull(page))
            }
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            ImeTestImeState.enableAndSelect(context, ime, ::shell)
            val owned = ActivityScenario.launch(ImeTestEditorActivity::class.java)
            scenario = owned
            driver.awaitKeyboard(owned) { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
            setState("关闭")
            keyboardReady = true
            editor(owned, "")
            body(owned)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanupImeFixture(primaryFailure, {
                if (keyboardReady) scenario?.let {
                    ImeTestImeState.await(context, ime, enabled = true, selected = ime)
                    driver.awaitKeyboard(it) { node -> node.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
                    setState("关闭")
                }
            }, { scenario?.close() }, {
                ImeTestImeState.restore(context, ime, oldIme, wasEnabled, ::shell)
            }, { page?.let { LayoutFileManager.deleteLayout(context, it) } }, {
                instrumentation.runOnMainSync {
                    assertTrue(prefs.edit().clear().apply { before.forEach { (key, value) -> restoreValue(key, value) } }.commit())
                    assertEquals(before, prefs.all)
                }
            }, { ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags } })
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
            null -> remove(key)
            else -> error("Unsupported preference type")
        }
    }
}
