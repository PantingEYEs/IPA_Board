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

/** Original screen pointer crosses from an actual IME key into its non-touchable popup. */
class PopupActionsImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val driver = ImeTestDriver()
    private val context get() = instrumentation.targetContext
    private val prefs get() = EngineSettings.preferences(context)
    private val density get() = context.resources.displayMetrics.density
    private var lastGesture = "No gesture injected"

    private val config = PopupConfiguration(
        vertices = listOf(PopupPoint(0f, 0f), PopupPoint(160f, 0f), PopupPoint(160f, 80f), PopupPoint(0f, 80f)),
        anchor = PopupPoint(80f, 140f),
        items = listOf(
            PopupItem(PopupPoint(40f, 40f), 24f, LongPressItem("β😀")),
            PopupItem(PopupPoint(120f, 40f), 24f, LongPressItem(action = KeyAction.BACKSPACE))
        )
    )
    private val layout = KeyboardLayout("Popup real pointer", listOf(RowLayout(1f, listOf(
        KeySlot(1f, "a", longPressItems = listOf(LongPressItem("CLASSIC_HOLD")),
            swipeLeftText = "CLASSIC_LEFT", swipeRightText = "CLASSIC_RIGHT",
            multifunctionMode = MultifunctionMode.POPUP, popupConfiguration = config),
        KeySlot(1f, action = KeyAction.ENTER),
        KeySlot(1f, "classic", longPressItems = listOf(LongPressItem("β😀"),
            LongPressItem(action = KeyAction.BACKSPACE)))
    ))))

    @Test fun originalPointerSelectsPopupTextAndFunctionWithoutLosingEditorFocus() = withKeyboard { scenario ->
        holdAndSlide(PopupPoint(40f, 40f))
        editorText(scenario, "β😀", composing = true)
        holdAndSlide(PopupPoint(120f, 40f))
        val popupResult = awaitEditorChange(scenario, EditorSnapshot("β😀", true))
        driver.click { isKey(it, 2) }
        editorText(scenario, popupResult.text, composing = false)
        assertEditorFocus(scenario)

        // This issue preserves Classic semantics, including a separately recorded Unicode
        // backspace defect. Compare observed text and composition, without encoding the
        // defective surrogate result as the expected behavior or silently fixing it here.
        scenario.onActivity { it.editor.setText(""); it.editor.setSelection(0) }
        classicHold(itemIndex = 0)
        editorText(scenario, "β😀", composing = true)
        classicHold(itemIndex = 1)
        val classicResult = awaitEditorChange(scenario, EditorSnapshot("β😀", true))
        assertEquals("Popup must execute the same BACKSPACE behavior as the physical Classic key",
            classicResult, popupResult)
        driver.click { isKey(it, 2) }
        editorText(scenario, classicResult.text, composing = false)
        assertEditorFocus(scenario)
    }

    private fun assertEditorFocus(scenario: ActivityScenario<ImeTestEditorActivity>) {
        scenario.onActivity {
            assertTrue("Popup must preserve the owned editor's input focus", it.editor.hasFocus())
            assertTrue("The preview must not take focus from its editor window", it.editor.hasWindowFocus())
        }
    }

    @Test fun leavingPopupCancelsBeforeReturningToSourceOrItemAndTapStillWorks() = withKeyboard { scenario ->
        // Cancel on an outside move while still held, then return all the way to the source.
        canceledReturn(returnToItem = false)
        editorText(scenario, "", composing = false)
        // A second fresh gesture also cannot reactivate on returning directly to the item.
        canceledReturn(returnToItem = true)
        editorText(scenario, "", composing = false)
        driver.click { isKey(it, 1) }
        editorText(scenario, "a", composing = true)

        // Retained Classic bindings remain stored but cannot run as quick Popup-mode swipes.
        val source = sourceBounds()
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, source.exactCenterX(), source.exactCenterY())
        var ended = false
        try {
            inject(down, MotionEvent.ACTION_MOVE, source.exactCenterX() + 36f * density, source.exactCenterY())
            inject(down, MotionEvent.ACTION_UP, source.exactCenterX() + 36f * density, source.exactCenterY())
            ended = true
        } finally {
            if (!ended) cancelForCleanup(down, source.exactCenterX(), source.exactCenterY())
        }
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            assertFalse("Popup mode must not invoke its retained Classic hold or swipe text",
                it.editor.text.contains("CLASSIC"))
        }
    }

    private fun isKey(node: AccessibilityNodeInfo, column: Int): Boolean =
        node.contentDescription?.toString()?.startsWith("Row 1, key $column:") == true

    private fun sourceBounds(column: Int = 1): Rect {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var previous: Rect? = null
        var stableSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            // Resolve the owned node again: IME entry animation and redraws invalidate
            // the coordinates obtained before this fixture's layout is fully visible.
            val node = driver.find { isKey(it, column) && it.isVisibleToUser }
            val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
            val now = SystemClock.uptimeMillis()
            if (bounds != null && !bounds.isEmpty) {
                if (bounds != previous) { previous = bounds; stableSince = now }
                else if (now - stableSince >= 200L) return bounds
            } else { previous = null; stableSince = now }
            SystemClock.sleep(40L)
        }
        error("Owned IME key $column did not reach stable nonempty screen bounds within 5s; last=$previous")
    }

    private fun screenPoint(source: Rect, point: PopupPoint): Pair<Float, Float> =
        (source.exactCenterX() + (point.x - config.anchor.x) * density) to
            (source.exactCenterY() + (point.y - config.anchor.y) * density)

    private fun holdAndSlide(item: PopupPoint) {
        val source = sourceBounds()
        val target = screenPoint(source, item)
        lastGesture = "Popup source=$source target=(${target.first}, ${target.second}) item=$item"
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, source.exactCenterX(), source.exactCenterY())
        var ended = false
        try {
            SystemClock.sleep(KeyboardGestureSettings.longPressTimeoutMs(context).toLong() + 120L)
            inject(down, MotionEvent.ACTION_MOVE, target.first, target.second)
            inject(down, MotionEvent.ACTION_UP, target.first, target.second)
            ended = true
        } finally {
            if (!ended) cancelForCleanup(down, target.first, target.second)
        }
        instrumentation.waitForIdleSync()
    }

    private fun classicHold(itemIndex: Int) {
        val source = sourceBounds(column = 3)
        var step = 0f
        instrumentation.runOnMainSync {
            step = KeyPreviewMetrics.classicQueue(context, source.height(),
                layout.rows.single().slots[2].effectiveLongPressItems, 0).items.first().radius * 2f * density
        }
        val endX = source.exactCenterX() + itemIndex * step
        val y = source.exactCenterY()
        lastGesture = "Classic source=$source end=($endX, $y) itemIndex=$itemIndex"
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, source.exactCenterX(), y)
        var ended = false
        try {
            SystemClock.sleep(KeyboardGestureSettings.longPressTimeoutMs(context).toLong() + 120L)
            if (itemIndex != 0) inject(down, MotionEvent.ACTION_MOVE, endX, y)
            inject(down, MotionEvent.ACTION_UP, endX, y)
            ended = true
        } finally {
            if (!ended) cancelForCleanup(down, endX, y)
        }
        instrumentation.waitForIdleSync()
    }

    private fun canceledReturn(returnToItem: Boolean) {
        val source = sourceBounds()
        val target = screenPoint(source, PopupPoint(40f, 40f))
        val outside = screenPoint(source, PopupPoint(190f, 40f))
        val end = if (returnToItem) target else source.exactCenterX() to source.exactCenterY()
        lastGesture = "Cancel source=$source target=$target outside=$outside end=$end"
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, source.exactCenterX(), source.exactCenterY())
        var ended = false
        try {
            SystemClock.sleep(KeyboardGestureSettings.longPressTimeoutMs(context).toLong() + 120L)
            inject(down, MotionEvent.ACTION_MOVE, target.first, target.second)
            inject(down, MotionEvent.ACTION_MOVE, outside.first, outside.second)
            inject(down, MotionEvent.ACTION_MOVE, end.first, end.second)
            inject(down, MotionEvent.ACTION_UP, end.first, end.second)
            ended = true
        } finally {
            if (!ended) cancelForCleanup(down, end.first, end.second)
        }
        instrumentation.waitForIdleSync()
    }

    /** Cleanup must not inject an orphan CANCEL after UP, or mask a failed test assertion. */
    private fun cancelForCleanup(down: Long, x: Float, y: Float) {
        runCatching { inject(down, MotionEvent.ACTION_CANCEL, x, y, assertAccepted = false) }
    }

    private fun inject(down: Long, action: Int, x: Float, y: Float, assertAccepted: Boolean = true) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            val accepted = ui.injectInputEvent(event, true)
            if (assertAccepted) assertTrue("System must accept owned IME event ${MotionEvent.actionToString(action)} at ($x, $y); $lastGesture", accepted)
        }
        finally { event.recycle() }
    }

    private data class EditorSnapshot(val text: String, val composing: Boolean)

    private fun editorSnapshot(scenario: ActivityScenario<ImeTestEditorActivity>): EditorSnapshot {
        var snapshot = EditorSnapshot("", false)
        scenario.onActivity {
            snapshot = EditorSnapshot(it.editor.text.toString(),
                BaseInputConnection.getComposingSpanStart(it.editor.text) >= 0)
        }
        return snapshot
    }

    private fun awaitEditorChange(scenario: ActivityScenario<ImeTestEditorActivity>, before: EditorSnapshot): EditorSnapshot {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var actual = editorSnapshot(scenario)
        while (actual == before && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(40L)
            actual = editorSnapshot(scenario)
        }
        assertNotEquals("The selected function must reach the real editor; $lastGesture", before, actual)
        return actual
    }

    private fun editorText(scenario: ActivityScenario<ImeTestEditorActivity>, expected: String, composing: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var actual = ""
        var composingStart = -1
        do {
            scenario.onActivity {
                actual = it.editor.text.toString()
                composingStart = BaseInputConnection.getComposingSpanStart(it.editor.text)
            }
            if (actual == expected && (composingStart >= 0) == composing) return
            SystemClock.sleep(40L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("Original-pointer popup gesture must reach the real editor exactly once; $lastGesture", expected, actual)
        assertEquals("Popup input must use the same composing behavior as keyboard input", composing, composingStart >= 0)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun withKeyboard(body: (ActivityScenario<ImeTestEditorActivity>) -> Unit) {
        val groups = PageGroupTestState(context)
        val keys = EngineFeature.entries.map { it.key } + listOf(RemoteKeyboardMode.KEY_SNAPSHOT,
            SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS)
        val before = keys.associateWith { prefs.all[it] }
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val wasEnabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var page: String? = null
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        try {
            assertTrue(prefs.edit().apply {
                EngineFeature.entries.forEach { putBoolean(it.key, false) }
                remove(RemoteKeyboardMode.KEY_SNAPSHOT)
                putInt(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS, SettingsConstants.MIN_LONG_PRESS_TIMEOUT_MS)
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
            driver.awaitKeyboard(editor) { isKey(it, 3) && it.contentDescription?.toString()?.contains("classic") == true }
            body(editor)
        } finally {
            try { scenario?.close() } finally {
                try {
                    shell("ime set $oldIme")
                    if (!wasEnabled) shell("ime disable $ime")
                } finally {
                    try { page?.let { LayoutFileManager.deleteLayout(context, it) } } finally {
                        try { groups.close() } finally {
                            try {
                                assertTrue("Popup fixture must restore engine and gesture preferences",
                                    prefs.edit().apply { before.forEach { (key, value) -> restoreValue(key, value) } }.commit())
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
            is Int -> putInt(key, value)
            else -> error("Unexpected preference type in Popup fixture: $key")
        }
    }
}
