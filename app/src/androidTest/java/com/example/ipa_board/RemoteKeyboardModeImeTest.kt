package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
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

/** Actual service and test-owned editor; run only through the external backup workflow. */
class RemoteKeyboardModeImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val driver = ImeTestDriver()
    private val context get() = instrumentation.targetContext
    private val prefs get() = EngineSettings.preferences(context)

    @Test fun boundTogglePreservesCompositionAndRestoresStatesAfterANewInputSession() {
        val layout = KeyboardLayout("Remote mode E2E", listOf(RowLayout(1f, listOf(
            KeySlot(1f, action = KeyAction.REMOTE_KEYBOARD),
            KeySlot(1f, "ɑ中😀"),
            KeySlot(1f, "123"),
            KeySlot(1f, action = KeyAction.ENTER)
        ))))
        val initialEnabled = setOf(EngineFeature.CHINESE_CONVERSION, EngineFeature.JAPANESE_PREDICTION)
        withKeyboard(layout, initialEnabled) { scenario ->
            modeState(false)
            key(2)
            editorText(scenario, "ɑ中😀", composing = true)
            driver.click { it.contentDescription?.toString() == "Remote Keyboard Mode" }
            modeState(true)
            assertTrue(driver.awaitNode { it.contentDescription?.toString() == "Remote Keyboard Mode" }.isSelected)
            assertTrue(prefs.contains(RemoteKeyboardMode.KEY_SNAPSHOT))
            assertTrue(EngineFeature.entries.all { !EngineSettings.enabled(prefs, it) })
            editorText(scenario, "ɑ中😀", composing = true)
            assertFalse(driver.awaitNode { it.contentDescription?.toString() == "Expand" }.isEnabled)
            key(3)
            editorText(scenario, "ɑ中😀123", composing = true)
            key(4)
            editorText(scenario, "ɑ中😀123", composing = false)

            // The mode applies its override once. A user's later choice remains in effect.
            instrumentation.runOnMainSync {
                assertTrue(prefs.edit().putBoolean(EngineFeature.ENGLISH_COMPLETION.key, true).commit())
            }
            instrumentation.waitForIdleSync()
            modeState(true)
            assertTrue(EngineSettings.enabled(prefs, EngineFeature.ENGLISH_COMPLETION))
            scenario.onActivity {
                it.getSystemService(InputMethodManager::class.java).restartInput(it.editor)
            }
            driver.awaitKeyboard(scenario) { isKey(it, 1) }
            modeState(true)
            assertTrue("Reopening input must not apply the override a second time",
                EngineSettings.enabled(prefs, EngineFeature.ENGLISH_COMPLETION))
            editorText(scenario, "ɑ中😀123", composing = false)

            key(1)
            modeState(false)
            assertFalse(driver.awaitNode { it.contentDescription?.toString() == "Remote Keyboard Mode" }.isSelected)
            assertFalse(prefs.contains(RemoteKeyboardMode.KEY_SNAPSHOT))
            assertEquals("Restore the captured choices, including engines originally disabled",
                initialEnabled, EngineFeature.entries.filter { EngineSettings.enabled(prefs, it) }.toSet())
            editorText(scenario, "ɑ中😀123", composing = false)
        }
    }

    @Test fun tapAndLongPressRemainIndependentWhenEitherBindingTogglesTheMode() {
        val layout = KeyboardLayout("Remote hold E2E", listOf(RowLayout(1f, listOf(
            KeySlot(1f, "ɑ", longPressAction = KeyAction.REMOTE_KEYBOARD),
            KeySlot(1f, action = KeyAction.REMOTE_KEYBOARD,
                longPressItems = listOf(LongPressItem(text = "β"))),
            KeySlot(1f, action = KeyAction.ENTER)
        ))))
        withKeyboard(layout) { scenario ->
            modeState(false, column = 2)
            key(1)
            editorText(scenario, "ɑ", composing = true)
            key(1, AccessibilityNodeInfo.ACTION_LONG_CLICK)
            modeState(true, column = 2)
            editorText(scenario, "ɑ", composing = true)
            key(1)
            editorText(scenario, "ɑɑ", composing = true)
            key(1, AccessibilityNodeInfo.ACTION_LONG_CLICK)
            modeState(false, column = 2)
            key(2, AccessibilityNodeInfo.ACTION_LONG_CLICK)
            modeState(false, column = 2)
            editorText(scenario, "ɑɑβ", composing = true)
            key(2)
            modeState(true, column = 2)
            editorText(scenario, "ɑɑβ", composing = true)
            key(3)
            editorText(scenario, "ɑɑβ", composing = false)
            key(2)
            modeState(false, column = 2)
        }
    }

    @Test fun modifierShortcutsAndQuickSwipeInvokeTheModeWithoutTypingTheirKeys() {
        val layout = KeyboardLayout("Remote shortcut E2E", listOf(RowLayout(1f, listOf(
            KeySlot(1f, action = KeyAction.REMOTE_KEYBOARD),
            KeySlot(1f, action = KeyAction.SHIFT),
            KeySlot(1f, action = KeyAction.CTRL),
            KeySlot(1f, "r"),
            KeySlot(1f, "s", swipeLeftAction = KeyAction.REMOTE_KEYBOARD,
                swipeRightAction = KeyAction.REMOTE_KEYBOARD)
        ))))
        val shortcut = SettingsConstants.serializeShortcutsJson(mapOf("r" to KeyAction.REMOTE_KEYBOARD))
        withKeyboard(layout, shortcutJson = shortcut) { scenario ->
            key(2)
            key(4)
            modeState(true)
            assertFalse(driver.awaitNode { isKey(it, 2) }.isSelected)
            editorText(scenario, "", composing = false)
            key(3)
            key(4)
            modeState(false)
            assertFalse(driver.awaitNode { isKey(it, 3) }.isSelected)
            editorText(scenario, "", composing = false)

            quickSwipe(column = 5, left = true)
            modeState(true)
            editorText(scenario, "", composing = false)
            quickSwipe(column = 5, left = false)
            modeState(false)
            editorText(scenario, "", composing = false)
            key(5)
            editorText(scenario, "s", composing = true)
        }
    }

    private fun isKey(node: AccessibilityNodeInfo, column: Int) =
        node.contentDescription?.toString()?.startsWith("Row 1, key $column:") == true

    private fun key(column: Int, action: Int = AccessibilityNodeInfo.ACTION_CLICK) =
        driver.click(action) { isKey(it, column) }

    private fun modeState(enabled: Boolean, column: Int = 1) {
        val node = driver.awaitNode {
            isKey(it, column) && it.stateDescription?.toString() ==
                if (enabled) "Remote keyboard mode on" else "Remote keyboard mode off"
        }
        assertEquals(enabled, node.isSelected)
        assertEquals(enabled, RemoteKeyboardMode(prefs).active)
    }

    private fun editorText(scenario: ActivityScenario<ImeTestEditorActivity>, expected: String, composing: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var actual = ""
        var composingStart = -1
        do {
            scenario.onActivity {
                actual = it.editor.text.toString()
                composingStart = BaseInputConnection.getComposingSpanStart(it.editor.text)
            }
            if (actual == expected && (composingStart >= 0) == composing) return
            Thread.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("Changing modes must preserve actual editor text", expected, actual)
        assertEquals("Changing modes must preserve the composing span", composing, composingStart >= 0)
    }

    private fun quickSwipe(column: Int, left: Boolean) {
        val node = driver.awaitNode { isKey(it, column) && it.isVisibleToUser }
        val bounds = Rect().also(node::getBoundsInScreen)
        val startX = if (left) bounds.right - bounds.width() / 4f else bounds.left + bounds.width() / 4f
        val endX = startX + (if (left) -1 else 1) * 36f * context.resources.displayMetrics.density
        val y = bounds.exactCenterY()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, at: Long) {
            val event = MotionEvent.obtain(down, at, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try { assertTrue(ui.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, startX, down)
        send(MotionEvent.ACTION_MOVE, endX, down + 20)
        send(MotionEvent.ACTION_UP, endX, down + 40)
        instrumentation.waitForIdleSync()
    }

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun withKeyboard(
        layout: KeyboardLayout,
        enabled: Set<EngineFeature> = emptySet(),
        shortcutJson: String? = null,
        body: (ActivityScenario<ImeTestEditorActivity>) -> Unit
    ) {
        val groupSnapshot = PageGroupTestState(context)
        val touchedKeys = EngineFeature.entries.map { it.key } + listOf(RemoteKeyboardMode.KEY_SNAPSHOT,
            SettingsConstants.KEY_SHIFT_SHORTCUTS, SettingsConstants.KEY_CTRL_SHORTCUTS)
        val before = touchedKeys.associateWith { prefs.all[it] }
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val alreadyEnabled = context.getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var page: String? = null
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        try {
            assertTrue(prefs.edit().apply {
                remove(RemoteKeyboardMode.KEY_SNAPSHOT)
                EngineFeature.entries.forEach { putBoolean(it.key, it in enabled) }
                putString(SettingsConstants.KEY_SHIFT_SHORTCUTS, shortcutJson)
                putString(SettingsConstants.KEY_CTRL_SHORTCUTS, shortcutJson)
            }.commit())
            ui.serviceInfo = ui.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            page = LayoutFileManager.createLayout(context, layout.name, layout)
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(page))
            PageGroupManager.selectPage(context, page)
            shell("ime enable $ime")
            shell("ime set $ime")
            val editor = ActivityScenario.launch(ImeTestEditorActivity::class.java)
            scenario = editor
            driver.awaitKeyboard(editor) { isKey(it, 1) }
            body(editor)
        } finally {
            try {
                scenario?.close()
            } finally {
                try {
                    shell("ime set $oldIme")
                    if (!alreadyEnabled) shell("ime disable $ime")
                } finally {
                    try {
                        page?.let { LayoutFileManager.deleteLayout(context, it) }
                    } finally {
                        try {
                            groupSnapshot.close()
                        } finally {
                            try {
                                assertTrue("Test engine choices, shortcuts and recovery snapshot must be restored",
                                    prefs.edit().apply {
                                        before.forEach { (key, value) -> restoreValue(key, value) }
                                    }.commit())
                            } finally {
                                ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            else -> error("Unexpected preference type for test-owned engine setting $key")
        }
    }
}
