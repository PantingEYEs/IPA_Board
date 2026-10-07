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
    @Test fun widthToggleRepaintsCompositionButKeepsEngineEncodingAndRejectsOldCandidates() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            ic.commitText("已有上下文 ", 1)
            var enabled = false
            val queries = mutableListOf<String>()
            val controller = CompositionController({ ic }, { _, raw -> queries += raw }, {},
                transformOutput = { if (enabled) CharacterWidthConverter.swap(it) else it })
            val raw = "nihao123,Ａ"
            controller.input(raw)
            assertEquals("已有上下文 $raw", editor.text.toString())
            val old = controller.revision
            val word = Candidate("你好123,Ａ", "简/EN")
            controller.acceptResults(old, listOf(word))
            enabled = true
            controller.refreshOutput()
            assertEquals(raw, controller.raw)
            assertEquals(raw, queries.last())
            assertEquals("已有上下文 ｎｉｈａｏ１２３，A", editor.text.toString())
            assertEquals("已有上下文 ", controller.beforeCursor)
            assertFalse("A toggle must invalidate the previous candidate snapshot", controller.select(word, old))
            controller.acceptResults(old, listOf(word))
            assertTrue(controller.candidates.isEmpty())
            controller.acceptResults(controller.revision, listOf(word))
            assertTrue(controller.select(word))
            assertEquals("已有上下文 你好１２３，A", editor.text.toString())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
            enabled = false
            controller.input("b")
            assertTrue(controller.literal())
            assertEquals("已有上下文 你好１２３，Ab", editor.text.toString())
        }
    }

    @Test fun widthConversionKeepsContextWhenKanaLengthChangesAndConvertsOverflowTextOnce() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            ic.commitText("前文後文", 1)
            editor.setSelection(2)
            val controller = CompositionController({ ic }, { _, _ -> }, {},
                transformOutput = CharacterWidthConverter::swap)
            controller.input("ｶﾞ")
            assertEquals("前文ガ後文", editor.text.toString())
            assertEquals("前文", controller.beforeCursor)
            assertEquals("後文", controller.afterCursor)
            val word = Candidate("パ", "日")
            controller.acceptResults(controller.revision, listOf(word))
            assertTrue(controller.select(word))
            assertEquals("前文ﾊﾟ後文", editor.text.toString())
            assertEquals("前文ﾊﾟ", controller.beforeCursor)
            assertEquals("後文", controller.afterCursor)
            controller.input("a")
            controller.input("Ａ".repeat(65))
            assertEquals("前文ﾊﾟａ" + "A".repeat(65) + "後文", editor.text.toString())
            assertEquals("", controller.raw)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
            controller.input("1１")
            controller.finish()
            assertEquals("前文ﾊﾟａ" + "A".repeat(65) + "１1後文", editor.text.toString())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
        }
    }

    @Test fun rejectedWidthCommitRetainsOriginalRawForAnUnambiguousRetry() {
        instrumentation.runOnMainSync {
            val committed = mutableListOf<String>()
            var accept = false
            val ic = object : BaseInputConnection(View(instrumentation.targetContext), false) {
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = false
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    committed += text.toString()
                    return accept
                }
            }
            val controller = CompositionController({ ic }, { _, _ -> }, {},
                transformOutput = CharacterWidthConverter::swap)
            controller.input("aＡ😀")
            assertFalse(controller.literal())
            assertEquals("aＡ😀", controller.raw)
            accept = true
            assertTrue(controller.literal())
            assertEquals(listOf("ａA😀", "ａA😀"), committed)
            assertEquals("", controller.raw)
        }
    }

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
    @Test fun chineseAndJapanesePredictionsInsertWithoutEnglishSpaces() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = editor.onCreateInputConnection(EditorInfo())!!
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            ic.commitText("今天", 1)
            controller.start()
            val chinese = Candidate("天气", "简", kind = CandidateKind.PREDICTION)
            controller.acceptResults(controller.revision, listOf(chinese))
            assertTrue(controller.select(chinese))
            assertEquals("今天天气", editor.text.toString())
            val japanese = Candidate("です", "日", kind = CandidateKind.PREDICTION)
            controller.acceptResults(controller.revision, listOf(japanese))
            assertTrue(controller.select(japanese))
            assertEquals("今天天气です", editor.text.toString())
        }
    }
    @Test fun mixedCandidatePreservesNumbersAndPunctuationOnCommit() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = editor.onCreateInputConnection(EditorInfo())!!
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("nihao123,hel!")
            val converted = Candidate("你好123,hello!", "简/EN", kind = CandidateKind.COMPLETION)
            controller.acceptResults(controller.revision, listOf(converted))
            assertTrue(controller.select(converted))
            assertEquals("你好123,hello!", editor.text.toString())
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
