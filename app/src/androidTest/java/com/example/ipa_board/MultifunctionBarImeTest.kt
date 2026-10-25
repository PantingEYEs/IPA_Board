package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.RemoteKeyboardMode
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic text in our own editor; device runs require regression.py's external snapshot. */
class MultifunctionBarImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val driver = ImeTestDriver()
    private val context get() = instrumentation.targetContext

    @Test fun configuredAndLiveReplacedBindingsUseTheExistingServiceActionsWithoutLosingComposition() {
        withKeyboard(mapOf(
            MultifunctionBarSlot.LEFT_FIRST to MultifunctionBarBinding(KeyAction.TEXT, "ɑ中😀"),
            MultifunctionBarSlot.LEFT_SECOND to MultifunctionBarBinding(KeyAction.ENGINE_DASHBOARD),
            MultifunctionBarSlot.LEFT_THIRD to MultifunctionBarBinding(KeyAction.SHIFT),
            MultifunctionBarSlot.RIGHT_THIRD to MultifunctionBarBinding(KeyAction.BACKSPACE)
        )) { scenario ->
            if (driver.awaitNode { it.contentDescription?.toString() == "全角 / 半角转换" }.isSelected) click("全角 / 半角转换")
            click("ɑ中😀")
            editor(scenario, "ɑ中😀")
            driver.click { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
            editor(scenario, "ɑ中😀123456789")
            repeat(9) { click("Backspace") }
            editor(scenario, "ɑ中😀")
            click("Engine Dashboard")
            driver.awaitNode { it.text?.toString() == "Engine Dashboard" }
            editor(scenario, "ɑ中😀")
            click("Return to Keyboard")
            click("全角 / 半角转换")
            assertTrue(driver.awaitNode { it.contentDescription?.toString() == "全角 / 半角转换" }.isSelected)
            instrumentation.runOnMainSync {
                MultifunctionBarSettings.save(context, MultifunctionBarSlot.LEFT_FIRST,
                    MultifunctionBarBinding(KeyAction.TEXT, "Ab １", TextBehavior.AUTO))
            }
            driver.awaitNode { it.contentDescription?.toString() == "Ab １" }
            editor(scenario, "ɑ中😀")
            click("Shift / Uppercase")
            click("Ab １")
            editor(scenario, "ɑ中😀ＡＢ　1")
            assertFalse(driver.awaitNode { it.contentDescription?.toString() == "Shift / Uppercase" }.isSelected)
            click("Engine Dashboard")
            driver.awaitNode { it.text?.toString() == "Engine Dashboard" }
            // Move another width-toggle into a different position while a panel is open.
            instrumentation.runOnMainSync {
                MultifunctionBarSettings.save(context, MultifunctionBarSlot.LEFT_THIRD,
                    MultifunctionBarBinding(KeyAction.WIDTH_SWAP))
            }
            editor(scenario, "ɑ中😀ＡＢ　1")
            click("全角 / 半角转换")
            assertFalse(driver.awaitNode { it.contentDescription?.toString() == "全角 / 半角转换" }.isSelected)
            driver.awaitNode { it.text?.toString() == "Engine Dashboard" }
            // The existing width action repaints pending input from its original encoding.
            editor(scenario, "ɑ中😀AB １")
            click("Return to Keyboard")
            driver.click { it.contentDescription?.toString()?.startsWith("Row 1, key 2:") == true }
            editor(scenario, "ɑ中😀AB １", composing = false)
        }
    }

    @Test fun continuousDeleteToolbarBindingStopsOnReleaseWithoutAnExtraDeletion() {
        withKeyboard(mapOf(MultifunctionBarSlot.LEFT_FIRST to MultifunctionBarBinding(KeyAction.REPEAT_BACKSPACE))) { scenario ->
            driver.click { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
            editor(scenario, "123456789")
            val node = driver.awaitNode { it.contentDescription?.toString() == "Continuous Delete" && it.isVisibleToUser }
            val rect = Rect().also(node::getBoundsInScreen)
            val down = SystemClock.uptimeMillis()
            fun event(action: Int) {
                val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { assertTrue(ui.injectInputEvent(motion, true)) } finally { motion.recycle() }
            }
            event(MotionEvent.ACTION_DOWN)
            try {
                val deadline = SystemClock.uptimeMillis() + KeyboardGestureSettings.longPressTimeoutMs(context) + 2000
                var length = 9
                while (length >= 8 && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { length = it.editor.text.length }
                    SystemClock.sleep(10)
                }
                assertTrue("Holding the toolbar binding must repeatedly delete through the service", length < 8)
            } finally {
                event(MotionEvent.ACTION_UP)
            }
            var afterRelease = ""
            scenario.onActivity { afterRelease = it.editor.text.toString() }
            SystemClock.sleep(180) // More than one repeat interval: verify the timer has actually stopped.
            scenario.onActivity { assertEquals(afterRelease, it.editor.text.toString()) }
            assertTrue("A short hold must leave text so stopping is observable", afterRelease.isNotEmpty())
        }
    }

    private fun click(description: String) = driver.click { it.contentDescription?.toString() == description }

    private fun editor(scenario: ActivityScenario<ImeTestEditorActivity>, expected: String, composing: Boolean = true) {
        var actual = ""
        var hasComposition = false
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            scenario.onActivity {
                actual = it.editor.text.toString()
                hasComposition = BaseInputConnection.getComposingSpanStart(it.editor.text) >= 0
            }
            if (expected == actual && composing == hasComposition) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, actual)
        assertEquals(composing, hasComposition)
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private fun withKeyboard(bindings: Map<MultifunctionBarSlot, MultifunctionBarBinding>, body: (ActivityScenario<ImeTestEditorActivity>) -> Unit) {
        val groupSnapshot = PageGroupTestState(context)
        val prefs = EngineSettings.preferences(context)
        val keys = EngineFeature.entries.map { it.key } + listOf(RemoteKeyboardMode.KEY_SNAPSHOT, MultifunctionBarSettings.KEY_BINDINGS)
        val before = keys.associateWith { prefs.all[it] }
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val alreadyEnabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var filename: String? = null
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        try {
            instrumentation.runOnMainSync {
                assertTrue(prefs.edit().remove(MultifunctionBarSettings.KEY_BINDINGS).remove(RemoteKeyboardMode.KEY_SNAPSHOT)
                    .apply { EngineFeature.entries.forEach { putBoolean(it.key, false) } }.commit())
                bindings.forEach { (slot, binding) -> MultifunctionBarSettings.save(context, slot, binding) }
            }
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            val layout = KeyboardLayout("Bar fixture", listOf(RowLayout(1f, listOf(
                KeySlot(1f, "123456789"), KeySlot(1f, action = KeyAction.ENTER)))))
            filename = LayoutFileManager.createLayout(context, layout.name, layout)
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(filename))
            PageGroupManager.selectPage(context, filename)
            shell("ime enable $ime")
            shell("ime set $ime")
            val editor = ActivityScenario.launch(ImeTestEditorActivity::class.java)
            scenario = editor
            driver.awaitKeyboard(editor) { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
            body(editor)
        } finally {
            try { scenario?.close() } finally {
                try { shell("ime set $oldIme"); if (!alreadyEnabled) shell("ime disable $ime") } finally {
                    try { filename?.let { LayoutFileManager.deleteLayout(context, it) } } finally {
                        try { groupSnapshot.close() } finally {
                            try {
                                assertTrue(prefs.edit().apply { before.forEach { (key, value) -> restoreValue(key, value) } }.commit())
                            } finally { ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags } }
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
            else -> error("Unexpected test setting type: $key")
        }
    }
}
