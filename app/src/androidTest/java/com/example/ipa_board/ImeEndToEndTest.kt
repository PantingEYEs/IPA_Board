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

class ImeEndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue; visit(child)?.let { return it } }
            return null
        }
        return ui.windows.firstNotNullOfOrNull { it.root?.let(::visit) }
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            find(predicate)?.let { return it }
            Thread.sleep(80)
        }
        error("Timed out waiting for IME node")
    }
    @Test fun actualKeyboardConvertsAndExpandedPanelReturnsToKeys() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val oldPage = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        val ime = "${context.packageName}/.IpaBoardService"
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val alreadyEnabled = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var page: String? = null
        var alternatePage: String? = null
        try {
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            page = LayoutFileManager.createLayout(context, "IME E2E", BuiltinLayouts.mixed.copy(name = "IME E2E"))
            alternatePage = LayoutFileManager.createLayout(context, "IME E2E 2", BuiltinLayouts.mixed.copy(name = "IME E2E 2"))
            prefs.edit().putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, page).commit()
            shell("ime enable $ime"); shell("ime set $ime")
            ActivityScenario.launch(ImeTestEditorActivity::class.java).use { scenario ->
                awaitNode { it.contentDescription?.toString() == "打开键盘页总览" }
                for (letter in "nihao") {
                    val key = awaitNode { it.contentDescription?.toString()?.let { d -> d.startsWith("Row ") && d.endsWith(": $letter") } == true }
                    assertTrue(key.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                awaitNode { it.text?.toString()?.startsWith("你好 ") == true }
                awaitNode { it.contentDescription?.toString() == "打开键盘页总览" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                awaitNode { it.contentDescription?.toString() == "IME E2E 2，切换键盘页" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                awaitNode { it.text?.toString() == "nihao" }
                awaitNode { it.text?.toString()?.startsWith("你好 ") == true }
                assertTrue(awaitNode { it.contentDescription?.toString() == "展开候选词" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitNode { it.text?.toString()?.startsWith("候选词 ·") == true }
                assertNull(find { it.contentDescription?.toString()?.startsWith("Row ") == true })
                shell("input keyevent 4")
                awaitNode { it.contentDescription?.toString()?.startsWith("Row ") == true }
                assertTrue(awaitNode { it.text?.toString()?.startsWith("你好 ") == true }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals("你好", it.editor.text.toString()) }
                for ((raw, word) in listOf("hello" to "hello", "hanyu" to "汉语", "hanyu" to "漢語", "nihongo" to "日本語")) {
                    for (letter in raw) {
                        awaitNode { it.contentDescription?.toString()?.let { d -> d.startsWith("Row ") && d.endsWith(": $letter") } == true }
                            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    awaitNode { it.text?.toString()?.startsWith("$word ") == true }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    instrumentation.waitForIdleSync()
                }
                scenario.onActivity { assertEquals("你好hello汉语漢語日本語", it.editor.text.toString()) }
                awaitNode { it.contentDescription?.toString() == "打开剪贴板" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                awaitNode { it.contentDescription?.toString() == "返回键盘" }
                assertNull(find { it.contentDescription?.toString()?.startsWith("Row ") == true })
                shell("input keyevent 4")
                awaitNode { it.contentDescription?.toString()?.startsWith("Row ") == true }
                shell("input keyevent 4")
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                while (find { it.contentDescription?.toString() == "打开键盘页总览" } != null && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(80)
                assertNull(find { it.contentDescription?.toString() == "打开键盘页总览" })
            }
        } finally {
            shell("ime set $oldIme")
            if (!alreadyEnabled) shell("ime disable $ime")
            prefs.edit().apply { if (oldPage == null) remove(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE) else putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, oldPage) }.commit()
            page?.let { LayoutFileManager.deleteLayout(context, it) }
            alternatePage?.let { LayoutFileManager.deleteLayout(context, it) }
            ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
        }
    }
}
