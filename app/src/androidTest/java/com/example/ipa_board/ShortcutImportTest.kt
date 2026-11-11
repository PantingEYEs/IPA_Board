package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.shortcuts.ReplacementSettings
import com.example.ipa_board.shortcuts.ShortcutExport
import com.example.ipa_board.shortcuts.ShortcutImport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

/** Import contracts use isolated preferences and synthetic in-memory documents only. */
class ShortcutImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val keys = listOf(SettingsConstants.KEY_SHIFT_SHORTCUTS, SettingsConstants.KEY_CTRL_SHORTCUTS,
        MultifunctionBarSettings.KEY_BINDINGS, ReplacementSettings.KEY)

    @Test fun anExportRestoresAllMappingsToolbarBindingsAndOrderedDuplicateRuleContent() = isolated { context, prefs ->
        val encoded = completeExport(includeIsolatedSurrogate = true)
        val expected = JSONObject(encoded)
        assertTrue(prefs.edit().putString("unrelated", "keep").putBoolean("engine_option", false).commit())
        val input = object : ByteArrayInputStream(encoded.toByteArray(Charsets.UTF_8)) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val plan = ShortcutImport.read(input)
        assertFalse("The caller owns the document stream", input.closed)
        input.close()
        assertEquals(setOf("shift", "ctrl", "multifunctionBar", "replacementText"), plan.categoryNames.toSet())
        assertEquals(KeyAction.entries.size, plan.counts.getValue("shift"))
        assertEquals(KeyAction.entries.size, plan.counts.getValue("ctrl"))
        assertEquals(6, plan.counts.getValue("multifunctionBar"))
        assertEquals(3, plan.counts.getValue("replacementText"))
        assertEquals(ShortcutImport.ApplyResult.APPLIED, ShortcutImport.apply(context, plan))
        assertJsonEquals(expected, JSONObject(ShortcutExport.capture(context)))
        assertEquals("keep", prefs.getString("unrelated", null))
        assertFalse(prefs.getBoolean("engine_option", true))
        val rules = ReplacementSettings.load(context)
        assertEquals(listOf("rule-1", "rule-2", "rule-3"), rules.map { it.id })
        assertEquals(rules[0].source, rules[1].source)
        assertEquals(rules[1].replacement, rules[2].replacement)
        assertEquals('\uD800', rules[0].replacement.last())
    }

    @Test fun aDefaultExportRestoresEmptyCategoriesAndAllSixEffectiveToolbarDefaults() = isolated { context, prefs ->
        assertEquals(ShortcutImport.ApplyResult.APPLIED, ShortcutImport.apply(context, ShortcutImport.parse(completeExport())))
        val defaults = ShortcutExport.encode(emptyMap<String, Any>())
        assertEquals(ShortcutImport.ApplyResult.APPLIED, ShortcutImport.apply(context, ShortcutImport.parse(defaults)))
        assertJsonEquals(JSONObject(defaults), JSONObject(ShortcutExport.capture(context)))
        assertTrue(SettingsConstants.parseShortcutsJson(prefs.getString(keys[0], null)).isEmpty())
        assertTrue(SettingsConstants.parseShortcutsJson(prefs.getString(keys[1], null)).isEmpty())
        assertTrue(ReplacementSettings.load(context).isEmpty())
        assertEquals(MultifunctionBarSettings.defaults(), MultifunctionBarSettings.load(context))
    }

    @Test fun missingCategoriesKeepTheirExactSavedValuesWhileExplicitEmptyCategoriesAreCleared() = isolated { context, prefs ->
        assertEquals(ShortcutImport.ApplyResult.APPLIED, ShortcutImport.apply(context, ShortcutImport.parse(completeExport())))
        val before = prefs.all
        val plan = ShortcutImport.parse(document(JSONObject().put("ctrl", JSONObject().put("new", "copy"))))
        assertEquals(listOf("ctrl"), plan.categoryNames)
        assertEquals(ShortcutImport.ApplyResult.APPLIED, ShortcutImport.apply(context, plan))
        assertEquals(mapOf("new" to KeyAction.COPY), SettingsConstants.parseShortcutsJson(prefs.getString(keys[1], null)))
        for (key in listOf(keys[0], keys[2], keys[3])) assertEquals(before[key], prefs.all[key])
        val afterPartial = prefs.all
        val clear = ShortcutImport.parse(document(JSONObject().put("shift", JSONObject())
            .put("replacementText", replacement(JSONArray()))))
        assertEquals(ShortcutImport.ApplyResult.APPLIED, ShortcutImport.apply(context, clear))
        assertTrue(SettingsConstants.parseShortcutsJson(prefs.getString(keys[0], null)).isEmpty())
        assertTrue(ReplacementSettings.load(context).isEmpty())
        assertEquals(afterPartial[keys[1]], prefs.all[keys[1]])
        assertEquals(afterPartial[keys[2]], prefs.all[keys[2]])
    }

    @Test fun unknownSchemaSemanticsAndInvalidStoredTypesRejectTheWholeDocumentBeforeAnyWrite() = isolated { _, prefs ->
        assertTrue(prefs.edit().putString("sentinel", "keep").putInt(keys[0], 17).commit())
        val before = prefs.all
        fun changed(change: (JSONObject, JSONObject) -> Unit): String {
            val root = JSONObject(completeExport())
            change(root, root.getJSONObject("categories"))
            return root.toString()
        }
        val invalid = listOf(
            "format" to changed { root, _ -> root.put("format", "another-format") },
            "version" to changed { root, _ -> root.put("schemaVersion", 2) },
            "version type" to changed { root, _ -> root.put("schemaVersion", "1") },
            "category" to changed { _, categories -> categories.put("futureCategory", JSONObject()) },
            "action" to changed { _, categories -> categories.getJSONObject("shift").put("future", "future_action") },
            "toolbar action" to changed { _, categories -> categories.getJSONObject("multifunctionBar").getJSONObject("left_first").put("action", "future_action") },
            "behavior" to changed { _, categories -> categories.getJSONObject("multifunctionBar").getJSONObject("left_first").put("textBehavior", "future_behavior") },
            "slot" to changed { _, categories -> categories.getJSONObject("multifunctionBar").put("future_slot", JSONObject()) },
            "missing slot" to changed { _, categories -> categories.getJSONObject("multifunctionBar").remove("right_third") },
            "blank shortcut" to changed { _, categories -> categories.getJSONObject("shift").put("  ", "copy") },
            "action type" to changed { _, categories -> categories.getJSONObject("ctrl").put("a", true) },
            "toolbar text type" to changed { _, categories -> categories.getJSONObject("multifunctionBar").getJSONObject("left_first").put("text", 7) },
            "toolbar text length" to changed { _, categories -> categories.getJSONObject("multifunctionBar").getJSONObject("left_first").put("text", "a".repeat(1001)) },
            "rule id length" to changed { _, categories -> categories.getJSONObject("replacementText").getJSONArray("rules").getJSONObject(0).put("id", "a".repeat(101)) },
            "rule source length" to changed { _, categories -> categories.getJSONObject("replacementText").getJSONArray("rules").getJSONObject(0).put("source", "a".repeat(1001)) },
            "rule replacement length" to changed { _, categories -> categories.getJSONObject("replacementText").getJSONArray("rules").getJSONObject(0).put("replacement", "a".repeat(1001)) },
            "duplicate rule id" to changed { _, categories -> categories.getJSONObject("replacementText").getJSONArray("rules").getJSONObject(1).put("id", "rule-1") },
            "replacement version" to changed { _, categories -> categories.getJSONObject("replacementText").put("schemaVersion", 2) },
            "empty categories" to document(JSONObject()))
        for ((reason, value) in invalid) {
            assertTrue("Must reject $reason", runCatching { ShortcutImport.parse(value) }.isFailure)
            assertEquals(before, prefs.all)
        }
    }

    @Test fun strictJsonUtf8AndSizeLimitsRejectAmbiguousTruncatedOrExcessiveInput() = isolated { _, prefs ->
        assertTrue(prefs.edit().putString("sentinel", "keep").commit())
        val before = prefs.all
        val valid = document(JSONObject().put("shift", JSONObject().put("a", "copy")))
        fun extension(value: String) = valid.dropLast(1) + ",\"extension\":$value}"
        assertNotNull(ShortcutImport.parse(extension("{\"future\":true}")))
        val badJson = listOf(
            valid.dropLast(1), valid + "{}", valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replace("\"a\":\"copy\"", "\"a\":\"copy\",\"\\u0061\":\"paste\""),
            valid.replace("\"a\"", "'a'"), "/* comment */$valid", valid.replace(',', ';'),
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            extension("NaN"), extension("TRUE"), extension("NULL"), extension("1."), extension("01"),
            extension("\"line\nbreak\""), extension("\"\\q\""), extension("\"\\u１２３４\""),
            valid.replace("\"a\":\"copy\"", "\"a\":\"\uD800\""))
        for (value in badJson) assertTrue(runCatching { ShortcutImport.parse(value) }.isFailure)
        // A replacement-character decoder would accept this otherwise valid JSON string.
        val invalidUtf8 = (valid.dropLast(1) + ",\"extension\":\"").toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xE4.toByte(), 0xB8.toByte()) + "\"}".toByteArray(Charsets.UTF_8)
        assertTrue(runCatching { ShortcutImport.read(ByteArrayInputStream(invalidUtf8)) }.isFailure)
        assertTrue(runCatching { ShortcutImport.read(object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw IOException("synthetic read failure")
        }) }.isFailure)
        val oversized = JSONObject(completeExport()).apply {
            getJSONObject("categories").getJSONObject("multifunctionBar").getJSONObject("left_first")
                .put("extension", "中".repeat(ShortcutImport.MAX_BYTES / 3 + 100))
        }.toString()
        assertTrue("UTF-8 bytes, not UTF-16 length, bound the document", oversized.length < ShortcutImport.MAX_BYTES)
        assertTrue(runCatching { ShortcutImport.parse(oversized) }.isFailure)
        assertTrue(runCatching { ShortcutImport.read(ByteArrayInputStream(oversized.toByteArray(Charsets.UTF_8))) }.isFailure)
        var nested: Any = "leaf"
        repeat(ShortcutImport.MAX_DEPTH + 1) { nested = JSONArray().put(nested) }
        val deep = JSONObject(completeExport()).apply {
            getJSONObject("categories").getJSONObject("multifunctionBar").getJSONObject("left_first").put("extension", nested)
        }.toString()
        assertTrue(runCatching { ShortcutImport.parse(deep) }.isFailure)
        assertEquals(before, prefs.all)
    }

    @Test fun anImmutablePlanPublishesOneCompletePreferenceSnapshotAndPreservesUnrelatedState() = isolated { _, prefs ->
        assertTrue(prefs.edit().putString(keys[0], "old-shift").putString(keys[1], "old-ctrl")
            .putString(keys[2], "old-bar").putString(keys[3], "old-rules")
            .putStringSet("unrelated", setOf("one", "two")).commit())
        val plan = ShortcutImport.parse(completeExport())
        val originalCounts = plan.counts.toMap()
        assertTrue(runCatching { (plan.categoryNames as MutableList<String>).clear() }.isFailure)
        assertTrue(runCatching { (plan.counts as MutableMap<String, Int>)["shift"] = 999 }.isFailure)
        assertEquals(originalCounts, plan.counts)
        val tracked = ScriptedPreferences(prefs, listOf(CommitAction.SUCCEED))
        val snapshots = mutableListOf<Map<String, *>>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, _ -> snapshots += changed.all }
        instrumentation.runOnMainSync {
            prefs.registerOnSharedPreferenceChangeListener(listener)
            try {
                assertEquals(ShortcutImport.ApplyResult.APPLIED, ShortcutImport.apply(tracked, plan))
            } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        assertEquals(1, tracked.commits)
        assertTrue("Observers must receive a completed update", snapshots.isNotEmpty())
        snapshots.forEach { assertEquals(prefs.all, it) }
        assertEquals(setOf("one", "two"), prefs.getStringSet("unrelated", null))
        assertJsonEquals(JSONObject(completeExport()), JSONObject(ShortcutExport.encode(prefs.all)))
    }

    @Test fun failedOrThrowingCommitsRestoreOriginalTypesAndMissingKeysExactly() = isolated { _, prefs ->
        val plan = ShortcutImport.parse(completeExport())
        for (failure in listOf(CommitAction.WRITE_THEN_FALSE, CommitAction.WRITE_THEN_THROW)) {
            for (variant in 0..1) {
                assertTrue(prefs.edit().clear().apply {
                    putString("unrelated", "keep")
                    if (variant == 0) {
                        putInt(keys[0], 7)
                        putStringSet(keys[2], setOf("old", "set"))
                        putBoolean(keys[3], true)
                    } else {
                        putLong(keys[0], 8L)
                        putFloat(keys[1], 0.5f)
                        putString(keys[2], "malformed original string")
                    }
                }.commit())
                val before = prefs.all
                val store = ScriptedPreferences(prefs, listOf(failure, CommitAction.SUCCEED))
                assertEquals(ShortcutImport.ApplyResult.FAILED, ShortcutImport.apply(store, plan))
                assertEquals(2, store.commits)
                assertEquals(before, prefs.all)
            }
        }
    }

    @Test fun failureToRestoreIsDistinctFromAnOrdinaryFailedImport() = isolated { _, prefs ->
        assertTrue(prefs.edit().putInt(keys[0], 17).putString("unrelated", "keep").commit())
        val before = prefs.all
        val store = ScriptedPreferences(prefs, listOf(CommitAction.WRITE_THEN_FALSE, CommitAction.SKIP_THEN_FALSE))
        assertEquals(ShortcutImport.ApplyResult.RESTORE_FAILED,
            ShortcutImport.apply(store, ShortcutImport.parse(completeExport())))
        assertEquals(2, store.commits)
        assertNotEquals(before, prefs.all)
        assertEquals("keep", prefs.getString("unrelated", null))
    }

    @Test fun unreadableExistingPreferencesFailBeforeOpeningAnEditor() = isolated { _, prefs ->
        assertTrue(prefs.edit().putString("unrelated", "keep").commit())
        val before = prefs.all
        val plan = ShortcutImport.parse(completeExport())
        val inaccessible = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                throw SecurityException("synthetic inaccessible settings")
        }
        assertEquals(ShortcutImport.ApplyResult.FAILED, ShortcutImport.apply(inaccessible, plan))
        val unreadable = object : SharedPreferences by prefs {
            override fun getAll(): MutableMap<String, *> = throw IOException("synthetic snapshot failure")
            override fun edit(): SharedPreferences.Editor = error("Do not write without the original snapshot")
        }
        assertEquals(ShortcutImport.ApplyResult.FAILED, ShortcutImport.apply(unreadable, plan))
        assertEquals(before, prefs.all)
    }

    private fun completeExport(includeIsolatedSurrogate: Boolean = false): String {
        val shift = JSONObject()
        val ctrl = JSONObject()
        KeyAction.entries.forEachIndexed { index, action ->
            shift.put("s-$index", action.wireValue)
            ctrl.put("c-😀-$index", action.wireValue)
        }
        val text = "ɑ中😀e\u0301\n\t\"\\" + if (includeIsolatedSurrogate) "\uD800" else ""
        val toolbar = JSONObject()
        MultifunctionBarSlot.entries.forEachIndexed { index, slot ->
            toolbar.put(slot.wireValue, JSONObject().put("action", "text").put("text", "$index:$text")
                .put("textBehavior", TextBehavior.entries[index % TextBehavior.entries.size].wireValue)
                .put("extension", JSONObject().put("preserved", true)))
        }
        val rules = JSONArray().put(rule("rule-1", "same", text))
            .put(rule("rule-2", "same", "重复目标")).put(rule("rule-3", "other", "重复目标"))
        return ShortcutExport.encode(mapOf(keys[0] to shift.toString(), keys[1] to ctrl.toString(),
            keys[2] to toolbar.toString(), keys[3] to replacement(rules).toString()))
    }

    private fun document(categories: JSONObject) = JSONObject().put("format", "ipa-board-shortcuts")
        .put("schemaVersion", 1).put("categories", categories).toString()

    private fun rule(id: String, source: String, replacement: String) =
        JSONObject().put("id", id).put("source", source).put("replacement", replacement)

    private fun replacement(rules: JSONArray) =
        JSONObject().put("schemaVersion", 1).put("category", "replacementText").put("rules", rules)

    private fun names(value: JSONObject) = value.keys().asSequence().toSet()

    private fun assertJsonEquals(expected: Any?, actual: Any?) {
        when (expected) {
            is JSONObject -> {
                assertTrue(actual is JSONObject)
                actual as JSONObject
                assertEquals(names(expected), names(actual))
                names(expected).forEach { assertJsonEquals(expected.get(it), actual.get(it)) }
            }
            is JSONArray -> {
                assertTrue(actual is JSONArray)
                actual as JSONArray
                assertEquals(expected.length(), actual.length())
                for (index in 0 until expected.length()) assertJsonEquals(expected.get(index), actual.get(index))
            }
            else -> assertEquals(expected, actual)
        }
    }

    private enum class CommitAction { SUCCEED, WRITE_THEN_FALSE, WRITE_THEN_THROW, SKIP_THEN_FALSE }

    /** Returning false after mutation models SharedPreferences' in-memory update on disk failure. */
    private class ScriptedPreferences(private val base: SharedPreferences, private val actions: List<CommitAction>) : SharedPreferences by base {
        var commits = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = base.edit()
            return object : SharedPreferences.Editor {
                override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                override fun putStringSet(key: String, values: Set<String>?) = apply { editor.putStringSet(key, values) }
                override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
                override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
                override fun putFloat(key: String, value: Float) = apply { editor.putFloat(key, value) }
                override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                override fun remove(key: String) = apply { editor.remove(key) }
                override fun clear() = apply { editor.clear() }
                override fun apply() { error("Import must observe the commit result") }
                override fun commit(): Boolean {
                    val action = actions.getOrElse(commits++) { error("Unexpected preference transaction") }
                    if (action == CommitAction.SKIP_THEN_FALSE) return false
                    check(editor.commit()) { "Synthetic underlying preference write failed" }
                    return when (action) {
                        CommitAction.SUCCEED -> true
                        CommitAction.WRITE_THEN_FALSE -> false
                        CommitAction.WRITE_THEN_THROW -> throw IOException("synthetic commit failure")
                        CommitAction.SKIP_THEN_FALSE -> error("unreachable")
                    }
                }
            }
        }
    }

    private fun isolated(body: (Context, SharedPreferences) -> Unit) {
        val file = "shortcut-import-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(file, mode)
        }
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        try { body(context, prefs) } finally { target.deleteSharedPreferences(file) }
    }
}
