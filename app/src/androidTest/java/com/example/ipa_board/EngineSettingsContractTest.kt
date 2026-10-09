package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineQueryPolicy
import com.example.ipa_board.ime.EngineSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Settings have to reach isolated native processes without merging independent feature choices. */
@RunWith(AndroidJUnit4::class)
class EngineSettingsContractTest {
    private lateinit var context: EnginePreferenceContext

    @Before fun setUp() {
        context = EnginePreferenceContext(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun tearDown() {
        context.close()
    }

    @Test fun independentChoicesPersistAndSurviveTheCrossProcessPolicyEnvelope() {
        val expected = EngineFeature.entries.mapIndexed { index, feature -> feature to (index % 3 == 1) }.toMap()
        val prefs = EngineSettings.preferences(context)
        prefs.edit().apply { expected.forEach { (feature, enabled) -> putBoolean(feature.key, enabled) } }.commit()
        val secondReader = EngineSettings.preferences(context)
        val transfer = Bundle()
        EngineSettings.snapshot(secondReader).writeTo(transfer)
        val restored = EngineQueryPolicy.from(transfer)

        for ((feature, value) in expected) {
            assertEquals("Persisted ${feature.name}", value, EngineSettings.enabled(secondReader, feature))
            assertEquals("Transferred ${feature.name}", value, restored.enabled(feature))
        }
    }

    @Test fun changingOneChoiceDoesNotResetAnotherChoiceOrFutureSettings() {
        val prefs = EngineSettings.preferences(context)
        prefs.edit().putString("future_engine_option", "keep this value")
            .putBoolean(EngineFeature.JAPANESE_PREDICTION.key, false).commit()
        val original = EngineFeature.entries.filter { it != EngineFeature.ENGLISH_COMPLETION }
            .associateWith { prefs.contains(it.key) to EngineSettings.enabled(prefs, it) }
        EngineSettings.setEnabled(context, EngineFeature.ENGLISH_COMPLETION, false)
        EngineSettings.setEnabled(context, EngineFeature.ENGLISH_COMPLETION, true)

        assertEquals("keep this value", prefs.getString("future_engine_option", null))
        for ((feature, state) in original) {
            assertEquals("Presence of ${feature.name}", state.first, prefs.contains(feature.key))
            assertEquals("Value of ${feature.name}", state.second, EngineSettings.enabled(prefs, feature))
        }
    }

    @Test fun optionalProcessorsCannotReactivateDisabledLanguageEngines() {
        val coreFeatures = listOf(
            EngineFeature.IPA_CONVERSION,
            EngineFeature.CHINESE_CONVERSION, EngineFeature.CHINESE_PREDICTION,
            EngineFeature.JAPANESE_CONVERSION, EngineFeature.JAPANESE_PREDICTION,
            EngineFeature.ENGLISH_COMPLETION, EngineFeature.ENGLISH_CORRECTION, EngineFeature.ENGLISH_PREDICTION)
        val prefs = EngineSettings.preferences(context)
        prefs.edit().apply {
            EngineFeature.entries.forEach { putBoolean(it.key, it !in coreFeatures) }
        }.commit()
        val bundle = Bundle()
        EngineSettings.snapshot(prefs).writeTo(bundle)
        val policy = EngineQueryPolicy.from(bundle)
        assertTrue(policy.enabled(EngineFeature.SEMANTIC))
        assertTrue(policy.enabled(EngineFeature.RANKING))
        assertTrue(policy.enabled(EngineFeature.SEGMENTATION))
        for (language in listOf("ZH", "JA", "EN", "IPA")) {
            assertFalse("$language runtime must stay off", policy.needsRuntime(language))
            assertFalse("$language conversion must stay off", policy.wantsQuery(language, false))
            assertFalse("$language prediction must stay off", policy.wantsQuery(language, true))
        }
    }

    @Test fun olderPolicyEnvelopesUseDefaultsForMissingFeaturesAndIgnoreUnknownKeys() {
        val bundle = Bundle().apply {
            putBoolean(EngineFeature.ENGLISH_COMPLETION.key, false)
            putBoolean("future_engine_toggle", false)
        }
        val restored = EngineQueryPolicy.from(bundle)
        assertFalse(restored.enabled(EngineFeature.ENGLISH_COMPLETION))
        assertFalse(restored.enabled(EngineFeature.SEMANTIC))
        assertTrue(restored.enabled(EngineFeature.CHINESE_CONVERSION))
        assertTrue(restored.enabled(EngineFeature.JAPANESE_PREDICTION))
        assertTrue(restored.enabled(EngineFeature.ENGLISH_CORRECTION))
    }

    private class EnginePreferenceContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val prefix = "engine-contract-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }

        override fun close() {
            names.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }
}
