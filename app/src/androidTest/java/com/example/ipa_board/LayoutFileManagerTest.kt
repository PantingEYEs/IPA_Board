package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LayoutFileManagerTest {
    private lateinit var context: IsolatedLayoutContext

    @Before fun setUp() {
        context = IsolatedLayoutContext(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun tearDown() {
        context.cleanUp()
    }

    @Test fun importPreservesFilenameAndMappingsWithoutOverwritingEarlierImports() {
        val original = SettingsConstants.DEFAULT_LAYOUT.withKeyText(0, 0, "t͡ʃ")
        val replacement = original.withKeyMapping(0, 0, "", KeyAction.BACKSPACE)
        val filename = LayoutFileManager.createLayout(context, "音标 chart.JSON", original)
        val duplicate = LayoutFileManager.createLayout(context, "音标 chart.JSON", replacement)

        assertEquals("音标 chart.JSON", filename)
        assertEquals("音标 chart (2).JSON", duplicate)
        assertEquals(original, LayoutFileManager.loadLayout(context, filename))
        assertEquals(replacement, LayoutFileManager.loadLayout(context, duplicate))
        assertEquals(listOf(duplicate, filename).sorted(), LayoutFileManager.listLayoutFiles(context))
    }

    @Test fun blankLayoutHasDefaultGeometryAndOnlyUnassignedTextKeys() {
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, "previous.json").commit()
        val filename = LayoutFileManager.createBlankLayout(context, "  New IPA 😀  ")
        val reloaded = requireNotNull(LayoutFileManager.loadLayout(context, filename))

        assertEquals("New IPA 😀.json", filename)
        assertEquals(SettingsConstants.DEFAULT_LAYOUT.copy(name = "New IPA 😀"), reloaded)
        assertTrue(reloaded.rows.flatMap { it.slots }.all { it.text.isEmpty() && it.action == KeyAction.TEXT })
        assertEquals("previous.json", prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null))
        assertEquals(1L, prefs.getLong(SettingsConstants.KEY_LAYOUT_REVISION, 0))
    }

    @Test fun defaultLayoutIsReservedAndCannotBeOverwrittenByImport() {
        val imported = SettingsConstants.DEFAULT_LAYOUT.withKeyText(0, 0, "x")
        val filename = LayoutFileManager.createLayout(context, "default.json", imported)
        LayoutFileManager.initDefaultLayout(context)
        val duplicate = LayoutFileManager.createLayout(context, "default.json", imported)

        assertEquals("default (2).json", filename)
        assertEquals("default (3).json", duplicate)
        assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.loadLayout(context, "default.json"))
        assertFalse(LayoutFileManager.deleteLayout(context, "default.json"))
    }

    @Test fun unnamedImportUsesLayoutNameAndTraversalCannotEscapeLayoutsDirectory() {
        val layout = SettingsConstants.DEFAULT_LAYOUT.copy(name = "IPA symbols")
        assertEquals("IPA symbols.json", LayoutFileManager.createLayout(context, " ", layout))
        val filename = LayoutFileManager.createLayout(context, "../outside.json", layout)
        assertEquals(layout, LayoutFileManager.loadLayout(context, filename))
        assertFalse(File(context.filesDir, "outside.json").exists())
        assertNull(LayoutFileManager.loadLayout(context, "../outside.json"))
        assertFalse(LayoutFileManager.deleteLayout(context, "../outside.json"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankNewLayoutName() {
        LayoutFileManager.createBlankLayout(context, "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalWhenSavingDirectly() {
        LayoutFileManager.saveLayout(context, "../outside.json", SettingsConstants.DEFAULT_LAYOUT)
    }

    /** Keep both test files and revision notifications out of the user's real configuration. */
    private class IsolatedLayoutContext(base: Context) : ContextWrapper(base) {
        private val root = Files.createTempDirectory(base.cacheDir.toPath(), "layout-files-test-").toFile()
        private val prefix = "layout-files-test-${UUID.randomUUID()}-"
        private val preferenceNames = mutableSetOf<String>()

        override fun getFilesDir(): File = root

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            preferenceNames.add(isolatedName)
            return baseContext.getSharedPreferences(isolatedName, mode)
        }

        fun cleanUp() {
            root.deleteRecursively()
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }
}
