package com.example.ipa_board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.ContextRankingSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Focused device check for the opt-in contract; no engine inference or unit suite. */
class ContextRankingSettingsTest {
    @Test fun defaultOffAndChoicePersistsAcrossRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ContextRankingSettings.preferences(context)
        val existed = prefs.contains(ContextRankingSettings.KEY_ENABLED)
        val old = ContextRankingSettings.isEnabled(context)
        prefs.edit().remove(ContextRankingSettings.KEY_ENABLED).commit()
        try {
            ActivityScenario.launch(EngineManagementActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val toggle = activity.findViewById<SwitchCompat>(R.id.switch_semantic_context)
                    assertFalse(toggle.isChecked)
                    assertTrue(activity.findViewById<TextView>(R.id.semantic_context_notice).text.contains("latency"))
                    toggle.performClick()
                    assertTrue(ContextRankingSettings.isEnabled(activity))
                    val root = activity.findViewById<android.view.View>(R.id.engine_management_root)
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(bitmap))
                        File(activity.cacheDir, "semantic-context-settings.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { bitmap.recycle() }
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val toggle = activity.findViewById<SwitchCompat>(R.id.switch_semantic_context)
                    assertTrue(toggle.isChecked)
                    toggle.performClick()
                    assertFalse(ContextRankingSettings.isEnabled(activity))
                }
            }
        } finally {
            prefs.edit().apply {
                if (existed) putBoolean(ContextRankingSettings.KEY_ENABLED, old)
                else remove(ContextRankingSettings.KEY_ENABLED)
            }.commit()
        }
    }
}
