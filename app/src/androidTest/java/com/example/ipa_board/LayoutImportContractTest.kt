package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID

/** Import is a transaction: invalid input must not change a working keyboard configuration. */
@RunWith(AndroidJUnit4::class)
class LayoutImportContractTest {
    private lateinit var context: ImportContext

    @Before fun setUp() {
        context = ImportContext(InstrumentationRegistry.getInstrumentation().targetContext)
        LayoutFileManager.createBlankLayout(context, "Existing user page")
        val group = PageGroupManager.active(context)
        PageGroupManager.setAppearance(context, group.id, group.appearance.copy(
            backgroundColor = "#102030", symbolColor = "#DDEEFF", heightDp = 270, fontSizeSp = 19))
    }

    @After fun tearDown() {
        context.close()
    }

    @Test fun rejectedConfigurationsLeaveFilesSelectionAppearanceAndRevisionsUntouched() {
        val invalidConfigurations = listOf(
            "{broken",
            envelope().put("version", 99).toString(),
            envelope().put("appearance", appearance().put("backgroundColor", "red")).toString(),
            envelope().put("appearance", appearance().put("heightDp", 149)).toString(),
            envelope().put("appearance", appearance().put("fontSizeSp", SettingsConstants.MAX_KEYBOARD_FONT_SIZE + 1)).toString(),
            envelope().put("appearance", appearance().apply { remove("symbolColor") }).toString(),
            changedLayout { it.put("rows", JSONArray()) }.toString(),
            changedKey { it.put("widthWeight", 0) }.toString(),
            changedKey { it.put("action", "future_unknown_action") }.toString(),
            changedKey { it.put("textBehavior", "future_unknown_mode") }.toString(),
            changedKey { it.put("text", "a".repeat(1001)) }.toString()
        )
        val before = snapshot()
        for ((index, json) in invalidConfigurations.withIndex()) {
            assertThrows("Invalid configuration $index", Exception::class.java) {
                json.byteInputStream().use { LayoutFileManager.importLayout(context, it, "Rejected $index.json") }
            }
            assertEquals("Rejected configuration $index changed existing state", before, snapshot())
        }
    }

    @Test fun oversizedAndInterruptedStreamsCannotPartiallyCreateOrActivateAPage() {
        val before = snapshot()
        val tooLarge = paddedEnvelope(MAX_IMPORT_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            tooLarge.byteInputStream().use { LayoutFileManager.importLayout(context, it, "Too large.json") }
        }
        assertEquals(before, snapshot())

        val json = envelope().toString().toByteArray()
        val interrupted = object : InputStream() {
            private var offset = 0
            override fun read(): Int {
                if (offset >= json.size / 2) throw IOException("Test source disconnected")
                return json[offset++].toInt() and 0xff
            }
        }
        assertThrows(IOException::class.java) {
            interrupted.use { LayoutFileManager.importLayout(context, it, "Interrupted.json") }
        }
        assertEquals(before, snapshot())
    }

    @Test fun maximumSizeConfigurationIsAcceptedWithoutChangingEarlierPage() {
        val originalFile = PageGroupManager.activeFilename(context)!!
        val original = LayoutFileManager.loadLayout(context, originalFile)
        val exactLimit = paddedEnvelope(MAX_IMPORT_BYTES)
        assertEquals(MAX_IMPORT_BYTES, exactLimit.toByteArray().size)
        val imported = exactLimit.byteInputStream().use {
            LayoutFileManager.importLayout(context, it, "At limit.json")
        }
        assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.loadLayout(context, imported))
        assertEquals(original, LayoutFileManager.loadLayout(context, originalFile))
        assertEquals(imported, PageGroupManager.activeFilename(context))
        assertEquals(listOf(originalFile, imported), PageGroupManager.active(context).pages)
    }

    @Test fun importedMappingsSurviveStorageIncludingCommaFunctionsAndSwipes() {
        val slot = KeySlot(
            widthWeight = 2f, text = "ㄋ", textBehavior = TextBehavior.AUTO,
            longPressItems = listOf(LongPressItem(","), LongPressItem("t͡ʃ😀"), LongPressItem(action = KeyAction.BACKSPACE)),
            swipeLeftText = "‹", swipeLeftAction = KeyAction.TEXT,
            swipeRightAction = KeyAction.NEXT_PAGE)
        val layout = KeyboardLayout("Complete mappings", listOf(RowLayout(1.5f, listOf(slot))))
        val config = JSONObject().put("version", 4).put("layout", JSONObject(layout.toJson()))
            .put("appearance", appearance().put("fontSizeSp", 18))
        val imported = config.toString().byteInputStream().use {
            LayoutFileManager.importLayout(context, it, "Mappings.json")
        }
        val stored = requireNotNull(LayoutFileManager.loadLayout(context, imported))
        assertEquals(layout, stored)
        val restored = stored.rows.single().slots.single()
        assertEquals(listOf(",", "t͡ʃ😀", ""), restored.effectiveLongPressItems.map { it.text })
        assertEquals(KeyAction.BACKSPACE, restored.effectiveLongPressItems.last().action)
        assertEquals(LongPressItem("‹"), restored.effectiveSwipeLeftItem)
        assertEquals(LongPressItem(action = KeyAction.NEXT_PAGE), restored.effectiveSwipeRightItem)
        assertEquals(18, PageGroupManager.active(context).appearance.fontSizeSp)
        assertEquals("#123456", PageGroupManager.active(context).appearance.backgroundColor)
    }

    @Test fun legacyLayoutWithoutBehaviorOrGestureFieldsKeepsDirectTextEntry() {
        val raw = """{"name":"Legacy IPA","rows":[{"heightWeight":1,"slots":[{"widthWeight":1,"text":"t͡ʃ"}]}]}"""
        val imported = raw.byteInputStream().use { LayoutFileManager.importLayout(context, it) }
        val slot = requireNotNull(LayoutFileManager.loadLayout(context, imported)).rows.single().slots.single()
        assertEquals("t͡ʃ", slot.text)
        assertEquals(KeyAction.TEXT, slot.action)
        assertEquals(TextBehavior.LITERAL, slot.textBehavior)
        assertTrue(slot.effectiveLongPressItems.isEmpty())
        assertNull(slot.effectiveSwipeLeftItem)
        assertNull(slot.effectiveSwipeRightItem)
        assertEquals("#102030", PageGroupManager.active(context).appearance.backgroundColor)
    }

    private fun envelope() = JSONObject().put("version", 4)
        .put("layout", JSONObject(SettingsConstants.DEFAULT_LAYOUT.toJson()))

    private fun appearance() = JSONObject().put("backgroundColor", "#123456")
        .put("symbolColor", "#ABCDEF").put("heightDp", 250)

    private fun changedLayout(change: (JSONObject) -> Unit): JSONObject = envelope().apply {
        change(getJSONObject("layout"))
    }

    private fun changedKey(change: (JSONObject) -> Unit): JSONObject = changedLayout {
        change(it.getJSONArray("rows").getJSONObject(0).getJSONArray("slots").getJSONObject(0))
    }

    private fun paddedEnvelope(bytes: Int): String {
        val json = envelope().toString()
        return json + " ".repeat(bytes - json.toByteArray().size)
    }

    private fun snapshot() = State(
        context.filesDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(context.filesDir).path to it.readBytes().toList()
        },
        context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE).all.toMap())

    private data class State(val files: Map<String, List<Byte>>, val preferences: Map<String, *>)

    private class ImportContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val root = Files.createTempDirectory(base.cacheDir.toPath(), "import-contract-").toFile()
        private val prefix = "import-contract-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }

        override fun close() {
            root.deleteRecursively()
            names.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }

    private companion object { const val MAX_IMPORT_BYTES = 1024 * 1024 }
}
