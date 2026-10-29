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
