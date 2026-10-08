package com.example.ipa_board

import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test

class EngineControlsTest {
    @Test fun independentSwitchesAndE5ChoicePersist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = EngineSettings.preferences(context)
        val features = listOf(EngineFeature.ENGLISH_COMPLETION, EngineFeature.SEMANTIC)
        val before = features.associateWith { prefs.contains(it.key) to EngineSettings.enabled(prefs, it) }
        try {
            ActivityScenario.launch(EngineManagementActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val root = activity.findViewById<android.view.View>(R.id.engine_management_root)
                    val toggle = root.findViewWithTag<SwitchCompat>("engine-switch:${EngineFeature.ENGLISH_COMPLETION.key}")
                    toggle.isChecked = false
                    assertFalse(EngineSettings.enabled(activity, EngineFeature.ENGLISH_COMPLETION))
                    val semantic = root.findViewWithTag<SwitchCompat>("engine-switch:${EngineFeature.SEMANTIC.key}")
                    semantic.isChecked = true
                    assertTrue(ContextRankingSettings.isEnabled(activity))
                    val entry = EngineCatalog.categories("test").first { it.id == "completion" }.engines.single()
                    val latest = root.findViewWithTag<TextView>("engine-latest:${entry.name}")
                    assertTrue(latest.text.contains("expand"))
                    root.findViewWithTag<android.view.View>("category:completion").performClick()
                    assertFalse(latest.text.contains("expand"))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<android.view.View>(R.id.engine_management_root)
                    assertFalse(root.findViewWithTag<SwitchCompat>("engine-switch:${EngineFeature.ENGLISH_COMPLETION.key}").isChecked)
                    assertTrue(root.findViewWithTag<SwitchCompat>("engine-switch:${EngineFeature.SEMANTIC.key}").isChecked)
                }
            }
        } finally {
            prefs.edit().apply {
                before.forEach { (feature, state) -> if (state.first) putBoolean(feature.key, state.second) else remove(feature.key) }
            }.commit()
        }
    }
    @Test fun liveCoordinatorDropsDisabledCandidatesWithoutLosingComposition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = EngineSettings.preferences(context)
        val before = EngineFeature.entries.associateWith { prefs.contains(it.key) to EngineSettings.enabled(prefs, it) }
        var coordinator: EngineCoordinator? = null
        lateinit var controller: CompositionController
        lateinit var editor: android.widget.EditText
        var phase = 1
        val first = java.util.concurrent.CountDownLatch(1)
        val resumed = java.util.concurrent.CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                prefs.edit().apply {
                    EngineFeature.entries.forEach { putBoolean(it.key, it == EngineFeature.ENGLISH_COMPLETION) }
                }.commit()
                editor = android.widget.EditText(context)
                val ic = editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                controller = CompositionController({ ic }, { id, raw -> coordinator!!.query(id, raw) }, {})
                coordinator = EngineCoordinator(context) { id, candidates, _ ->
                    controller.acceptResults(id, candidates)
                    if (candidates.any { it.text == "hello" }) {
                        if (phase == 1) first.countDown() else resumed.countDown()
                    }
                }
                coordinator!!.start()
                controller.input("hel")
            }
            assertTrue(first.await(90, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                EngineSettings.setEnabled(context, EngineFeature.ENGLISH_COMPLETION, false)
                assertTrue(controller.candidates.isEmpty())
                assertEquals("hel", controller.raw)
                assertTrue(controller.literal())
                assertEquals("hel", editor.text.toString())
                phase = 2
                EngineSettings.setEnabled(context, EngineFeature.ENGLISH_COMPLETION, true)
                controller.input("hel")
            }
            assertTrue(resumed.await(90, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                coordinator?.close()
                prefs.edit().apply {
                    before.forEach { (feature, state) -> if (state.first) putBoolean(feature.key, state.second) else remove(feature.key) }
                }.commit()
            }
        }
    }
    @Test fun publicVersionMetadataIsParsedAndPrereleasesAreRejected() {
        assertEquals("v4.1", EngineVersionLookup.parse(EngineVersionSource.HELIBOARD,
            """{"tag_name":"v4.1","draft":false,"prerelease":false}"""))
        assertEquals("revision 614241f622f5", EngineVersionLookup.parse(EngineVersionSource.E5,
            """{"sha":"614241f622f53c4eeff9890bdc4f31cfecc418b3"}"""))
        try {
            EngineVersionLookup.parse(EngineVersionSource.HELIBOARD, """{"tag_name":"v5-beta","prerelease":true}""")
            fail("Prerelease cannot be shown as latest stable")
        } catch (_: IllegalStateException) { }
    }
}
