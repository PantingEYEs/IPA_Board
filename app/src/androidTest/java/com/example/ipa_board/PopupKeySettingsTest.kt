package com.example.ipa_board

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Test

/** Tests own their layout and restore page selection; the external runner protects app state. */
class PopupKeySettingsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun config(): PopupConfiguration = PopupConfiguration.default(widthDp = 100f, heightDp = 100f).copy(items = listOf(
        PopupItem(PopupPoint(-8f, 50f), 12f, LongPressItem("ɑ😀")),
        PopupItem(PopupPoint(50f, 50f), 12f),
        PopupItem(PopupPoint(200f, 200f), 12f, LongPressItem(action = KeyAction.COPY))))
    private fun key(mode: MultifunctionMode = MultifunctionMode.CLASSIC) = KeySlot(1f, "a",
        longPressText = "old", longPressItems = listOf(LongPressItem("old"), LongPressItem(action = KeyAction.COPY)),
        swipeLeftText = "left", swipeRightAction = KeyAction.PASTE,
        multifunctionMode = mode, popupConfiguration = config())
    private fun open() = onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(click())
    private fun mode(name: String) {
        onView(withId(R.id.sp_multifunction_mode)).perform(scrollTo(), click())
        onData(equalTo(name)).inRoot(isPlatformPopup()).perform(click())
    }
    private fun save() {
        onView(withId(R.id.et_key_text)).perform(closeSoftKeyboard())
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
    }
    private fun undoOperation() = onView(withId(com.google.android.material.R.id.snackbar_action)).perform(click())
    private fun addItem() = onView(withText("Add Item")).inRoot(isDialog()).perform(scrollTo(), click())
    private fun popupDraft(): PopupConfiguration {
        var draft: PopupConfiguration? = null
        onView(isAssignableFrom(PopupActionEditorView::class.java)).check { view, error ->
            if (error != null) throw error
            draft = (view as PopupActionEditorView).configuration
        }
        return requireNotNull(draft)
    }

    @Test fun modeSwitchSaveFiltersOnlyActivePopupAndPreservesClassicMappingsAndSaveUndo() = withLayout(key()) { file ->
        val original = LayoutFileManager.loadLayout(context, file)!!.rows.single().slots.single()
        open(); mode("Popup")
        onView(withId(R.id.classic_multifunction_editor)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.popup_multifunction_editor)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        save()
        val saved = LayoutFileManager.loadLayout(context, file)!!.rows.single().slots.single()
        assertEquals(original.copy(multifunctionMode = MultifunctionMode.POPUP,
            popupConfiguration = config().copy(items = listOf(config().items.first()))), saved)
        undoOperation()
        assertEquals(original, LayoutFileManager.loadLayout(context, file)!!.rows.single().slots.single())
        open(); mode("Popup"); addItem()
        val editedPopup = popupDraft()
        mode("Classic"); save()
        assertEquals("Switching modes must not delete inactive popup edits or mixed Classic bindings",
            original.copy(popupConfiguration = editedPopup), LayoutFileManager.loadLayout(context, file)!!.rows.single().slots.single())
        open(); mode("Popup")
        assertEquals(editedPopup, popupDraft())
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
    }

    @Test fun systemBackCancelsAndOperationUndoReopensUnsavedDraft() = withLayout(key(MultifunctionMode.POPUP)) { file ->
        val original = LayoutFileManager.loadLayout(context, file)
        open()
        addItem()
        val unsavedPopup = popupDraft()
        onView(withId(R.id.et_key_text)).perform(scrollTo(), replaceText("unsaved ɑ"), closeSoftKeyboard())
        pressBack()
        assertEquals(original, LayoutFileManager.loadLayout(context, file))
        undoOperation()
        onView(withId(R.id.et_key_text)).check(matches(withText("unsaved ɑ")))
        onView(withId(R.id.sp_multifunction_mode)).check(matches(withSpinnerText("Popup")))
        assertEquals("Cancel Undo must restore the unsaved popup draft too", unsavedPopup, popupDraft())
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
        assertEquals(original, LayoutFileManager.loadLayout(context, file))
    }

    private fun withLayout(slot: KeySlot, body: (String) -> Unit) {
        val snapshot = PageGroupTestState(context)
        val file = "test_popup_settings_${SystemClock.elapsedRealtimeNanos()}.json"
        try {
            LayoutFileManager.saveLayout(context, file, KeyboardLayout("Popup settings", listOf(RowLayout(1f, listOf(slot)))))
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(file))
            PageGroupManager.selectPage(context, file)
            ActivityScenario.launch(KeyboardPageActivity::class.java).use { body(file) }
        } finally {
            LayoutFileManager.deleteLayout(context, file)
            snapshot.close()
        }
    }
}
