package com.example.ipa_board

import android.content.Context
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class KeyboardInputControllerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun key(action: KeyAction) = KeySlot(1f, action = action)
    private fun text(value: String) = KeySlot(1f, value)

    private fun withRecordingConnection(test: (KeyboardInputController, RecordingConnection) -> Unit) {
        instrumentation.runOnMainSync {
            test(KeyboardInputController(), RecordingConnection(instrumentation.targetContext))
        }
    }

    @Test fun singleShiftCapitalizesOneKeyAndTurnsOff() = withRecordingConnection { controller, connection ->
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals(ShiftState.OFF, controller.shiftState)
            assertTrue(controller.handle(key(KeyAction.SHIFT), connection, null))
            assertEquals(ShiftState.SINGLE, controller.shiftState)
            assertTrue(controller.shiftEnabled)
            controller.handle(text("iɐß"), connection, null)
            assertEquals("IⱯSS", connection.committed.last())
            assertEquals(ShiftState.OFF, controller.shiftState)
            assertFalse(controller.shiftEnabled)
            controller.handle(text("a"), connection, null)
            assertEquals("a", connection.committed.last())
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test fun doubleShiftActivatesCapsLockUntilTappedThirdTime() = withRecordingConnection { controller, connection ->
        assertTrue(controller.handle(key(KeyAction.SHIFT), connection, null))
        assertEquals(ShiftState.SINGLE, controller.shiftState)
        assertTrue(controller.handle(key(KeyAction.SHIFT), connection, null))
        assertEquals(ShiftState.CAPS_LOCK, controller.shiftState)
        assertTrue(controller.shiftEnabled)
        controller.handle(text("iɐß"), connection, null)
        controller.handle(text("a"), connection, null)
        assertEquals(listOf("IⱯSS", "A"), connection.committed)
        assertEquals(ShiftState.CAPS_LOCK, controller.shiftState)
        assertTrue(controller.shiftEnabled)
        controller.handle(key(KeyAction.SHIFT), connection, null)
        assertEquals(ShiftState.OFF, controller.shiftState)
        assertFalse(controller.shiftEnabled)
        controller.handle(text("b"), connection, null)
        assertEquals("b", connection.committed.last())
    }

    @Test fun ctrlUsesCommonEditorActionsAndOnlyAppliesToOneKey() = withRecordingConnection { controller, connection ->
        val actions = listOf("a" to android.R.id.selectAll, "C" to android.R.id.copy,
            "x" to android.R.id.cut, "v" to android.R.id.paste)
        for ((character, action) in actions) {
            controller.handle(key(KeyAction.CTRL), connection, null)
            assertTrue(controller.handle(text(character), connection, null))
            assertFalse(controller.ctrlEnabled)
            assertEquals(action, connection.contextActions.last())
        }
        assertTrue(connection.events.isEmpty())
        controller.handle(text("z"), connection, null)
        assertEquals(listOf("z"), connection.committed)
    }

    @Test fun rejectedContextActionFallsBackToBalancedControlKeyEvents() = withRecordingConnection { controller, connection ->
        connection.acceptContextActions = false
        controller.handle(key(KeyAction.CTRL), connection, null)
        assertTrue(controller.handle(text("a"), connection, null))
        assertKeyPair(connection.events, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
        assertTrue(connection.committed.isEmpty())
    }

    @Test fun unsupportedCtrlMappingIsNotInsertedAndClearsLatch() = withRecordingConnection { controller, connection ->
        for (value in listOf("t͡ʃ", "ɐ", "😀", "K", "İ", "")) {
            controller.handle(key(KeyAction.CTRL), connection, null)
            assertFalse(controller.handle(text(value), connection, null))
            assertFalse(controller.ctrlEnabled)
        }
        assertTrue(connection.committed.isEmpty())
        assertTrue(connection.events.isEmpty())
        controller.handle(text("ɐ"), connection, null)
        assertEquals(listOf("ɐ"), connection.committed)
    }

    @Test fun genericCtrlLettersDigitsAndSpaceUseKeyEvents() = withRecordingConnection { controller, connection ->
        for ((value, keyCode) in listOf("Z" to KeyEvent.KEYCODE_Z,
                "0" to KeyEvent.KEYCODE_0, "9" to KeyEvent.KEYCODE_9, " " to KeyEvent.KEYCODE_SPACE)) {
            connection.events.clear()
            controller.handle(key(KeyAction.CTRL), connection, null)
            assertTrue(controller.handle(text(value), connection, null))
            assertKeyPair(connection.events, keyCode, KeyEvent.META_CTRL_ON)
        }
        assertTrue(connection.committed.isEmpty())
    }

    @Test fun modifiersCanBeCancelledAndResetEvenWithoutInputConnection() = withRecordingConnection { controller, connection ->
        controller.handle(key(KeyAction.CTRL), null, null)
        controller.handle(key(KeyAction.CTRL), null, null)
        assertFalse(controller.ctrlEnabled)
        controller.handle(key(KeyAction.CTRL), null, null)
        assertFalse(controller.handle(text("a"), null, null))
        assertFalse(controller.ctrlEnabled)
        controller.handle(key(KeyAction.SHIFT), connection, null)
        controller.handle(key(KeyAction.CTRL), connection, null)
        controller.reset()
        assertFalse(controller.shiftEnabled)
        assertFalse(controller.ctrlEnabled)
    }

    @Test fun navigationAndTabCarryModifiersWithoutLeavingKeysPressed() = withRecordingConnection { controller, connection ->
        val mappings = listOf(KeyAction.LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            KeyAction.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT, KeyAction.UP to KeyEvent.KEYCODE_DPAD_UP,
            KeyAction.DOWN to KeyEvent.KEYCODE_DPAD_DOWN, KeyAction.HOME to KeyEvent.KEYCODE_MOVE_HOME,
            KeyAction.END to KeyEvent.KEYCODE_MOVE_END, KeyAction.TAB to KeyEvent.KEYCODE_TAB)
        controller.handle(key(KeyAction.SHIFT), connection, null)
        controller.handle(key(KeyAction.SHIFT), connection, null)
        for ((action, code) in mappings) {
            connection.events.clear()
            controller.handle(key(KeyAction.CTRL), connection, null)
            assertTrue(controller.handle(key(action), connection, null))
            assertKeyPair(connection.events, code, KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON)
            assertFalse(controller.ctrlEnabled)
            assertTrue(controller.shiftEnabled)
        }
    }

    @Test fun ctrlShiftShortcutPreservesShiftInsteadOfRunningPlainContextAction() = withRecordingConnection { controller, connection ->
        controller.handle(key(KeyAction.CTRL), connection, null)
        controller.handle(key(KeyAction.SHIFT), connection, null)
        controller.handle(text("v"), connection, null)
        assertTrue(connection.contextActions.isEmpty())
        assertKeyPair(connection.events, KeyEvent.KEYCODE_V, KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON)
    }

    @Test fun keyUpIsSentEvenIfEditorRejectsKeyDown() = withRecordingConnection { controller, connection ->
        connection.acceptEvents = false
        assertFalse(controller.handle(key(KeyAction.LEFT), connection, null))
        assertKeyPair(connection.events, KeyEvent.KEYCODE_DPAD_LEFT, 0)
    }

    @Test fun backspaceRemovesSelectionEmojiAndCombiningMarkInRealEditor() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            val info = EditorInfo()
            val connection = requireNotNull(editor.onCreateInputConnection(info))
            val controller = KeyboardInputController()
            editor.setText("a😀b")
            editor.setSelection(3)
            assertTrue(controller.handle(key(KeyAction.BACKSPACE), connection, info))
            assertEquals("ab", editor.text.toString())
            assertEquals(1, editor.selectionStart)
            editor.setText("a😀b")
            editor.setSelection(1, 3)
            controller.handle(key(KeyAction.BACKSPACE), connection, info)
            assertEquals("ab", editor.text.toString())
            editor.setText("ã")
            editor.setSelection(editor.length())
            controller.handle(key(KeyAction.BACKSPACE), connection, info)
            assertEquals("a", editor.text.toString())
            editor.setSelection(0)
            controller.handle(key(KeyAction.BACKSPACE), connection, info)
            assertEquals("a", editor.text.toString())
        }
    }

    @Test fun backspaceFallbackDeletesCompleteSurrogatePairs() = withRecordingConnection { controller, connection ->
        connection.acceptCodePointDeletion = false
        connection.beforeCursor = "😀"
        assertTrue(controller.handle(key(KeyAction.BACKSPACE), connection, null))
        assertEquals(2 to 0, connection.surroundingDeletions.last())
        connection.beforeCursor = "\uD83D"
        connection.afterCursor = "\uDE00"
        assertTrue(controller.handle(key(KeyAction.BACKSPACE), connection, null))
        assertEquals(1 to 1, connection.surroundingDeletions.last())
        connection.beforeCursor = "ã"
        connection.afterCursor = ""
        assertTrue(controller.handle(key(KeyAction.BACKSPACE), connection, null))
        assertEquals(1 to 0, connection.surroundingDeletions.last())
    }

    @Test fun ctrlBackspaceDelegatesWordDeletionToTheEditor() = withRecordingConnection { controller, connection ->
        controller.handle(key(KeyAction.CTRL), connection, null)
        controller.handle(key(KeyAction.BACKSPACE), connection, null)
        assertKeyPair(connection.events, KeyEvent.KEYCODE_DEL, KeyEvent.META_CTRL_ON)
        assertTrue(connection.codePointDeletions.isEmpty())
        assertTrue(connection.surroundingDeletions.isEmpty())
    }

    @Test fun repeatBackspaceDeletesCharactersLikeBackspace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            editor.inputType = InputType.TYPE_CLASS_TEXT
            val info = EditorInfo()
            val connection = requireNotNull(editor.onCreateInputConnection(info))
            val controller = KeyboardInputController()
            editor.setText("hello")
            editor.setSelection(5)
            assertTrue(controller.handle(key(KeyAction.REPEAT_BACKSPACE), connection, info))
            assertEquals("hell", editor.text.toString())
        }
    }

    @Test fun enterHonorsEditorActionAndMultilineNewline() = withRecordingConnection { controller, connection ->
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        controller.handle(key(KeyAction.ENTER), connection, info)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), connection.editorActions)
        info.actionLabel = "Custom"
        info.actionId = 42
        controller.handle(key(KeyAction.ENTER), connection, info)
        assertEquals(42, connection.editorActions.last())
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        info.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        controller.handle(key(KeyAction.ENTER), connection, info)
        assertEquals(listOf("\n"), connection.committed)
        assertEquals(2, connection.editorActions.size)
        controller.handle(key(KeyAction.CTRL), connection, info)
        controller.handle(key(KeyAction.ENTER), connection, info)
        assertKeyPair(connection.events, KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON)
    }

    private fun assertKeyPair(events: List<KeyEvent>, keyCode: Int, metaState: Int) {
        assertEquals(2, events.size)
        assertEquals(KeyEvent.ACTION_DOWN, events[0].action)
        assertEquals(KeyEvent.ACTION_UP, events[1].action)
        for (event in events) {
            assertEquals(keyCode, event.keyCode)
            assertEquals(metaState, event.metaState)
            assertEquals(KeyCharacterMap.VIRTUAL_KEYBOARD, event.deviceId)
            assertEquals(InputDevice.SOURCE_KEYBOARD, event.source)
            assertEquals(KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, event.flags)
            assertEquals(events[0].downTime, event.downTime)
        }
    }

    private class RecordingConnection(context: Context) : BaseInputConnection(View(context), true) {
        val committed = mutableListOf<String>()
        val events = mutableListOf<KeyEvent>()
        val contextActions = mutableListOf<Int>()
        val editorActions = mutableListOf<Int>()
        val codePointDeletions = mutableListOf<Pair<Int, Int>>()
        val surroundingDeletions = mutableListOf<Pair<Int, Int>>()
        var acceptContextActions = true
        var acceptEvents = true
        var acceptCodePointDeletion = true
        var beforeCursor: CharSequence? = ""
        var afterCursor: CharSequence? = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            committed += text.toString()
            return true
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            events += event
            return acceptEvents
        }
        override fun performContextMenuAction(id: Int): Boolean {
            contextActions += id
            return acceptContextActions
        }
        override fun performEditorAction(editorAction: Int): Boolean {
            editorActions += editorAction
            return true
        }
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = beforeCursor
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = afterCursor
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            codePointDeletions += beforeLength to afterLength
            return acceptCodePointDeletion
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            surroundingDeletions += beforeLength to afterLength
            return true
        }
    }
}
