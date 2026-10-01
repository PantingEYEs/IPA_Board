package com.example.ipa_board

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.emoji.*
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.*
import org.junit.Test

class EmojiFeatureTest {
    @Test fun emojiActionCanBeAssignedAndRoundTripsWithoutLosingLongPress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val previous = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        val layout = KeyboardLayout("Emoji test", listOf(RowLayout(1f, listOf(KeySlot(1f, "a", longPressText = "ɑ")))))
        val filename = LayoutFileManager.createLayout(context, "Emoji test", layout)
        try {
            prefs.edit().putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, filename).commit()
            ActivityScenario.launch(KeyboardPageActivity::class.java).use {
                onView(withId(R.id.btn_edit)).perform(scrollTo(), click())
                onView(withId(R.id.sp_key_type)).perform(click())
                onData(equalTo("Emoji")).inRoot(isPlatformPopup()).perform(click())
                onView(withText("Save")).perform(click())
                val saved = requireNotNull(LayoutFileManager.loadLayout(context, filename))
                assertEquals(KeyAction.EMOJI, saved.rows[0].slots[0].action)
                assertEquals("ɑ", saved.rows[0].slots[0].longPressText)
                assertEquals(saved, KeyboardLayout.fromJson(saved.toJson()))
            }
        } finally {
            prefs.edit().apply { if (previous == null) remove(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE) else putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, previous) }.commit()
            LayoutFileManager.deleteLayout(context, filename)
        }
    }

    @Test fun allEntriesAreReachableInRecycledGridAndCategoryFilteringWorks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val catalog = BundledEmojiCatalogSource(instrumentation.targetContext).load()
        lateinit var picker: EmojiPickerView
        var selected: EmojiEntry? = null
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                picker = EmojiPickerView(activity, catalog) { selected = it }
                activity.setContentView(picker)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(0, picker.currentCategoryIndex)
                picker.filterCategory(1) // Category 1 is "All emoji"
                assertEquals(3972, picker.grid.adapter.count)
                assertTrue(picker.grid.childCount in 1..200)
                picker.grid.setSelection(catalog.entries.lastIndex)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(catalog.entries.lastIndex, picker.grid.lastVisiblePosition)
                val position = picker.grid.lastVisiblePosition
                picker.grid.performItemClick(picker.grid.getChildAt(position - picker.grid.firstVisiblePosition), position, position.toLong())
                assertEquals(catalog.entries.last(), selected)
                picker.filterCategory(catalog.groups.indexOf("Component") + 2) // Most commonly (0), All (1), then groups
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(9, picker.grid.adapter.count)
                assertTrue((picker.grid.adapter.getItem(0) as EmojiEntry).isComponent)
            }
        }
    }
}
