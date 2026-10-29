package com.example.ipa_board

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import com.example.ipa_board.shortcuts.*
import com.example.ipa_board.ime.*

class ReplacementTextTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences(SettingsConstants.PREFS_NAME, 0)
    private var previous: String? = null
    @Before fun setup() { previous = prefs.getString(ReplacementSettings.KEY, null); prefs.edit().remove(ReplacementSettings.KEY).commit() }
    @After fun cleanup() { prefs.edit().apply { previous?.let { putString(ReplacementSettings.KEY, it) } ?: remove(ReplacementSettings.KEY) }.commit() }

    @Test fun versionedCategoryRoundTripsOrderMultipleTargetsDuplicatesAndUnicode() {
        val rules = listOf(ReplacementRule("1", "鞥", "嗯"), ReplacementRule("2", "鞥", "嗯"),
            ReplacementRule("3", "emoji😀", " ɑ́\n\n日本語 😀 "))
        assertTrue(ReplacementSettings.save(context, rules))
        assertEquals(rules, ReplacementSettings.load(context))
        assertEquals(rules, ReplacementSettings.decode(ReplacementSettings.encode(rules)))
        assertEquals("replacementText", org.json.JSONObject(ReplacementSettings.encode(rules)).getString("category"))
        assertTrue(ReplacementSettings.decode(null).isEmpty())
        assertThrows(Exception::class.java) { ReplacementSettings.decode("{\"schemaVersion\":2}") }
    }
    @Test fun selectingAppendedPartialAndPredictionCandidatesUsesOriginalBehavior() {
        instrumentation.runOnMainSync {
            val editor = EditText(context); val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("nihaoshijie")
            val source = Candidate("你好", "简", consumption = CandidateConsumption(controller.raw, 5, "Rime", "0"))
            val result = ReplacementRules.append(listOf(source), listOf(ReplacementRule("1", "你好", "您好")))
            controller.acceptResults(controller.revision, result)
            assertTrue(controller.select(result.last()))
            assertEquals("您好shijie", editor.text.toString())
            assertEquals("shijie", controller.raw)
            assertEquals(2, BaseInputConnection.getComposingSpanStart(editor.text))
            val full = Candidate("世界", "简")
            controller.acceptResults(controller.revision, listOf(full)); assertTrue(controller.select(full))
            val prediction = Candidate("你好", "简", kind = CandidateKind.PREDICTION)
            val predictions = ReplacementRules.append(listOf(prediction), listOf(ReplacementRule("2", "你好", "好")))
            controller.acceptResults(controller.revision, predictions); assertTrue(controller.select(predictions.last()))
            assertEquals("您好世界好", editor.text.toString())
            controller.input("1+2")
            val math = Candidate("3", "∑", rank = -1)
            val alternatives = ReplacementRules.append(listOf(math), listOf(ReplacementRule("math", "3", "4+5")))
            controller.acceptResults(controller.revision, alternatives)
            assertTrue(controller.select(alternatives.last()))
            assertEquals("4+5", controller.raw)
            assertEquals("您好世界好4+5", editor.text.toString())
            assertEquals(5, BaseInputConnection.getComposingSpanStart(editor.text))
        }
    }
    @Test fun duplicateRowsAreIndependentlyDisplayedAndOldSelectionsAreRejected() {
        instrumentation.runOnMainSync {
            val editor = EditText(context); val ic = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            val controller = CompositionController({ ic }, { _, _ -> }, {})
            controller.input("a")
            val result = ReplacementRules.append(listOf(Candidate("same", "EN")),
                listOf(ReplacementRule("1", "same", "same"), ReplacementRule("2", "same", "same")))
            controller.acceptResults(controller.revision, result)
            val view = ImeChromeView(context)
            val selected = mutableListOf<Candidate>()
            view.onCandidate = { item, _ -> selected += item }
            view.render(controller.raw, result, "", controller.revision)
            val buttons = mutableListOf<Button>()
            fun walk(v: View) { if (v is Button && v.contentDescription == "same") buttons += v; if (v is ViewGroup) repeat(v.childCount) { walk(v.getChildAt(it)) } }
            walk(view)
            assertEquals(3, buttons.size)
            buttons.forEach { it.performClick() }
            assertEquals(result, selected)
            val old = controller.revision
            controller.input("b")
            assertFalse(controller.select(result.last(), old))
        }
    }
    @Test fun editorAddsEditsOrdersDeletesAndPersistsWithoutChangingOtherShortcuts() {
        val other = prefs.all.filterKeys { it != ReplacementSettings.KEY }
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            fun add(source: String, target: String) {
                scenario.onActivity { it.findViewById<ViewGroup>(R.id.replacement_rules).findViewWithTag<Button>("replacement-add").performClick() }
                onView(withTagValue(equalTo("replacement-source"))).inRoot(isDialog()).perform(replaceText(source))
                onView(withTagValue(equalTo("replacement-target"))).inRoot(isDialog()).perform(replaceText(target), closeSoftKeyboard())
                onView(withText(R.string.multifunction_bar_save)).inRoot(isDialog()).perform(click())
            }
            add("鞥", "嗯"); add("鞥", "恩")
            fun action(index: Int, text: String) = scenario.onActivity { activity ->
                val parent = activity.findViewById<ViewGroup>(R.id.replacement_rules)
                val row = parent.getChildAt(index * 2 + 1) as ViewGroup
                (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<Button>().single { it.text == text }.performClick()
            }
            action(1, "Up")
            assertEquals(listOf("恩", "嗯"), ReplacementSettings.load(context).map { it.replacement })
            action(0, "Edit")
            onView(withTagValue(equalTo("replacement-target"))).inRoot(isDialog()).perform(replaceText("改"), closeSoftKeyboard())
            onView(withText(R.string.multifunction_bar_cancel)).inRoot(isDialog()).perform(click())
            assertEquals("恩", ReplacementSettings.load(context).first().replacement)
            action(0, "Edit")
            onView(withTagValue(equalTo("replacement-target"))).inRoot(isDialog()).perform(replaceText("改"), closeSoftKeyboard())
            onView(withText(R.string.multifunction_bar_save)).inRoot(isDialog()).perform(click())
            scenario.recreate()
            assertEquals("改", ReplacementSettings.load(context).first().replacement)
            action(0, "Delete")
            assertEquals(listOf("嗯"), ReplacementSettings.load(context).map { it.replacement })
        }
        assertEquals(other, prefs.all.filterKeys { it != ReplacementSettings.KEY })
    }
}
