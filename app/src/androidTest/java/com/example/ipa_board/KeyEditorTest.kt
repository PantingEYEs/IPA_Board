package com.example.ipa_board

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyEditorTest {
    @Test fun widthSwapCanBeSavedAndReopenedAsTapAndHoldActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val groupSnapshot = PageGroupTestState(context)
        val filename = "test_width_swap_${System.nanoTime()}.json"
        try {
            LayoutFileManager.saveLayout(context, filename, SettingsConstants.DEFAULT_LAYOUT)
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(filename))
            PageGroupManager.selectPage(context, filename)
            ActivityScenario.launch(KeyboardPageActivity::class.java).use {
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(scrollTo(), click())
                onView(withId(R.id.sp_key_type)).perform(click())
                onData(equalTo("全角 / 半角转换")).inRoot(isPlatformPopup()).perform(click())
                onView(withId(R.id.et_key_text)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.sp_long_press_type)).perform(scrollTo(), click())
                onData(equalTo("全角 / 半角转换")).inRoot(isPlatformPopup()).perform(click())
                onView(withId(R.id.et_long_press_text)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(awaitDialogButton(), click())
                val saved = requireNotNull(LayoutFileManager.loadLayout(context, filename))
                val slot = saved.rows[0].slots[0]
                assertEquals(KeyAction.WIDTH_SWAP, slot.action)
                assertEquals(KeyAction.WIDTH_SWAP, slot.longPressAction)
                assertEquals(KeyAction.WIDTH_SWAP, slot.effectiveLongPressItems.single().action)
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(scrollTo(), click())
                onView(withId(R.id.sp_key_type)).check(matches(withSpinnerText("全角 / 半角转换")))
                onView(withId(R.id.sp_long_press_type)).check(matches(withSpinnerText("全角 / 半角转换")))
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(awaitDialogButton(), click())
                assertEquals(saved, LayoutFileManager.loadLayout(context, filename))
            }
        } finally {
            LayoutFileManager.deleteLayout(context, filename)
            groupSnapshot.close()
        }
    }

    @Test fun saveFunctionKeyAndCancelAnotherChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val groupSnapshot = PageGroupTestState(context)
        val originalFile = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        val filename = "test_functions_${System.nanoTime()}.json"
        try {
            LayoutFileManager.saveLayout(context, filename, SettingsConstants.DEFAULT_LAYOUT)
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(filename))
            PageGroupManager.selectPage(context, filename)
            ActivityScenario.launch(KeyboardPageActivity::class.java).use {
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(scrollTo(), click())
                onView(withId(R.id.sp_key_type)).perform(click())
                onData(equalTo("Backspace")).inRoot(isPlatformPopup()).perform(click())
                onView(withId(R.id.et_key_text)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.et_long_press_text)).perform(scrollTo(), replaceText("t͡ʃ😀"), closeSoftKeyboard())
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(awaitDialogButton(), click())
                val saved = requireNotNull(LayoutFileManager.loadLayout(context, filename))
                assertEquals(KeyAction.BACKSPACE, saved.rows[0].slots[0].action)
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(scrollTo(), click())
                onView(withId(R.id.et_long_press_text)).check(matches(withText("t͡ʃ😀")))
                assertEquals("t͡ʃ😀", LayoutFileManager.loadLayout(context, filename)!!.rows[0].slots[0].longPressText)
                onView(withId(R.id.sp_key_type)).check(matches(withSpinnerText("Backspace")))
                onView(withId(R.id.sp_key_type)).perform(click())
                onData(equalTo("Text")).inRoot(isPlatformPopup()).perform(click())
                onView(withId(R.id.et_key_text)).check(matches(isDisplayed()))
                onView(withId(R.id.et_key_text)).perform(replaceText("Discard this edit"), closeSoftKeyboard())
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(awaitDialogButton(), click())
                assertEquals(saved, LayoutFileManager.loadLayout(context, filename))
            }
        } finally {
            prefs.edit().apply {
                if (originalFile == null) remove(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE)
                else putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, originalFile)
            }.commit()
            LayoutFileManager.deleteLayout(context, filename)
            groupSnapshot.close()
        }
    }

    /** Wait for the dialog to resize after the IME hides; keep Espresso's 90% click requirement. */
    private fun awaitDialogButton() = object : ViewAction {
        override fun getConstraints(): Matcher<View> = allOf(
            isAssignableFrom(Button::class.java), withEffectiveVisibility(Visibility.VISIBLE))
        override fun getDescription() = "wait for the dialog button to become fully usable after closing the keyboard"
        override fun perform(uiController: UiController, view: View) {
            val visible = isDisplayingAtLeast(90)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!visible.matches(view) && SystemClock.uptimeMillis() < deadline) {
                uiController.loopMainThreadForAtLeast(16)
            }
            check(visible.matches(view)) {
                val bounds = android.graphics.Rect()
                view.getGlobalVisibleRect(bounds)
                val rootBounds = android.graphics.Rect()
                view.rootView.getGlobalVisibleRect(rootBounds)
                "Dialog ${(view as Button).text} button remained obscured after closing the soft keyboard; " +
                    "size=${view.width}x${view.height}, visible=$bounds, root=$rootBounds, " +
                    "imeVisible=${view.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())}"
            }
        }
    }
}
