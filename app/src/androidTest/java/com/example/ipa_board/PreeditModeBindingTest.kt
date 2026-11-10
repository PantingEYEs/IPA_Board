package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.Candidate
import com.example.ipa_board.ime.ImeChromeView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Owned-view and configuration checks; no editor, clipboard or system settings are changed. */
class PreeditModeBindingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test fun independentTapHoldSwipePopupAndShortcutBindingsSurviveExport() {
        val action = KeyAction.PREEDIT_MODE
        val popup = PopupConfiguration.default().copy(items = listOf(
            PopupItem(PopupPoint(28f, 28f), 20f, LongPressItem(action = action))))
        val layout = KeyboardLayout("Preedit mappings", listOf(RowLayout(1f, listOf(
            KeySlot(1f, "ɑ😀", longPressAction = action, swipeLeftText = "‹", swipeRightAction = action),
            KeySlot(2f, action = action, longPressItems = listOf(LongPressItem(text = "β")), swipeLeftAction = action),
            KeySlot(1f, "γ", multifunctionMode = MultifunctionMode.POPUP, popupConfiguration = popup)
        ))))
        val json = layout.toJson()
        val slots = JSONObject(json).getJSONArray("rows").getJSONObject(0).getJSONArray("slots")
        assertEquals("preedit_mode", slots.getJSONObject(0).getString("longPressAction"))
        assertEquals("preedit_mode", slots.getJSONObject(0).getString("swipeRightAction"))
        assertEquals("preedit_mode", slots.getJSONObject(1).getString("action"))
        assertEquals("preedit_mode", slots.getJSONObject(1).getString("swipeLeftAction"))
        assertEquals(layout, KeyboardLayout.fromJson(json))
        for (shortcuts in listOf(mapOf("m" to action, "c" to KeyAction.COPY),
            mapOf("m" to KeyAction.PASTE, "c" to action))) {
            assertEquals(shortcuts, SettingsConstants.parseShortcutsJson(SettingsConstants.serializeShortcutsJson(shortcuts)))
        }
        // Existing layouts do not acquire the optional action during a round trip.
        val old = KeyboardLayout.fromJson("""{"name":"old","rows":[{"heightWeight":1,"slots":[{"widthWeight":1,"text":"ɑ"}]}]}""")
        assertEquals(KeySlot(1f, "ɑ"), KeyboardLayout.fromJson(old.toJson()).rows.single().slots.single())
    }

    @Test fun toolbarPersistenceAndStateUseTheSameDispatchAsOtherBindings() {
        val name = "preedit-binding-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(ignored: String, mode: Int) = target.getSharedPreferences(name, mode)
        }
        try {
            val slot = MultifunctionBarSlot.LEFT_FIRST
            MultifunctionBarSettings.save(context, slot, MultifunctionBarBinding(KeyAction.PREEDIT_MODE))
            val restored = MultifunctionBarSettings.load(context)
            assertEquals(KeyAction.PREEDIT_MODE, restored.getValue(slot).asKeySlot().action)
            assertEquals(MultifunctionBarSettings.defaults().getValue(MultifunctionBarSlot.RIGHT_THIRD),
                restored.getValue(MultifunctionBarSlot.RIGHT_THIRD))
            instrumentation.runOnMainSync {
                val chrome = ImeChromeView(target)
                chrome.setBarBindings(restored)
                val button = chrome.multifunctionBar.buttonAt(slot)
                assertEquals("内联", button.text.toString())
                assertEquals("预编辑模式", button.contentDescription)
                assertEquals("内联", button.stateDescription)
                var toggles = 0
                chrome.onPreeditModeToggle = { toggles++; chrome.setInlinePreeditEnabled(false) }
                button.performClick()
                assertEquals(1, toggles)
                assertEquals("非内联", button.text.toString())
                assertEquals("非内联", button.stateDescription)
                assertTrue(button.isSelected)
                val dispatched = mutableListOf<KeyAction>()
                chrome.onKeyBinding = { dispatched += it.action }
                button.performClick()
                assertEquals(listOf(KeyAction.PREEDIT_MODE), dispatched)
                assertEquals("Central dispatch must not also invoke the fallback", 1, toggles)
                chrome.setInlinePreeditEnabled(true)
                assertEquals("内联", button.text.toString())
                assertFalse(button.isSelected)

                val editor = FunctionBindingEditor(target, LongPressItem(text = "discard"))
                val spinner = editor.getChildAt(0) as Spinner
                spinner.setSelection(KeyAction.entries.indexOf(KeyAction.PREEDIT_MODE))
                assertEquals(LongPressItem(action = KeyAction.PREEDIT_MODE), editor.bindingOrNull())
            }
        } finally {
            target.deleteSharedPreferences(name)
        }
    }

    @Test fun renderedTapAccessibleHoldAndQuickSwipesDispatchExactlyOnce() {
        instrumentation.runOnMainSync {
            val host = LinearLayout(target)
            val action = KeyAction.PREEDIT_MODE
            val slot = KeySlot(1f, action = action, longPressAction = action,
                swipeLeftAction = action, swipeRightAction = action)
            val events = mutableListOf<String>()
            KeyboardRenderer.render(target, host, KeyboardLayout("Mode", listOf(RowLayout(1f, listOf(slot)))),
                200, Color.WHITE, showKeyPreview = true,
                onKeyClick = { _, _, key -> events += "tap:${key.action}" },
                onKeyLongItemClick = { _, _, _, item -> events += "hold:${item.action}" },
                onKeyQuickSwipeItemClick = { _, _, _, item -> events += "swipe:${item.action}" })
            measure(host)
            val key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            key.performClick()
            key.performLongClick()
            assertEquals(listOf("tap:$action", "hold:$action"), events)
            assertNull("Mode changes must never repeat while held", key.repeatForItem?.invoke(LongPressItem(action = action)))
            for (direction in listOf(-1, 1)) {
                val start = key.width / 2f
                val end = start + direction * 40f * target.resources.displayMetrics.density
                val down = SystemClock.uptimeMillis()
                for ((step, type) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
                    val event = MotionEvent.obtain(down, down + step * 10L, type,
                        if (step == 0) start else end, key.height / 2f, 0)
                    try { key.dispatchTouchEvent(event) } finally { event.recycle() }
                }
            }
            assertEquals(listOf("tap:$action", "hold:$action", "swipe:$action", "swipe:$action"), events)
        }
    }

    @Test fun nonInlinePreeditKeepsHeightAndTextStableAndOnlyConfirmsWhenNonempty() {
        instrumentation.runOnMainSync {
            val chrome = ImeChromeView(target)
            chrome.setKeyboardHeight(240)
            chrome.render("", emptyList(), "Ready", 1)
            measure(chrome)
            val inlineHeight = chrome.height
            assertNull(chrome.findViewWithTag<TextView>("ime_preedit_text"))
            chrome.setInlinePreeditEnabled(false)
            measure(chrome)
            val detachedHeight = chrome.height
            assertTrue(detachedHeight > inlineHeight)
            assertEquals(240, chrome.keyboardHost.height)
            var confirmations = 0
            chrome.onLiteral = { confirmations++ }
            val preedit = chrome.findViewWithTag<TextView>("ime_preedit_text")!!
            assertEquals("预编辑", preedit.hint.toString())
            assertFalse(preedit.isEnabled)
            preedit.performClick()
            assertEquals(0, confirmations)

            val candidate = Candidate("零", "synthetic")
            chrome.render("a😀b", listOf(candidate), "Ready", 2, preeditDisplay = "ａ😀b", preeditCaret = 3)
            measure(chrome)
            assertEquals(detachedHeight, chrome.height)
            assertEquals(240, chrome.keyboardHost.height)
            assertEquals("ａ😀b", preedit.text.toString())
            assertEquals("光标在第 2 个字符后", preedit.stateDescription)
            assertEquals("ａ😀b，点按原样确认", preedit.contentDescription)
            assertTrue(preedit.isEnabled)
            preedit.performClick()
            assertEquals(1, confirmations)
            chrome.render("a😀b", listOf(candidate), "Ready", 2, preeditDisplay = "ａ😀b", preeditCaret = 2)
            assertEquals("A caret must not divide the surrogate pair", "光标在第 1 个字符后", preedit.stateDescription)
            assertEquals("ａ😀b", preedit.text.toString())

            chrome.render("", emptyList(), "Ready", 3)
            measure(chrome)
            assertEquals(detachedHeight, chrome.height)
            assertEquals("", preedit.text.toString())
            assertFalse(preedit.isClickable)
            preedit.performClick()
            assertEquals(1, confirmations)
            chrome.setInlinePreeditEnabled(true)
            measure(chrome)
            assertEquals(inlineHeight, chrome.height)
            assertNull(chrome.findViewWithTag<TextView>("ime_preedit_text"))
        }
    }

    @Test fun editorControllerLeavesModeActionsForTheImeWithoutWritingText() {
        instrumentation.runOnMainSync {
            var writes = 0
            val connection = object : BaseInputConnection(View(target), true) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { writes++; return true }
                override fun sendKeyEvent(event: KeyEvent?): Boolean { writes++; return true }
            }
            val controller = KeyboardInputController()
            assertFalse(controller.handle(KeySlot(1f, action = KeyAction.PREEDIT_MODE), connection, null))
            assertFalse(controller.executeAction(KeyAction.PREEDIT_MODE, connection, null, 0))
            assertEquals(0, writes)
        }
    }

    private fun measure(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
