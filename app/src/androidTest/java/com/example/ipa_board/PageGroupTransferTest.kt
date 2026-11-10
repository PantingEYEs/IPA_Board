package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.PageEngineBinding
import com.example.ipa_board.shortcuts.ShortcutDefaults
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** All files and preferences belong to this fixture; no document provider or real user state is used. */
class PageGroupTransferTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val metadataName = "group_config.json"
    private val style = GroupAppearance(backgroundColor = "#80112233", symbolColor = "#445566",
        popupBackgroundColor = "#778899", popupSymbolColor = "#AABBCC", pointerPathColor = "#112244",
        pointerAxisColor = "#335577", pointerCrosshairColor = "#446688", heightDp = 310, fontSizeSp = 23,
        showGrid = false, showPointerLocation = true, usePointerDifferenceBlend = true)

    @Test fun metadataAndPageContentRoundTripAsIndependentCopiesWithoutChangingExistingGroups() = owned { context ->
        val existing = LayoutFileManager.createBlankLayout(context, "shared")
        val old = PageGroupManager.state(context)
        val oldBytes = File(context.filesDir, "layouts/$existing").readBytes().toList()
        val first = layout("First", "甲\uD800")
        val second = layout("Second", "乙")
        // Keep valid emoji as UTF-8, while the lone UTF-16 surrogate enters through a JSON escape.
        val wrappedFirst = JSONObject().put("version", 4).put("layout", JSONObject(first.toJson()))
            .toString().replace("\uD800", "\\uD800")
        assertTrue(wrappedFirst.contains("😀"))
        assertTrue(wrappedFirst.contains("\\uD800"))
        val folder = PageGroupTransfer.SourceFolder("Imported group", listOf(
            page("second.json", second), source(metadataName, metadata(listOf("shared.json", "second.json"), "second.json")
                .put("name", "This metadata name must not replace the selected folder name").toString()), source("shared.json", wrappedFirst)))
        val plan = PageGroupTransfer.readFolder(folder)
        assertEquals(listOf("shared.json", "second.json"), plan.pageNames)
        assertEquals(style, plan.appearance)
        val result = PageGroupTransfer.importGroup(context, plan)
        assertEquals(PageGroupTransfer.Status.IMPORTED, result.status)
        val state = PageGroupManager.state(context)
        val imported = state.groups.single { it.id == result.groupIds.single() }
        assertEquals("Imported group", imported.name)
        assertEquals(old.groups, state.groups.filter { it.id != imported.id })
        assertEquals(oldBytes, File(context.filesDir, "layouts/$existing").readBytes().toList())
        assertEquals(result.pageFiles, imported.pages)
        assertNotEquals(existing, imported.pages.first())
        assertEquals(listOf(first, second), imported.pages.map { LayoutFileManager.loadLayout(context, it) })
        assertEquals(imported.pages[1], imported.activePage)
        assertEquals(style, imported.appearance)
        assertEquals(imported.id, state.activeGroupId)

        val exported = PageGroupTransfer.captureGroup(context, imported.id)
        assertEquals(imported.name, exported.folderName)
        assertEquals(metadataName, exported.files.last().name)
        assertEquals(3, exported.files.size)
        val config = JSONObject(exported.files.last().bytes.toString(Charsets.UTF_8))
        assertEquals(style, GroupAppearance.fromJson(config.getJSONObject("appearance")))
        assertEquals(exported.files.dropLast(1).map { it.name }, strings(config.getJSONArray("pages")))
        owned { restoredContext ->
            // The metadata order must win even when a provider returns the exported files backwards.
            val restored = PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder(exported.folderName,
                exported.files.reversed().map { file -> PageGroupTransfer.SourceFile(file.name) { file.bytes.inputStream() } }))
            val restoredResult = PageGroupTransfer.importGroup(restoredContext, restored)
            assertEquals(PageGroupTransfer.Status.IMPORTED, restoredResult.status)
            val restoredGroup = PageGroupManager.state(restoredContext).groups.single()
            assertNotEquals(imported.id, restoredGroup.id)
            assertEquals(imported.name, restoredGroup.name)
            assertEquals(style, restoredGroup.appearance)
            assertEquals(listOf(first, second), restoredGroup.pages.map { LayoutFileManager.loadLayout(restoredContext, it) })
            assertEquals(restoredGroup.pages[1], restoredGroup.activePage)
        }
    }

    @Test fun missingMetadataUsesSourceOrderDefaultsAndFirstPageWithoutReadingUnrelatedFiles() = owned { context ->
        val first = layout("Z page", "Z")
        val second = layout("A page", "A")
        val wrapped = JSONObject().put("version", 4).put("layout", JSONObject(first.toJson()))
            .put("appearance", style.toJson()).toString()
        val plan = PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Without metadata", listOf(
            source("z.json", wrapped), PageGroupTransfer.SourceFile("README.md") { error("Not a page") }, page("A.JSON", second))))
        assertEquals(listOf("z.json", "A.JSON"), plan.pageNames)
        assertEquals("z.json", plan.activePage)
        assertEquals(GroupAppearance(), plan.appearance)
        val result = PageGroupTransfer.importGroup(context, plan)
        assertEquals(PageGroupTransfer.Status.IMPORTED, result.status)
        val group = PageGroupManager.state(context).groups.single()
        assertEquals(listOf(first, second), group.pages.map { LayoutFileManager.loadLayout(context, it) })
        assertEquals(group.pages.first(), group.activePage)
        assertEquals(GroupAppearance(), group.appearance)
    }

    @Test fun sameBatchCaseCollisionsGetDifferentTargetsAndKeepMetadataReferencesCorrect() = owned { context ->
        val lower = layout("Lower", "小")
        val upper = layout("Upper", "大")
        val plan = PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Case", listOf(page("a.json", lower),
            page("A.JSON", upper), source(metadataName, metadata(listOf("A.JSON", "a.json"), "a.json").toString()))))
        val result = PageGroupTransfer.importGroup(context, plan)
        assertEquals(PageGroupTransfer.Status.IMPORTED, result.status)
        val group = PageGroupManager.state(context).groups.single()
        assertEquals(2, group.pages.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size)
        assertEquals(listOf(upper, lower), group.pages.map { LayoutFileManager.loadLayout(context, it) })
        assertEquals(group.pages[1], group.activePage)
        val second = PageGroupTransfer.importGroup(context, plan)
        assertEquals(PageGroupTransfer.Status.IMPORTED, second.status)
        val groups = PageGroupManager.state(context).groups
        assertEquals(group, groups.first())
        val copy = groups.last()
        assertNotEquals(group.name.lowercase(java.util.Locale.ROOT), copy.name.lowercase(java.util.Locale.ROOT))
        assertTrue(group.pages.toSet().intersect(copy.pages.toSet()).isEmpty())
        assertEquals(listOf(upper, lower), copy.pages.map { LayoutFileManager.loadLayout(context, it) })
        assertEquals(copy.pages[1], copy.activePage)
    }

    @Test fun reservedPageFilenamesAreRenamedOnlyInTheExportAndMissingMembersAreNotSilentlyDropped() = owned { context ->
        val filename = LayoutFileManager.createBlankLayout(context, "group_config")
        val group = PageGroupManager.active(context)
        val before = snapshot(context)
        val exported = PageGroupTransfer.captureGroup(context, group.id)
        assertEquals(before, snapshot(context))
        assertEquals(metadataName, exported.files.last().name)
        assertEquals(1, exported.files.count { it.name.equals(metadataName, true) })
        val page = exported.files.first()
        assertFalse(page.name.equals(metadataName, true))
        val metadata = JSONObject(exported.files.last().bytes.toString(Charsets.UTF_8))
        assertEquals(listOf(page.name), strings(metadata.getJSONArray("pages")))
        assertEquals(page.name, metadata.getString("activePage"))
        assertEquals(listOf(filename), PageGroupManager.active(context).pages)
        assertEquals(LayoutFileManager.loadLayout(context, filename), KeyboardLayout.fromJson(page.bytes.toString(Charsets.UTF_8)))
        assertTrue(File(context.filesDir, "layouts/$filename").delete())
        val missing = snapshot(context)
        assertTrue(runCatching { PageGroupTransfer.captureGroup(context, group.id) }.isFailure)
        assertEquals(missing, snapshot(context))
    }

    @Test fun anExplicitEmptyGroupRoundTripsAsMetadataOnlyWithoutCreatingATemplatePage() = owned { context ->
        val plan = PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Empty", listOf(
            source("GROUP_CONFIG.JSON", metadata(emptyList(), null).toString()))))
        assertTrue(plan.pageNames.isEmpty())
        assertNull(plan.activePage)
        val result = PageGroupTransfer.importGroup(context, plan)
        assertEquals(PageGroupTransfer.Status.IMPORTED, result.status)
        val group = PageGroupManager.state(context).groups.single()
        assertTrue(group.pages.isEmpty())
        assertNull(PageGroupManager.activeFilename(context))
        assertTrue(LayoutFileManager.listLayoutFiles(context).isEmpty())
        assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.activeLayout(context))
        val exported = PageGroupTransfer.captureGroup(context, group.id)
        assertEquals(listOf(metadataName), exported.files.map { it.name })
        val again = PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Empty copy", exported.files.map { file ->
            PageGroupTransfer.SourceFile(file.name) { file.bytes.inputStream() }
        }))
        assertTrue(again.pageNames.isEmpty())
        assertEquals(style, again.appearance)
        assertNull(again.activePage)
    }

    @Test fun invalidMetadataPagesAndDuplicateSourcesCannotMutateAnExistingConfiguration() = owned { context ->
        LayoutFileManager.createBlankLayout(context, "Existing")
        val before = snapshot(context)
        fun config(change: (JSONObject) -> Unit) = metadata(listOf("page.json"), "page.json").apply(change).toString()
        val invalid = listOf("{broken", config { it.put("format", "unknown") }, config { it.put("schemaVersion", 2) },
            config { it.put("pages", JSONArray().put("page.json").put("page.json")) },
            config { it.put("pages", JSONArray().put("missing.json")) }, config { it.put("pages", JSONArray()) },
            config { it.put("activePage", "missing.json") }, config { it.put("activePage", 17) },
            config { it.getJSONObject("appearance").remove("pointerAxisColor") },
            config { it.getJSONObject("appearance").put("heightDp", 149) },
            config { it.getJSONObject("appearance").put("showGrid", "false") },
            layout("Not metadata", "x").toJson())
        for (config in invalid) {
            val files = listOf(page("page.json", layout("Valid", "x")), source(metadataName, config))
            assertTrue(runCatching { PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Rejected", files)) }.isFailure)
            assertEquals(before, snapshot(context))
        }
        val files = listOf(page("page.json", layout("Valid", "x")), page("page.json", layout("Duplicate", "y")))
        assertTrue(runCatching { PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Duplicate", files)) }.isFailure)
        val metadata = metadata(listOf("page.json"), "page.json").toString()
        assertTrue(runCatching { PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Duplicate metadata", listOf(
            files.first(), source(metadataName, metadata), source("GROUP_CONFIG.JSON", metadata)))) }.isFailure)
        assertTrue(runCatching { PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Bad second page", listOf(
            files.first(), source("broken.json", "{broken")))) }.isFailure)
        var opened = 0
        val tooMany = (0..256).map { index ->
            PageGroupTransfer.SourceFile("page-$index.json") { opened++; error("The page-count limit must reject before opening files") }
        }
        assertTrue(runCatching { PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Too many", tooMany)) }.isFailure)
        assertEquals(0, opened)
        assertEquals(before, snapshot(context))
    }

    @Test fun pageSizeReadAndCloseFailuresAbortPlanningAndKeepTheOriginalState() = owned { context ->
        LayoutFileManager.createBlankLayout(context, "Existing")
        val before = snapshot(context)
        val json = layout("Boundary", "x").toJson()
        val padded = json + " ".repeat(1024 * 1024 - json.toByteArray(Charsets.UTF_8).size)
        assertEquals(listOf("limit.json"), PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Boundary", listOf(source("limit.json", padded)))).pageNames)
        assertTrue(runCatching { PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Too large", listOf(source("large.json", padded + " ")))) }.isFailure)
        for (failClose in listOf(false, true)) {
            var closed = false
            val broken = PageGroupTransfer.SourceFile("broken.json") {
                object : ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (!failClose) throw IOException("Owned read failure")
                        return super.read(buffer, offset, length)
                    }
                    override fun close() {
                        closed = true
                        super.close()
                        if (failClose) throw IOException("Owned close failure")
                    }
                }
            }
            assertTrue(runCatching { PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder("Broken", listOf(
                page("valid.json", layout("First", "x")), broken))) }.isFailure)
            assertTrue(closed)
            assertEquals(before, snapshot(context))
        }
    }

    @Test fun partiallyWrittenNewPagesAreRemovedWhenALaterPageWriteFails() = owned { context ->
        LayoutFileManager.createBlankLayout(context, "Existing")
        val before = snapshot(context)
        val plan = twoPagePlan("Failed write")
        var writes = 0
        val result = PageGroupTransfer.importGroup(context, plan, writePage = { file, bytes ->
            file.writeBytes(bytes)
            if (++writes == 2) throw IOException("Owned second-page failure")
        })
        assertEquals(2, writes)
        assertEquals(PageGroupTransfer.Status.FAILED, result.status)
        assertEquals(before, snapshot(context))
        val shortWrite = PageGroupTransfer.importGroup(context, plan, writePage = { file, bytes ->
            file.writeBytes(bytes.copyOf(bytes.size / 2))
        })
        assertEquals(PageGroupTransfer.Status.FAILED, shortWrite.status)
        assertEquals(before, snapshot(context))
    }

    @Test fun preferenceFailuresRestoreFilesAndMetadataAndDistinguishFailedRestoration() {
        for (failure in listOf(Fault.WRITE_FALSE, Fault.WRITE_THROW)) owned { context ->
            LayoutFileManager.createBlankLayout(context, "Existing")
            assertTrue(context.raw.edit().putStringSet("unrelated", setOf("keep", "values")).commit())
            val before = snapshot(context)
            val scripted = FaultPreferences(context.raw, listOf(failure, Fault.SUCCESS))
            context.store = scripted
            val result = PageGroupTransfer.importGroup(context, twoPagePlan("Failed preference"))
            assertEquals(PageGroupTransfer.Status.FAILED, result.status)
            assertEquals(2, scripted.commits)
            assertEquals(before, snapshot(context))
        }
        owned { context ->
            val filename = LayoutFileManager.createBlankLayout(context, "Existing")
            val bytes = File(context.filesDir, "layouts/$filename").readBytes().toList()
            context.store = FaultPreferences(context.raw, listOf(Fault.WRITE_FALSE, Fault.SKIP_FALSE))
            assertEquals(PageGroupTransfer.Status.RESTORE_FAILED,
                PageGroupTransfer.importGroup(context, twoPagePlan("Failed restore")).status)
            assertEquals(bytes, File(context.filesDir, "layouts/$filename").readBytes().toList())
        }
    }

    @Test fun freshInitializationIsOneBatchAndExistingInitializationOrUserStateIsNeverReseeded() {
        val plans = listOf(twoPagePlan("First group"), twoPagePlan("Second group"))
        owned { context ->
            assertTrue(context.raw.edit().putBoolean(ShortcutDefaults.KEY_INITIALIZED, true)
                .putString(SettingsConstants.KEY_CTRL_SHORTCUTS, "{\"z\":\"undo\"}").commit())
            val result = PageGroupTransfer.initializeGroups(context, plans)
            assertEquals(PageGroupTransfer.Status.IMPORTED, result.status)
            val groups = PageGroupManager.state(context)
            assertEquals(listOf("First group", "Second group"), groups.groups.map { it.name })
            assertEquals(groups.groups.first().id, groups.activeGroupId)
            assertEquals(4, LayoutFileManager.listLayoutFiles(context).size)
            assertTrue(context.raw.getBoolean("default_layout_created", false))
            assertEquals("{\"z\":\"undo\"}", context.raw.getString(SettingsConstants.KEY_CTRL_SHORTCUTS, null))
            val before = snapshot(context)
            assertEquals(PageGroupTransfer.Status.SKIPPED, PageGroupTransfer.initializeGroups(context, plans).status)
            assertEquals(before, snapshot(context))
        }
        for (existing in listOf("marker", "empty-group", "page")) owned { context ->
            when (existing) {
                "marker" -> assertTrue(context.raw.edit().putBoolean("default_layout_created", true).commit())
                "empty-group" -> PageGroupManager.initialize(context)
                else -> {
                    val directory = File(context.filesDir, "layouts").apply { mkdirs() }
                    File(directory, "personal.json").writeText(layout("Personal", "私").toJson())
                }
            }
            val before = snapshot(context)
            assertEquals(existing, PageGroupTransfer.Status.SKIPPED, PageGroupTransfer.initializeGroups(context, plans).status)
            assertEquals(before, snapshot(context))
        }
    }

    @Test fun failedInitializationDoesNotSetTheCompletionMarkerAndCanRetryWithoutDuplicateGroups() = owned { context ->
        val plans = listOf(twoPagePlan("First group"), twoPagePlan("Second group"))
        val before = snapshot(context)
        context.store = FaultPreferences(context.raw, listOf(Fault.WRITE_FALSE, Fault.SUCCESS))
        assertEquals(PageGroupTransfer.Status.FAILED, PageGroupTransfer.initializeGroups(context, plans).status)
        assertEquals(before, snapshot(context))
        assertFalse(context.raw.contains("default_layout_created"))
        context.store = context.raw
        assertEquals(PageGroupTransfer.Status.IMPORTED, PageGroupTransfer.initializeGroups(context, plans).status)
        assertEquals(listOf("First group", "Second group"), PageGroupManager.state(context).groups.map { it.name })
        assertEquals(4, LayoutFileManager.listLayoutFiles(context).size)
    }

    private fun twoPagePlan(name: String) = PageGroupTransfer.readFolder(PageGroupTransfer.SourceFolder(name, listOf(
        page("first.json", layout("First", "甲")), page("second.json", layout("Second", "乙")),
        source(metadataName, metadata(listOf("second.json", "first.json"), "first.json").toString()))))

    private fun layout(name: String, text: String) = KeyboardLayout(name, listOf(RowLayout(1.5f, listOf(
        KeySlot(2f, "$text t͡ʃ😀", textBehavior = TextBehavior.AUTO,
            longPressItems = listOf(LongPressItem(","), LongPressItem(action = KeyAction.UNDO), LongPressItem(action = KeyAction.REDO)),
            swipeLeftAction = KeyAction.PREV_PAGE, swipeRightText = "›")))),
        PageEngineBinding(mapOf("engine_chinese_conversion" to true, "future_engine" to false)))

    private fun source(name: String, json: String) = PageGroupTransfer.SourceFile(name) { json.byteInputStream(Charsets.UTF_8) }
    private fun page(name: String, layout: KeyboardLayout) = source(name, layout.toJson())
    private fun metadata(pages: List<String>, active: String?) = JSONObject().put("format", "ipa-board-page-group")
        .put("schemaVersion", 1).put("appearance", style.toJson()).put("pages", JSONArray(pages))
        .put("activePage", active ?: JSONObject.NULL)
    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    private data class Snapshot(val files: Map<String, List<Byte>>, val preferences: Map<String, *>)
    private fun snapshot(context: OwnedContext) = Snapshot(context.filesDir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(context.filesDir).path to it.readBytes().toList() }, context.raw.all)
    private fun owned(body: (OwnedContext) -> Unit) = OwnedContext(target).use(body)

    private class OwnedContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val root = Files.createTempDirectory(base.cacheDir.toPath(), "page-group-transfer-").toFile()
        private val name = "page-group-transfer-${UUID.randomUUID()}"
        val raw = base.getSharedPreferences(name, Context.MODE_PRIVATE)
        var store: SharedPreferences = raw
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int) = store
        override fun close() { root.deleteRecursively(); baseContext.deleteSharedPreferences(name) }
    }

    private enum class Fault { SUCCESS, WRITE_FALSE, WRITE_THROW, SKIP_FALSE }
    private class FaultPreferences(private val base: SharedPreferences, private val script: List<Fault>) : SharedPreferences by base {
        var commits = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = base.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                override fun putStringSet(key: String, values: Set<String>?) = apply { editor.putStringSet(key, values) }
                override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
                override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
                override fun putFloat(key: String, value: Float) = apply { editor.putFloat(key, value) }
                override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                override fun remove(key: String) = apply { editor.remove(key) }
                override fun clear() = apply { editor.clear() }
                override fun commit(): Boolean {
                    val fault = script.getOrElse(commits++) { error("Unexpected preference transaction") }
                    if (fault == Fault.SKIP_FALSE) return false
                    check(editor.commit()) { "Owned preference storage failed" }
                    return when (fault) {
                        Fault.SUCCESS -> true
                        Fault.WRITE_FALSE -> false
                        Fault.WRITE_THROW -> throw IOException("Owned preference commit failure")
                        Fault.SKIP_FALSE -> error("unreachable")
                    }
                }
                override fun apply() { error("A group transaction must check its commit result") }
            }
        }
    }
}
