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
import com.example.ipa_board.ime.SpaceResult
import org.junit.Assert.*
import org.junit.Test

/** Space confirmation uses only synthetic text in test-owned editors. */
class SpaceConfirmationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class OwnedEditor(context: Context, val inline: Boolean) {
        val editor = EditText(context).apply { setText("前後"); setSelection(1) }
        var acceptCommit = true
        val commits = mutableListOf<String>()
        private val realConnection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        val connection = object : InputConnectionWrapper(realConnection, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                commits += text.toString()
                return acceptCommit && super.commitText(text, newCursorPosition)
            }
        }
        val controller = CompositionController({ connection }, { _, _ -> }, {}, initialInlineEnabled = inline)
        val widthModel = WidthSwapController()
        fun offer(candidate: Candidate) = controller.acceptResults(controller.revision, listOf(candidate))
        fun confirmPreparedSpace(): SpaceResult {
            val prepared = widthModel.prepare(" ")
            val result = controller.space(prepared.text)
            if (result.insertedSpace) widthModel.accepted(prepared)
            return result
        }
    }

    private fun withEditor(inline: Boolean, block: (OwnedEditor) -> Unit) {
        instrumentation.runOnMainSync {
            val owned = OwnedEditor(instrumentation.targetContext, inline)
            try { block(owned) } finally { owned.controller.finish() }
        }
    }

    @Test fun rawWithoutCandidatesCommitsExactlyTheRequestedSuffixAndReportsItsInsertion() {
        for (inline in listOf(true, false)) for (suffix in listOf(" ", "　", "")) withEditor(inline) { owned ->
            assertTrue(owned.controller.input("qＡ"))
            val result = owned.controller.space(suffix)
            assertTrue(result.handled)
            assertEquals(suffix.isNotEmpty(), result.insertedSpace)
            assertEquals("前qＡ${suffix}後", owned.editor.text.toString())
            assertEquals("", owned.controller.raw)
            assertEquals(listOf("qＡ$suffix"), owned.commits)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(owned.editor.text))
        }
    }

    @Test fun wholeCandidatesApplyTheRequestedSuffixOnlyToAnEnglishLanguageToken() {
        val cases = listOf(
            Triple("EN", "Hello", true),
            Triple("简/EN", "你好Hello", true),
            Triple("EN/日", "Helloカナ", true),
            Triple("简/繁", "你好", false),
            Triple("FRENCH", "bonjour", false),
        )
        for (inline in listOf(true, false)) for ((language, text, addsSuffix) in cases) {
            for (suffix in listOf(" ", "　", "")) withEditor(inline) { owned ->
                assertTrue(owned.controller.input("source"))
                owned.offer(Candidate(text, language))
                val result = owned.controller.space(suffix)
                val expected = text + if (addsSuffix) suffix else ""
                assertTrue(result.handled)
                assertEquals(addsSuffix && suffix.isNotEmpty(), result.insertedSpace)
                assertEquals("前${expected}後", owned.editor.text.toString())
                assertEquals("", owned.controller.raw)
                assertEquals(listOf(expected), owned.commits)
            }
        }
    }

    @Test fun partialEnglishCandidateCommitsItsPrefixWithoutAppendingOrConsumingThePreparedSpace() {
        for (inline in listOf(true, false)) withEditor(inline) { owned ->
            assertTrue(owned.controller.input("helloworld"))
            owned.offer(Candidate("hello", "EN", consumption =
                CandidateConsumption(owned.controller.raw, 5, "Rime", "owned-prefix")))
            owned.widthModel.cycle()
            val result = owned.confirmPreparedSpace()
            assertTrue(result.handled)
            assertFalse(result.insertedSpace)
            assertEquals("world", owned.controller.raw)
            assertEquals(if (inline) "前helloworld後" else "前hello後", owned.editor.text.toString())
            assertEquals(WidthSwapState.SINGLE, owned.widthModel.state)
            assertTrue(owned.controller.literal())
            assertEquals("前helloworld後", owned.editor.text.toString())
        }
    }

    @Test fun calculatorResultsReplacePendingInputWhileUndefinedLeavesItUnchangedWithoutSpace() {
        for (inline in listOf(true, false)) for (undefined in listOf(false, true)) withEditor(inline) { owned ->
            val raw = if (undefined) "90°" else "sqrt(i)"
            assertTrue(owned.controller.input(raw))
            owned.offer(Candidate(if (undefined) "Undefined" else "0.5…+(0.5…)i", "∑"))
            owned.widthModel.cycle()
            val result = owned.confirmPreparedSpace()
            val expectedRaw = if (undefined) raw else "0.5+(0.5)i"
            assertEquals(!undefined, result.handled)
            assertFalse(result.insertedSpace)
            assertEquals(expectedRaw, owned.controller.raw)
            assertEquals(if (inline) "前${expectedRaw}後" else "前後", owned.editor.text.toString())
            assertTrue(owned.commits.isEmpty())
            assertEquals(WidthSwapState.SINGLE, owned.widthModel.state)
        }
    }

    @Test fun rejectedConfirmationRetainsRawAndSingleModeUntilTheRequestedSpaceIsActuallyCommitted() {
        for (inline in listOf(true, false)) for (english in listOf(false, true)) withEditor(inline) { owned ->
            assertTrue(owned.controller.input("source"))
            if (english) owned.offer(Candidate("Hello", "EN"))
            owned.widthModel.cycle()
            owned.acceptCommit = false
            val rejected = owned.confirmPreparedSpace()
            assertFalse(rejected.handled)
            assertFalse(rejected.insertedSpace)
            assertEquals("source", owned.controller.raw)
            assertEquals(if (inline) "前source後" else "前後", owned.editor.text.toString())
            assertEquals(WidthSwapState.SINGLE, owned.widthModel.state)
            owned.acceptCommit = true
            val accepted = owned.confirmPreparedSpace()
            assertTrue(accepted.handled)
            assertTrue(accepted.insertedSpace)
            assertEquals("前${if (english) "Hello" else "source"}　後", owned.editor.text.toString())
            assertEquals("", owned.controller.raw)
            assertEquals(WidthSwapState.OFF, owned.widthModel.state)
        }
    }

    @Test fun nonEnglishConfirmationDoesNotConsumeTheSingleModeReservedForNewInput() {
        for (inline in listOf(true, false)) withEditor(inline) { owned ->
            assertTrue(owned.controller.input("nihao"))
            owned.offer(Candidate("你好", "简/繁"))
            owned.widthModel.cycle()
            val result = owned.confirmPreparedSpace()
            assertTrue(result.handled)
            assertFalse(result.insertedSpace)
            assertEquals("前你好後", owned.editor.text.toString())
            assertEquals("", owned.controller.raw)
            assertEquals(WidthSwapState.SINGLE, owned.widthModel.state)
        }
    }

    @Test fun emptyRawDoesNotConfirmAPredictionOrClaimThatSpaceWasInserted() {
        for (inline in listOf(true, false)) for (prediction in listOf(false, true)) withEditor(inline) { owned ->
            if (prediction) owned.offer(Candidate("next", "EN", kind = CandidateKind.PREDICTION))
            owned.widthModel.cycle()
            val result = owned.confirmPreparedSpace()
            assertFalse(result.handled)
            assertFalse(result.insertedSpace)
            assertEquals("", owned.controller.raw)
            assertEquals("前後", owned.editor.text.toString())
            assertTrue(owned.commits.isEmpty())
            assertEquals(WidthSwapState.SINGLE, owned.widthModel.state)
        }
    }
}
