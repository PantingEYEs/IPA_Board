package com.example.ipa_board

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyEditorTest {
    @Test fun saveFunctionKeyAndCancelAnotherChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val originalFile = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        val filename = "test_functions_${System.nanoTime()}.json"
        try {
            LayoutFileManager.saveLayout(context, filename, SettingsConstants.DEFAULT_LAYOUT)
            prefs.edit().putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, filename).commit()
            ActivityScenario.launch(SettingsActivity::class.java).use {
                onView(withId(R.id.btn_edit)).perform(click())
                onView(withId(R.id.sp_key_type)).perform(click())
                onData(equalTo("Backspace")).inRoot(isPlatformPopup()).perform(click())
                onView(withId(R.id.et_key_text)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withText("Save")).perform(click())
                assertEquals(KeyAction.BACKSPACE, LayoutFileManager.loadLayout(context, filename)!!.rows[0].slots[0].action)
                onView(withId(R.id.btn_edit)).perform(click())
                onView(withId(R.id.sp_key_type)).check(matches(withSpinnerText("Backspace")))
                onView(withId(R.id.sp_key_type)).perform(click())
                onData(equalTo("Text")).inRoot(isPlatformPopup()).perform(click())
                onView(withId(R.id.et_key_text)).check(matches(isDisplayed()))
                onView(withText("Cancel")).perform(click())
                assertEquals(KeyAction.BACKSPACE, LayoutFileManager.loadLayout(context, filename)!!.rows[0].slots[0].action)
            }
        } finally {
            prefs.edit().apply {
                if (originalFile == null) remove(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE)
                else putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, originalFile)
            }.commit()
            LayoutFileManager.deleteLayout(context, filename)
        }
    }
}
