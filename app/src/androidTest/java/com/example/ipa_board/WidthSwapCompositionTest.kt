package com.example.ipa_board

import android.content.Context
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.Candidate
import com.example.ipa_board.ime.CandidateConsumption
import com.example.ipa_board.ime.CandidateKind
import com.example.ipa_board.ime.CompositionController
import com.example.ipa_board.ime.SelectionTransformResult
import org.junit.Assert.*
import org.junit.Test

/** Width changes use only disposable editors and synthetic text, never the active application. */
class WidthSwapCompositionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class OwnedEditor(context: Context, inline: Boolean, initiallySwapped: Boolean) {
        val editor = EditText(context)
        val widthModel = WidthSwapController().apply { if (initiallySwapped) { cycle(); cycle() } }
        var swapEnabled: Boolean
            get() = widthModel.state != WidthSwapState.OFF
            set(value) {
                if (value) while (widthModel.state != WidthSwapState.LOCKED) widthModel.cycle()
                else widthModel.reset()
            }
        var connectionAvailable = true
        var acceptCommit = true
        var acceptComposition = true
        var acceptFinishComposition = true
        var rejectedComposition: String? = null
        var rejectedSelectionWrites = 0
        val commits = mutableListOf<String>()
        private val realConnection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        val connection = object : InputConnectionWrapper(realConnection, false) {
            override fun finishComposingText(): Boolean =
                acceptFinishComposition && super.finishComposingText()
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                acceptComposition && text?.toString() != rejectedComposition &&
                    super.setComposingText(text, newCursorPosition)
            override fun setSelection(start: Int, end: Int): Boolean {
                if (rejectedSelectionWrites > 0) {
                    rejectedSelectionWrites--
                    return false
                }
                return super.setSelection(start, end)
            }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                commits += text.toString()
                return acceptCommit && super.commitText(text, newCursorPosition)
            }
        }
        val queries = mutableListOf<String>()
        val controller = CompositionController({ if (connectionAvailable) connection else null }, { _, raw -> queries += raw }, {},
            initialInlineEnabled = inline)
        fun input(text: String): Boolean {
            val prepared = widthModel.prepare(text)
            val accepted = controller.input(prepared.text)
            if (accepted) widthModel.accepted(prepared)
            return accepted
        }
        fun seed(text: String, position: Int) {
            editor.setText(text)
            editor.setSelection(position)
        }
    }

    private fun withEditor(inline: Boolean = true, swapped: Boolean = false,
        block: (OwnedEditor) -> Unit) {
        instrumentation.runOnMainSync {
            val owned = OwnedEditor(instrumentation.targetContext, inline, swapped)
            try { block(owned) } finally { owned.controller.finish() }
        }
    }

    @Test fun togglingWithoutASelectionPreservesPendingTextCaretAndEditorInBothModes() {
        for (inline in listOf(true, false)) withEditor(inline) { owned ->
            val controller = owned.controller
            owned.seed("前後", 1)
            owned.input("aＡ😀")
            assertTrue(controller.moveCaret(-2))
            val document = owned.editor.text.toString()
            val selection = owned.editor.selectionStart to owned.editor.selectionEnd
            val caret = controller.caret
            val displayCaret = controller.displayCaret
            owned.swapEnabled = true
            assertEquals(SelectionTransformResult.NONE, controller.transformSelection(CharacterWidthConverter::swap))
            assertEquals("aＡ😀", controller.raw)
            assertEquals("aＡ😀", controller.displayText)
            assertEquals(caret, controller.caret)
            assertEquals(displayCaret, controller.displayCaret)
            assertEquals(selection, owned.editor.selectionStart to owned.editor.selectionEnd)
            assertEquals(document, owned.editor.text.toString())
            assertTrue(controller.literal())
            assertEquals("前aＡ😀後", owned.editor.text.toString())
            assertTrue("A locked mode survives literal commit", owned.swapEnabled)
            owned.input("b")
            assertEquals("ｂ", controller.raw)
        }
    }

    @Test fun subsequentInputMiddleInsertionAndBackspacePreserveEachEarlierWidthChoice() {
        for (inline in listOf(true, false)) withEditor(inline) { owned ->
            val controller = owned.controller
            owned.seed("前後", 1)
            owned.input("ab")
            owned.swapEnabled = true
            owned.input("Ｃd")
            assertEquals("abCｄ", controller.raw)
            assertEquals("abCｄ", controller.displayText)
            assertTrue(controller.moveCaret(-1))
            owned.input("e")
            assertEquals("abCｅｄ", controller.raw)
            assertEquals("abCｅｄ", controller.displayText)
            assertTrue(controller.backspace())
            assertEquals("abCｄ", controller.raw)
            assertEquals("abCｄ", controller.displayText)
            owned.swapEnabled = false
            owned.input("f")
            assertEquals("abCfｄ", controller.raw)
            assertEquals("abCfｄ", controller.displayText)
            assertEquals("abCfｄ", owned.queries.last())
            assertEquals("前", controller.beforeCursor)
            assertEquals("後", controller.afterCursor)
            assertTrue(controller.literal())
            assertEquals("前abCfｄ後", owned.editor.text.toString())
        }
    }

    @Test fun frozenKanaLengthMappingKeepsAnInteriorInsertionAtItsDisplayedBoundary() {
        for (inline in listOf(true, false)) withEditor(inline, swapped = true) { owned ->
            val controller = owned.controller
            owned.seed("前後", 1)
            owned.input("ｶﾞ")
            assertEquals("ガ", controller.raw)
            assertEquals("ガ", controller.displayText)
            assertEquals(1, controller.caret)
            assertEquals(1, controller.displayCaret)
            owned.swapEnabled = false
            owned.input("x")
            assertTrue(controller.moveCaret(-1))
            owned.input("Ａ")
            assertEquals("ガＡx", controller.raw)
            assertEquals("ガＡx", controller.displayText)
            assertTrue(controller.backspace())
            assertEquals("ガx", controller.displayText)
            assertTrue(controller.literal())
            assertEquals("前ガx後", owned.editor.text.toString())
        }
    }

    @Test fun overflowCommitsFrozenPendingThenUsesTheNewModeExactlyOnce() {
        for (inline in listOf(true, false)) withEditor(inline) { owned ->
            val controller = owned.controller
            owned.input("a".repeat(64))
            owned.swapEnabled = true
            owned.input("b")
            assertEquals("ｂ", controller.raw)
            assertEquals("ｂ", controller.displayText)
            assertTrue("Overflow must not disable the locked mode", owned.swapEnabled)
            owned.swapEnabled = false
            assertTrue(controller.literal())
            assertEquals("a".repeat(64) + "ｂ", owned.editor.text.toString())
            owned.input("c")
            owned.swapEnabled = true
            owned.input("Ａ".repeat(65))
            assertEquals("", controller.raw)
            assertEquals("a".repeat(64) + "ｂc" + "A".repeat(65), owned.editor.text.toString())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(owned.editor.text))
            assertTrue(owned.swapEnabled)
        }
    }

    @Test fun rejectedLiteralCommitRetainsFrozenOutputForARetryAfterAnotherToggle() {
        for (inline in listOf(true, false)) withEditor(inline, swapped = true) { owned ->
            val controller = owned.controller
            owned.input("aＡｶﾞ")
            val document = owned.editor.text.toString()
            owned.acceptCommit = false
            assertFalse(controller.literal())
            assertEquals("ａAガ", controller.raw)
            assertEquals("ａAガ", controller.displayText)
            assertEquals(document, owned.editor.text.toString())
            assertTrue("A rejected commit must not reset the switch", owned.swapEnabled)
            owned.swapEnabled = false
            owned.acceptCommit = true
            assertTrue(controller.literal())
            assertEquals("ａAガ", owned.editor.text.toString())
            assertEquals(listOf("ａAガ", "ａAガ"), owned.commits)
        }
    }

    @Test fun partialCandidateSelectionKeepsTheSuffixWidthChosenWhenItWasTyped() {
        for (inline in listOf(true, false)) withEditor(inline) { owned ->
            val controller = owned.controller
            owned.input("ni")
            owned.swapEnabled = true
            owned.input("haoＡ")
            val candidate = Candidate("你", "简", consumption =
                CandidateConsumption(controller.raw, 2, "Rime", "owned-prefix"))
            controller.acceptResults(controller.revision, listOf(candidate))
            assertTrue(controller.select(candidate))
            assertEquals("ｈａｏA", controller.raw)
            assertEquals("ｈａｏA", controller.displayText)
            assertEquals(if (inline) "你ｈａｏA" else "你", owned.editor.text.toString())
            assertTrue("Committing only a prefix must keep the current mode", owned.swapEnabled)
            owned.input("z")
            assertTrue(controller.literal())
            assertEquals("你ｈａｏAｚ", owned.editor.text.toString())
            assertTrue(owned.swapEnabled)
        }
    }

    @Test fun candidatesAreCommittedVerbatimWithoutRestoringOrConvertingTheirWidth() {
        for (inline in listOf(true, false)) for (initial in listOf(false, true)) withEditor(inline, initial) { owned ->
            val controller = owned.controller
            owned.input("hello")
            assertEquals(if (initial) "ｈｅｌｌｏ" else "hello", owned.queries.last())
            val candidate = Candidate("Hello", "EN")
            controller.acceptResults(controller.revision, listOf(candidate))
            owned.swapEnabled = !initial
            assertTrue(controller.select(candidate))
            assertEquals("Hello", owned.editor.text.toString())
            assertEquals(!initial, owned.swapEnabled)
        }
    }

    @Test fun selectionConversionPreservesForwardAndReverseRangesWhenKanaChangesLength() {
        for ((original, expected) in listOf("ｶﾞ" to "ガ", "パ" to "ﾊﾟ")) {
            for (reverse in listOf(false, true)) withEditor { owned ->
                owned.seed("x${original}z", 1)
                val originalEnd = 1 + original.length
                owned.editor.setSelection(if (reverse) originalEnd else 1, if (reverse) 1 else originalEnd)
                assertEquals(SelectionTransformResult.APPLIED,
                    owned.controller.transformSelection(CharacterWidthConverter::swap))
                assertEquals("x${expected}z", owned.editor.text.toString())
                val expectedEnd = 1 + expected.length
                assertEquals(if (reverse) expectedEnd else 1, owned.editor.selectionStart)
                assertEquals(if (reverse) 1 else expectedEnd, owned.editor.selectionEnd)
                assertEquals("", owned.controller.raw)
                owned.swapEnabled = true
                owned.editor.setSelection(expectedEnd)
                owned.input("a")
                assertEquals("ａ", owned.controller.raw)
                assertEquals("x${expected}ａz", owned.editor.text.toString())
            }
        }
    }

    @Test fun deletingTheLastPendingCharacterDoesNotResetTheInputMode() {
        for (inline in listOf(true, false)) withEditor(inline, swapped = true) { owned ->
            owned.input("a")
            assertTrue(owned.controller.backspace())
            assertEquals("", owned.controller.raw)
            assertEquals("", owned.editor.text.toString())
            assertTrue(owned.swapEnabled)
            owned.input("b")
            assertEquals("ｂ", owned.controller.raw)
            assertTrue(owned.controller.literal())
            assertEquals("ｂ", owned.editor.text.toString())
            assertTrue(owned.swapEnabled)
        }
    }

    @Test fun convertingAnInteriorSelectionUpdatesTheRealCompositionAndEngineInput() {
        for (reverse in listOf(false, true)) withEditor { owned ->
            val controller = owned.controller
            owned.seed("前後", 1)
            owned.input("aｶﾞＡ")
            owned.editor.setSelection(if (reverse) 4 else 2, if (reverse) 2 else 4)
            assertEquals(SelectionTransformResult.APPLIED, controller.transformSelection(CharacterWidthConverter::swap))
            assertEquals("aガＡ", controller.raw)
            assertEquals("aガＡ", controller.displayText)
            assertEquals("aガＡ", owned.queries.last())
            assertEquals("前aガＡ後", owned.editor.text.toString())
            assertEquals(if (reverse) 3 else 2, owned.editor.selectionStart)
            assertEquals(if (reverse) 2 else 3, owned.editor.selectionEnd)
            assertEquals(1, BaseInputConnection.getComposingSpanStart(owned.editor.text))
            assertEquals(4, BaseInputConnection.getComposingSpanEnd(owned.editor.text))
            owned.swapEnabled = true
            owned.editor.setSelection(4)
            controller.externalSelection(4, 4, 4)
            owned.input("b")
            assertEquals("aガＡｂ", controller.raw)
            assertTrue(controller.literal())
            assertEquals("前aガＡｂ後", owned.editor.text.toString())
        }
    }

    @Test fun aSelectionCrossingOrContainingCompositionConvertsOnlyTheSelectedRange() {
        val cases = listOf(Triple(0, 2, "前ａＡ後"), Triple(2, 4, "前aA後"), Triple(0, 4, "前ａA後"))
        for ((start, end, expected) in cases) withEditor { owned ->
            owned.seed("前後", 1)
            owned.input("aＡ")
            owned.editor.setSelection(start, end)
            assertEquals(SelectionTransformResult.APPLIED,
                owned.controller.transformSelection(CharacterWidthConverter::swap))
            assertEquals(expected, owned.editor.text.toString())
            assertEquals("", owned.controller.raw)
            assertEquals(start, owned.editor.selectionStart)
            assertEquals(end, owned.editor.selectionEnd)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(owned.editor.text))
        }
    }

    @Test fun rejectedSelectionConversionRetainsTheSelectedTextAndPendingTarget() {
        for (inline in listOf(true, false)) withEditor(inline, swapped = true) { owned ->
            owned.seed("前ｶﾞ後", 1)
            owned.editor.setSelection(1, 3)
            if (!inline) owned.input("a")
            owned.acceptCommit = false
            assertEquals(SelectionTransformResult.FAILED,
                owned.controller.transformSelection(CharacterWidthConverter::swap))
            assertEquals("前ｶﾞ後", owned.editor.text.toString())
            assertEquals(1, owned.editor.selectionStart)
            assertEquals(3, owned.editor.selectionEnd)
            assertEquals(if (inline) "" else "ａ", owned.controller.raw)
            assertTrue(owned.swapEnabled)
            owned.acceptCommit = true
            if (!inline) {
                assertTrue(owned.controller.literal())
                assertEquals("前ａ後", owned.editor.text.toString())
            }
        }
    }

    @Test fun nonInlineSelectionConversionReanchorsTheOriginalDeferredTargetAfterItsLengthChanges() {
        for (reverse in listOf(false, true)) withEditor(inline = false) { owned ->
            val controller = owned.controller
            owned.seed("前ｶﾞ後", 1)
            owned.editor.setSelection(if (reverse) 3 else 1, if (reverse) 1 else 3)
            owned.input("ab")
            assertEquals("前ｶﾞ後", owned.editor.text.toString())
            assertEquals(SelectionTransformResult.APPLIED, controller.transformSelection(CharacterWidthConverter::swap))
            assertEquals("前ガ後", owned.editor.text.toString())
            assertEquals("ab", controller.raw)
            assertEquals("ab", controller.displayText)
            assertEquals(if (reverse) 2 else 1, owned.editor.selectionStart)
            assertEquals(if (reverse) 1 else 2, owned.editor.selectionEnd)
            owned.swapEnabled = true
            owned.input("c")
            assertEquals("abｃ", controller.raw)
            assertTrue(controller.literal())
            assertEquals("前abｃ後", owned.editor.text.toString())
            assertTrue(owned.swapEnabled)
        }
    }

    @Test fun replacingAFullLengthInternalSelectionDoesNotFlushTheOldComposition() {
        for (reverse in listOf(false, true)) withEditor { owned ->
            val controller = owned.controller
            owned.seed("前後", 1)
            owned.input("a".repeat(64))
            owned.editor.setSelection(if (reverse) 65 else 1, if (reverse) 1 else 65)
            controller.externalSelection(owned.editor.selectionStart, owned.editor.selectionEnd, 65)
            owned.swapEnabled = true
            owned.input("b")
            assertEquals("ｂ", controller.raw)
            assertEquals("前ｂ後", owned.editor.text.toString())
            assertTrue("Replacing the selected buffer must not commit its former contents", owned.commits.isEmpty())
            assertEquals(1, BaseInputConnection.getComposingSpanStart(owned.editor.text))
            assertEquals(2, BaseInputConnection.getComposingSpanEnd(owned.editor.text))
        }
    }

    @Test fun backspaceDeletesAnInternalSelectionAtTheCompositionStartInEitherDirection() {
        for (reverse in listOf(false, true)) withEditor(swapped = true) { owned ->
            val controller = owned.controller
            owned.seed("前後", 1)
            owned.input("abc")
            owned.editor.setSelection(if (reverse) 3 else 1, if (reverse) 1 else 3)
            controller.externalSelection(owned.editor.selectionStart, owned.editor.selectionEnd, 4)
            assertTrue(controller.backspace())
            assertEquals("ｃ", controller.raw)
            assertEquals(0, controller.caret)
            assertEquals("前ｃ後", owned.editor.text.toString())
            assertTrue(owned.swapEnabled)
        }
    }

    @Test fun singleConvertsOneAcceptedCharacterThenLeavesLaterInputInItsOriginalWidth() {
        for (inline in listOf(true, false)) withEditor(inline) { owned ->
            assertEquals(WidthSwapState.SINGLE, owned.widthModel.cycle())
            assertFalse(owned.input(""))
            assertEquals(WidthSwapState.SINGLE, owned.widthModel.state)
            assertTrue(owned.input("ｶﾞx"))
            assertEquals("ガx", owned.controller.raw)
            assertEquals("ガx", owned.queries.last())
            assertEquals(WidthSwapState.OFF, owned.widthModel.state)
            assertTrue(owned.input("y"))
            assertEquals("ガxy", owned.controller.raw)
            assertTrue(owned.controller.literal())
            assertEquals("ガxy", owned.editor.text.toString())
        }
    }

    @Test fun aRejectedComposingDisplayStillConsumesSingleOnceTheActualInputIsRetained() {
        withEditor { owned ->
            owned.widthModel.cycle()
            owned.acceptComposition = false
            assertTrue(owned.input("ab"))
            assertEquals("ａb", owned.controller.raw)
            assertEquals("ａb", owned.queries.last())
            assertEquals("", owned.editor.text.toString())
            assertEquals(WidthSwapState.OFF, owned.widthModel.state)
            owned.acceptComposition = true
            assertTrue(owned.controller.literal())
            assertEquals("ａb", owned.editor.text.toString())
        }
    }

    @Test fun missingConnectionDoesNotConsumeSingleOrOverwriteAlreadyPendingInput() {
        for (inline in listOf(true, false)) withEditor(inline) { owned ->
            assertTrue(owned.input("q"))
            owned.widthModel.cycle()
            owned.connectionAvailable = false
            assertFalse(owned.input("a"))
            assertEquals("q", owned.controller.raw)
            assertEquals(WidthSwapState.SINGLE, owned.widthModel.state)
            owned.connectionAvailable = true
            assertTrue(owned.input("a"))
            assertEquals("qａ", owned.controller.raw)
            assertEquals(WidthSwapState.OFF, owned.widthModel.state)
            assertTrue(owned.controller.literal())
            assertEquals("qａ", owned.editor.text.toString())
        }
    }

    @Test fun rejectedOverflowFlushDoesNotConsumeSingleAndRetryConvertsOnlyTheNewInput() {
        for (inline in listOf(true, false)) withEditor(inline) { owned ->
            val old = "a".repeat(64)
            assertTrue(owned.input(old))
            owned.widthModel.cycle()
            owned.acceptCommit = false
            assertFalse(owned.input("b"))
            assertEquals(old, owned.controller.raw)
            assertEquals(WidthSwapState.SINGLE, owned.widthModel.state)
            owned.acceptCommit = true
            assertTrue(owned.input("b"))
            assertEquals("ｂ", owned.controller.raw)
            assertEquals(WidthSwapState.OFF, owned.widthModel.state)
            assertTrue(owned.controller.literal())
            assertEquals(old + "ｂ", owned.editor.text.toString())
        }
    }

    @Test fun failedInternalSelectionRollbackClearsTheHostSpanBeforeAnotherDirectCommit() {
        withEditor { owned ->
            owned.seed("前後", 1)
            assertTrue(owned.input("ｶﾞa"))
            owned.editor.setSelection(1, 3)
            owned.rejectedComposition = "ｶﾞa"
            owned.rejectedSelectionWrites = 1
            assertEquals(SelectionTransformResult.FAILED,
                owned.controller.transformSelection(CharacterWidthConverter::swap))
            assertEquals("前ガa後", owned.editor.text.toString())
            assertEquals("", owned.controller.raw)
            assertEquals("Discarding the mirror must also release the actual editor span", -1,
                BaseInputConnection.getComposingSpanStart(owned.editor.text))
            assertTrue(owned.connection.commitText("X", 1))
            assertEquals("前ガaX後", owned.editor.text.toString())
        }
    }

    @Test fun failedExternalSelectionRollbackDropsTheOldRangeWithoutReusingItForAnotherCommit() {
        withEditor { owned ->
            owned.seed("前後", 1)
            assertTrue(owned.input("ｶﾞa"))
            owned.editor.setSelection(0, 3)
            owned.rejectedSelectionWrites = 2
            assertEquals(SelectionTransformResult.FAILED,
                owned.controller.transformSelection(CharacterWidthConverter::swap))
            assertEquals("前ガa後", owned.editor.text.toString())
            assertEquals("", owned.controller.raw)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(owned.editor.text))
            assertTrue(owned.connection.commitText("X", 1))
            assertEquals("前ガXa後", owned.editor.text.toString())
        }
    }

    @Test fun aRollbackWhoseSpanCannotBeFinishedBlocksWritesUntilAnExplicitNewInputSession() {
        withEditor { owned ->
            val controller = owned.controller
            owned.seed("前後", 1)
            assertTrue(owned.input("ｶﾞa"))
            owned.editor.setSelection(1, 3)
            owned.rejectedComposition = "ｶﾞa"
            owned.rejectedSelectionWrites = 1
            owned.acceptFinishComposition = false
            assertEquals(SelectionTransformResult.FAILED,
                controller.transformSelection(CharacterWidthConverter::swap))
            assertEquals("前ガa後", owned.editor.text.toString())
            assertEquals("", controller.raw)
            assertTrue("The refusing editor still owns the span",
                BaseInputConnection.getComposingSpanStart(owned.editor.text) >= 0)
            assertFalse("Empty raw is not safe permission to commit over an unknown span", controller.literal())
            assertFalse(owned.input("X"))
            val candidate = Candidate("候选", "简", kind = CandidateKind.PREDICTION)
            controller.acceptResults(controller.revision, listOf(candidate))
            assertFalse(controller.select(candidate))
            assertEquals(SelectionTransformResult.FAILED,
                controller.transformSelection(CharacterWidthConverter::swap))
            controller.replaceRaw("replacement")
            assertEquals("", controller.raw)
            assertFalse(controller.setInlineEnabled(false))
            assertFalse(controller.backspace())
            assertFalse(controller.moveCaret(-1))
            controller.finish()
            controller.startInputView()
            assertFalse(controller.literal())
            assertFalse(owned.input("X"))
            assertEquals("前ガa後", owned.editor.text.toString())

            owned.acceptFinishComposition = true
            owned.rejectedComposition = null
            controller.start()
            assertTrue(owned.input("X"))
            assertEquals("X", controller.raw)
            assertTrue(controller.literal())
            assertEquals("前ガaX後", owned.editor.text.toString())
        }
    }

    @Test fun oversizedInputReplacesTheInternalSelectionOrInsertsAtTheExistingCaret() {
        for ((from, to) in listOf(0 to 64, 64 to 0, 10 to 20, 32 to 32)) withEditor { owned ->
            val old = "a".repeat(64)
            val inserted = "b".repeat(65)
            owned.seed("前後", 1)
            assertTrue(owned.input(old))
            owned.editor.setSelection(1 + from, 1 + to)
            owned.controller.externalSelection(1 + from, 1 + to, 65)
            assertTrue(owned.input(inserted))
            val expected = old.take(minOf(from, to)) + inserted + old.drop(maxOf(from, to))
            assertEquals("前${expected}後", owned.editor.text.toString())
            assertEquals("", owned.controller.raw)
            assertEquals(1 + minOf(from, to) + inserted.length, owned.editor.selectionStart)
            assertEquals(owned.editor.selectionStart, owned.editor.selectionEnd)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(owned.editor.text))
        }
    }

    @Test fun rejectedOversizedInternalEditPreservesTheBufferSelectionAndSingleModeForRetry() {
        for ((from, to) in listOf(20 to 10, 32 to 32)) withEditor { owned ->
            val old = "a".repeat(64)
            val inserted = "b".repeat(65)
            owned.seed("前後", 1)
            assertTrue(owned.input(old))
            owned.editor.setSelection(1 + from, 1 + to)
            owned.controller.externalSelection(1 + from, 1 + to, 65)
            val oldCaret = owned.controller.caret
            owned.widthModel.cycle()
            owned.acceptCommit = false
            assertFalse(owned.input(inserted))
            assertEquals("前${old}後", owned.editor.text.toString())
            assertEquals(old, owned.controller.raw)
            assertEquals(oldCaret, owned.controller.caret)
            assertEquals(1 + from, owned.editor.selectionStart)
            assertEquals(1 + to, owned.editor.selectionEnd)
            assertEquals(1, BaseInputConnection.getComposingSpanStart(owned.editor.text))
            assertEquals(65, BaseInputConnection.getComposingSpanEnd(owned.editor.text))
            assertEquals(WidthSwapState.SINGLE, owned.widthModel.state)

            owned.acceptCommit = true
            assertTrue(owned.input(inserted))
            val expected = old.take(minOf(from, to)) + "ｂ" + "b".repeat(64) + old.drop(maxOf(from, to))
            assertEquals("前${expected}後", owned.editor.text.toString())
            assertEquals("", owned.controller.raw)
            assertEquals(1 + minOf(from, to) + inserted.length, owned.editor.selectionStart)
            assertEquals(owned.editor.selectionStart, owned.editor.selectionEnd)
            assertEquals(WidthSwapState.OFF, owned.widthModel.state)
        }
    }
}
