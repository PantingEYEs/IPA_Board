package com.example.ipa_board

import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.ExtractedTextRequest
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic editor and input only; no other application, clipboard or user text is accessed. */
class SegmentedCompositionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun selectedPrefixRetainsSuffixAndCaretEditsBothSidesOfBoundary() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("nihaoshijie")
            val prefix = Candidate("你好", "简", consumption = CandidateConsumption(controller.raw, 5, "Rime", "test:0"))
            controller.acceptResults(controller.revision, listOf(prefix))
            assertTrue(controller.select(prefix))
            assertEquals("你好shijie", editor.text.toString())
            assertEquals(2, BaseInputConnection.getComposingSpanStart(editor.text))
            assertEquals("shijie", controller.raw)
            assertTrue(controller.moveCaret(-1))
            assertEquals(5, controller.caret)
            assertEquals(7, editor.selectionStart)
            assertTrue(controller.backspace())
            assertEquals("shije", controller.raw)
            controller.input("i")
            assertEquals("shijie", controller.raw)
            repeat(5) { assertTrue(controller.moveCaret(-1)) }
            assertEquals(0, controller.caret)
            assertTrue(controller.backspace())
            assertEquals("你shijie", editor.text.toString())
            assertEquals(1, BaseInputConnection.getComposingSpanStart(editor.text))
            assertEquals("shijie", controller.raw)
            val whole = Candidate("世界", "简")
            controller.acceptResults(controller.revision, listOf(whole))
            assertTrue(controller.select(whole))
            assertEquals("你世界", editor.text.toString())
        }
    }

    @Test fun refusingEditorKeepsOriginalEncodingWithoutCommittingOrWarning() {
        instrumentation.runOnMainSync {
            for (rejectPosition in listOf(false, true)) {
                val editor = EditText(instrumentation.targetContext)
                val base = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
                val ic = object : InputConnectionWrapper(base, false) {
                    override fun setComposingRegion(start: Int, end: Int) = false
                    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int) =
                        if (rejectPosition) null else super.getExtractedText(request, flags)
                }
                val controller = CompositionController({ ic }, { _, _ -> }, {})
                controller.input("nihaoshijie")
                val prefix = Candidate("你好", "简", consumption = CandidateConsumption(controller.raw, 5, "Rime", "0"))
                controller.acceptResults(controller.revision, listOf(prefix))
                assertFalse(controller.select(prefix))
                assertEquals("nihaoshijie", controller.raw)
                assertEquals(controller.raw, editor.text.toString())
                assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.text))
                if (rejectPosition) assertFalse(controller.moveCaret(-1))
            }
        }
    }

    @Test fun leftBoundaryIsInclusiveButMovingIntoCommittedPrefixFinalizesInPlace() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            ic.commitText("待输入", 1)
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("wenben")
            val old = controller.revision
            val word = Candidate("文本", "简")
            controller.acceptResults(old, listOf(word))
            assertEquals(9, editor.selectionStart)
            repeat(6) { index ->
                assertTrue(controller.moveCaret(-1))
                assertEquals("wenben", controller.raw)
                assertEquals(3, BaseInputConnection.getComposingSpanStart(editor.text))
                assertEquals(8 - index, editor.selectionStart)
            }
            assertTrue(controller.moveCaret(-1))
            assertEquals("待输入wenben", editor.text.toString())
            assertEquals(2, editor.selectionStart)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
            assertEquals("", controller.raw)
            controller.acceptResults(old, listOf(word))
            assertFalse(controller.select(word, old))
            assertTrue(controller.candidates.isEmpty())
            assertTrue(controller.literal())
            assertEquals("待输入wenben", editor.text.toString())
        }
    }

    @Test fun rightBoundaryIsInclusiveButMovingIntoFollowingTextFinalizes() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            ic.commitText("前後", 1); editor.setSelection(1)
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("wenben")
            assertEquals(7, editor.selectionStart)
            assertEquals("wenben", controller.raw)
            assertTrue(controller.moveCaret(-1)); assertTrue(controller.moveCaret(1))
            assertEquals("wenben", controller.raw)
            assertTrue(controller.moveCaret(1))
            assertEquals("前wenben後", editor.text.toString())
            assertEquals(8, editor.selectionStart)
            assertEquals("", controller.raw)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
        }
    }

    @Test fun externalCaretUsesTheSameBoundaryAndIgnoresQueuedOldPositions() {
        instrumentation.runOnMainSync {
            for (outside in listOf(0, 8)) {
                val editor = EditText(instrumentation.targetContext)
                val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
                ic.commitText("前後", 1); editor.setSelection(1)
                val controller = CompositionController({ ic }, { _, _ -> }, {})
                controller.input("wenben")
                for (position in listOf(1, 3, 7)) {
                    editor.setSelection(position)
                    controller.externalSelection(position, position, 7)
                    assertEquals("wenben", controller.raw)
                }
                // A queued outside callback must not finalize the still-inside actual caret.
                controller.externalSelection(outside, outside, 7)
                assertEquals("wenben", controller.raw)
                editor.setSelection(outside)
                controller.externalSelection(outside, outside, 7)
                assertEquals("", controller.raw)
                assertEquals(outside, editor.selectionStart)
                assertEquals("前wenben後", editor.text.toString())
                assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
            }
        }
    }

    @Test fun boundaryUsesDisplayedLengthWithoutApplyingWidthConversionTwice() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            ic.commitText("前後", 1); editor.setSelection(1)
            val controller = CompositionController({ ic }, { _, _ -> }, {}, transformOutput = CharacterWidthConverter::swap)
            controller.input("ｶﾞ")
            assertEquals("前ガ後", editor.text.toString())
            assertTrue(controller.moveCaret(-1)); assertEquals("ｶﾞ", controller.raw)
            assertTrue(controller.moveCaret(1)); assertEquals("ｶﾞ", controller.raw)
            assertTrue(controller.moveCaret(1)); assertEquals("", controller.raw)
            assertEquals("前ガ後", editor.text.toString())
            assertEquals(3, editor.selectionStart)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
        }
    }

    @Test fun staleOrInvalidConsumptionCannotCommitAndFullCandidateRemainsAvailable() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("nihao")
            val old = controller.revision
            val prefix = Candidate("你", "简", consumption = CandidateConsumption("nihao", 2, "Rime", "0"))
            controller.acceptResults(old, listOf(prefix))
            controller.moveCaret(-1)
            assertFalse(controller.select(prefix, old))
            val bad = prefix.copy(consumption = CandidateConsumption("other", 2, "Rime", "0"))
            controller.acceptResults(controller.revision, listOf(bad))
            assertFalse(controller.select(bad))
            assertEquals("nihao", editor.text.toString())
            val full = Candidate("你好", "简")
            controller.acceptResults(controller.revision, listOf(full))
            assertTrue(controller.select(full))
            assertEquals("你好", editor.text.toString())
        }
    }

    @Test fun nativeChineseAndZhuyinCandidatesCarryActualPrefixCoverage() {
        val engine = RimeEngine(instrumentation.targetContext)
        for ((raw, text, remaining) in listOf(Triple("nihaoshijie", "你好", "shijie"),
            Triple("ㄋㄧˇㄏㄠˇㄕˋㄐㄧㄝˋ", "你好", "ㄕˋㄐㄧㄝˋ"))) {
            val results = engine.query(raw)
            val candidate = results.firstOrNull { it.text == text && it.consumption != null }
            assertNotNull("Missing partial Chinese candidate: $raw; $results", candidate)
            assertEquals(remaining, raw.drop(candidate!!.consumption!!.end))
            assertTrue(results.any { it.consumption == null })
        }
    }

    @Test fun nativeJapaneseConversionProvidesPrefixAndFullChoices() {
        val engine = MozcEngine(instrumentation.targetContext)
        val raw = "watashihanihonjin"
        val results = engine.query(raw)
        assertTrue("No segmented Japanese candidate: $results", results.any { it.consumption != null })
        assertTrue(results.any { it.consumption == null })
        val prefix = results.first { it.consumption != null }
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input(raw)
            controller.acceptResults(controller.revision, results)
            assertTrue(controller.select(prefix))
            assertEquals("nihonjin", controller.raw)
            assertEquals(prefix.text + controller.raw, editor.text.toString())
            assertEquals(prefix.text.length, BaseInputConnection.getComposingSpanStart(editor.text))
        }
        results.filter { it.consumption != null }.forEach {
            assertTrue(it.consumption!!.validFor(raw))
            assertTrue(raw.drop(it.consumption.end).isNotEmpty())
        }
    }
}
