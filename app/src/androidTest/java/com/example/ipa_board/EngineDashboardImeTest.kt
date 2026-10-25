package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import org.junit.Assert.*
import org.junit.Test

/** Real IME only; this class is independently runnable under the external backup workflow. */
class EngineDashboardImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val driver = ImeTestDriver()
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean) = driver.find(predicate)
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean) = driver.awaitNode(predicate)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun boundDashboardPreservesCompositionReturnsAndOpensMainSettings() {
        val layout = KeyboardLayout("Dashboard E2E", listOf(RowLayout(1f, listOf(
            KeySlot(1f, "ɑ😀"), KeySlot(1f, action = KeyAction.ENGINE_DASHBOARD), KeySlot(1f, action = KeyAction.ENTER)
        ))))
        withKeyboard(layout) { scenario ->
            val context = instrumentation.targetContext
            val prefs = com.example.ipa_board.ime.EngineSettings.preferences(context)
            val before = com.example.ipa_board.ime.EngineFeature.entries.associateWith { prefs.all[it.key] }
            assertTrue(awaitNode { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitComposition(scenario, "ɑ😀")
            fun open() {
                assertTrue(awaitNode { it.contentDescription?.toString()?.startsWith("Row 1, key 2:") == true }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitNode { it.text?.toString() == context.getString(R.string.engine_dashboard_settings) }
                assertNull(find { it.isVisibleToUser && it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true })
            }
            open()
            awaitComposition(scenario, "ɑ😀")
            assertEquals(before, com.example.ipa_board.ime.EngineFeature.entries.associateWith { prefs.all[it.key] })
            assertTrue(awaitNode { it.contentDescription?.toString() == "Return to Keyboard" }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
            awaitComposition(scenario, "ɑ😀")
            // A second open exercises observer detach/attach and actual Android settings navigation.
            open()
            val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
            try {
                assertTrue(awaitNode { it.text?.toString() == context.getString(R.string.engine_dashboard_settings) }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val settings = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull(settings)
                instrumentation.runOnMainSync { settings!!.finish() }
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    private fun awaitComposition(scenario: ActivityScenario<ImeTestEditorActivity>, text: String) {
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            assertEquals(text, it.editor.text.toString())
            assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(it.editor.text) >= 0)
        }
    }
    private fun withKeyboard(layout: KeyboardLayout, body: (ActivityScenario<ImeTestEditorActivity>) -> Unit) {
        val context = instrumentation.targetContext
        val groups = PageGroupTestState(context)
        val prefs = EngineSettings.preferences(context)
        val before = EngineFeature.entries.associateWith { prefs.all[it.key] }
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val enabled = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var page: String? = null
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        try {
            prefs.edit().apply { EngineFeature.entries.forEach { putBoolean(it.key, false) } }.commit()
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            page = LayoutFileManager.createLayout(context, layout.name, layout)
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(page))
            PageGroupManager.selectPage(context, page)
            shell("ime enable $ime"); shell("ime set $ime")
            val editor = ActivityScenario.launch(ImeTestEditorActivity::class.java)
            scenario = editor
            driver.awaitKeyboard(editor) { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
            body(editor)
        } finally {
            scenario?.close()
            shell("ime set $oldIme")
            if (!enabled) shell("ime disable $ime")
            page?.let { LayoutFileManager.deleteLayout(context, it) }
            groups.close()
            prefs.edit().apply { before.forEach { (feature, value) ->
                if (value is Boolean) putBoolean(feature.key, value) else remove(feature.key)
            } }.commit()
            ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
        }
    }
}
