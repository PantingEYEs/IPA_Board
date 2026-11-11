package com.example.ipa_board

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Only fixture preferences are used; no engine process or document provider is started. */
class PinyinSettingsTest {
    @Test fun oldAndMalformedPreferencesKeepTheCompatibilityDefaultWithoutRewritingData() = owned { prefs ->
        val before = prefs.all
        assertEquals(PinyinScheme.LUNA, PinyinSettings.scheme(prefs))
        assertFalse(PinyinSettings.alternativeTones(prefs))
        assertEquals(before, prefs.all)
        for (bad in listOf<Any>("future-schema", 17, false, setOf("opaque"))) {
            val editor = prefs.edit()
            put(editor, PinyinSettings.KEY_SCHEME, bad)
            put(editor, PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES, "true")
            assertTrue(editor.commit())
            val malformed = prefs.all
            val policy = EngineSettings.snapshot(prefs)
            assertEquals(PinyinScheme.LUNA, policy.pinyinScheme)
            assertFalse(policy.terraAlternativeTones)
            assertEquals(malformed, prefs.all)
        }
    }

    @Test fun schemeAndAliasChoicePersistWithoutChangingFeaturesAndTravelInThePolicyEnvelope() = owned { prefs ->
        assertTrue(prefs.edit().putBoolean(EngineFeature.CHINESE_CONVERSION.key, false)
            .putBoolean(EngineFeature.SCRIPT_CONVERSION.key, false).putString("future_setting", "keep").commit())
        val unrelated = prefs.all
        assertEquals(PinyinSettings.SaveResult.SAVED, PinyinSettings.setScheme(prefs, PinyinScheme.TERRA))
        assertEquals(PinyinSettings.SaveResult.SAVED, PinyinSettings.setAlternativeTones(prefs, true))
        assertEquals(unrelated, prefs.all.filterKeys { !PinyinSettings.isKey(it) })
        val original = EngineSettings.snapshot(prefs)
        val envelope = Bundle().also { original.writeTo(it) }
        val restored = EngineQueryPolicy.from(envelope)
        assertEquals(original, restored)
        assertEquals(PinyinScheme.TERRA, restored.pinyinScheme)
        assertTrue(restored.terraAlternativeTones)
        assertFalse(restored.wantsQuery("ZH", false))
        assertFalse(restored.enabled(EngineFeature.SCRIPT_CONVERSION))
        assertTrue(restored.wantsQuery("ZH", true))

        assertEquals(PinyinSettings.SaveResult.SAVED, PinyinSettings.setScheme(prefs, PinyinScheme.LUNA))
        assertTrue("Changing schemas must not erase the saved Terra preference", PinyinSettings.alternativeTones(prefs))
        assertEquals(PinyinSettings.SaveResult.SAVED, PinyinSettings.setScheme(prefs, PinyinScheme.TERRA))
        assertTrue(PinyinSettings.alternativeTones(prefs))
    }

    @Test fun olderOrMalformedPolicyEnvelopesUseDefaultsWithoutDisablingOtherOptions() {
        val bundle = Bundle().apply { putBoolean(EngineFeature.JAPANESE_CONVERSION.key, false) }
        assertEquals(PinyinSettings.DEFAULT_SCHEME, EngineQueryPolicy.from(bundle).pinyinScheme)
        bundle.putInt(PinyinSettings.KEY_SCHEME, 4)
        bundle.putString(PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES, "yes")
        val policy = EngineQueryPolicy.from(bundle)
        assertEquals(PinyinSettings.DEFAULT_SCHEME, policy.pinyinScheme)
        assertFalse(policy.terraAlternativeTones)
        assertFalse(policy.enabled(EngineFeature.JAPANESE_CONVERSION))
        assertTrue(policy.enabled(EngineFeature.CHINESE_CONVERSION))
    }

    @Test fun remoteKeyboardOverridesFeaturesButDoesNotRestoreOrReplacePinyinChoices() = owned { prefs ->
        PinyinSettings.setScheme(prefs, PinyinScheme.TERRA)
        PinyinSettings.setAlternativeTones(prefs, true)
        val mode = RemoteKeyboardMode(prefs)
        assertEquals(RemoteKeyboardMode.Result.ENABLED, mode.toggle())
        assertFalse(EngineSettings.snapshot(prefs).needsRuntime("ZH"))
        assertEquals(PinyinScheme.TERRA, PinyinSettings.scheme(prefs))
        assertTrue(PinyinSettings.alternativeTones(prefs))
        assertEquals(PinyinSettings.SaveResult.SAVED, PinyinSettings.setScheme(prefs, PinyinScheme.LUNA))
        assertEquals(PinyinSettings.SaveResult.SAVED, PinyinSettings.setAlternativeTones(prefs, false))
        assertEquals(RemoteKeyboardMode.Result.RESTORED, mode.restore())
        assertEquals(PinyinScheme.LUNA, PinyinSettings.scheme(prefs))
        assertFalse(PinyinSettings.alternativeTones(prefs))
        assertTrue(EngineSettings.snapshot(prefs).wantsQuery("ZH", false))
    }

    @Test fun failedWritesRestoreTheExactPreviousValueOrItsAbsence() = owned { prefs ->
        for (previous in listOf<Any?>(null, "future-schema", 17, false, 5L, 1.25f, setOf("old", "value"))) {
            val editor = prefs.edit()
            put(editor, PinyinSettings.KEY_SCHEME, previous)
            assertTrue(editor.putString("unrelated", "original").commit())
            val before = prefs.all
            val failing = CommitResults(prefs, listOf(false, true))
            assertEquals(PinyinSettings.SaveResult.FAILED, PinyinSettings.setScheme(failing, PinyinScheme.TERRA))
            assertEquals(2, failing.commits)
            assertEquals(before, prefs.all)
        }
        assertTrue(prefs.edit().remove(PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES).commit())
        val beforeAlias = prefs.all
        assertEquals(PinyinSettings.SaveResult.FAILED,
            PinyinSettings.setAlternativeTones(CommitResults(prefs, listOf(false, true)), true))
        assertEquals(beforeAlias, prefs.all)
    }

    @Test fun unchangedWritesAreSkippedAndFailedRecoveryIsReportedSeparately() = owned { prefs ->
        assertEquals(PinyinSettings.SaveResult.SAVED, PinyinSettings.setScheme(prefs, PinyinScheme.TERRA))
        val noWrites = CommitResults(prefs, emptyList())
        assertEquals(PinyinSettings.SaveResult.UNCHANGED, PinyinSettings.setScheme(noWrites, PinyinScheme.TERRA))
        assertEquals(0, noWrites.commits)
        val failing = CommitResults(prefs, listOf(false, false))
        assertEquals(PinyinSettings.SaveResult.RESTORE_FAILED, PinyinSettings.setScheme(failing, PinyinScheme.LUNA))
        assertEquals(2, failing.commits)
    }

    private class CommitResults(private val delegate: SharedPreferences, private val outcomes: List<Boolean>) : SharedPreferences by delegate {
        var commits = 0
        override fun edit(): SharedPreferences.Editor {
            val actual = delegate.edit()
            return object : SharedPreferences.Editor by actual {
                override fun putString(key: String?, value: String?) = apply { actual.putString(key, value) }
                override fun putStringSet(key: String?, value: MutableSet<String>?) = apply { actual.putStringSet(key, value) }
                override fun putBoolean(key: String?, value: Boolean) = apply { actual.putBoolean(key, value) }
                override fun putInt(key: String?, value: Int) = apply { actual.putInt(key, value) }
                override fun putLong(key: String?, value: Long) = apply { actual.putLong(key, value) }
                override fun putFloat(key: String?, value: Float) = apply { actual.putFloat(key, value) }
                override fun remove(key: String?) = apply { actual.remove(key) }
                override fun clear() = apply { actual.clear() }
                override fun commit(): Boolean {
                    check(commits < outcomes.size) { "Unexpected preference commit" }
                    check(actual.commit())
                    return outcomes[commits++]
                }
            }
        }
    }

    private fun owned(body: (SharedPreferences) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pinyin-settings-test-${UUID.randomUUID()}"
        try { body(base.getSharedPreferences(name, Context.MODE_PRIVATE)) }
        finally { check(base.deleteSharedPreferences(name)) }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
