package com.example.ipa_board

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.shortcuts.ReplacementSettings
import com.example.ipa_board.shortcuts.ShortcutExport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/** Synthetic settings and memory destinations only; no system picker or external file is opened. */
class ShortcutExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val destination = Uri.parse("content://ipa-board-export-test/owned-memory")
    private val keys = listOf(SettingsConstants.KEY_SHIFT_SHORTCUTS, SettingsConstants.KEY_CTRL_SHORTCUTS,
        MultifunctionBarSettings.KEY_BINDINGS, ReplacementSettings.KEY)

    @Test fun exportsEveryMappingAndAllToolbarPositionsWithoutLosingOrderedUnicodeRules() = isolated { context, prefs ->
        val shift = JSONObject()
        val ctrl = JSONObject()
        KeyAction.entries.forEachIndexed { index, action ->
            shift.put("shift-$index", action.wireValue)
            ctrl.put("ctrl-\"\\😀-$index", action.wireValue)
        }
        // Includes newlines, escapes, a combining mark, a surrogate pair and an isolated surrogate.
        val longText = " ɑ中😀e\u0301\n\t\"\\ ".repeat(150) + "\uD83D"
        val bar = JSONObject()
        MultifunctionBarSlot.entries.forEachIndexed { index, slot ->
            bar.put(slot.wireValue, JSONObject().put("action", "text").put("text", "$index:$longText")
                .put("textBehavior", TextBehavior.entries[index % TextBehavior.entries.size].wireValue))
        }
        val rules = JSONArray().put(rule("first", "same", longText))
            .put(rule("second", "same", "重复目标"))
            .put(rule("third", "另一来源", "重复目标"))
        val replacement = replacement(rules)
        assertTrue(prefs.edit().putString(keys[0], shift.toString()).putString(keys[1], ctrl.toString())
            .putString(keys[2], bar.toString()).putString(keys[3], replacement.toString()).commit())
        val before = prefs.all
        val root = exported(ShortcutExport.capture(context))
        val categories = root.getJSONObject("categories")
        assertJsonEquals(shift, categories.getJSONObject("shift"))
        assertJsonEquals(ctrl, categories.getJSONObject("ctrl"))
        assertJsonEquals(bar, categories.getJSONObject("multifunctionBar"))
        assertJsonEquals(replacement, categories.getJSONObject("replacementText"))
        assertEquals(before, prefs.all)
    }

    @Test fun absentSettingsExportEffectiveDefaultsWithoutWritingPreferencesOrUnrelatedSettings() = isolated { context, prefs ->
        assertTrue(prefs.edit().putString("unrelated_private_setting", "not-an-export-category").commit())
        val before = prefs.all
        val encoded = ShortcutExport.capture(context)
        val categories = exported(encoded).getJSONObject("categories")
        assertEquals(setOf("shift", "ctrl", "multifunctionBar", "replacementText"), names(categories))
        assertEquals(0, categories.getJSONObject("shift").length())
        assertEquals(0, categories.getJSONObject("ctrl").length())
        val bar = categories.getJSONObject("multifunctionBar")
        assertEquals(MultifunctionBarSlot.entries.map { it.wireValue }.toSet(), names(bar))
        for (slot in MultifunctionBarSlot.entries) {
            val binding = bar.getJSONObject(slot.wireValue)
            assertEquals(slot.defaultAction.wireValue, binding.getString("action"))
            assertEquals("", binding.getString("text"))
            assertEquals("literal", binding.getString("textBehavior"))
        }
        assertJsonEquals(replacement(JSONArray()), categories.getJSONObject("replacementText"))
        assertFalse(encoded.contains("not-an-export-category"))
        assertEquals(before, prefs.all)
        assertJsonEquals(exported(encoded), exported(ShortcutExport.encode(before)))
    }

    @Test fun futureActionsSlotsAndNestedFieldsSurviveExportWithoutNormalization() {
        val shift = JSONObject().put("a", "future_action").put("b", "LEGACY_ACTION")
        val binding = JSONObject().put("action", "future_action").put("text", "原样保留")
            .put("textBehavior", "future_behavior").put("future", JSONObject().put("enabled", true))
        val futureSlot = JSONArray().put("future-slot-data").put(JSONObject().put("version", 3))
        val bar = JSONObject().put("left_first", binding).put("future_position", futureSlot)
        val row = rule("future-rule", "source", "target").put("future", JSONArray().put(7).put(false))
        val replacement = replacement(JSONArray().put(row)).put("futureCategory", JSONObject().put("keep", "yes"))
        val snapshot = mapOf(keys[0] to shift.toString(), keys[2] to bar.toString(), keys[3] to replacement.toString())
        val categories = exported(ShortcutExport.encode(snapshot)).getJSONObject("categories")
        assertJsonEquals(shift, categories.getJSONObject("shift"))
        assertJsonEquals(binding, categories.getJSONObject("multifunctionBar").getJSONObject("left_first"))
        assertJsonEquals(futureSlot, categories.getJSONObject("multifunctionBar").getJSONArray("future_position"))
        assertJsonEquals(replacement, categories.getJSONObject("replacementText"))
        assertEquals("pages", categories.getJSONObject("multifunctionBar").getJSONObject("left_second").getString("action"))
    }

    @Test fun malformedSavedSettingsAreRejectedWithoutReplacingThemWithDefaults() = isolated { context, prefs ->
        val duplicateIds = replacement(JSONArray().put(rule("same-id", "a", "b")).put(rule("same-id", "c", "d")))
        val bad = listOf<Pair<String, Any>>(
            keys[0] to 42, keys[0] to "{broken", keys[0] to "[]", keys[0] to "{\"a\":17}",
            keys[1] to "{} trailing-data", keys[2] to "{\"left_first\":[]}",
            keys[2] to "{\"left_first\":{\"action\":\"text\",\"text\":true}}",
            keys[3] to duplicateIds.toString(),
            keys[3] to replacement(JSONArray()).put("schemaVersion", 2).toString(),
            keys[3] to replacement(JSONArray().put(rule("", "a", "b"))).toString())
        for ((key, value) in bad) {
            assertTrue(prefs.edit().apply {
                keys.forEach(::remove)
                if (value is Int) putInt(key, value) else putString(key, value as String)
            }.commit())
            val before = prefs.all
            assertTrue("Malformed saved category must fail: $key", runCatching { ShortcutExport.capture(context) }.isFailure)
            assertTrue(runCatching { ShortcutExport.encode(before) }.isFailure)
            assertEquals(before, prefs.all)
        }
    }

    @Test fun cancellationAndTheExportButtonUseTheDocumentFlowWithoutOpeningOrWritingAFile() = withAppSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            val before = prefs.all
            scenario.onActivity { activity ->
                var intent: Intent? = null
                activity.launchShortcutExport { intent = it }
                assertDocumentIntent(requireNotNull(intent))
                assertFalse(activity.findViewById<Button>(R.id.btn_export_shortcuts).isEnabled)
                activity.launchShortcutExport { fail("A pending export must not open a second destination") }
                assertEquals(ShortcutManagerActivity.ExportOutcome.CANCELLED,
                    activity.completeShortcutExport(Activity.RESULT_CANCELED, destination) { error("Cancel cannot open output") })
                assertStatus(activity, R.string.shortcut_export_cancelled)
                assertEquals(ShortcutManagerActivity.ExportOutcome.IGNORED,
                    activity.completeShortcutExport(Activity.RESULT_OK, destination) { error("Duplicate result cannot open output") })
            }
            // Intercept every attempted activity launch so even a malformed button intent cannot open a real picker.
            var buttonIntent: Intent? = null
            var launches = 0
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                    launches++
                    buttonIntent = Intent(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                scenario.onActivity { assertTrue(it.findViewById<Button>(R.id.btn_export_shortcuts).performClick()) }
                instrumentation.waitForIdleSync()
                assertEquals(1, launches)
                assertDocumentIntent(requireNotNull(buttonIntent))
                scenario.onActivity { activity ->
                    assertStatus(activity, R.string.shortcut_export_cancelled)
                    assertEquals(ShortcutManagerActivity.ExportOutcome.IGNORED,
                        activity.completeShortcutExport(Activity.RESULT_OK, destination) { error("Cancel was already consumed") })
                }
            } finally { instrumentation.removeMonitor(monitor) }
            assertEquals(before, prefs.all)
        }
    }

    @Test fun destinationConfirmationExportsCurrentSavedSettingsAndClosesTheStreamExactlyOnce() = withAppSettings { prefs ->
        assertTrue(prefs.edit().putString(keys[0], "{\"a\":\"left\"}").commit())
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            scenario.onActivity { it.launchShortcutExport { intent -> assertDocumentIntent(intent) } }
            assertTrue(prefs.edit().putString(keys[0], "{\"b\":\"preedit_mode\"}").commit())
            val before = prefs.all
            val output = MemoryOutput()
            var opens = 0
            scenario.onActivity { activity ->
                assertEquals(ShortcutManagerActivity.ExportOutcome.SAVED,
                    activity.completeShortcutExport(Activity.RESULT_OK, destination) { uri ->
                        assertEquals(destination, uri); opens++; output
                    })
                assertStatus(activity, R.string.shortcut_export_saved)
                assertEquals(ShortcutManagerActivity.ExportOutcome.IGNORED,
                    activity.completeShortcutExport(Activity.RESULT_OK, destination) { error("Do not write twice") })
            }
            assertEquals(1, opens)
            assertEquals(1, output.closeCount)
            assertTrue(output.flushCount > 0)
            val exportedShift = JSONObject(output.toString("UTF-8")).getJSONObject("categories").getJSONObject("shift")
            assertEquals(setOf("b"), names(exportedShift))
            assertEquals("preedit_mode", exportedShift.getString("b"))
            assertEquals(before, prefs.all)
        }
    }

    @Test fun invalidConfigurationDestinationAndStreamFailuresAreReportedWithoutChangingSettings() = withAppSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.launchShortcutExport { throw ActivityNotFoundException("synthetic unavailable picker") }
                assertStatus(activity, R.string.shortcut_export_destination_failed)
                assertEquals(ShortcutManagerActivity.ExportOutcome.IGNORED,
                    activity.completeShortcutExport(Activity.RESULT_OK, destination) { error("Launch failed") })
                val cases = listOf<Pair<Uri?, MemoryOutput?>>(
                    null to MemoryOutput(), destination to null,
                    destination to MemoryOutput(failWrite = true), destination to MemoryOutput(failClose = true))
                for ((uri, output) in cases) {
                    activity.launchShortcutExport {}
                    val before = prefs.all
                    assertEquals(ShortcutManagerActivity.ExportOutcome.FAILED,
                        activity.completeShortcutExport(Activity.RESULT_OK, uri) { output })
                    assertStatus(activity, R.string.shortcut_export_failed)
                    if (uri != null && output != null) assertEquals(1, output.closeCount)
                    assertEquals(before, prefs.all)
                    assertEquals(ShortcutManagerActivity.ExportOutcome.IGNORED,
                        activity.completeShortcutExport(Activity.RESULT_OK, destination) { error("Do not retry a failed result") })
                }
                // A configuration can become unreadable after destination selection was launched.
                activity.launchShortcutExport {}
                assertTrue(prefs.edit().putInt(keys[0], 7).commit())
                val damaged = prefs.all
                assertEquals(ShortcutManagerActivity.ExportOutcome.FAILED,
                    activity.completeShortcutExport(Activity.RESULT_OK, destination) { error("Invalid configuration cannot open output") })
                assertStatus(activity, R.string.shortcut_export_failed)
                activity.launchShortcutExport { error("Invalid configuration cannot open a picker") }
                assertStatus(activity, R.string.shortcut_export_read_failed)
                assertEquals(damaged, prefs.all)
            }
        }
    }

    @Test fun recreationKeepsOnlyThePendingOperationAndAcceptsItsResultOnce() = withAppSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            scenario.onActivity { it.launchShortcutExport {} }
            scenario.recreate()
            assertTrue(prefs.edit().putString(keys[1], "{\"c\":\"copy\"}").commit())
            val before = prefs.all
            val output = MemoryOutput()
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<Button>(R.id.btn_export_shortcuts).isEnabled)
                assertEquals(ShortcutManagerActivity.ExportOutcome.SAVED,
                    activity.completeShortcutExport(Activity.RESULT_OK, destination) { output })
                assertStatus(activity, R.string.shortcut_export_saved)
                assertEquals(ShortcutManagerActivity.ExportOutcome.IGNORED,
                    activity.completeShortcutExport(Activity.RESULT_OK, destination) { error("Recreated result was already consumed") })
            }
            assertEquals(1, output.closeCount)
            val ctrl = JSONObject(output.toString("UTF-8")).getJSONObject("categories").getJSONObject("ctrl")
            assertEquals("copy", ctrl.getString("c"))
            assertEquals(before, prefs.all)
        }
    }

    private class MemoryOutput(private val failWrite: Boolean = false, private val failClose: Boolean = false) : ByteArrayOutputStream() {
        var closeCount = 0
        var flushCount = 0
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (failWrite) throw IOException("synthetic write failure")
            super.write(bytes, offset, length)
        }
        override fun flush() { flushCount++; super.flush() }
        override fun close() {
            closeCount++
            if (failClose) throw IOException("synthetic close failure")
            super.close()
        }
    }

    private fun exported(value: String): JSONObject {
        val root = JSONObject(value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertEquals("ipa-board-shortcuts", root.getString("format"))
        assertEquals(1, root.getInt("schemaVersion"))
        return root
    }

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

    private fun assertDocumentIntent(intent: Intent) {
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("application/json", intent.type)
        assertTrue(requireNotNull(intent.getStringExtra(Intent.EXTRA_TITLE)).endsWith(".json"))
    }

    private fun assertStatus(activity: ShortcutManagerActivity, resource: Int) {
        assertTrue(activity.findViewById<Button>(R.id.btn_export_shortcuts).isEnabled)
        val status = activity.findViewById<TextView>(R.id.shortcut_export_status)
        assertEquals(View.VISIBLE, status.visibility)
        assertEquals(activity.getString(resource), status.text.toString())
    }

    private fun isolated(body: (Context, SharedPreferences) -> Unit) {
        val file = "shortcut-export-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(file, mode)
        }
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        try { body(context, prefs) } finally { target.deleteSharedPreferences(file) }
    }

    private fun withAppSettings(body: (SharedPreferences) -> Unit) {
        val prefs = target.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val before = keys.associateWith { prefs.all[it] }
        assertTrue(prefs.edit().apply { keys.forEach(::remove) }.commit())
        try { body(prefs) } finally {
            assertTrue(prefs.edit().apply {
                before.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
                        else -> error("Unexpected synthetic preference type")
                    }
                }
            }.commit())
        }
    }
}
