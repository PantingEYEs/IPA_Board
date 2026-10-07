package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.BuiltinLayouts
import org.junit.Assert.*
import org.junit.Test

class EnglishImeIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private val driver = ImeTestDriver()
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean) = driver.awaitNode(predicate)
    private fun type(raw: String) {
        raw.forEach { letter ->
            assertTrue(awaitNode {
                val d = it.contentDescription?.toString().orEmpty()
                d.startsWith("Row ") && d.endsWith(": $letter")
            }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }
    private fun select(scenario: ActivityScenario<ImeTestEditorActivity>, word: String, expectedText: String) =
        driver.select(scenario, word, expectedText)

    @Test fun actualKeyboardMixesLanguagesAndInsertsContextualPrediction() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val groupSnapshot = PageGroupTestState(context)
        val oldPage = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        val ime = "${context.packageName}/.IpaBoardService"
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val manager = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val enabled = manager.enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var page: String? = null
        try {
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            page = LayoutFileManager.createLayout(context, "English integration test", BuiltinLayouts.mixed)
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(page))
            PageGroupManager.selectPage(context, page)
            shell("ime enable $ime"); shell("ime set $ime")
            ActivityScenario.launch(ImeTestEditorActivity::class.java).use { scenario ->
                driver.awaitKeyboard(scenario) { it.contentDescription?.toString() == "Keyboard Overview" }
                type("nihao"); select(scenario, "你好", "你好")
                type("helo"); select(scenario, "hello", "你好hello")
                type("nihongo"); select(scenario, "日本語", "你好hello日本語")
                type("thank"); select(scenario, "thank", "你好hello日本語thank")
                select(scenario, "you", "你好hello日本語thank you ")
                scenario.onActivity { assertEquals("你好hello日本語thank you ", it.editor.text.toString()) }
                // A space with no composition must stay a committed delimiter for the next word.
                assertTrue(awaitNode { it.contentDescription?.toString()?.startsWith("Row 4, key 3:") == true }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                type("hel"); select(scenario, "hello", "你好hello日本語thank you  hello")
                scenario.onActivity { assertEquals("你好hello日本語thank you  hello", it.editor.text.toString()) }
            }
        } finally {
            shell("ime set $oldIme")
            if (!enabled) shell("ime disable $ime")
            prefs.edit().apply {
                if (oldPage == null) remove(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE)
                else putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, oldPage)
            }.commit()
            page?.let { LayoutFileManager.deleteLayout(context, it) }
            ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
            groupSnapshot.close()
        }
    }
}
