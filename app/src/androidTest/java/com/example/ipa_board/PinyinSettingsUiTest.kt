package com.example.ipa_board

import android.content.SharedPreferences
import android.view.View
import android.widget.RadioButton
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.PinyinScheme
import com.example.ipa_board.ime.PinyinSettings
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.*
import org.junit.Test

/** Owned management controls only. The test opens the settings section without starting version lookups. */
class PinyinSettingsUiTest {
    private val prefs get() = EngineSettings.preferences(InstrumentationRegistry.getInstrumentation().targetContext)
    private val keys = listOf(PinyinSettings.KEY_SCHEME, PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES,
        EngineFeature.CHINESE_CONVERSION.key)

    @Test fun realSchemeAndAliasControlsPersistAndRemainIndependentOfTheConversionSwitch() = withSettings {
        assertTrue(prefs.edit().remove(PinyinSettings.KEY_SCHEME).remove(PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES)
            .putBoolean(EngineFeature.CHINESE_CONVERSION.key, true).commit())
        val unrelated = prefs.all.filterKeys { it !in keys }
        ActivityScenario.launch(EngineManagementActivity::class.java).use { scenario ->
            openOwnedControls(scenario)
            scenario.onActivity { activity ->
                assertTrue(scheme(activity, PinyinScheme.LUNA).isChecked)
                assertFalse(prefs.contains(PinyinSettings.KEY_SCHEME))
                assertEquals(View.GONE, root(activity).findViewWithTag<View>("terra-pinyin-options").visibility)
            }
            onView(withTagValue(equalTo("pinyin-scheme:${PinyinScheme.TERRA.schemaId}"))).perform(scrollTo(), click())
            onView(withTagValue(equalTo("terra-alternative-tones"))).perform(scrollTo(), click())
            assertEquals(PinyinScheme.TERRA, PinyinSettings.scheme(prefs))
            assertTrue(PinyinSettings.alternativeTones(prefs))
            scenario.recreate()
            openOwnedControls(scenario)
            scenario.onActivity { activity ->
                assertTrue(scheme(activity, PinyinScheme.TERRA).isChecked)
                assertTrue(root(activity).findViewWithTag<SwitchCompat>("terra-alternative-tones").isChecked)
                root(activity).findViewWithTag<SwitchCompat>("engine-switch:${EngineFeature.CHINESE_CONVERSION.key}").performClick()
                assertFalse(EngineSettings.enabled(prefs, EngineFeature.CHINESE_CONVERSION))
                assertTrue(scheme(activity, PinyinScheme.TERRA).isChecked)
                assertTrue(scheme(activity, PinyinScheme.LUNA).isEnabled)
            }
            onView(withTagValue(equalTo("pinyin-scheme:${PinyinScheme.LUNA.schemaId}"))).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                assertEquals(View.GONE, root(activity).findViewWithTag<View>("terra-pinyin-options").visibility)
                assertTrue(PinyinSettings.alternativeTones(prefs))
            }
            onView(withTagValue(equalTo("pinyin-scheme:${PinyinScheme.TERRA.schemaId}"))).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                assertTrue(root(activity).findViewWithTag<SwitchCompat>("terra-alternative-tones").isChecked)
                assertFalse(EngineSettings.enabled(prefs, EngineFeature.CHINESE_CONVERSION))
            }
        }
        assertEquals(unrelated, prefs.all.filterKeys { it !in keys })
    }

    @Test fun malformedAndExternallyChangedPreferencesAreReflectedWithoutAViewStateRewrite() = withSettings {
        assertTrue(prefs.edit().putInt(PinyinSettings.KEY_SCHEME, 99)
            .putString(PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES, "unknown").commit())
        val malformed = prefs.all
        ActivityScenario.launch(EngineManagementActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(scheme(activity, PinyinScheme.LUNA).isChecked)
                assertEquals(malformed, prefs.all)
                assertTrue(prefs.edit().putString(PinyinSettings.KEY_SCHEME, PinyinScheme.TERRA.schemaId)
                    .putBoolean(PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES, true).commit())
                assertTrue(scheme(activity, PinyinScheme.TERRA).isChecked)
                assertTrue(root(activity).findViewWithTag<SwitchCompat>("terra-alternative-tones").isChecked)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(scheme(activity, PinyinScheme.TERRA).isChecked)
                assertEquals(View.VISIBLE, root(activity).findViewWithTag<View>("terra-pinyin-options").visibility)
                assertEquals(PinyinScheme.TERRA.schemaId, prefs.all[PinyinSettings.KEY_SCHEME])
                assertTrue(PinyinSettings.alternativeTones(prefs))
            }
        }
    }

    private fun openOwnedControls(scenario: ActivityScenario<EngineManagementActivity>) = scenario.onActivity { activity ->
        // Expansion/version lookup has separate tests; this fixture exercises local settings offline.
        root(activity).findViewWithTag<View>("engines:syllable").visibility = View.VISIBLE
    }
    private fun root(activity: EngineManagementActivity): View = activity.findViewById(R.id.engine_management_root)
    private fun scheme(activity: EngineManagementActivity, value: PinyinScheme): RadioButton =
        root(activity).findViewWithTag("pinyin-scheme:${value.schemaId}")

    private fun withSettings(body: () -> Unit) {
        val before = keys.associateWith { prefs.all[it] }
        try { body() }
        finally {
            val editor = prefs.edit()
            before.forEach { (key, value) -> when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            assertTrue(editor.commit())
        }
    }
}
