package com.example.ipa_board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.appcompat.widget.SwitchCompat
import com.example.ipa_board.diagnostics.AppDiagnostics
import com.example.ipa_board.diagnostics.DebugDiagnosticsSettings
import com.example.ipa_board.ime.EngineCatalog
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineStatus
import com.example.ipa_board.ime.EngineVersionLookup
import com.example.ipa_board.ime.EngineVersionSource
import com.example.ipa_board.ime.MozcBundle
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EngineManagementTest {
    @Test fun semanticRowKeepsTheBundledExtensionsIdentityAfterMetadataRefresh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notice = context.assets.open("licenses/onnx-extensions/SOURCE.txt").bufferedReader().use { it.readText() }
        val buildRevision = requireNotNull(Regex("Build: https://github.com/PantingEYEs/IPA_Engine/tree/([a-f0-9]{40})")
            .find(notice)).groupValues[1]
        val row = EngineCatalog.configurable().single { it.feature == EngineFeature.SEMANTIC }
        val originalLoader = EngineVersionLookup.metadataLoaderOverride
        EngineVersionLookup.metadataLoaderOverride = { source ->
            if (source == EngineVersionSource.MOZC || source == EngineVersionSource.E5)
                """{"sha":"1234567890abcdef1234567890abcdef12345678"}"""
            else """{"tag_name":"v-future","draft":false,"prerelease":false}"""
        }
        fun assertCurrent(root: View) {
            val current = root.findViewWithTag<TextView>("engine-current:${row.name}").text.toString()
            assertTrue(current.contains("ONNX Runtime 1.23.2"))
            assertTrue(current.contains("Extensions 0.13.0"))
            assertTrue(current.contains("IPA build ${buildRevision.take(7)}"))
            assertFalse(current.contains("v-future"))
            assertFalse(current.contains("1234567890ab"))
        }
        try {
            ActivityScenario.launch(EngineManagementActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.engine_management_root)
                    assertCurrent(root)
                    root.findViewWithTag<View>("category:ranking").performClick()
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                var latestReceived = false
                do {
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<View>(R.id.engine_management_root)
                        latestReceived = root.findViewWithTag<TextView>("engine-latest:${row.name}").text
                            .contains("ONNX Runtime: v-future")
                        assertCurrent(root)
                    }
                    if (!latestReceived) android.os.SystemClock.sleep(20)
                } while (!latestReceived && android.os.SystemClock.elapsedRealtime() < deadline)
                assertTrue("Latest metadata must remain separate from the installed build", latestReceived)
                scenario.recreate()
                scenario.onActivity { activity -> assertCurrent(activity.findViewById(R.id.engine_management_root)) }
            }
        } finally { EngineVersionLookup.metadataLoaderOverride = originalLoader }
    }

    @Test fun mozcRowsKeepBundledIdentityWhenLatestUpstreamChanges() {
        val originalLoader = EngineVersionLookup.metadataLoaderOverride
        EngineVersionLookup.metadataLoaderOverride = { source ->
            if (source == EngineVersionSource.MOZC || source == EngineVersionSource.E5)
                """{"sha":"1234567890abcdef1234567890abcdef12345678"}"""
            else """{"tag_name":"v-test","draft":false,"prerelease":false}"""
        }
        val rows = EngineCatalog.categories("test").flatMap { it.engines }.filter {
            it.feature == EngineFeature.JAPANESE_CONVERSION || it.feature == EngineFeature.JAPANESE_PREDICTION
        }
        fun assertCurrent(root: View) {
            rows.forEach { row ->
                val current = root.findViewWithTag<TextView>("engine-current:${row.name}").text.toString()
                assertTrue(current.contains("Google Mozc rev ${MozcBundle.UPSTREAM_REVISION.take(12)}"))
                assertTrue(current.contains("OSS data ${MozcBundle.DATA_VERSION}"))
                assertTrue(current.contains("IPA_Engine build ${MozcBundle.BUILD_REVISION.take(12)}"))
                assertFalse(current.contains("Libre Japanese Input"))
                assertFalse("Latest upstream metadata replaced the bundled identity", current.contains("1234567890ab"))
            }
        }
        try {
            ActivityScenario.launch(EngineManagementActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.engine_management_root)
                    assertCurrent(root)
                    root.findViewWithTag<View>("category:syllable").performClick()
                    root.findViewWithTag<View>("category:prediction").performClick()
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                var latestReceived = false
                do {
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<View>(R.id.engine_management_root)
                        latestReceived = rows.all {
                            root.findViewWithTag<TextView>("engine-latest:${it.name}").text
                                .contains("Mozc upstream: revision 1234567890ab")
                        }
                        assertCurrent(root)
                    }
                    if (!latestReceived) android.os.SystemClock.sleep(20)
                } while (!latestReceived && android.os.SystemClock.elapsedRealtime() < deadline)
                assertTrue("The official Mozc commit metadata did not reach both rows", latestReceived)
                scenario.recreate()
                scenario.onActivity { activity -> assertCurrent(activity.findViewById(R.id.engine_management_root)) }
            }
        } finally { EngineVersionLookup.metadataLoaderOverride = originalLoader }
    }

    @Test fun diagnosticsSwitchStartsOffSavesClicksAndSurvivesActivityRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = DebugDiagnosticsSettings.preferences(context)
        val key = DebugDiagnosticsSettings.KEY_ENABLED
        val original = preferences.all[key]
        val originallyPresent = preferences.contains(key)
        try {
            preferences.edit().remove(key).commit()
            AppDiagnostics.configure(preferences)
            ActivityScenario.launch(EngineManagementActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.engine_management_root)
                    val toggle = root.findViewWithTag<SwitchCompat>("debug-diagnostics-switch")
                    assertEquals("Debug diagnostics", toggle.text.toString())
                    assertEquals(Color.WHITE, toggle.currentTextColor)
                    assertFalse(toggle.isChecked)
                    assertFalse(preferences.contains(key))
                    toggle.performClick()
                    assertTrue(toggle.isChecked)
                    assertTrue(DebugDiagnosticsSettings.enabled(preferences))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val toggle = activity.findViewById<View>(R.id.engine_management_root)
                        .findViewWithTag<SwitchCompat>("debug-diagnostics-switch")
                    assertTrue(toggle.isChecked)
                    toggle.performClick()
                    assertFalse(toggle.isChecked)
                    assertFalse(DebugDiagnosticsSettings.enabled(preferences))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val toggle = activity.findViewById<View>(R.id.engine_management_root)
                        .findViewWithTag<SwitchCompat>("debug-diagnostics-switch")
                    assertFalse(toggle.isChecked)
                    assertTrue(preferences.contains(key))
                }
            }
        } finally {
            val edit = preferences.edit()
            if (!originallyPresent) edit.remove(key)
            else when (original) {
                is Boolean -> edit.putBoolean(key, original)
                is String -> edit.putString(key, original)
                is Int -> edit.putInt(key, original)
                is Long -> edit.putLong(key, original)
                is Float -> edit.putFloat(key, original)
                is Set<*> -> edit.putStringSet(key, original.filterIsInstance<String>().toSet())
            }
            assertTrue("Restore the original diagnostics preference", edit.commit())
            AppDiagnostics.configure(preferences)
        }
    }

    @Test fun homeEngineEntryOpensManagementAndReturnsHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(EngineManagementActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.btn_entry_engine).performClick() }
                val management = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertTrue(management is EngineManagementActivity)
                instrumentation.runOnMainSync {
                    assertNotNull(management.findViewById<View>(R.id.engine_category_list))
                    management.findViewById<View>(R.id.btn_back).performClick()
                    assertTrue(management.isFinishing)
                }
                scenario.onActivity { assertNotNull(it.findViewById<View>(R.id.btn_entry_engine)) }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun categoriesExpandWithReadonlyVersionsAndEmptyCategoriesStayEmptyAfterRecreation() {
        ActivityScenario.launch(EngineManagementActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.engine_management_root)
                val appVersion = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName!!
                val categories = EngineCatalog.categories(appVersion)
                assertTrue(categories.any { it.engines.isEmpty() })
                assertEquals(2, categories.single { it.id == "syllable" }.engines.size)
                capture(activity, "engine-management-collapsed.png")
                categories.forEach { category ->
                    val heading = root.findViewWithTag<View>("category:${category.id}")
                    val entries = root.findViewWithTag<ViewGroup>("engines:${category.id}")
                    assertEquals(View.GONE, entries.visibility)
                    heading.performClick()
                    assertEquals(View.VISIBLE, entries.visibility)
                    assertEquals(category.engines.size, entries.childCount)
                    for (index in 0 until entries.childCount) {
                        assertFalse(entries.getChildAt(index).isClickable)
                        assertFalse(entries.getChildAt(index).isFocusable)
                        val engine = category.engines[index]
                        val description = entries.getChildAt(index).contentDescription.toString()
                        if (engine.status == EngineStatus.PLANNED) {
                            assertTrue(description.contains("Planned"))
                            assertTrue(description.contains("not integrated"))
                        } else assertTrue(description.contains(engine.version))
                    }
                }
            }
            // Render only app-owned content after the expansion layout has settled.
            scenario.onActivity { capture(it, "engine-management-expanded.png") }
            scenario.recreate()
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.engine_management_root)
                EngineCatalog.categories("test").forEach { category ->
                    val heading = root.findViewWithTag<View>("category:${category.id}")
                    val entries = root.findViewWithTag<ViewGroup>("engines:${category.id}")
                    assertEquals(View.VISIBLE, entries.visibility)
                    if (category.engines.isEmpty()) assertEquals(0, entries.childCount)
                    heading.performClick()
                    assertEquals(View.GONE, entries.visibility)
                }
            }
        }
    }

    private fun capture(activity: EngineManagementActivity, filename: String) {
        val view = activity.findViewById<View>(R.id.engine_management_root)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            File(activity.cacheDir, filename).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
