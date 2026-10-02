package com.example.ipa_board

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test

class CompositionControllerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun selectedCandidateReplacesComposingTextExactlyOnceAndOldResultsCannotCommit() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = editor.onCreateInputConnection(EditorInfo())!!
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("nihao")
            assertEquals("nihao", editor.text.toString())
            val old = controller.revision
            controller.acceptResults(old, listOf(Candidate("你好", "简/繁")))
            assertTrue(controller.select(controller.candidates.single(), old))
            assertEquals("你好", editor.text.toString())
            controller.input("hello")
            controller.acceptResults(old, listOf(Candidate("坏", "简")))
            assertTrue(controller.candidates.isEmpty())
            assertFalse(controller.select(Candidate("你好", "简/繁"), old))
            assertTrue(controller.literal())
            assertEquals("你好hello", editor.text.toString())
        }
    }
    @Test fun backspaceDeletesCompositionBeforeCommittedTextAndSpaceUsesLanguageRule() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = editor.onCreateInputConnection(EditorInfo())!!
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            ic.commitText("IPA ", 1)
            controller.input("ab"); controller.backspace(); controller.backspace()
            assertEquals("IPA ", editor.text.toString())
            assertFalse(controller.backspace())
            controller.input("hello")
            controller.acceptResults(controller.revision, listOf(Candidate("hello", "EN")))
            controller.space()
            assertEquals("IPA hello ", editor.text.toString())
            controller.input("hanyu")
            controller.acceptResults(controller.revision, listOf(Candidate("漢語", "繁")))
            controller.space()
            assertEquals("IPA hello 漢語", editor.text.toString())
        }
    }
    @Test fun movingCursorEndsCompositionWithoutReinsertingAtNewCursor() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = editor.onCreateInputConnection(EditorInfo())!!
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("hello")
            controller.externalSelection(5, 5, 5)
            editor.setSelection(0)
            controller.externalSelection(0, 0, 5)
            assertEquals("", controller.raw)
            assertEquals("hello", editor.text.toString())
            assertTrue(controller.literal())
            assertEquals("hello", editor.text.toString())
        }
    }
    @Test fun editorRejectingCompositionStillAcceptsOneLiteralCommit() {
        instrumentation.runOnMainSync {
            val committed = mutableListOf<String>()
            val ic = object : BaseInputConnection(View(instrumentation.targetContext), false) {
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = false
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { committed.add(text.toString()); return true }
            }
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("nihao"); controller.literal()
            assertEquals(listOf("nihao"), committed)
        }
    }
    @Test fun predictionUsesCursorContextAndNeverReplacesPreviousWordsOrSelection() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = editor.onCreateInputConnection(EditorInfo())!!
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            ic.commitText("你好 thank ", 1)
            controller.start()
            assertEquals("你好 thank ", controller.beforeCursor)
            val prediction = Candidate("you", "EN", kind = CandidateKind.PREDICTION)
            controller.acceptResults(controller.revision, listOf(prediction))
            assertTrue(controller.select(prediction))
            assertEquals("你好 thank you ", editor.text.toString())
            controller.acceptResults(controller.revision, listOf(prediction))
            val old = controller.revision
            controller.input("hel")
            assertEquals("你好 thank you ", controller.beforeCursor)
            assertFalse(controller.select(prediction, old))
            controller.literal()
            controller.acceptResults(controller.revision, listOf(prediction))
            editor.setSelection(0, 2)
            assertFalse(controller.select(prediction))
            assertEquals("你好 thank you hel", editor.text.toString())
        }
    }
    @Test fun sameWordInNewRevisionCannotBeCommittedFromOldCandidateSnapshot() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = editor.onCreateInputConnection(EditorInfo())!!
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("hel")
            val word = Candidate("hello", "EN", kind = CandidateKind.COMPLETION)
            val old = controller.revision
            controller.acceptResults(old, listOf(word))
            controller.input("l")
            controller.acceptResults(controller.revision, listOf(word))
            assertFalse(controller.select(word, old))
            assertEquals("hell", editor.text.toString())
            assertTrue(controller.select(word))
            controller.acceptResults(controller.revision, listOf(Candidate("world", "EN", kind = CandidateKind.PREDICTION)))
            assertTrue(controller.select(controller.candidates.single()))
            assertEquals("hello world ", editor.text.toString())
        }
    }
}
