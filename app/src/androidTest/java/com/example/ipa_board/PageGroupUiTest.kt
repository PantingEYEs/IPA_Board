package com.example.ipa_board

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageGroupUiTest {
    @Test fun managementSupportsEmptyGroupsMembershipUnassignedPagesAndGroupAppearance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        LayoutFileManager.initDefaultLayout(context)
        val snapshot = PageGroupTestState(context)
        val file = LayoutFileManager.createLayout(context, "group_ui_page", SettingsConstants.DEFAULT_LAYOUT)
        try {
            val first = PageGroupManager.active(context).id
            PageGroupManager.state(context).groups.filter { it.id != first }.forEach { PageGroupManager.delete(context, it.id) }
            PageGroupManager.setPages(context, first, listOf(file))
            PageGroupManager.setAppearance(context, first, GroupAppearance("#123456", "#FFFFFF", 250, 20))
            val second = PageGroupManager.create(context, "Group UI Empty")
            ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<Button>(R.id.btn_delete_group).isEnabled)
                    assertFalse(activity.findViewById<Button>(R.id.btn_delete_layout).isEnabled)
                    assertEquals(0, activity.findViewById<Spinner>(R.id.sp_layouts).count)
                }
                onView(withId(R.id.btn_group_pages)).perform(scrollTo(), click())
                onView(withText(file.removeSuffix(".json"))).inRoot(isDialog()).perform(click())
                onView(withText("Save")).inRoot(isDialog()).perform(click())
                assertEquals(listOf(file), PageGroupManager.pages(context))
                PageGroupManager.setAppearance(context, second, GroupAppearance("#654321", "#FFFFFF", 350, 30))
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("350dp", activity.findViewById<TextView>(R.id.tv_height_value).text.toString())
                    assertEquals("30sp", activity.findViewById<TextView>(R.id.tv_font_size_value).text.toString())
                    assertEquals(Color.parseColor("#654321"), (activity.findViewById<android.view.View>(R.id.keyboard_container).background as ColorDrawable).color)
                    activity.findViewById<Spinner>(R.id.sp_page_groups).setSelection(0)
                }
                instrumentation.waitForIdleSync()
                assertEquals(first, PageGroupManager.active(context).id)
                scenario.onActivity { activity ->
                    assertEquals("250dp", activity.findViewById<TextView>(R.id.tv_height_value).text.toString())
                    assertEquals("20sp", activity.findViewById<TextView>(R.id.tv_font_size_value).text.toString())
                    assertEquals(Color.parseColor("#123456"), (activity.findViewById<android.view.View>(R.id.keyboard_container).background as ColorDrawable).color)
                }
                onView(withId(R.id.btn_group_pages)).perform(scrollTo(), click())
                onView(withText(file.removeSuffix(".json"))).inRoot(isDialog()).perform(click())
                onView(withText("Save")).inRoot(isDialog()).perform(click())
                assertTrue(PageGroupManager.pages(context).isEmpty())
                onView(withId(R.id.cb_all_pages)).perform(scrollTo(), click())
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<CheckBox>(R.id.cb_all_pages).isChecked)
                    assertTrue(activity.findViewById<Button>(R.id.btn_rename_layout).isEnabled)
                    assertTrue(activity.findViewById<TextView>(R.id.tv_layout_name).text.contains("outside this group"))
                }
                assertNull(PageGroupManager.activeFilename(context))
                assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.activeLayout(context))
                onView(withId(R.id.btn_delete_group)).perform(scrollTo(), click())
                onView(withText("Delete")).inRoot(isDialog()).perform(click())
                assertEquals(second, PageGroupManager.active(context).id)
                assertNotNull(LayoutFileManager.loadLayout(context, file))
                scenario.onActivity { assertFalse(it.findViewById<Button>(R.id.btn_delete_group).isEnabled) }
            }
        } finally {
            LayoutFileManager.deleteLayout(context, file)
            snapshot.close()
        }
    }
}
