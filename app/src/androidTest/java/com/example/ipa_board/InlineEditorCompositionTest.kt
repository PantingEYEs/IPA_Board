package com.example.ipa_board

import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.Candidate
import com.example.ipa_board.ime.CompositionController
import org.junit.Assert.*
import org.junit.Test

/** Synthetic editor compatibility checks, not an implementation or live test of Notion. */
class InlineEditorCompositionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Models a host that finalizes composition when asked to set its selection explicitly. */
    private class SelectionFinalizingConnection(base: InputConnection) : InputConnectionWrapper(base, false) {
        override fun setSelection(start: Int, end: Int): Boolean {
            if (!super.setSelection(start, end)) return false
            super.finishComposingText()
            return true
        }
    }

    private class Fixture(
        val editor: EditText,
        val prefix: String,
        val suffix: String,
        finalizesOnSelection: Boolean,
        transform: (String) -> String
    ) {
        val queries = mutableListOf<String>()
        private val base = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        private val ic: InputConnection = if (finalizesOnSelection) SelectionFinalizingConnection(base) else base
        val controller = CompositionController({ ic }, { _, raw -> queries += raw }, {}, transformOutput = transform)

        fun pending(raw: String, display: String = raw, rawCaret: Int = raw.length, displayCaret: Int = display.length) {
            assertEquals("The complete pending encoding must reach the engine", raw, queries.last())
            assertEquals(raw, controller.raw)
            assertEquals(rawCaret, controller.caret)
            assertEquals(prefix + display + suffix, editor.text.toString())
            assertEquals("The first character must remain composing", prefix.length,
                BaseInputConnection.getComposingSpanStart(editor.text))
            assertEquals(prefix.length + display.length, BaseInputConnection.getComposingSpanEnd(editor.text))
            assertEquals(prefix.length + displayCaret, editor.selectionStart)
            assertEquals(editor.selectionStart, editor.selectionEnd)
            assertEquals(prefix, controller.beforeCursor)
            assertEquals(suffix, controller.afterCursor)
        }

        fun select(candidate: Candidate, output: String = candidate.text) {
            val revision = controller.revision
            controller.acceptResults(revision, listOf(candidate))
            assertTrue(controller.select(candidate, revision))
            assertEquals(prefix + output + suffix, editor.text.toString())
            assertEquals(prefix.length + output.length, editor.selectionStart)
            assertEquals(editor.selectionStart, editor.selectionEnd)
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
            assertEquals(-1, BaseInputConnection.getComposingSpanEnd(editor.text))
            assertEquals("", controller.raw)
            assertFalse("An already accepted candidate cannot be committed again", controller.select(candidate, revision))
            assertTrue(controller.literal())
            assertEquals(prefix + output + suffix, editor.text.toString())
        }
    }

    private fun fixture(
        prefix: String,
        suffix: String,
        finalizesOnSelection: Boolean = true,
        transform: (String) -> String = { it }
    ): Fixture {
        val editor = EditText(instrumentation.targetContext).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefix + suffix)
            setSelection(prefix.length)
        }
        return Fixture(editor, prefix, suffix, finalizesOnSelection, transform)
    }

    @Test fun appendingPinyinKeepsTheFirstCharacterComposingAtTheLastLineAndBeforeFollowingText() {
        instrumentation.runOnMainSync {
            // Only model the reported text contexts; these are not Notion code-block widgets.
            for ((prefix, suffix) in listOf("已有段落\n" to "", "已有段落\n1. " to "，保留后文",
                "已有段落\n2. " to "\n保留下一行")) {
                val f = fixture(prefix, suffix)
                var raw = ""
                for (character in "hao") {
                    f.controller.input(character.toString())
                    raw += character
                    f.pending(raw)
                }
                assertEquals(listOf("h", "ha", "hao"), f.queries)
                f.select(Candidate("好", "简"))
            }
        }
    }

    @Test fun editingInsideTheCompositionStillRestoresTheRequestedCaretAndInvalidatesOldCandidates() {
        instrumentation.runOnMainSync {
            val f = fixture("已有段落\n1. ", "，保留后文", finalizesOnSelection = false)
            val c = f.controller
            c.input("hao")
            f.pending("hao")
            val old = c.revision
            val word = Candidate("好", "简")
            c.acceptResults(old, listOf(word))
            assertTrue(c.moveCaret(-1))
            f.pending("hao", rawCaret = 2, displayCaret = 2)
            assertFalse(c.select(word, old))
            c.input("x")
            f.pending("haxo", rawCaret = 3, displayCaret = 3)
            assertTrue(c.backspace())
            f.pending("hao", rawCaret = 2, displayCaret = 2)
            c.acceptResults(old, listOf(word))
            assertTrue(c.candidates.isEmpty())
            assertFalse(c.select(word, old))
            f.select(word)
        }
    }

    @Test fun tailEditingUsesTheDisplayedLengthWhenWidthConversionChangesTheEncodingLength() {
        instrumentation.runOnMainSync {
            val f = fixture("前文\n123", "後文", transform = CharacterWidthConverter::swap)
            val c = f.controller
            c.input("ｶ")
            f.pending("ｶ", "カ")
            c.input("ﾞ")
            f.pending("ｶﾞ", "ガ")
            c.input("a")
            f.pending("ｶﾞa", "ガａ")
            assertTrue(c.backspace())
            f.pending("ｶﾞ", "ガ")
            f.select(Candidate("パ", "日"), output = "ﾊﾟ")
        }
    }
}
