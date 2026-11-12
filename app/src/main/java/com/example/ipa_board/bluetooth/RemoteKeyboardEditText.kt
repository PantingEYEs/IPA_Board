package com.example.ipa_board.bluetooth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.DragEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
import androidx.appcompat.widget.AppCompatEditText

/** A local, unsent draft. It never keeps a mirror of text already sent to the host. */
class RemoteKeyboardEditText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {
    enum class Mode { IMMEDIATE, MANUAL }

    var mode = Mode.MANUAL
        set(value) {
            if (field == value) return
            require(text.isNullOrEmpty() && !isSending) { "Finish or clear the draft before changing mode" }
            field = value
            renewConnection()
        }
    var transmitText: (String, (Boolean) -> Unit) -> Unit = { _, complete -> complete(false) }
    var transmitKey: (usage: Int, modifiers: Int) -> Boolean = { _, _ -> false }
    var onSendingChanged: (Boolean) -> Unit = {}
    var inputAllowed = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) cancelSending()
            renewConnection()
        }
    val isSending: Boolean get() = pendingSend != null

    private var suspended = false
    private var explicitRetryRequired = false
    private var connectionEpoch = 0L
    private var textRevision = 0L
    private var connection: DraftConnection? = null
    private var pendingSend: PendingSend? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private class PendingSend(val text: String, val revision: Long)

    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        textRevision++
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        cancelSending()
        retireConnection()
        super.onRestoreInstanceState(state)
        // EditText restores the draft, but cannot know how much a previous host received.
        // Restored text therefore requires the same explicit decision as a failed send.
        explicitRetryRequired = !text.isNullOrEmpty()
        restartInput()
    }

    /** Returns whether a request started. Completion, not this return value, determines success. */
    fun sendDraft(): Boolean {
        if (!canEdit() || text.isNullOrEmpty()) return false
        // Ending a local composition confirms the visible spelling, not an IME candidate.
        retireConnection()
        val request = PendingSend(text.toString(), textRevision)
        pendingSend = request
        explicitRetryRequired = true
        onSendingChanged(true)
        if (pendingSend !== request || !inputAllowed || suspended) return false
        try {
            transmitText(request.text) { accepted ->
                if (Looper.myLooper() == Looper.getMainLooper()) completeSend(request, accepted)
                else mainHandler.post { completeSend(request, accepted) }
            }
        } catch (_: RuntimeException) {
            completeSend(request, false)
        }
        return true
    }

    fun clearDraft() {
        cancelSending()
        retireConnection()
        text?.clear()
        explicitRetryRequired = false
        restartInput()
    }

    /** Invalidates queued IME calls without sending or discarding the visible draft. */
    fun suspendInput() {
        suspended = true
        cancelSending()
        retireConnection()
    }

    fun resumeInput() {
        if (!suspended) return
        suspended = false
        renewConnection()
    }

    private fun completeSend(request: PendingSend, accepted: Boolean) {
        if (pendingSend !== request) return
        pendingSend = null
        if (accepted && textRevision == request.revision && text.toString() == request.text) {
            text?.clear()
            explicitRetryRequired = false
        }
        onSendingChanged(false)
        renewConnection()
    }

    private fun cancelSending() {
        if (!text.isNullOrEmpty()) explicitRetryRequired = true
        if (pendingSend != null) {
            pendingSend = null
            onSendingChanged(false)
        }
    }

    private fun canEdit() = inputAllowed && !suspended && !isSending
    private fun retireConnection() {
        connectionEpoch++
        connection?.retire()
        connection = null
    }
    private fun renewConnection() {
        retireConnection()
        restartInput()
    }
    private fun restartInput() {
        // InputConnection: commit replaces composing/selection; finish merely removes composition.
        // https://developer.android.com/reference/android/view/inputmethod/InputConnection
        // Our clearing is an application edit: publish fresh context and invalidate old requests.
        context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        retireConnection()
        return DraftConnection(base, connectionEpoch).also { connection = it }
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        if (canEdit()) super.onTextContextMenuItem(id) else false

    // TextView handles clicks and text selection in super; blocked sending gestures are not clicks.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (isSending) true else super.onTouchEvent(event)

    override fun performClick(): Boolean = if (isSending) false else super.performClick()

    override fun onDragEvent(event: DragEvent): Boolean = canEdit() && super.onDragEvent(event)

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (!canEdit() && action in intArrayOf(
                AccessibilityNodeInfo.ACTION_SET_TEXT, AccessibilityNodeInfo.ACTION_SET_SELECTION,
                AccessibilityNodeInfo.ACTION_CUT, AccessibilityNodeInfo.ACTION_PASTE)) return false
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keyEvent(event) { super.onKeyDown(keyCode, event) }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keyEvent(event) { super.onKeyUp(keyCode, event) }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean =
        if (canEdit() && (mode == Mode.MANUAL || !text.isNullOrEmpty()))
            super.onKeyMultiple(keyCode, repeatCount, event) else false

    private fun keyEvent(event: KeyEvent, local: () -> Boolean): Boolean {
        if (!canEdit()) return false
        if (mode == Mode.MANUAL || !text.isNullOrEmpty()) return local()
        if (KeyEvent.isModifierKey(event.keyCode)) return true
        val usage = keyUsage(event.keyCode) ?: return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> sendKey(usage, modifiers(event.metaState))
            KeyEvent.ACTION_UP -> true // transmitKey sends a complete press/release pair.
            else -> false
        }
    }

    private fun sendKey(usage: Int, modifiers: Int): Boolean =
        try { transmitKey(usage, modifiers) } catch (_: RuntimeException) { false }

    private inner class DraftConnection(base: InputConnection, private val epoch: Long) :
        InputConnectionWrapper(base, false) {
        private var closed = false
        private var batchDepth = 0
        private var confirmed = false
        private fun valid() = !closed && epoch == connectionEpoch && canEdit()
        private fun range(start: Int, end: Int) = start in 0..length() && end in 0..length()
        private fun edit(action: () -> Boolean): Boolean = valid() && action()
        private fun confirm(action: () -> Boolean): Boolean {
            if (!edit(action)) return false
            confirmed = true
            flushConfirmed()
            return true // The local edit succeeded even if transmitting later fails.
        }
        private fun flushConfirmed() {
            if (confirmed && batchDepth == 0 && mode == Mode.IMMEDIATE &&
                !explicitRetryRequired && (text?.let(BaseInputConnection::getComposingSpanStart) ?: -1) < 0) {
                confirmed = false
                sendDraft()
            }
        }
        fun retire() {
            if (closed) return
            closed = true
            while (batchDepth > 0) { batchDepth--; super.endBatchEdit() }
            // Closing is local lifecycle cleanup, never a remote confirmation.
            super.closeConnection()
        }
        override fun closeConnection() {
            if (epoch == connectionEpoch) retire()
        }
        override fun beginBatchEdit(): Boolean = edit {
            if (!super.beginBatchEdit()) false else { batchDepth++; true }
        }
        override fun endBatchEdit(): Boolean {
            if (!valid() || batchDepth == 0) return false
            batchDepth--
            val result = super.endBatchEdit()
            flushConfirmed()
            return result
        }
        override fun setComposingText(value: CharSequence?, cursor: Int): Boolean =
            edit { super.setComposingText(value, cursor) }
        @RequiresApi(33)
        override fun setComposingText(value: CharSequence, cursor: Int, attributes: TextAttribute?): Boolean =
            edit { super.setComposingText(value, cursor, attributes) }
        override fun setComposingRegion(start: Int, end: Int): Boolean =
            edit { range(start, end) && super.setComposingRegion(start, end) }
        @RequiresApi(33)
        override fun setComposingRegion(start: Int, end: Int, attributes: TextAttribute?): Boolean =
            edit { range(start, end) && super.setComposingRegion(start, end, attributes) }
        override fun setSelection(start: Int, end: Int): Boolean =
            edit { range(start, end) && super.setSelection(start, end) }
        override fun commitText(value: CharSequence?, cursor: Int): Boolean =
            confirm { super.commitText(value, cursor) }
        @RequiresApi(33)
        override fun commitText(value: CharSequence, cursor: Int, attributes: TextAttribute?): Boolean =
            confirm { super.commitText(value, cursor, attributes) }
        override fun finishComposingText(): Boolean = confirm { super.finishComposingText() }
        @RequiresApi(34)
        override fun replaceText(start: Int, end: Int, value: CharSequence, cursor: Int, attributes: TextAttribute?): Boolean =
            confirm { range(start, end) && super.replaceText(start, end, value, cursor, attributes) }
        override fun commitCompletion(completion: CompletionInfo?): Boolean =
            if (completion == null) false else commitText(completion.text, 1)
        override fun deleteSurroundingText(before: Int, after: Int): Boolean = deletion(before, after) {
            super.deleteSurroundingText(before, after)
        }
        override fun deleteSurroundingTextInCodePoints(before: Int, after: Int): Boolean = deletion(before, after) {
            super.deleteSurroundingTextInCodePoints(before, after)
        }
        private fun deletion(before: Int, after: Int, local: () -> Boolean): Boolean = edit {
            when {
                before < 0 || after < 0 -> false
                mode == Mode.MANUAL || !text.isNullOrEmpty() -> local()
                before == 0 && after == 0 -> true
                before == 1 && after == 0 -> sendKey(0x2A, 0)
                before == 0 && after == 1 -> sendKey(0x4C, 0)
                else -> false // No remote Unicode/caret knowledge: never guess multi-unit deletion.
            }
        }
        override fun sendKeyEvent(event: KeyEvent?): Boolean = edit {
            event != null && this@RemoteKeyboardEditText.dispatchKeyEvent(event)
        }
        override fun performEditorAction(action: Int): Boolean = edit {
            if (action == EditorInfo.IME_ACTION_SEND) sendDraft() else super.performEditorAction(action)
        }
        override fun performContextMenuAction(id: Int): Boolean = edit { super.performContextMenuAction(id) }
        override fun clearMetaKeyStates(states: Int): Boolean = edit { super.clearMetaKeyStates(states) }
        @RequiresApi(31)
        override fun setImeConsumesInput(consumesInput: Boolean): Boolean = edit { super.setImeConsumesInput(consumesInput) }
        override fun commitCorrection(info: android.view.inputmethod.CorrectionInfo?): Boolean =
            edit { super.commitCorrection(info) }
        override fun commitContent(info: android.view.inputmethod.InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean = false
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = false
        @RequiresApi(34)
        override fun performHandwritingGesture(
            gesture: android.view.inputmethod.HandwritingGesture,
            executor: java.util.concurrent.Executor?, consumer: java.util.function.IntConsumer?
        ) {
            if (executor != null && consumer != null) executor.execute {
                consumer.accept(InputConnection.HANDWRITING_GESTURE_RESULT_UNSUPPORTED)
            }
        }
        @RequiresApi(34)
        override fun previewHandwritingGesture(
            gesture: android.view.inputmethod.PreviewableHandwritingGesture,
            cancellationSignal: android.os.CancellationSignal?
        ): Boolean = false
    }

    private fun modifiers(meta: Int): Int {
        var result = 0
        for ((generic, left, right, leftBit, rightBit) in arrayOf(
            intArrayOf(KeyEvent.META_SHIFT_ON, KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.META_SHIFT_RIGHT_ON, 2, 32),
            intArrayOf(KeyEvent.META_CTRL_ON, KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_RIGHT_ON, 1, 16),
            intArrayOf(KeyEvent.META_ALT_ON, KeyEvent.META_ALT_LEFT_ON, KeyEvent.META_ALT_RIGHT_ON, 4, 64),
            intArrayOf(KeyEvent.META_META_ON, KeyEvent.META_META_LEFT_ON, KeyEvent.META_META_RIGHT_ON, 8, 128)
        )) {
            if (meta and left != 0) result = result or leftBit
            if (meta and right != 0) result = result or rightBit
            if (meta and generic != 0 && meta and (left or right) == 0) result = result or leftBit
        }
        return result
    }

    private fun keyUsage(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + code - KeyEvent.KEYCODE_A
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + code - KeyEvent.KEYCODE_1
        KeyEvent.KEYCODE_0 -> 0x27
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x3A + code - KeyEvent.KEYCODE_F1
        KeyEvent.KEYCODE_ENTER -> 0x28
        KeyEvent.KEYCODE_ESCAPE -> 0x29
        KeyEvent.KEYCODE_DEL -> 0x2A
        KeyEvent.KEYCODE_TAB -> 0x2B
        KeyEvent.KEYCODE_SPACE -> 0x2C
        KeyEvent.KEYCODE_MINUS -> 0x2D
        KeyEvent.KEYCODE_EQUALS -> 0x2E
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
        KeyEvent.KEYCODE_BACKSLASH -> 0x31
        KeyEvent.KEYCODE_SEMICOLON -> 0x33
        KeyEvent.KEYCODE_APOSTROPHE -> 0x34
        KeyEvent.KEYCODE_GRAVE -> 0x35
        KeyEvent.KEYCODE_COMMA -> 0x36
        KeyEvent.KEYCODE_PERIOD -> 0x37
        KeyEvent.KEYCODE_SLASH -> 0x38
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
        KeyEvent.KEYCODE_INSERT -> 0x49
        KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
        KeyEvent.KEYCODE_PAGE_UP -> 0x4B
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C
        KeyEvent.KEYCODE_MOVE_END -> 0x4D
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
        KeyEvent.KEYCODE_DPAD_UP -> 0x52
        else -> null
    }
}
