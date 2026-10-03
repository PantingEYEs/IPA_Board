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
        assertTrue(reloaded.rows.take(4).flatMap { it.slots }.all { it.text.isEmpty() && it.action == KeyAction.TEXT })
        assertEquals(filename, prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null))
        assertEquals(1L, prefs.getLong(SettingsConstants.KEY_LAYOUT_REVISION, 0))
    }

    @Test fun initializationMatchesNormalImportForEveryBundledFile() {
        val filenames = context.assets.list("initialization/keyboards").orEmpty()
            .filter { it.endsWith(".json", ignoreCase = true) }.sorted()
        val manualContext = IsolatedLayoutContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            filenames.forEach { filename ->
                manualContext.assets.open("initialization/keyboards/$filename").use {
                    LayoutFileManager.importLayout(manualContext, it, filename)
                }
            }
            LayoutFileManager.initDefaultLayout(context)
            val expected = LayoutFileManager.listLayoutFiles(manualContext)
            assertEquals(expected, LayoutFileManager.listLayoutFiles(context))
            expected.forEach { filename ->
                assertEquals(LayoutFileManager.loadLayout(manualContext, filename),
                    LayoutFileManager.loadLayout(context, filename))
            }
            val manualPrefs = manualContext.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val actualPrefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            listOf(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, SettingsConstants.KEY_BG_COLOR_HEX,
                SettingsConstants.KEY_SYMBOL_COLOR_HEX, SettingsConstants.KEY_KEYBOARD_HEIGHT).forEach { key ->
                assertEquals(manualPrefs.all[key], actualPrefs.all[key])
            }
            assertEquals(PageGroupManager.active(manualContext).pages, PageGroupManager.active(context).pages)
            assertEquals(PageGroupManager.active(manualContext).appearance, PageGroupManager.active(context).appearance)
            LayoutFileManager.initDefaultLayout(context)
            assertEquals(expected, LayoutFileManager.listLayoutFiles(context))
        } finally {
            manualContext.cleanUp()
        }
    }

    @Test fun sharedImportAcceptsRawAndWrappedLayoutsAndHandlesFilenameCollisions() {
        val layout = SettingsConstants.DEFAULT_LAYOUT.withKeyText(0, 0, "x")
        val raw = layout.toJson()
        val wrapped = org.json.JSONObject().put("version", 4)
            .put("layout", org.json.JSONObject(raw)).put("appearance", org.json.JSONObject()
                .put("backgroundColor", "#123456").put("symbolColor", "#ABCDEF").put("heightDp", 250)).toString()
        val first = raw.byteInputStream().use { LayoutFileManager.importLayout(context, it, "Custom.json") }
        val second = wrapped.byteInputStream().use { LayoutFileManager.importLayout(context, it, "Custom.JSON") }
        assertEquals("Custom.json", first)
        assertEquals("Custom (2).JSON", second)
        assertEquals(layout, LayoutFileManager.loadLayout(context, first))
        assertEquals(layout, LayoutFileManager.loadLayout(context, second))
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(second, prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null))
        assertEquals("#123456", PageGroupManager.active(context).appearance.backgroundColor)
        assertEquals(250, PageGroupManager.active(context).appearance.heightDp)
    }

    @Test fun sharedImportRejectsUnsupportedVersionBeforeCreatingPage() {
        val invalid = org.json.JSONObject().put("version", 99)
            .put("layout", org.json.JSONObject(SettingsConstants.DEFAULT_LAYOUT.toJson())).toString()
        assertThrows(IllegalArgumentException::class.java) {
            invalid.byteInputStream().use { LayoutFileManager.importLayout(context, it, "invalid.json") }
        }
        assertTrue(LayoutFileManager.listLayoutFiles(context).isEmpty())
    }

    @Test fun initializationPreservesExistingUserPages() {
        val filename = LayoutFileManager.createBlankLayout(context, "Custom")
        LayoutFileManager.initDefaultLayout(context)
        assertEquals(listOf(filename), LayoutFileManager.listLayoutFiles(context))
    }

    @Test fun legacyInitializationMarkerPreventsReseedingDeletedPages() {
        context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("default_layout_created", true).commit()
        LayoutFileManager.initDefaultLayout(context)
        assertTrue(LayoutFileManager.listLayoutFiles(context).isEmpty())
    }

    @Test fun bundledKaomojisLoadOnceAndStayDeleted() {
        val repository = com.example.ipa_board.kaomoji.KaomojiRepository(context)
        val items = repository.getAllKaomojis()
        val expectedDirectory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "expected-kaomoji-").toFile()
        try {
            val imported = com.example.ipa_board.kaomoji.KaomojiRepository(storageDirOverride = expectedDirectory)
            val expectedDir = "initialization/kaomoji"
            context.assets.list(expectedDir).orEmpty().filter { it.endsWith(".json", true) }.sorted().forEach {
                context.assets.open("$expectedDir/$it").use { stream -> imported.importJson(stream) }
            }
            assertEquals(imported.getAllKaomojis().map { Triple(it.text, it.tags, it.usageCount) },
                items.map { Triple(it.text, it.tags, it.usageCount) })
        } finally {
            expectedDirectory.deleteRecursively()
        }
        repository.batchDelete(items.map { it.id }.toSet())
        assertTrue(com.example.ipa_board.kaomoji.KaomojiRepository(context).getAllKaomojis().isEmpty())
    }

    @Test fun defaultPageCanBeDeletedWithoutRecreatingItOrChangingTheTemplate() {
        LayoutFileManager.initDefaultLayout(context)
        LayoutFileManager.saveLayout(context, "default.json", SettingsConstants.DEFAULT_LAYOUT.withKeyText(0, 0, "x"))
        assertTrue(LayoutFileManager.deleteLayout(context, "default.json"))
        LayoutFileManager.initDefaultLayout(context)
        assertNull(LayoutFileManager.loadLayout(context, "default.json"))
        val filename = LayoutFileManager.createBlankLayout(context, "New")
        assertEquals(SettingsConstants.DEFAULT_LAYOUT.copy(name = "New"), LayoutFileManager.loadLayout(context, filename))
    }

    @Test fun deletingAllPagesUsesTemporaryTemplateUntilAPageIsCreated() {
        LayoutFileManager.initDefaultLayout(context)
        LayoutFileManager.listLayoutFiles(context).forEach { assertTrue(LayoutFileManager.deleteLayout(context, it)) }
        LayoutFileManager.initDefaultLayout(context)
        assertTrue(LayoutFileManager.listLayoutFiles(context).isEmpty())
        assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.activeLayout(context))
        assertTrue(LayoutFileManager.listLayoutFiles(context).isEmpty())
        val page = SettingsConstants.DEFAULT_LAYOUT.withKeyText(0, 0, "x")
        val filename = LayoutFileManager.createLayout(context, "default.json", page)
        assertEquals("default.json", filename)
        PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(filename))
        assertEquals(page, LayoutFileManager.activeLayout(context))
        assertTrue(LayoutFileManager.deleteLayout(context, filename))
        assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.activeLayout(context))
    }

    @Test fun deletingActivePageSelectsRemainingPage() {
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val first = LayoutFileManager.createBlankLayout(context, "first")
        val second = LayoutFileManager.createBlankLayout(context, "second")
        prefs.edit().putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, first).commit()
        assertTrue(LayoutFileManager.deleteLayout(context, first))
        assertEquals(second, prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null))
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
