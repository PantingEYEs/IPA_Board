package com.example.ipa_board

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.SeekBar
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File

@RunWith(AndroidJUnit4::class)
class LayoutManagementTest {
    @Test fun renamePageValidatesAndPersistsWithoutChangingMappingsOrActiveFile() {
        LayoutFixture().use { fixture ->
            val original = KeyboardLayout("Before", listOf(RowLayout(1f, listOf(KeySlot(1f, "a", longPressText = "ɑ")))))
            val filename = fixture.create(original)
            fixture.select(filename)
            ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
                onView(withId(R.id.btn_rename_layout)).perform(scrollTo(), click())
                onView(withId(R.id.et_layout_name)).check(matches(withText("Before")))
                onView(withId(R.id.et_layout_name)).perform(replaceText("Cancelled"), closeSoftKeyboard())
                onView(withText("Cancel")).perform(click())
                assertEquals(original, LayoutFileManager.loadLayout(fixture.context, filename))
                onView(withId(R.id.btn_rename_layout)).perform(scrollTo(), click())
                onView(withId(R.id.et_layout_name)).perform(replaceText("   "), closeSoftKeyboard())
                onView(withText("Save")).perform(click())
                onView(withId(R.id.et_layout_name)).check(matches(isDisplayed()))
                assertEquals(original, LayoutFileManager.loadLayout(fixture.context, filename))
                onView(withId(R.id.et_layout_name)).perform(replaceText("  IPA renamed  "), closeSoftKeyboard())
                onView(withText("Save")).perform(click())
                val newFilename = "IPA renamed.json"
                assertEquals(original.copy(name = "IPA renamed"), LayoutFileManager.loadLayout(fixture.context, newFilename))
                assertEquals(newFilename, fixture.activeFilename())
                onView(withId(R.id.tv_layout_name)).check(matches(withText("IPA renamed")))
                scenario.recreate()
                onView(withId(R.id.tv_layout_name)).check(matches(withText("IPA renamed")))
                assertEquals(newFilename, fixture.activeFilename())
            }
        }
    }

    @Test fun deletingActiveLayoutCanBeCancelledAndSelectsRemainingPage() {
        LayoutFixture().use { fixture ->
            val filename = fixture.create(SettingsConstants.DEFAULT_LAYOUT)
            fixture.select(filename)
            ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
                onView(withId(R.id.btn_delete_layout)).perform(scrollTo(), click())
                onView(withText("Cancel")).perform(click())
                assertEquals(filename, fixture.activeFilename())
                assertNotNull(LayoutFileManager.loadLayout(fixture.context, filename))
                onView(withId(R.id.btn_delete_layout)).perform(scrollTo(), click())
                onView(withText("Delete")).perform(click())
                assertNull(LayoutFileManager.loadLayout(fixture.context, filename))
                assertFalse(LayoutFileManager.listLayoutFiles(fixture.context).contains(filename))
                assertEquals(PageGroupManager.pages(fixture.context).firstOrNull(), fixture.activeFilename())
                onView(withId(R.id.sp_layouts)).check(matches(withSpinnerText(requireNotNull(fixture.activeFilename()).removeSuffix(".json"))))
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<android.widget.Button>(R.id.btn_delete_layout).isEnabled)
                }
                scenario.recreate()
                assertEquals(PageGroupManager.pages(fixture.context).firstOrNull(), fixture.activeFilename())
            }
        }
    }

    @Test fun importWithoutSourceMetadataUsesLayoutNameAndActivatesImportedConfiguration() {
        LayoutFixture().use { fixture ->
            val originalFilename = fixture.create(SettingsConstants.DEFAULT_LAYOUT)
            fixture.select(originalFilename)
            // Sort after the current file to expose stale position restoration in the layout spinner.
            val importedName = "zz_test_import_${System.nanoTime()}"
            val importedFilename = "$importedName.json"
            fixture.track(importedFilename)
            val imported = KeyboardLayout(importedName, listOf(
                RowLayout(2f, listOf(KeySlot(3f, "ɪ"), KeySlot(1f, action = KeyAction.BACKSPACE)))
            ))
            val config = JSONObject().apply {
                put("version", 2)
                put("layout", JSONObject(imported.toJson()))
                put("appearance", JSONObject().apply {
                    put("backgroundColor", "#123456")
                    put("symbolColor", "#FEDCBA")
                    put("heightDp", 300)
                })
            }
            val sourceFile = File.createTempFile("layout_import_test_", ".json", fixture.context.cacheDir)
            try {
                sourceFile.writeText(config.toString())
                ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
                    onView(withId(R.id.sp_layouts)).check(matches(withSpinnerText(originalFilename)))
                    scenario.onActivity { activity ->
                        // File URIs can be read but provide no DISPLAY_NAME, exercising the name fallback.
                        val result = Intent().setData(Uri.fromFile(sourceFile))
                        KeyboardPageActivity::class.java.getDeclaredMethod(
                            "onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java
                        ).apply { isAccessible = true }.invoke(activity, 123, Activity.RESULT_OK, result)
                    }

                    onView(withId(R.id.sp_layouts)).check(matches(withSpinnerText(importedFilename)))
                    onView(withId(R.id.tv_height_value)).check(matches(withText("300dp")))
                    assertEquals(importedFilename, fixture.activeFilename())
                    assertEquals(imported, LayoutFileManager.loadLayout(fixture.context, importedFilename))
                    assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.loadLayout(fixture.context, originalFilename))
                    val appearance = PageGroupManager.active(fixture.context).appearance
                    assertEquals("#123456", appearance.backgroundColor)
                    assertEquals("#FEDCBA", appearance.symbolColor)
                    assertEquals(300, appearance.heightDp)
                    scenario.onActivity { activity ->
                        assertEquals(150, activity.findViewById<SeekBar>(R.id.sb_height).progress)
                    }
                }
            } finally {
                sourceFile.delete()
            }
        }
    }

    @Test fun createNamedBlankLayoutSelectsAndPersistsIt() {
        LayoutFixture().use { fixture ->
            val original = fixture.create(SettingsConstants.DEFAULT_LAYOUT)
            fixture.select(original)
            val name = "Test Blank ${System.nanoTime()}"
            val expectedFilename = "$name.json"
            fixture.track(expectedFilename)

            ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
                onView(withId(R.id.btn_new_layout)).perform(scrollTo(), click())
                onView(withText("New Blank Layout")).check(matches(isDisplayed()))
                onView(withId(R.id.et_layout_name)).perform(replaceText(name), closeSoftKeyboard())
                onView(withText("Create")).perform(click())

                onView(withId(R.id.sp_layouts)).check(matches(withSpinnerText(expectedFilename)))
                assertEquals(expectedFilename, fixture.activeFilename())
                val saved = requireNotNull(LayoutFileManager.loadLayout(fixture.context, expectedFilename))
                assertEquals(name, saved.name)
                assertEquals(SettingsConstants.DEFAULT_LAYOUT.rows, saved.rows)
                assertTrue(saved.rows.flatMap { it.slots }.all { it.text.isEmpty() && it.action == KeyAction.TEXT })
                assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.loadLayout(fixture.context, original))

                scenario.recreate()
                onView(withId(R.id.sp_layouts)).check(matches(withSpinnerText(expectedFilename)))
                assertEquals(saved, LayoutFileManager.loadLayout(fixture.context, expectedFilename))
            }
        }
    }

    @Test fun cancellingNewLayoutLeavesFilesAndSelectionUnchanged() {
        LayoutFixture().use { fixture ->
            val filename = fixture.create(SettingsConstants.DEFAULT_LAYOUT)
            fixture.select(filename)
            val cancelledName = "Test Cancelled ${System.nanoTime()}"
            fixture.track("$cancelledName.json")

            ActivityScenario.launch(KeyboardPageActivity::class.java).use {
                val filesBefore = LayoutFileManager.listLayoutFiles(fixture.context)
                onView(withId(R.id.btn_new_layout)).perform(scrollTo(), click())
                onView(withId(R.id.et_layout_name)).perform(replaceText(cancelledName), closeSoftKeyboard())
                onView(withText("Cancel")).perform(click())

                onView(withId(R.id.sp_layouts)).check(matches(withSpinnerText(filename)))
                assertEquals(filename, fixture.activeFilename())
                assertEquals(filesBefore, LayoutFileManager.listLayoutFiles(fixture.context))
            }
        }
    }

    @Test fun clearRemovesTextAndFunctionMappingsPreservesGeometryAndCanBeUndone() {
        LayoutFixture().use { fixture ->
            val original = KeyboardLayout("Mixed IPA", listOf(
                RowLayout(2f, listOf(KeySlot(3f, "t͡ʃ"), KeySlot(1f, action = KeyAction.BACKSPACE))),
                RowLayout(1f, listOf(KeySlot(2f, action = KeyAction.CTRL), KeySlot(4f, action = KeyAction.SHIFT)))
            ))
            val filename = fixture.create(original)
            fixture.select(filename)

            ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
                onView(withId(R.id.btn_undo_clear)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.btn_clear_layout)).perform(scrollTo(), click())

                val cleared = requireNotNull(LayoutFileManager.loadLayout(fixture.context, filename))
                assertEquals(filename, fixture.activeFilename())
                assertEquals(original.name, cleared.name)
                assertEquals(original.rows.map { it.heightWeight }, cleared.rows.map { it.heightWeight })
                assertEquals(
                    original.rows.map { row -> row.slots.map { it.widthWeight } },
                    cleared.rows.map { row -> row.slots.map { it.widthWeight } }
                )
                assertTrue(cleared.rows.flatMap { it.slots }.all { it.text.isEmpty() && it.action == KeyAction.TEXT })
                onView(withId(R.id.btn_undo_clear)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))

                // Clearing an already empty layout must not replace the original undo snapshot.
                onView(withId(R.id.btn_clear_layout)).perform(scrollTo(), click())
                onView(withId(R.id.btn_undo_clear)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
                // The cleared configuration is saved, and recreation retains its undo snapshot.
                scenario.recreate()
                assertEquals(cleared, LayoutFileManager.loadLayout(fixture.context, filename))
                onView(withId(R.id.btn_undo_clear)).perform(scrollTo(), click())
                assertEquals(original, LayoutFileManager.loadLayout(fixture.context, filename))
                onView(withId(R.id.btn_undo_clear)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            }
        }
    }

    @Test fun selectingAnotherLayoutDiscardsUndoWithoutChangingThatLayout() {
        LayoutFixture().use { fixture ->
            val original = KeyboardLayout("First", listOf(RowLayout(1f, listOf(KeySlot(1f, "ɪ")))))
            val other = KeyboardLayout("Second", listOf(RowLayout(2f, listOf(KeySlot(3f, "ʊ")))))
            val firstFilename = fixture.create(original)
            val secondFilename = fixture.create(other)
            fixture.select(firstFilename)

            ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
                onView(withId(R.id.btn_clear_layout)).perform(scrollTo(), click())
                onView(withId(R.id.btn_undo_clear)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
                scenario.onActivity { activity ->
                    val spinner = activity.findViewById<Spinner>(R.id.sp_layouts)
                    val index = (0 until spinner.count).first { spinner.getItemAtPosition(it) == secondFilename }
                    spinner.setSelection(index)
                }

                onView(withId(R.id.sp_layouts)).check(matches(withSpinnerText(secondFilename)))
                onView(withId(R.id.btn_undo_clear)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                assertEquals(secondFilename, fixture.activeFilename())
                assertEquals(other, LayoutFileManager.loadLayout(fixture.context, secondFilename))
                assertEquals("", LayoutFileManager.loadLayout(fixture.context, firstFilename)!!.rows.single().slots.single().text)
            }
        }
    }

    /** Every UI operation targets disposable files; restore the preferences changed by those operations. */
    private class LayoutFixture : Closeable {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        private val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        private val groupSnapshot = PageGroupTestState(context)
        private val originalActive = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        private val hadRevision = prefs.contains(SettingsConstants.KEY_LAYOUT_REVISION)
        private val originalRevision = prefs.getLong(SettingsConstants.KEY_LAYOUT_REVISION, 0)
        private val originalAppearance = listOf(
            SettingsConstants.KEY_BG_COLOR_HEX,
            SettingsConstants.KEY_SYMBOL_COLOR_HEX,
            SettingsConstants.KEY_KEYBOARD_HEIGHT
        ).associateWith { prefs.all[it] }
        private val files = mutableSetOf<String>()

        fun track(filename: String) { files.add(filename) }

        fun create(layout: KeyboardLayout): String {
            val filename = "test_layout_management_${System.nanoTime()}.json"
            track(filename)
            LayoutFileManager.saveLayout(context, filename, layout)
            return filename
        }

        fun select(filename: String) {
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(filename))
            PageGroupManager.selectPage(context, filename)
        }

        fun activeFilename(): String? = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)

        override fun close() {
            prefs.edit().apply {
                if (originalActive == null) remove(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE)
                else putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, originalActive)
                if (hadRevision) putLong(SettingsConstants.KEY_LAYOUT_REVISION, originalRevision)
                else remove(SettingsConstants.KEY_LAYOUT_REVISION)
                originalAppearance.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        else -> remove(key)
                    }
                }
            }.commit()
            files.forEach { LayoutFileManager.deleteLayout(context, it) }
            groupSnapshot.close()
        }
    }
}
