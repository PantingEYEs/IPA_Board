package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.RemoteKeyboardMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RemoteKeyboardModeTest {
    @Test fun startsOffAndRestoresMixedStatesWithoutChangingUnrelatedPreferences() = withPreferences { context, prefs ->
        val original = EngineFeature.entries.associate { it.key to (it.ordinal % 2 == 0) }
        assertTrue(prefs.edit().apply {
            original.forEach { (key, value) -> putBoolean(key, value) }
            putString("unrelated", "preserve")
        }.commit())
        val mode = RemoteKeyboardMode(EngineSettings.preferences(context))
        assertFalse(mode.active)
        assertEquals(RemoteKeyboardMode.Result.UNCHANGED, mode.restore())
        assertEquals(RemoteKeyboardMode.Result.ENABLED, mode.toggle())
        assertTrue(mode.active)
        assertAllEngines(prefs, false)
        assertEquals(RemoteKeyboardMode.Result.RESTORED, mode.toggle())
        assertFalse(mode.active)
        original.forEach { (key, value) -> assertEquals(value, prefs.all[key]) }
        assertEquals("preserve", prefs.getString("unrelated", null))
    }

    @Test fun allDisabledStateIsRestoredInsteadOfEnablingEverything() = withPreferences { _, prefs ->
        assertTrue(prefs.edit().apply { EngineFeature.entries.forEach { putBoolean(it.key, false) } }.commit())
        val mode = RemoteKeyboardMode(prefs)
        assertEquals(RemoteKeyboardMode.Result.ENABLED, mode.toggle())
        assertEquals(RemoteKeyboardMode.Result.RESTORED, mode.toggle())
        assertAllEngines(prefs, false)
    }

    @Test fun manualEngineChangesWhileActiveDoNotReplaceOriginalSnapshot() = withPreferences { context, prefs ->
        // Cover both original values and both values selected manually while the mode is active.
        for (original in listOf(false, true)) {
            for (manual in listOf(false, true)) {
                assertTrue(prefs.edit().clear().apply {
                    EngineFeature.entries.forEach { putBoolean(it.key, original) }
                }.commit())
                val mode = RemoteKeyboardMode(prefs)
                assertEquals(RemoteKeyboardMode.Result.ENABLED, mode.toggle())
                val snapshot = prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null)
                EngineFeature.entries.forEach { EngineSettings.setEnabled(context, it, manual) }
                assertAllEngines(prefs, manual)
                val recreated = RemoteKeyboardMode(EngineSettings.preferences(context))
                assertTrue(recreated.active)
                assertAllEngines(prefs, manual)
                assertEquals(snapshot, prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null))
                assertEquals(RemoteKeyboardMode.Result.RESTORED, recreated.toggle())
                assertAllEngines(prefs, original)
            }
        }
    }

    @Test fun missingPreferencesAndDefaultsSurviveRecreationAndTwoCycles() = withPreferences { _, prefs ->
        val explicit = EngineFeature.entries.filter { it.ordinal % 3 == 0 }
        assertTrue(prefs.edit().apply { explicit.forEach { putBoolean(it.key, !it.defaultEnabled) } }.commit())
        repeat(2) {
            val before = prefs.all.toMap()
            val mode = RemoteKeyboardMode(prefs)
            assertEquals(RemoteKeyboardMode.Result.ENABLED, mode.toggle())
            assertAllEngines(prefs, false)
            val recreated = RemoteKeyboardMode(prefs)
            assertTrue(recreated.active)
            assertEquals(RemoteKeyboardMode.Result.RESTORED, recreated.restore())
            assertEquals(before, prefs.all)
            EngineFeature.entries.forEach { feature ->
                assertEquals(feature in explicit, prefs.contains(feature.key))
                assertEquals(if (feature in explicit) !feature.defaultEnabled else feature.defaultEnabled,
                    EngineSettings.enabled(prefs, feature))
            }
        }
    }

    @Test fun successfulTransitionsAtomicallyWriteSnapshotWithEngineStates() = withPreferences { _, prefs ->
        val tracked = FaultPreferences(prefs)
        val mode = RemoteKeyboardMode(tracked)
        assertEquals(RemoteKeyboardMode.Result.ENABLED, mode.toggle())
        assertEquals(1, tracked.committedMaps.size)
        assertTrue(tracked.committedMaps.single().containsKey(RemoteKeyboardMode.KEY_SNAPSHOT))
        EngineFeature.entries.forEach { assertEquals(false, tracked.committedMaps.single()[it.key]) }
        assertEquals(RemoteKeyboardMode.Result.RESTORED, mode.restore())
        assertEquals(2, tracked.committedMaps.size)
        assertTrue(tracked.committedMaps.last().isEmpty())
    }

    @Test fun failedEnableKeepsRecoveryEvenWhenCommitHasAlreadyChangedMemory() = withPreferences { _, prefs ->
        assertTrue(prefs.edit().putBoolean(EngineFeature.CHINESE_CONVERSION.key, true).commit())
        val before = prefs.all.toMap()
        for (fault in Fault.entries.filter { it != Fault.NONE }) {
            val failing = FaultPreferences(prefs)
            val mode = RemoteKeyboardMode(failing)
            failing.nextFault = fault
            assertEquals(RemoteKeyboardMode.Result.STORAGE_FAILED, mode.toggle())
            assertTrue(mode.active)
            assertTrue(RemoteKeyboardMode(prefs).active)
            assertEquals(RemoteKeyboardMode.Result.RESTORED, RemoteKeyboardMode(prefs).restore())
            assertFalse(mode.active)
            assertEquals(before, prefs.all)
        }
    }

    @Test fun failedRestoreRetainsOriginalRecoveryAcrossAnotherController() = withPreferences { _, prefs ->
        assertTrue(prefs.edit().apply {
            EngineFeature.entries.forEach { putBoolean(it.key, it.ordinal % 2 == 0) }
        }.commit())
        val before = prefs.all.toMap()
        for (fault in Fault.entries.filter { it != Fault.NONE }) {
            val failing = FaultPreferences(prefs)
            val mode = RemoteKeyboardMode(failing)
            assertEquals(RemoteKeyboardMode.Result.ENABLED, mode.toggle())
            val originalSnapshot = prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null)
            failing.nextFault = fault
            assertEquals(RemoteKeyboardMode.Result.STORAGE_FAILED, mode.restore())
            assertTrue(mode.active)
            assertEquals(originalSnapshot, prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null))
            assertTrue(RemoteKeyboardMode(prefs).active)
            assertEquals(RemoteKeyboardMode.Result.RESTORED, RemoteKeyboardMode(prefs).restore())
            assertFalse(mode.active)
            assertEquals(before, prefs.all)
        }
    }

    @Test fun repeatedDiskFailureKeepsSnapshotAvailableForLaterRecovery() = withPreferences { _, prefs ->
        val before = prefs.all.toMap()
        val failing = FaultPreferences(prefs)
        val mode = RemoteKeyboardMode(failing)
        assertEquals(RemoteKeyboardMode.Result.ENABLED, mode.toggle())
        val snapshot = prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null)
        failing.repeatedFault = Fault.FALSE_AFTER_WRITE
        assertEquals(RemoteKeyboardMode.Result.STORAGE_FAILED, mode.restore())
        assertTrue(mode.active)
        assertEquals(snapshot, prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null))
        failing.repeatedFault = Fault.NONE
        assertEquals(RemoteKeyboardMode.Result.RESTORED, RemoteKeyboardMode(failing).restore())
        assertFalse(mode.active)
        assertEquals(before, prefs.all)
    }

    @Test fun unreadablePreferencesDoNotStartAnOverride() = withPreferences { _, prefs ->
        val before = prefs.all.toMap()
        val unreadable = object : SharedPreferences by prefs {
            override fun getAll(): MutableMap<String, *> = throw IllegalStateException("injected read failure")
        }
        val mode = RemoteKeyboardMode(unreadable)
        assertEquals(RemoteKeyboardMode.Result.STORAGE_FAILED, mode.toggle())
        assertEquals(RemoteKeyboardMode.Result.STORAGE_FAILED, mode.restore())
        assertFalse(mode.active)
        assertEquals(before, prefs.all)
    }

    @Test fun malformedOrIncompleteSnapshotIsKeptWithoutGuessingEngineStates() = withPreferences { _, prefs ->
        val invalidStates = listOf(
            "not JSON", "{}", "{\"version\":100,\"features\":[],\"values\":{}}",
            "{\"version\":1,\"features\":[\"engine_chinese_conversion\"],\"values\":{}}",
            "{\"version\":1,\"features\":[\"engine_chinese_conversion\"],\"values\":{\"engine_chinese_conversion\":\"true\"}}"
        )
        for (snapshot in invalidStates) {
            assertTrue(prefs.edit().clear().putBoolean(EngineFeature.CHINESE_CONVERSION.key, false)
                .putString(RemoteKeyboardMode.KEY_SNAPSHOT, snapshot).commit())
            val before = prefs.all.toMap()
            val mode = RemoteKeyboardMode(prefs)
            assertTrue(mode.active)
            assertEquals(RemoteKeyboardMode.Result.INVALID_STATE, mode.toggle())
            assertEquals(RemoteKeyboardMode.Result.INVALID_STATE, mode.restore())
            assertEquals(before, prefs.all)
        }
        assertTrue(prefs.edit().putBoolean(RemoteKeyboardMode.KEY_SNAPSHOT, true).commit())
        val before = prefs.all.toMap()
        assertEquals(RemoteKeyboardMode.Result.INVALID_STATE, RemoteKeyboardMode(prefs).restore())
        assertEquals(before, prefs.all)
    }

    @Test fun unknownCapturedFeatureDoesNotWriteArbitraryPreferences() = withPreferences { _, prefs ->
        val mode = RemoteKeyboardMode(prefs)
        assertEquals(RemoteKeyboardMode.Result.ENABLED, mode.toggle())
        val snapshot = JSONObject(prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null)!!)
        snapshot.getJSONArray("features").put("future_or_removed_feature")
        snapshot.getJSONObject("values").put("future_or_removed_feature", true)
        assertTrue(prefs.edit().putString(RemoteKeyboardMode.KEY_SNAPSHOT, snapshot.toString())
            .putBoolean("future_or_removed_feature", false).commit())
        assertEquals(RemoteKeyboardMode.Result.RESTORED, mode.restore())
        assertFalse(prefs.getBoolean("future_or_removed_feature", true))
    }

    @Test fun invalidEnginePreferenceIsNotSilentlyOverwritten() = withPreferences { _, prefs ->
        assertTrue(prefs.edit().putString(EngineFeature.CHINESE_CONVERSION.key, "invalid").commit())
        val before = prefs.all.toMap()
        val mode = RemoteKeyboardMode(prefs)
        assertEquals(RemoteKeyboardMode.Result.INVALID_STATE, mode.toggle())
        assertFalse(mode.active)
        assertEquals(before, prefs.all)
    }

    private fun assertAllEngines(prefs: SharedPreferences, enabled: Boolean) {
        EngineFeature.entries.forEach { assertEquals(it.key, enabled, EngineSettings.enabled(prefs, it)) }
    }

    private fun withPreferences(test: (Context, SharedPreferences) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "remote-mode-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences(preferenceName, mode)
        }
        try {
            test(context, EngineSettings.preferences(context))
        } finally {
            target.deleteSharedPreferences(preferenceName)
        }
    }

    private enum class Fault { NONE, FALSE_AFTER_WRITE, THROW_BEFORE_WRITE, THROW_AFTER_WRITE }

    private class FaultPreferences(private val backing: SharedPreferences) : SharedPreferences by backing {
        var nextFault = Fault.NONE
        var repeatedFault = Fault.NONE
        val committedMaps = mutableListOf<Map<String, *>>()

        override fun edit(): SharedPreferences.Editor {
            val editor = backing.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    val fault = if (nextFault != Fault.NONE) nextFault.also { nextFault = Fault.NONE } else repeatedFault
                    if (fault == Fault.THROW_BEFORE_WRITE) throw IllegalStateException("injected write failure")
                    val committed = editor.commit()
                    committedMaps.add(backing.all.toMap())
                    if (fault == Fault.THROW_AFTER_WRITE) throw IllegalStateException("injected write failure")
                    return committed && fault != Fault.FALSE_AFTER_WRITE
                }
            }
        }
    }
}
