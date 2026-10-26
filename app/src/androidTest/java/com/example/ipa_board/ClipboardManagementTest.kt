package com.example.ipa_board

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import android.os.SystemClock
import org.hamcrest.Matcher
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.clipboard.*
import com.example.ipa_board.kaomoji.KaomojiManagerActivity
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** App-private synthetic history and in-memory export only; never touches the system clipboard or file picker. */
class ClipboardManagementTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val first = ClipboardItem("management-first", "Synthetic Alpha", 2000L, isPinned = true)
    private val second = ClipboardItem("management-second", "SecretSynthetic β😀\n{quoted: \"text\"}\\path", 1000L, isSensitive = true)

    @Test fun editingSavesFullTextPreservesMetadataAndSynchronizesExistingRepository() = withHistory { repo ->
        val edited = "  EditedSynthetic 你好😀\n{quoted: \"value\"}\\literal  "
        ActivityScenario.launch(ClipboardManagerActivity::class.java).use { scenario ->
            showHistory()
            onView(allOf(withText(first.text), isDescendantOfA(withId(R.id.clipboard_history_panel)))).perform(click())
            onView(withId(R.id.clipboard_edit_text)).perform(replaceText(edited), closeSoftKeyboard())
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            assertEquals(first.copy(text = edited), repo.getItemById(first.id))
            onView(allOf(withText(edited), isDescendantOfA(withId(R.id.clipboard_history_panel)))).perform(click())
            onView(withId(R.id.clipboard_edit_text)).perform(replaceText("DiscardSynthetic"), closeSoftKeyboard())
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            assertEquals(edited, repo.getItemById(first.id)?.text)
            // Existing IME-side repositories must not write their stale pre-edit list over the edit.
            repo.addClip("Synthetic later copy")
            assertEquals(edited, repo.getItemById(first.id)?.text)
            scenario.recreate()
            showHistory()
            onView(allOf(withText(edited), isDescendantOfA(withId(R.id.clipboard_history_panel)))).check(matches(isDisplayed()))
        }
    }

    @Test fun batchSelectionSurvivesRecreationAndExportsOnlySelectedOriginalRecords() = withHistory { repo ->
        ActivityScenario.launch(ClipboardManagerActivity::class.java).use { scenario ->
            onView(withId(R.id.fab_main)).perform(click())
            onView(withId(R.id.btn_batch_manage)).perform(click())
            onView(withId(R.id.clipboard_select_all)).perform(scrollTo(), click())
            scenario.recreate()
            showHistory()
            scenario.onActivity { activity ->
                val panel = activity.findViewById<ClipboardPanelView>(R.id.clipboard_history_panel)
                assertTrue(panel.isSelectionMode)
                assertEquals(setOf(first.id, second.id), panel.getSelectedIds())
                val stream = ByteArrayOutputStream()
                activity.writeClipboardExport(stream)
                val json = JSONArray(stream.toString("UTF-8"))
                assertEquals(listOf(first, second), (0 until json.length()).map { ClipboardItem.fromJsonString(json.getJSONObject(it).toString()) })
                val before = repo.getItems()
                assertThrows(IOException::class.java) {
                    activity.writeClipboardExport(object : OutputStream() { override fun write(value: Int) { throw IOException("Synthetic sink failure") } })
                }
                assertEquals(before, repo.getItems())
            }
            onView(withId(R.id.clipboard_select_all)).perform(scrollTo(), click())
            onView(withId(R.id.clipboard_export_selected)).check(matches(not(isEnabled())))
            scenario.onActivity { activity ->
                val panel = activity.findViewById<ClipboardPanelView>(R.id.clipboard_history_panel)
                val recycler = descendants(panel).filterIsInstance<RecyclerView>().single()
                val holder = recycler.findViewHolderForAdapterPosition(0) as ClipboardPanelView.ClipboardAdapter.ViewHolder
                holder.container.performClick()
                assertEquals(setOf(first.id), panel.getSelectedIds())
                val stream = ByteArrayOutputStream(); activity.writeClipboardExport(stream)
                val json = JSONArray(stream.toString("UTF-8"))
                assertEquals(1, json.length())
                assertEquals(first, ClipboardItem.fromJsonString(json.getJSONObject(0).toString()))
            }
            pressBack()
            scenario.onActivity { assertFalse(it.findViewById<ClipboardPanelView>(R.id.clipboard_history_panel).isSelectionMode) }
        }
    }

    @Test fun nestedHistoryGridCanScrollToAndEditItsLastCards() = withHistory { repo ->
        repeat(36) { index -> repo.restoreItem(ClipboardItem("nested-$index", "Synthetic nested item $index", 100L + index, isPinned = true)) }
        ActivityScenario.launch(ClipboardManagerActivity::class.java).use { scenario ->
            showHistory()
            val grid = allOf(isAssignableFrom(RecyclerView::class.java), isDescendantOfA(withId(R.id.clipboard_history_panel)))
            repeat(10) { onView(grid).perform(swipeUp()) }
            onView(grid).perform(awaitGridIdle())
            scenario.onActivity { activity ->
                val panel = activity.findViewById<ClipboardPanelView>(R.id.clipboard_history_panel)
                val recycler = descendants(panel).filterIsInstance<RecyclerView>().single()
                val layout = recycler.layoutManager as androidx.recyclerview.widget.GridLayoutManager
                assertTrue("Vertical touches must scroll the nested grid", layout.findFirstVisibleItemPosition() > 0)
            }
            onView(allOf(withText("Synthetic nested item 0"), isDescendantOfA(withId(R.id.clipboard_history_panel)))).perform(click())
            onView(withId(R.id.clipboard_edit_text)).check(matches(withText("Synthetic nested item 0")))
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        }
    }

    @Test fun sharedKaomojiActionMenuStillOpensAndBackClosesOnlyItsMenu() {
        ActivityScenario.launch(KaomojiManagerActivity::class.java).use {
            onView(withId(R.id.fab_main)).perform(click())
            onView(withId(R.id.btn_add_kaomoji)).check(matches(isDisplayed()))
            pressBack()
            onView(withId(R.id.fab_menu_container)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.fab_main)).check(matches(isDisplayed()))
        }
    }

    private fun awaitGridIdle() = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(RecyclerView::class.java)
        override fun getDescription() = "wait for the owned history grid fling to finish before clicking a card"
        override fun perform(controller: UiController, view: View) {
            val recycler = view as RecyclerView
            val deadline = SystemClock.uptimeMillis() + 5_000L
            while (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE && SystemClock.uptimeMillis() < deadline) {
                controller.loopMainThreadForAtLeast(16)
            }
            assertEquals("Grid must be idle before the edit tap", RecyclerView.SCROLL_STATE_IDLE, recycler.scrollState)
        }
    }

    private fun showHistory() = onView(withId(R.id.clipboard_history_host)).perform(scrollTo())
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun withHistory(body: (ClipboardRepository) -> Unit) {
        val prefs = context.getSharedPreferences(ClipboardRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val original = prefs.getString(ClipboardRepository.KEY_CLIPBOARD_ITEMS, null)
        try {
            prefs.edit().putString(ClipboardRepository.KEY_CLIPBOARD_ITEMS, "[]").commit()
            val repository = ClipboardRepository(context)
            repository.restoreItem(first); repository.restoreItem(second)
            body(repository)
        } finally {
            prefs.edit().apply {
                if (original == null) remove(ClipboardRepository.KEY_CLIPBOARD_ITEMS)
                else putString(ClipboardRepository.KEY_CLIPBOARD_ITEMS, original)
            }.commit()
        }
    }
}
