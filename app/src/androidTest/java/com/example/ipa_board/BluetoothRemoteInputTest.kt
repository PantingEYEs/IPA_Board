package com.example.ipa_board

import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.text.InputType
import android.util.SparseArray
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextAttribute
import androidx.appcompat.widget.AppCompatEditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.bluetooth.RemoteKeyboardEditText
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Test-owned views and synthetic IME calls only: no Bluetooth, clipboard, or other applications. */
class BluetoothRemoteInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Request(val text: String, val complete: (Boolean) -> Unit)
    private inner class Fixture(mode: RemoteKeyboardEditText.Mode) {
        val requests = mutableListOf<Request>()
        val keys = mutableListOf<Pair<Int, Int>>()
        val sendingStates = mutableListOf<Boolean>()
        var acceptKeys = true
        val editor = RemoteKeyboardEditText(instrumentation.targetContext).apply {
            this.mode = mode
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            inputAllowed = true
            transmitText = { text, complete -> requests += Request(text, complete) }
            transmitKey = { usage, modifiers -> keys += usage to modifiers; acceptKeys }
            onSendingChanged = { sendingStates += it }
        }
        fun connection(info: EditorInfo = EditorInfo()): InputConnection =
            requireNotNull(editor.onCreateInputConnection(info))
        fun assertDraft(expected: String) = assertEquals(expected, editor.text.toString())
        fun finishRequest(accepted: Boolean, index: Int = requests.lastIndex) = requests[index].complete(accepted)
    }

    private fun withEditor(mode: RemoteKeyboardEditText.Mode = RemoteKeyboardEditText.Mode.IMMEDIATE,
                           block: (Fixture) -> Unit) {
        instrumentation.runOnMainSync {
            val fixture = Fixture(mode)
            try { block(fixture) } finally { fixture.editor.suspendInput() }
        }
    }

    @Test fun manualModeKeepsAllEditingLocalAndSendsTheVisibleCompositionOnlyOnRequest() =
        withEditor(RemoteKeyboardEditText.Mode.MANUAL) { f ->
            val ic = f.connection()
            assertTrue(ic.setComposingText("ni", 1))
            assertTrue(ic.setComposingText("nihao", 1))
            assertTrue(ic.commitText("hello", 1))
            assertTrue(ic.setComposingText(" draft", 1))
            assertTrue(f.requests.isEmpty())
            assertTrue(f.editor.sendDraft())
            assertEquals(listOf("hello draft"), f.requests.map { it.text })
            f.assertDraft("hello draft")
            assertTrue(f.editor.isSending)
            f.finishRequest(true)
            f.assertDraft("")
            assertFalse(f.editor.isSending)
            assertEquals(listOf(true, false), f.sendingStates)
            assertFalse(f.editor.sendDraft())
        }

    @Test fun immediateCompositionIsNotSentUntilCommittedAndAnOldConnectionCannotResendIt() = withEditor { f ->
        val old = f.connection()
        assertTrue(old.setComposingText("h", 1))
        assertTrue(old.setComposingText("hello", 1))
        assertTrue(f.requests.isEmpty())
        assertTrue(old.commitText("hello", 1))
        assertEquals(listOf("hello"), f.requests.map { it.text })
        assertFalse(old.finishComposingText())
        assertFalse(old.commitText("hello", 1))
        f.finishRequest(true)
        f.assertDraft("")
        assertFalse(old.finishComposingText())
        assertFalse(old.setComposingText("stale", 1))
        val next = f.connection()
        assertTrue(next.commitText("hello", 1))
        assertEquals("Identical, separately entered text is not deduplicated", listOf("hello", "hello"),
            f.requests.map { it.text })
        f.finishRequest(true)
    }

    @Test fun finishOnlyConfirmsOnceWithoutInventingAnImeCandidate() = withEditor { f ->
        val ic = f.connection()
        assertTrue(ic.setComposingText("hao", 1))
        assertTrue(ic.finishComposingText())
        assertEquals(listOf("hao"), f.requests.map { it.text })
        assertFalse(ic.finishComposingText())
        f.finishRequest(true)
        assertTrue(f.connection().finishComposingText())
        assertEquals(1, f.requests.size)
    }

    @Test fun failedTransmissionKeepsEditableDraftAndRequiresExplicitRetry() = withEditor { f ->
        assertTrue(f.connection().commitText("abc", 1))
        f.finishRequest(false)
        f.assertDraft("abc")
        val next = f.connection()
        assertTrue(next.commitText("d", 1))
        assertTrue(next.finishComposingText())
        f.assertDraft("abcd")
        assertEquals(1, f.requests.size)
        assertTrue(f.editor.sendDraft())
        assertEquals(listOf("abc", "abcd"), f.requests.map { it.text })
        f.finishRequest(true)
        f.assertDraft("")
        assertTrue(f.connection().commitText("new", 1))
        assertEquals("new", f.requests.last().text)
    }

    @Test fun pendingTransmissionRejectsImeKeysSelectionPasteAndAccessibilityEdits() = withEditor { f ->
        var clicks = 0
        f.editor.setOnClickListener { clicks++ }
        assertTrue(f.connection().commitText("abc", 1))
        val blocked = f.connection()
        assertFalse(blocked.beginBatchEdit())
        assertFalse(blocked.commitText("x", 1))
        assertFalse(blocked.setComposingText("x", 1))
        assertFalse(blocked.setComposingRegion(0, 1))
        assertFalse(blocked.setSelection(0, 1))
        assertFalse(blocked.deleteSurroundingText(1, 0))
        assertFalse(blocked.deleteSurroundingTextInCodePoints(1, 0))
        assertFalse(blocked.finishComposingText())
        assertFalse(blocked.performContextMenuAction(android.R.id.paste))
        assertFalse(blocked.clearMetaKeyStates(KeyEvent.META_CTRL_ON))
        assertFalse(blocked.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
        assertFalse(f.editor.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "replacement")
        }))
        assertFalse(f.editor.sendDraft())
        assertFalse(f.editor.performClick())
        assertEquals(0, clicks)
        f.assertDraft("abc")
        assertTrue(f.keys.isEmpty())
        f.finishRequest(false)
        assertTrue(f.editor.performClick())
        assertEquals(1, clicks)
    }

    @Test fun suspendAndDisconnectInvalidateOldRequestsWithoutDiscardingOrAutoResending() = withEditor { f ->
        val old = f.connection()
        assertTrue(old.commitText("retained", 1))
        f.editor.suspendInput()
        f.editor.inputAllowed = false
        f.finishRequest(true) // This send was abandoned; a late result cannot clear the draft.
        f.assertDraft("retained")
        assertFalse(old.commitText("stale", 1))
        assertFalse(f.editor.sendDraft())
        f.editor.inputAllowed = true
        f.editor.resumeInput()
        val next = f.connection()
        assertTrue(next.finishComposingText())
        assertEquals(1, f.requests.size)
        assertTrue(f.editor.sendDraft())
        f.finishRequest(true)
        f.assertDraft("")
    }

    @Test fun clearAndNewDraftAreNotAffectedByThePreviousRequestsLateCompletion() = withEditor { f ->
        assertTrue(f.connection().commitText("old", 1))
        f.editor.clearDraft()
        assertTrue(f.connection().commitText("new", 1))
        f.finishRequest(true, 0)
        f.assertDraft("new")
        assertTrue(f.editor.isSending)
        f.finishRequest(false, 1)
        f.assertDraft("new")
        assertFalse(f.editor.isSending)
        f.finishRequest(true, 1) // A callback can settle a request only once.
        f.assertDraft("new")
    }

    @Test fun localReplacementDuringSendCannotBeClearedByAStaleSnapshot() = withEditor { f ->
        assertTrue(f.connection().commitText("old", 1))
        f.editor.setText("restored local draft") // Models lifecycle/state restoration, not IME input.
        f.finishRequest(true)
        f.assertDraft("restored local draft")
        assertFalse(f.editor.isSending)
        assertTrue(f.connection().finishComposingText())
        assertEquals(1, f.requests.size)
    }

    @Test fun nestedBatchEditsSendOnlyAfterTheOutermostBatchAndNeverIncludeLiveComposition() = withEditor { f ->
        val ic = f.connection()
        assertTrue(ic.beginBatchEdit())
        assertTrue(ic.beginBatchEdit())
        assertTrue(ic.commitText("hello", 1))
        ic.endBatchEdit()
        assertTrue(f.requests.isEmpty())
        assertTrue(ic.setComposingText(" wor", 1))
        ic.endBatchEdit()
        assertTrue(f.requests.isEmpty())
        assertTrue(ic.commitText(" world", 1))
        assertEquals(listOf("hello world"), f.requests.map { it.text })
        f.finishRequest(true)
    }

    @Test fun composingRegionAndSelectionEditsApplyOnlyToTheUnsentLocalDraft() = withEditor { f ->
        val ic = f.connection()
        assertTrue(ic.setComposingText("hellp", 1))
        assertTrue(ic.finishComposingText())
        f.finishRequest(false)
        val correction = f.connection()
        assertTrue(correction.setComposingRegion(0, 5))
        assertTrue(correction.commitText("hello", 1))
        assertTrue(correction.setSelection(1, 4))
        assertTrue(correction.commitText("i", 1))
        f.assertDraft("hio")
        assertEquals(1, f.requests.size)
        assertTrue(f.editor.sendDraft())
        f.finishRequest(true)
        val empty = f.connection()
        assertFalse(empty.setComposingRegion(0, 5))
        assertFalse(empty.setSelection(0, 5))
        assertEquals(2, f.requests.size)
    }

    @Test fun modernCommitAndReplacementOverloadsFollowTheSameDraftBoundary() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        withEditor { f ->
            val attrs = TextAttribute.Builder().build()
            val ic = f.connection()
            assertTrue(ic.setComposingText("hellp", 1, attrs))
            assertTrue(ic.setComposingRegion(0, 5, attrs))
            assertTrue(f.requests.isEmpty())
            assertTrue(ic.replaceText(4, 5, "o", 1, attrs))
            assertEquals(listOf("hello"), f.requests.map { it.text })
            f.finishRequest(true)
            val next = f.connection()
            assertFalse(next.replaceText(0, 5, "stale", 1, attrs))
            assertTrue(next.commitText("again", 1, attrs))
            assertEquals(listOf("hello", "again"), f.requests.map { it.text })
        }
    }

    @Test fun localDeletionKeepsUnicodeEditingLocalAndEmptyDeletionUsesOnlySingleRemoteKeys() = withEditor { f ->
        f.editor.setText("a😀")
        f.editor.setSelection(f.editor.length())
        val local = f.connection()
        assertTrue(local.deleteSurroundingTextInCodePoints(1, 0))
        f.assertDraft("a")
        assertTrue(local.deleteSurroundingText(1, 0))
        f.assertDraft("")
        assertTrue(f.keys.isEmpty())
        assertTrue(local.deleteSurroundingText(1, 0))
        assertTrue(local.deleteSurroundingTextInCodePoints(0, 1))
        assertFalse(local.deleteSurroundingText(2, 0))
        assertFalse(local.deleteSurroundingText(1, 1))
        assertFalse(local.deleteSurroundingText(-1, 0))
        assertEquals(listOf(0x2A to 0, 0x4C to 0), f.keys)
        f.acceptKeys = false
        assertFalse(local.deleteSurroundingText(1, 0))
        assertTrue(f.requests.isEmpty())
    }

    @Test fun keyEventsSendOneTapWithModifiersAndStayLocalWhenThereIsADraft() = withEditor { f ->
        val ic = f.connection()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_RIGHT_ON
        assertTrue(ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, meta)))
        assertTrue(ic.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, meta)))
        assertTrue(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        assertEquals(listOf(0x04 to 0x21, 0x28 to 0), f.keys)
        assertFalse(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        f.editor.setText("abc")
        f.editor.setSelection(3)
        val ordinary = AppCompatEditText(instrumentation.targetContext, null, android.R.attr.editTextStyle).apply {
            inputType = f.editor.inputType
            setText("abc")
            setSelection(3)
        }
        val ordinaryDown = ordinary.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertTrue(ordinaryDown)
        assertEquals(ordinaryDown, ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
        f.assertDraft("ab")
        assertEquals("ab", ordinary.text.toString())
        // TextView may leave DEL-up unhandled. Preserve its return value, not a blanket true.
        val ordinaryUp = ordinary.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        assertEquals(ordinaryUp, ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)))
        f.assertDraft("ab")
        assertEquals("ab", ordinary.text.toString())
        assertEquals(2, f.keys.size)
        assertTrue(f.requests.isEmpty())
    }

    @Test fun manualEmptyKeysNeverBecomeRemoteCommandsAndModeChangesRequireAnEmptyDraft() =
        withEditor(RemoteKeyboardEditText.Mode.MANUAL) { f ->
            val info = EditorInfo()
            f.editor.privateImeOptions = "com.example.ipa_board.bluetooth_remote:owned-test-session"
            val ic = f.connection(info)
            assertEquals(f.editor.privateImeOptions, info.privateImeOptions)
            assertTrue(ic.deleteSurroundingText(1, 0))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            assertTrue(f.keys.isEmpty())
            f.editor.setText("draft")
            assertThrows(IllegalArgumentException::class.java) { f.editor.mode = RemoteKeyboardEditText.Mode.IMMEDIATE }
            f.editor.clearDraft()
            f.editor.mode = RemoteKeyboardEditText.Mode.IMMEDIATE
            assertFalse(ic.commitText("stale", 1))
            assertTrue(f.connection().commitText("new", 1))
            assertEquals(listOf("new"), f.requests.map { it.text })
        }

    @Test fun disablingInputRejectsNewAndExistingConnectionsAndPreservesDraft() = withEditor { f ->
        val old = f.connection()
        assertTrue(old.setComposingText("unsent", 1))
        f.editor.inputAllowed = false
        assertFalse(old.commitText("stale", 1))
        assertFalse(f.connection().commitText("new", 1))
        assertFalse(f.editor.sendDraft())
        f.assertDraft("unsent")
        f.editor.inputAllowed = true
        assertTrue(f.connection().finishComposingText())
        assertTrue(f.requests.isEmpty())
        assertTrue(f.editor.sendDraft())
        assertEquals(listOf("unsent"), f.requests.map { it.text })
        f.finishRequest(true)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(requireNotNull(f.editor.text)))
    }

    @Test fun backgroundCompletionSettlesTheSnapshotOnTheEditorThread() {
        lateinit var f: Fixture
        instrumentation.runOnMainSync {
            f = Fixture(RemoteKeyboardEditText.Mode.IMMEDIATE)
            assertTrue(f.connection().commitText("async", 1))
        }
        try {
            val worker = Thread { f.requests.single().complete(true) }
            worker.start()
            worker.join()
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                f.assertDraft("")
                assertFalse(f.editor.isSending)
                assertEquals(listOf(true, false), f.sendingStates)
            }
        } finally { instrumentation.runOnMainSync { f.editor.suspendInput() } }
    }

    @Test fun restoredNonEmptyDraftRequiresExplicitSendEvenAfterMoreImmediateInput() = withEditor { original ->
        original.editor.id = android.R.id.edit
        assertTrue(original.editor.freezesText)
        assertTrue(original.connection().commitText("possibly sent", 1))
        original.editor.suspendInput() // Rotation abandons a possibly partially delivered request.
        val hierarchy = SparseArray<Parcelable>()
        original.editor.saveHierarchyState(hierarchy)
        val parcel = Parcel.obtain()
        val restoredState: SparseArray<Parcelable>
        try {
            parcel.writeBundle(Bundle().apply { putSparseParcelableArray("editor", hierarchy) })
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            restoredState = requireNotNull(parcel.readBundle(RemoteKeyboardEditText::class.java.classLoader)
                ?.getSparseParcelableArray("editor"))
        } finally { parcel.recycle() }

        val restored = Fixture(RemoteKeyboardEditText.Mode.IMMEDIATE)
        try {
            restored.editor.id = android.R.id.edit
            restored.editor.restoreHierarchyState(restoredState)
            restored.assertDraft("possibly sent")
            assertFalse(restored.editor.isSending)
            val ic = restored.connection()
            assertTrue(ic.finishComposingText())
            assertTrue(ic.commitText(" again", 1))
            restored.assertDraft("possibly sent again")
            assertTrue(restored.requests.isEmpty())
            original.finishRequest(true) // Old owner completion cannot settle the new editor.
            restored.assertDraft("possibly sent again")
            assertTrue(restored.editor.sendDraft())
            assertEquals(listOf("possibly sent again"), restored.requests.map { it.text })
            restored.finishRequest(true)
            restored.assertDraft("")
            assertTrue(restored.connection().commitText("fresh", 1))
            assertEquals(listOf("possibly sent again", "fresh"), restored.requests.map { it.text })
        } finally { restored.editor.suspendInput() }
    }
}
