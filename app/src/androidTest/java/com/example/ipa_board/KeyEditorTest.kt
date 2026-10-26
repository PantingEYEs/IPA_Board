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
    @Test fun continuousCursorHoldCanBeSavedWithoutReplacingItsSingleStepTap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = PageGroupTestState(context)
        val filename = "test_continuous_cursor_${System.nanoTime()}.json"
        try {
            LayoutFileManager.saveLayout(context, filename, SettingsConstants.DEFAULT_LAYOUT)
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(filename))
            PageGroupManager.selectPage(context, filename)
            ActivityScenario.launch(KeyboardPageActivity::class.java).use {
                for ((tap, hold) in listOf(KeyAction.LEFT to KeyAction.REPEAT_LEFT, KeyAction.RIGHT to KeyAction.REPEAT_RIGHT,
                    KeyAction.UP to KeyAction.REPEAT_UP, KeyAction.DOWN to KeyAction.REPEAT_DOWN)) {
                    onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(click())
                    onView(withId(R.id.sp_key_type)).perform(click())
                    onData(equalTo(tap.title)).inRoot(isPlatformPopup()).perform(click())
                    openQueue()
                    onView(withContentDescription("Long-press action 1 function")).perform(scrollTo(), click())
                    onData(equalTo(hold.title)).inRoot(isPlatformPopup()).perform(click())
                    saveDialog()
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(awaitDialogButton(), click())
                    val saved = requireNotNull(LayoutFileManager.loadLayout(context, filename))
                    val key = saved.rows[0].slots[0]
                    assertEquals(tap, key.action)
                    assertEquals(hold, key.longPressAction)
                    assertEquals(hold, key.effectiveLongPressItems.single().action)
                    assertEquals(saved, KeyboardLayout.fromJson(saved.toJson()))
                    onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(click())
                    onView(withId(R.id.sp_key_type)).check(matches(withSpinnerText(tap.title)))
                    openQueue()
                    onView(withContentDescription("Long-press action 1 function")).check(matches(withSpinnerText(hold.title)))
                    cancelDialog()
                    onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(awaitDialogButton(), click())
                    assertEquals(saved, LayoutFileManager.loadLayout(context, filename))
                }
            }
        } finally {
            LayoutFileManager.deleteLayout(context, filename)
            snapshot.close()
        }
    }

    @Test fun widthSwapCanBeSavedAndReopenedAsTapAndHoldActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val groupSnapshot = PageGroupTestState(context)
        val filename = "test_width_swap_${System.nanoTime()}.json"
        try {
            LayoutFileManager.saveLayout(context, filename, SettingsConstants.DEFAULT_LAYOUT)
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(filename))
            PageGroupManager.selectPage(context, filename)
            ActivityScenario.launch(KeyboardPageActivity::class.java).use {
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(click())
                onView(withId(R.id.sp_key_type)).perform(click())
                onData(equalTo("全角 / 半角转换")).inRoot(isPlatformPopup()).perform(click())
                onView(withId(R.id.et_key_text)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                openQueue()
                onView(withContentDescription("Long-press action 1 function")).perform(scrollTo(), click())
                onData(equalTo("全角 / 半角转换")).inRoot(isPlatformPopup()).perform(click())
                onView(withContentDescription("Long-press action 1 text")).check(matches(withEffectiveVisibility(Visibility.GONE)))
                saveDialog()
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(awaitDialogButton(), click())
                val saved = requireNotNull(LayoutFileManager.loadLayout(context, filename))
                val slot = saved.rows[0].slots[0]
                assertEquals(KeyAction.WIDTH_SWAP, slot.action)
                assertEquals(KeyAction.WIDTH_SWAP, slot.longPressAction)
                assertEquals(KeyAction.WIDTH_SWAP, slot.effectiveLongPressItems.single().action)
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(click())
                onView(withId(R.id.sp_key_type)).check(matches(withSpinnerText("全角 / 半角转换")))
                openQueue()
                onView(withContentDescription("Long-press action 1 function")).check(matches(withSpinnerText("全角 / 半角转换")))
                cancelDialog()
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
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(click())
                onView(withId(R.id.sp_key_type)).perform(click())
                onData(equalTo("Backspace")).inRoot(isPlatformPopup()).perform(click())
                onView(withId(R.id.et_key_text)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                openQueue()
                onView(withContentDescription("Long-press action 1 text")).perform(scrollTo(), replaceText("t͡ʃ😀"), closeSoftKeyboard())
                saveDialog()
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(awaitDialogButton(), click())
                val saved = requireNotNull(LayoutFileManager.loadLayout(context, filename))
                assertEquals(KeyAction.BACKSPACE, saved.rows[0].slots[0].action)
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(click())
                openQueue()
                onView(withContentDescription("Long-press action 1 text")).check(matches(withText("t͡ʃ😀")))
                cancelDialog()
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

    @Test fun mixedQueueEditsDefaultDeletionAndBothCancelLevelsPreserveOtherMappings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = PageGroupTestState(context)
        val filename = "test_classic_queue_${System.nanoTime()}.json"
        val popup = PopupConfiguration.default()
        val original = KeySlot(1f, "tap", longPressItems = listOf(LongPressItem("old"),
            LongPressItem(action = KeyAction.COPY)), longPressDefaultIndex = 1,
            swipeLeftText = "left", swipeRightAction = KeyAction.PASTE, popupConfiguration = popup)
        fun saved() = LayoutFileManager.loadLayout(context, filename)!!.rows.single().slots.single()
        try {
            LayoutFileManager.saveLayout(context, filename, KeyboardLayout("Queue", listOf(RowLayout(1f, listOf(original)))))
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(filename))
            PageGroupManager.selectPage(context, filename)
            ActivityScenario.launch(KeyboardPageActivity::class.java).use {
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(click())
                openQueue()
                onView(withContentDescription("Default action 2")).check(matches(isChecked()))
                onView(withContentDescription("Long-press action 1 text")).perform(scrollTo(), replaceText("t͡ʃ,😀"), closeSoftKeyboard())
                onView(withId(R.id.classic_queue_add)).perform(click())
                onView(withContentDescription("Long-press action 3 function")).perform(scrollTo(), click())
                onData(equalTo(KeyAction.REPEAT_DOWN.title)).inRoot(isPlatformPopup()).perform(click())
                onView(withContentDescription("Default action 3")).perform(scrollTo(), click())
                saveDialog()
                assertEquals("Nested Save must not persist the outer key draft", original, saved())
                // Both mode changes retain the edited queue and the independent Popup geometry.
                onView(withId(R.id.sp_multifunction_mode)).perform(scrollTo(), click())
                onData(equalTo("Popup")).inRoot(isPlatformPopup()).perform(click())
                onView(withId(R.id.sp_multifunction_mode)).perform(scrollTo(), click())
                onData(equalTo("Classic")).inRoot(isPlatformPopup()).perform(click())
                openQueue()
                onView(withContentDescription("Default action 3")).check(matches(isChecked()))
                onView(withContentDescription("Delete action 3")).perform(scrollTo(), click())
                onView(withContentDescription("Default action 1")).check(matches(isChecked()))
                cancelDialog()
                openQueue()
                onView(withContentDescription("Default action 3")).check(matches(isChecked()))
                cancelDialog()
                saveDialog()
                val expected = original.withLongPressQueue(listOf(LongPressItem("t͡ʃ,😀"),
                    LongPressItem(action = KeyAction.COPY), LongPressItem(action = KeyAction.REPEAT_DOWN)), 2)
                assertEquals(expected, saved())
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(click())
                openQueue()
                onView(withContentDescription("Delete action 3")).perform(scrollTo(), click())
                onView(withContentDescription("Default action 1")).check(matches(isChecked()))
                saveDialog()
                cancelDialog()
                assertEquals("Outer Cancel discards an inner saved queue", expected, saved())
            }
        } finally {
            LayoutFileManager.deleteLayout(context, filename)
            snapshot.close()
        }
    }

    private fun openQueue() {
        onView(withId(R.id.btn_long_press_queue)).perform(scrollTo(), click())
        // The initial key can have no long-press binding.
        var hasRows = false
        onView(withId(R.id.classic_queue_add)).check { view, error ->
            if (error != null) throw error
            val root = view.rootView
            fun contains(v: View): Boolean = v.contentDescription == "Long-press action 1 function" ||
                (v is android.view.ViewGroup && (0 until v.childCount).any { contains(v.getChildAt(it)) })
            hasRows = contains(root)
        }
        if (!hasRows) onView(withId(R.id.classic_queue_add)).perform(click())
    }
    private fun saveDialog() = onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(awaitDialogButton(), click())
    private fun cancelDialog() = onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(awaitDialogButton(), click())

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
