package com.example.ipa_board

import android.R
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.util.Locale

enum class ShiftState {
    OFF,
    SINGLE,
    CAPS_LOCK
}

/** Executes mappings without keeping modifier state in the receiving application. */
class KeyboardInputController {
    var shiftState: ShiftState = ShiftState.OFF
        private set
    val shiftEnabled: Boolean
        get() = shiftState != ShiftState.OFF
    var ctrlEnabled: Boolean = false
        private set

    fun reset() {
        shiftState = ShiftState.OFF
        ctrlEnabled = false
    }

    fun consumeSingleShift(): Boolean {
        if (shiftState == ShiftState.SINGLE) {
            shiftState = ShiftState.OFF
            return true
        }
        return false
    }

    fun handle(slot: KeySlot, connection: InputConnection?, editorInfo: EditorInfo?): Boolean {
        when (slot.action) {
            KeyAction.SHIFT -> {
                shiftState = when (shiftState) {
                    ShiftState.OFF -> ShiftState.SINGLE
                    ShiftState.SINGLE -> ShiftState.CAPS_LOCK
                    ShiftState.CAPS_LOCK -> ShiftState.OFF
                }
                return true
            }
            KeyAction.CTRL -> {
                ctrlEnabled = !ctrlEnabled
                return true
            }
            else -> Unit
        }

        val useCtrl = ctrlEnabled
        // Consume the latch even when this mapping or the current editor is unsupported.
        ctrlEnabled = false
        connection ?: return false
        val metaState = (if (shiftEnabled) KeyEvent.META_SHIFT_ON else 0) or
            (if (useCtrl) KeyEvent.META_CTRL_ON else 0)
        val handled = when (slot.action) {
            KeyAction.TEXT -> if (useCtrl) {
                sendShortcut(connection, slot.text, metaState)
            } else {
                slot.text.isEmpty() || connection.commitText(
                    if (shiftEnabled) slot.text.uppercase(Locale.ROOT) else slot.text, 1
                )
            }
            KeyAction.BACKSPACE, KeyAction.REPEAT_BACKSPACE -> if (useCtrl) {
                sendKey(connection, KeyEvent.KEYCODE_DEL, metaState)
            } else {
                backspace(connection)
            }
            KeyAction.LEFT -> sendKey(connection, KeyEvent.KEYCODE_DPAD_LEFT, metaState)
            KeyAction.RIGHT -> sendKey(connection, KeyEvent.KEYCODE_DPAD_RIGHT, metaState)
            KeyAction.UP -> sendKey(connection, KeyEvent.KEYCODE_DPAD_UP, metaState)
            KeyAction.DOWN -> sendKey(connection, KeyEvent.KEYCODE_DPAD_DOWN, metaState)
            KeyAction.HOME -> sendKey(connection, KeyEvent.KEYCODE_MOVE_HOME, metaState)
            KeyAction.END -> sendKey(connection, KeyEvent.KEYCODE_MOVE_END, metaState)
            KeyAction.TAB -> sendKey(connection, KeyEvent.KEYCODE_TAB, metaState)
            KeyAction.ENTER -> enter(connection, editorInfo, metaState)
            KeyAction.EMOJI, KeyAction.CANDIDATES, KeyAction.CLIPBOARD, KeyAction.PAGES -> false // Panel actions are handled by IpaBoardService.
            else -> true
        }
        if (handled && (slot.action == KeyAction.TEXT) && slot.text.isNotEmpty()) {
            consumeSingleShift()
        }
        return handled
    }

    private fun sendShortcut(connection: InputConnection, text: String, metaState: Int): Boolean {
        // A mapping may contain several Unicode characters, but a hardware shortcut has one key.
        if (text.length != 1) return false
        val original = text[0]
        val character = if (original in 'A'..'Z') original.lowercaseChar() else original
        val keyCode = when (character) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (character - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (character - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '/' -> KeyEvent.KEYCODE_SLASH
            '`' -> KeyEvent.KEYCODE_GRAVE
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '{' -> KeyEvent.KEYCODE_LEFT_BRACKET
            '}' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '|' -> KeyEvent.KEYCODE_BACKSLASH
            ':' -> KeyEvent.KEYCODE_SEMICOLON
            '"' -> KeyEvent.KEYCODE_APOSTROPHE
            '<' -> KeyEvent.KEYCODE_COMMA
            '>' -> KeyEvent.KEYCODE_PERIOD
            '?' -> KeyEvent.KEYCODE_SLASH
            '~' -> KeyEvent.KEYCODE_GRAVE
            '_' -> KeyEvent.KEYCODE_MINUS
            '+' -> KeyEvent.KEYCODE_EQUALS
            '!' -> KeyEvent.KEYCODE_1
            '@' -> KeyEvent.KEYCODE_2
            '#' -> KeyEvent.KEYCODE_3
            '$' -> KeyEvent.KEYCODE_4
            '%' -> KeyEvent.KEYCODE_5
            '^' -> KeyEvent.KEYCODE_6
            '&' -> KeyEvent.KEYCODE_7
            '*' -> KeyEvent.KEYCODE_8
            '(' -> KeyEvent.KEYCODE_9
            ')' -> KeyEvent.KEYCODE_0
            else -> {
                val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(charArrayOf(character))
                events?.firstOrNull()?.keyCode ?: return false
            }
        }
        if (metaState and KeyEvent.META_SHIFT_ON == 0) {
            val contextAction = when (character) {
                'a' -> R.id.selectAll
                'c' -> if (!connection.getSelectedText(0).isNullOrEmpty()) R.id.copy else null
                'x' -> if (!connection.getSelectedText(0).isNullOrEmpty()) R.id.cut else null
                'v' -> android.R.id.paste
                else -> null
            }
            if (contextAction != null && connection.performContextMenuAction(contextAction)) {
                return true
            }
        }
        return sendKey(connection, keyCode, metaState)
    }

    private fun backspace(connection: InputConnection): Boolean {
        // commitText replaces a composing span before a selection; finish it first.
        connection.finishComposingText()
        val selected = try { connection.getSelectedText(0) } catch (_: Exception) { null }
        if (!selected.isNullOrEmpty()) {
            return connection.commitText("", 1)
        }

        val before = try { connection.getTextBeforeCursor(2, 0) } catch (_: Exception) { null }
        if (before.isNullOrEmpty()) {
            return sendKey(connection, KeyEvent.KEYCODE_DEL, 0)
        }

        if (connection.deleteSurroundingTextInCodePoints(1, 0)) return true

        // Older/custom editors can reject code-point deletion. Never split a surrogate pair.
        val last = before[before.length - 1]
        if (Character.isHighSurrogate(last)) {
            val after = try { connection.getTextAfterCursor(1, 0) } catch (_: Exception) { null }
            if (!after.isNullOrEmpty() && Character.isLowSurrogate(after[0])) {
                if (connection.deleteSurroundingText(1, 1)) return true
            }
        }
        val length = if (before.length >= 2 && Character.isSurrogatePair(
                before[before.length - 2], last
            )) 2 else 1
        return connection.deleteSurroundingText(length, 0) || sendKey(connection, KeyEvent.KEYCODE_DEL, 0)
    }

    private fun enter(connection: InputConnection, editorInfo: EditorInfo?, metaState: Int): Boolean {
        if (metaState != 0) return sendKey(connection, KeyEvent.KEYCODE_ENTER, metaState)
        if (editorInfo != null) {
            if (editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
                val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
                if (editorInfo.actionLabel != null) {
                    return connection.performEditorAction(editorInfo.actionId)
                }
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    return connection.performEditorAction(action)
                }
            }
            if (editorInfo.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                editorInfo.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) {
                return connection.commitText("\n", 1)
            }
        }
        return sendKey(connection, KeyEvent.KEYCODE_ENTER, 0)
    }

    private fun sendKey(connection: InputConnection, keyCode: Int, metaState: Int): Boolean {
        val time = SystemClock.uptimeMillis()
        fun event(action: Int) = KeyEvent(
            time, time, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD
        )
        val downAccepted = connection.sendKeyEvent(event(KeyEvent.ACTION_DOWN))
        // Always release the key, including when the target declines the down event.
        val upAccepted = connection.sendKeyEvent(event(KeyEvent.ACTION_UP))
        return downAccepted || upAccepted
    }
}
