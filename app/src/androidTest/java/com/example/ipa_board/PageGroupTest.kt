package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.example.ipa_board.ime.BuiltinLayouts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PageGroupTest {
    private lateinit var context: GroupContext
    @Before fun setup() { context = GroupContext(InstrumentationRegistry.getInstrumentation().targetContext) }
    @After fun cleanup() { context.cleanup() }

    @Test fun migratesExistingPagesOrderSelectionAndAppearanceExactlyOnce() {
        val first = LayoutFileManager.createLayout(context, "first", SettingsConstants.DEFAULT_LAYOUT)
        val second = LayoutFileManager.createLayout(context, "second", BuiltinLayouts.mixed)
        LayoutFileManager.saveLayoutOrder(context, listOf(second, first))
        context.getSharedPreferences(SettingsConstants.PREFS_NAME, 0).edit()
            .putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, first)
            .putString(SettingsConstants.KEY_BG_COLOR_HEX, "#123456")
            .putString(SettingsConstants.KEY_SYMBOL_COLOR_HEX, "#ABCDEF")
            .putInt(SettingsConstants.KEY_KEYBOARD_HEIGHT, 300)
            .putInt(SettingsConstants.KEY_KEYBOARD_FONT_SIZE, 25).commit()
        val state = PageGroupManager.state(context)
        assertEquals(1, state.groups.size)
        assertEquals(listOf(second, first), state.active.pages)
        assertEquals(first, PageGroupManager.activeFilename(context))
        assertEquals(GroupAppearance("#123456", "#ABCDEF", 300, 25), state.active.appearance)
        PageGroupManager.initialize(context)
        assertEquals(state, PageGroupManager.state(context))
        assertFalse(context.getSharedPreferences(SettingsConstants.PREFS_NAME, 0).contains(SettingsConstants.KEY_BG_COLOR_HEX))
    }

    @Test fun membershipsAreUniqueManyToManyAndEmptyGroupsDoNotBorrowOtherPages() {
        val file = LayoutFileManager.createBlankLayout(context, "shared")
        val first = PageGroupManager.active(context).id
        val second = PageGroupManager.create(context, "Second")
        assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.activeLayout(context))
        assertNull(PageGroupManager.activeFilename(context))
        assertEquals(listOf(file), LayoutFileManager.listLayoutFiles(context))
        PageGroupManager.addPages(context, second, listOf(file, file))
        PageGroupManager.addPages(context, second, listOf(file))
        assertEquals(listOf(file), PageGroupManager.pages(context))
        PageGroupManager.setPages(context, second, emptyList())
        assertEquals(listOf(file), PageGroupManager.state(context).groups.first { it.id == first }.pages)
        PageGroupManager.setPages(context, first, emptyList())
        assertTrue(PageGroupManager.state(context).groups.all { it.pages.isEmpty() })
        assertNotNull(LayoutFileManager.loadLayout(context, file))
        assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.activeLayout(context))
    }

    @Test fun samePageUsesDifferentGroupAppearanceAndSelectionSurvivesReorder() {
        val file = LayoutFileManager.createBlankLayout(context, "shared")
        val first = PageGroupManager.active(context).id
        val firstStyle = GroupAppearance("#123456", "#ABCDEF", 250, 20)
        PageGroupManager.setAppearance(context, first, firstStyle)
        val second = PageGroupManager.create(context, "Second")
        PageGroupManager.addPages(context, second, listOf(file))
        val secondStyle = GroupAppearance("#654321", "#FEDCBA", 350, 30)
        PageGroupManager.setAppearance(context, second, secondStyle)
        PageGroupManager.reorder(context, listOf(second, first))
        assertEquals("0 · Second", PageGroupManager.label(context, PageGroupManager.active(context)))
        assertEquals(secondStyle, PageGroupManager.active(context).appearance)
        assertEquals(file, PageGroupManager.activeFilename(context))
        PageGroupManager.select(context, first)
        assertEquals(firstStyle, PageGroupManager.active(context).appearance)
        assertEquals("1 · Default", PageGroupManager.label(context, PageGroupManager.active(context)))
        val json = JSONObject(File(context.filesDir, "layouts/$file").readText())
        assertFalse(json.has("appearance"))
        assertFalse(json.has("groups"))
        assertFalse(json.has("pageGroups"))
    }

    @Test fun groupDeletionKeepsFilesAndLastGroupCannotBeDeleted() {
        val file = LayoutFileManager.createBlankLayout(context, "keep")
        val first = PageGroupManager.active(context).id
        val second = PageGroupManager.create(context, "Second")
        PageGroupManager.delete(context, first)
        assertEquals(second, PageGroupManager.active(context).id)
        assertEquals(listOf(file), LayoutFileManager.listLayoutFiles(context))
        assertThrows(IllegalArgumentException::class.java) { PageGroupManager.delete(context, second) }
        assertThrows(IllegalArgumentException::class.java) { PageGroupManager.reorder(context, listOf(second, second)) }
        PageGroupManager.rename(context, second, "  Last  ")
        assertEquals("0 · Last", PageGroupManager.label(context, PageGroupManager.active(context)))
    }

    @Test fun renameAndDeleteUpdateEveryMembershipAndRememberEachGroupsPage() {
        val one = LayoutFileManager.createBlankLayout(context, "one")
        val two = LayoutFileManager.createBlankLayout(context, "two")
        val first = PageGroupManager.active(context).id
        PageGroupManager.selectPage(context, one)
        val second = PageGroupManager.create(context, "Second")
        PageGroupManager.addPages(context, second, listOf(one, two))
        PageGroupManager.selectPage(context, two)
        PageGroupManager.select(context, first)
        assertEquals(one, PageGroupManager.activeFilename(context))
        val renamed = LayoutFileManager.renameLayout(context, one, "renamed")
        assertTrue(PageGroupManager.state(context).groups.all { renamed in it.pages && one !in it.pages })
        assertEquals(renamed, PageGroupManager.activeFilename(context))
        PageGroupManager.select(context, second)
        assertEquals(two, PageGroupManager.activeFilename(context))
        assertTrue(LayoutFileManager.deleteLayout(context, renamed))
        assertTrue(PageGroupManager.state(context).groups.all { it.pages == listOf(two) })
        assertTrue(LayoutFileManager.deleteLayout(context, two))
        assertTrue(PageGroupManager.state(context).groups.all { it.pages.isEmpty() })
    }

    @Test fun manualImportAndCreationJoinCurrentGroupAndInitializationUsesGroupZero() {
        LayoutFileManager.initDefaultLayout(context)
        val bundledCount = context.assets.list("initialization/keyboards").orEmpty().count { it.endsWith(".json", true) }
        val initial = PageGroupManager.active(context)
        val expected = initial.pages
        assertEquals(1, PageGroupManager.state(context).groups.size)
        assertEquals(bundledCount, expected.size)
        assertEquals(LayoutFileManager.listLayoutFiles(context).toSet(), expected.toSet())
        val second = PageGroupManager.create(context, "Manual")
        val imported = BuiltinLayouts.mixed.toJson().byteInputStream().use { LayoutFileManager.importLayout(context, it, "imported.json") }
        val blank = LayoutFileManager.createBlankLayout(context, "blank")
        assertEquals(listOf(imported, blank), PageGroupManager.pages(context))
        assertEquals(second, PageGroupManager.active(context).id)
        assertEquals(blank, PageGroupManager.activeFilename(context))
        LayoutFileManager.initDefaultLayout(context)
        assertEquals(expected, PageGroupManager.state(context).groups.first { it.id == initial.id }.pages)
        val before = LayoutFileManager.listLayoutFiles(context)
        val invalid = JSONObject().put("version", 99).put("layout", JSONObject(BuiltinLayouts.mixed.toJson())).toString()
        assertThrows(IllegalArgumentException::class.java) { invalid.byteInputStream().use { LayoutFileManager.importLayout(context, it) } }
        assertEquals(before, LayoutFileManager.listLayoutFiles(context))
        assertEquals(listOf(imported, blank), PageGroupManager.pages(context))
    }

    private class GroupContext(base: Context) : ContextWrapper(base) {
        private val root = Files.createTempDirectory(base.cacheDir.toPath(), "page-group-test-").toFile()
        private val prefix = "page-group-test-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(prefix + name)
            return baseContext.getSharedPreferences(prefix + name, mode)
        }
        fun cleanup() { root.deleteRecursively(); names.forEach { baseContext.deleteSharedPreferences(it) } }
    }
}
