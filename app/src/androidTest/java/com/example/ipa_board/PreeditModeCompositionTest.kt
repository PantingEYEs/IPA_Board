package com.example.ipa_board

import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.Candidate
import com.example.ipa_board.ime.CandidateConsumption
import com.example.ipa_board.ime.CompositionController
import org.junit.Assert.*
import org.junit.Test

/** Test-owned editors only. Pending non-inline input must not rewrite the host's text. */
class PreeditModeCompositionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class RecordingConnection(base: InputConnection) : InputConnectionWrapper(base, false) {
        var acceptCommit = true
        var acceptFinish = true
        var exposePosition = true
        var exposeSurroundingText = true
        val commits = mutableListOf<String>()
        var composingWrites = 0
        var selectionWrites = 0
        var deletionWrites = 0
        var finishes = 0

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits += text.toString()
            return acceptCommit && super.commitText(text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            finishes++
            return acceptFinish && super.finishComposingText()
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composingWrites++
            return super.setComposingText(text, newCursorPosition)
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            composingWrites++
            return super.setComposingRegion(start, end)
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            selectionWrites++
            return super.setSelection(start, end)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletionWrites++
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int) =
            if (exposePosition) super.getExtractedText(request, flags) else null

        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? =
            if (exposeSurroundingText) super.getTextBeforeCursor(length, flags) else null

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? =
            if (exposeSurroundingText) super.getTextAfterCursor(length, flags) else null

        fun assertNoPendingWrites() {
            assertTrue("Typing must not commit raw text", commits.isEmpty())
            assertEquals("Typing must not create a host composing span", 0, composingWrites)
            assertEquals("The preedit caret belongs to the keyboard", 0, selectionWrites)
            assertEquals("Backspace must not delete surrounding host text", 0, deletionWrites)
            assertEquals("Pending edits must not finish a host composition", 0, finishes)
        }
    }

    private class Fixture(
        editor: EditText,
        inline: Boolean = false,
        transform: (String) -> String = { it }
    ) {
        val editor = editor.apply { setText("前後"); setSelection(1) }
        val ic = RecordingConnection(requireNotNull(editor.onCreateInputConnection(EditorInfo())))
        var currentConnection: InputConnection? = ic
        val queries = mutableListOf<String>()
        val controller = CompositionController({ currentConnection }, { _, raw -> queries += raw }, {},
            transformOutput = transform, initialInlineEnabled = inline)

        fun offer(candidate: Candidate) = controller.acceptResults(controller.revision, listOf(candidate))

        fun assertHost(text: String = "前後", start: Int = 1, end: Int = start) {
            assertEquals(text, editor.text.toString())
            assertEquals(start, editor.selectionStart)
            assertEquals(end, editor.selectionEnd)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
        }
    }

    private fun fixture(inline: Boolean = false, transform: (String) -> String = { it }) =
        Fixture(EditText(instrumentation.targetContext), inline, transform)

    @Test fun nonInlineEditingKeepsTextSelectionAndContextInTheHostUntouched() {
        instrumentation.runOnMainSync {
            val f = fixture()
            val c = f.controller
            c.input("nihao")
            assertEquals("nihao", c.raw)
            assertEquals("前", c.beforeCursor)
            assertEquals("後", c.afterCursor)
            assertTrue(c.moveCaret(-2))
            assertEquals(3, c.caret)
            assertTrue(c.backspace())
            assertEquals("niao", c.raw)
            c.input("h")
            assertEquals("nihao", c.raw)
            assertTrue(c.moveCaret(-100))
            assertEquals(0, c.caret)
            assertTrue(c.backspace())
            assertEquals("nihao", c.raw)
            assertTrue(c.moveCaret(100))
            assertEquals(5, c.caret)
            repeat(5) { assertTrue(c.backspace()) }
            assertEquals("", c.raw)
            assertFalse(c.backspace())
            f.assertHost()
            f.ic.assertNoPendingWrites()
        }
    }

    @Test fun candidateAndSpaceCommitExactlyOnceAndRejectOldResults() {
        instrumentation.runOnMainSync {
            val f = fixture()
            val c = f.controller
            c.input("nihao")
            val old = c.revision
            val word = Candidate("你好", "简")
            f.offer(word)
            assertTrue(c.select(word, old))
            f.assertHost("前你好後", 3)
            assertEquals(listOf("你好"), f.ic.commits)
            assertEquals("", c.raw)
            c.acceptResults(old, listOf(word))
            assertFalse(c.select(word, old))
            assertTrue(c.literal())
            assertEquals(listOf("你好"), f.ic.commits)
            c.input("hello")
            f.offer(Candidate("hello", "EN"))
            assertTrue(c.space())
            f.assertHost("前你好hello 後", 9)
            c.input("x")
            assertTrue(c.space())
            f.assertHost("前你好hello x 後", 11)
            assertEquals(listOf("你好", "hello ", "x "), f.ic.commits)
            assertEquals(0, f.ic.composingWrites)
        }
    }

    @Test fun selectedPrefixCommitsOnlyThatPrefixAndKeepsSuffixInsideTheKeyboard() {
        instrumentation.runOnMainSync {
            val f = fixture()
            val c = f.controller
            c.input("nihaoshijie")
            val old = c.revision
            val prefix = Candidate("你好", "简",
                consumption = CandidateConsumption(c.raw, 5, "Rime", "prefix"))
            f.offer(prefix)
            assertTrue(c.select(prefix))
            f.assertHost("前你好後", 3)
            assertEquals(listOf("你好"), f.ic.commits)
            assertEquals("shijie", c.raw)
            assertEquals(6, c.caret)
            assertEquals("前你好", c.beforeCursor)
            assertEquals("後", c.afterCursor)
            assertFalse(c.select(prefix, old))
            assertTrue(c.moveCaret(-1))
            assertTrue(c.backspace())
            assertEquals("shije", c.raw)
            c.input("i")
            assertEquals("shijie", c.raw)
            f.assertHost("前你好後", 3)
            val suffix = Candidate("世界", "简")
            f.offer(suffix)
            assertTrue(c.select(suffix))
            f.assertHost("前你好世界後", 5)
            assertEquals(listOf("你好", "世界"), f.ic.commits)
            assertEquals(0, f.ic.composingWrites)
            assertEquals(0, f.ic.selectionWrites)
        }
    }

    @Test fun refusedPrefixAndInvalidConsumptionLeaveTheWholePendingEncodingAvailable() {
        instrumentation.runOnMainSync {
            val f = fixture()
            val c = f.controller
            c.input("nihaoshijie")
            val prefix = Candidate("你好", "简",
                consumption = CandidateConsumption(c.raw, 5, "Rime", "prefix"))
            val invalid = prefix.copy(consumption = CandidateConsumption("other", 5, "Rime", "prefix"))
            f.offer(invalid)
            assertFalse(c.select(invalid))
            assertTrue(f.ic.commits.isEmpty())
            f.offer(prefix)
            f.ic.acceptCommit = false
            val revision = c.revision
            assertFalse(c.select(prefix))
            assertEquals("nihaoshijie", c.raw)
            assertEquals(revision, c.revision)
            f.assertHost()
            f.ic.acceptCommit = true
            assertTrue(c.select(prefix))
            assertEquals("shijie", c.raw)
            f.assertHost("前你好後", 3)
        }
    }

    @Test fun widthChangesAndCalculatorReplacementStayPendingAndConvertOnlyOnCommit() {
        instrumentation.runOnMainSync {
            var width = false
            val f = fixture(transform = { if (width) CharacterWidthConverter.swap(it) else it })
            val c = f.controller
            c.input("ｶﾞaＡ😀")
            val old = c.revision
            val stale = Candidate("过期", "简")
            f.offer(stale)
            width = true
            c.refreshOutput()
            assertEquals("ｶﾞaＡ😀", c.raw)
            assertEquals(c.raw, f.queries.last())
            assertFalse(c.select(stale, old))
            f.assertHost()
            f.ic.assertNoPendingWrites()
            assertTrue(c.literal())
            f.assertHost("前ガａA😀後", 6)
            assertEquals(listOf("ガａA😀"), f.ic.commits)
            width = false
            c.input("sqrt(i)")
            f.offer(Candidate("0.7…+(0.7…)i", "∑"))
            assertTrue(c.select(c.candidates.single()))
            assertEquals("0.7+(0.7)i", c.raw)
            f.assertHost("前ガａA😀後", 6)
            c.input("+1")
            assertTrue(c.literal())
            f.assertHost("前ガａA😀0.7+(0.7)i+1後", 18)
            assertEquals(listOf("ガａA😀", "0.7+(0.7)i+1"), f.ic.commits)
        }
    }

    @Test fun changingModeConfirmsPendingTextWithoutReinsertingInlineText() {
        instrumentation.runOnMainSync {
            val f = fixture(inline = true)
            val c = f.controller
            assertTrue(c.inlineEnabled)
            c.input("nihao")
            assertTrue(c.moveCaret(-3))
            assertEquals(3, f.editor.selectionStart)
            val old = c.revision
            val word = Candidate("你好", "简")
            f.offer(word)
            assertTrue(c.setInlineEnabled(false))
            assertFalse(c.inlineEnabled)
            assertEquals("", c.raw)
            f.assertHost("前nihao後", 3)
            assertTrue("Inline text already exists in the editor", f.ic.commits.isEmpty())
            assertFalse(c.select(word, old))
            c.input("x")
            f.assertHost("前nihao後", 3)
            assertTrue(c.setInlineEnabled(true))
            assertTrue(c.inlineEnabled)
            assertEquals("", c.raw)
            f.assertHost("前nixhao後", 4)
            assertEquals(listOf("x"), f.ic.commits)
            val writes = f.ic.finishes
            assertTrue(c.setInlineEnabled(true))
            assertEquals(writes, f.ic.finishes)
            assertEquals(listOf("x"), f.ic.commits)
        }
    }

    @Test fun changingAwayFromInlineDoesNotDuplicateTextAfterTheHostDropsItsSpan() {
        instrumentation.runOnMainSync {
            val f = fixture(inline = true)
            val c = f.controller
            c.input("ling")
            BaseInputConnection.removeComposingSpans(f.editor.text)
            assertTrue(c.setInlineEnabled(false))
            f.assertHost("前ling後", 5)
            assertTrue(f.ic.commits.isEmpty())
            assertEquals("", c.raw)
        }
    }

    @Test fun refusedFinishOrCommitKeepsModeAndPendingInputForRetry() {
        instrumentation.runOnMainSync {
            for (inline in listOf(true, false)) {
                val f = fixture(inline)
                val c = f.controller
                c.input("ab")
                f.ic.acceptFinish = false
                f.ic.acceptCommit = false
                assertFalse(c.setInlineEnabled(!inline))
                assertEquals(inline, c.inlineEnabled)
                assertEquals("ab", c.raw)
                assertEquals(if (inline) "前ab後" else "前後", f.editor.text.toString())
                f.ic.acceptFinish = true
                f.ic.acceptCommit = true
                assertTrue(c.setInlineEnabled(!inline))
                assertEquals(!inline, c.inlineEnabled)
                assertEquals("", c.raw)
                f.assertHost("前ab後", 3)
            }
        }
    }

    @Test fun existingSelectionIsUntouchedUntilAnExplicitCandidateReplacesIt() {
        instrumentation.runOnMainSync {
            val f = fixture()
            f.editor.setText("前中後")
            f.editor.setSelection(1, 2)
            val c = f.controller
            c.input("nihao")
            f.assertHost("前中後", 1, 2)
            f.ic.assertNoPendingWrites()
            val word = Candidate("你好", "简")
            f.offer(word)
            assertTrue(c.select(word))
            f.assertHost("前你好後", 3)
            assertEquals(listOf("你好"), f.ic.commits)
        }
    }

    @Test fun externalSelectionChangeCancelsPendingInputWithoutWritingAtTheNewPosition() {
        instrumentation.runOnMainSync {
            for (selection in listOf(0 to 0, 0 to 2)) {
                val f = fixture()
                val c = f.controller
                c.input("ling")
                val old = c.revision
                val word = Candidate("零", "简")
                f.offer(word)
                c.externalSelection(0, 0, -1) // Queued callback; actual host caret is still 1.
                assertEquals("ling", c.raw)
                c.externalSelection(1, 1, -1)
                assertEquals("ling", c.raw)
                f.editor.setSelection(selection.first, selection.second)
                c.externalSelection(selection.first, selection.second, -1)
                assertEquals("", c.raw)
                c.acceptResults(old, listOf(word))
                assertFalse(c.select(word, old))
                assertTrue(c.literal())
                f.assertHost(start = selection.first, end = selection.second)
                f.ic.assertNoPendingWrites()
            }
        }
    }

    @Test fun endingTheSessionCancelsPendingTextAndDoesNotTransferItToAnotherEditor() {
        instrumentation.runOnMainSync {
            val f = fixture()
            val c = f.controller
            c.input("private")
            c.startInputView()
            assertEquals("private", c.raw)
            val old = c.revision
            val word = Candidate("旧候选", "简")
            f.offer(word)
            c.finish()
            assertEquals("", c.raw)
            f.assertHost()
            f.ic.assertNoPendingWrites()
            c.startInputView()
            assertEquals("", c.raw)
            val next = EditText(instrumentation.targetContext).apply { setText("新字段"); setSelection(3) }
            f.currentConnection = requireNotNull(next.onCreateInputConnection(EditorInfo()))
            c.start()
            assertFalse(c.inlineEnabled)
            c.acceptResults(old, listOf(word))
            assertFalse(c.select(word, old))
            assertEquals("新字段", next.text.toString())
            c.input("a")
            assertEquals("新字段", next.text.toString())
            assertTrue(c.literal())
            assertEquals("新字段a", next.text.toString())
        }
    }

    @Test fun missingExtractedPositionStillAllowsLocalEditingAndExplicitCommit() {
        instrumentation.runOnMainSync {
            val f = fixture()
            f.ic.exposePosition = false
            val c = f.controller
            c.input("ab")
            assertTrue(c.moveCaret(-1))
            c.input("x")
            assertEquals("axb", c.raw)
            f.assertHost()
            f.ic.assertNoPendingWrites()
            assertTrue(c.literal())
            f.assertHost("前axb後", 4)
        }
    }

    @Test fun disconnectedEditorCannotConfirmPendingTextOrSwitchItsMode() {
        instrumentation.runOnMainSync {
            val f = fixture()
            val c = f.controller
            c.input("ab")
            f.currentConnection = null
            assertFalse(c.literal())
            assertFalse(c.setInlineEnabled(true))
            assertFalse(c.inlineEnabled)
            assertEquals("ab", c.raw)
            f.assertHost()
            f.ic.assertNoPendingWrites()
            f.currentConnection = f.ic
            assertTrue(c.setInlineEnabled(true))
            f.assertHost("前ab後", 3)
            assertEquals(listOf("ab"), f.ic.commits)
        }
    }

    @Test fun aPreviouslyReadableTargetMustBeReadableAgainBeforeADeferredCommit() {
        instrumentation.runOnMainSync {
            for (losePosition in listOf(true, false)) {
                val f = fixture()
                val c = f.controller
                c.input("ab")
                if (losePosition) f.ic.exposePosition = false else f.ic.exposeSurroundingText = false
                assertFalse(c.literal())
                assertFalse(c.setInlineEnabled(true))
                assertEquals("ab", c.raw)
                assertFalse(c.inlineEnabled)
                f.assertHost()
                f.ic.assertNoPendingWrites()
                f.ic.exposePosition = true
                f.ic.exposeSurroundingText = true
                assertTrue(c.literal())
                f.assertHost("前ab後", 3)
                assertEquals(listOf("ab"), f.ic.commits)
            }
        }
    }

    @Test fun changedSelectionCallbackCancelsAnUnreadablePreviouslyKnownTarget() {
        instrumentation.runOnMainSync {
            val f = fixture()
            val c = f.controller
            c.input("ab")
            val revision = c.revision
            val word = Candidate("原目标", "简")
            f.offer(word)
            f.ic.exposePosition = false
            f.ic.exposeSurroundingText = false
            f.editor.setSelection(0)
            c.externalSelection(0, 0, -1)
            assertEquals("", c.raw)
            assertFalse(c.select(word, revision))
            assertTrue(c.literal())
            f.assertHost(start = 0)
            f.ic.assertNoPendingWrites()
        }
    }

    @Test fun defaultModeKeepsExistingInlineBehaviorWithoutAnExplicitPreference() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            val c = CompositionController({ ic }, { _, _ -> }, {})
            assertTrue(c.inlineEnabled)
            c.input("nihao")
            assertEquals("nihao", editor.text.toString())
            assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.text))
            val word = Candidate("你好", "简")
            c.acceptResults(c.revision, listOf(word))
            assertTrue(c.select(word))
            assertEquals("你好", editor.text.toString())
        }
    }

    @Test fun segmentedWidthConversionDoesNotTransformTheRemainingRawTwice() {
        instrumentation.runOnMainSync {
            val f = fixture(transform = CharacterWidthConverter::swap)
            val c = f.controller
            c.input("aｶﾞ")
            val prefix = Candidate("Ａ", "EN",
                consumption = CandidateConsumption(c.raw, 1, "Rime", "synthetic-width"))
            f.offer(prefix)
            assertTrue(c.select(prefix))
            assertEquals("ｶﾞ", c.raw)
            f.assertHost("前A後", 2)
            assertTrue(c.literal())
            f.assertHost("前Aガ後", 3)
            assertEquals(listOf("A", "ガ"), f.ic.commits)
            assertEquals(0, f.ic.composingWrites)
        }
    }

    @Test fun existingLengthLimitConfirmsOverflowOnceInTheOutputEncoding() {
        instrumentation.runOnMainSync {
            val f = fixture(transform = CharacterWidthConverter::swap)
            val c = f.controller
            c.input("Ａ".repeat(64))
            f.assertHost()
            f.ic.assertNoPendingWrites()
            c.input("ｶﾞ")
            assertEquals("ｶﾞ", c.raw)
            f.assertHost("前" + "A".repeat(64) + "後", 65)
            c.input("A".repeat(65))
            assertEquals("", c.raw)
            f.assertHost("前" + "A".repeat(64) + "ガ" + "Ａ".repeat(65) + "後", 131)
            assertEquals(listOf("A".repeat(64), "ガ", "Ａ".repeat(65)), f.ic.commits)
            assertEquals(0, f.ic.composingWrites)
        }
    }
}
