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
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i) ?: continue)?.let { return it }
            return null
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            ui.windows.forEach { window ->
                val root = window.root ?: return@forEach
                if (root.packageName?.toString() == instrumentation.targetContext.packageName)
                    visit(root)?.let { return it }
            }
            Thread.sleep(80)
        }
        error("Timed out waiting for test editor/IME")
    }
    private fun type(raw: String) {
        raw.forEach { letter ->
            assertTrue(awaitNode {
                val d = it.contentDescription?.toString().orEmpty()
                d.startsWith("Row ") && d.endsWith(": $letter")
            }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
    }
    private fun select(word: String) {
        assertTrue(awaitNode { it.contentDescription?.toString() == word && it.text?.toString() == "$word " }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

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
                type("nihao"); select("你好")
                type("helo"); select("hello")
                type("nihongo"); select("日本語")
                type("thank"); select("thank")
                select("you")
                scenario.onActivity { assertEquals("你好hello日本語thank you ", it.editor.text.toString()) }
                // A space with no composition must stay a committed delimiter for the next word.
                assertTrue(awaitNode { it.contentDescription?.toString()?.startsWith("Row 4, key 3:") == true }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                type("hel"); select("hello")
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
