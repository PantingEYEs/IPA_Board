package com.example.ipa_board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.EngineCatalog
import com.example.ipa_board.ime.EngineStatus
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EngineManagementTest {
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
