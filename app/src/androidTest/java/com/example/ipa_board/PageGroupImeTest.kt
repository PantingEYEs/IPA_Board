package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PageGroupImeTest {
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
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            ui.windows.forEach { window -> window.root?.let(::visit)?.let { return it } }
            Thread.sleep(80)
        }
        error("Timed out waiting for page-group IME node")
    }
    private fun clickMenu(label: String) {
        var node = awaitNode { it.text?.toString() == label }
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun statusSwitchesGroupsAndAnEmptyGroupDisplaysTheTemporaryTemplate() {
        val context = instrumentation.targetContext
        LayoutFileManager.initDefaultLayout(context)
        val snapshot = PageGroupTestState(context)
        val ime = "${context.packageName}/.IpaBoardService"
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val alreadyEnabled = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var file: String? = null
        try {
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            val first = PageGroupManager.create(context, "IME First")
            file = LayoutFileManager.createLayout(context, "group-ime-test", KeyboardLayout("Group IME", listOf(
                RowLayout(1f, listOf(KeySlot(1f, "Ω"), KeySlot(1f, action = KeyAction.PAGES))))))
            PageGroupManager.addPages(context, first, listOf(file))
            PageGroupManager.setAppearance(context, first, GroupAppearance(heightDp = 200, fontSizeSp = 20))
            val empty = PageGroupManager.create(context, "IME Empty")
            PageGroupManager.setAppearance(context, empty, GroupAppearance(heightDp = 300, fontSizeSp = 30))
            val emptyLabel = PageGroupManager.label(context, PageGroupManager.active(context))
            PageGroupManager.select(context, first)
            val firstLabel = PageGroupManager.label(context, PageGroupManager.active(context))
            shell("ime enable $ime"); shell("ime set $ime")
            ActivityScenario.launch(ImeTestEditorActivity::class.java).use {
                val firstKey = awaitNode { it.contentDescription?.toString() == "Row 1, key 1: Ω" }
                val firstBounds = android.graphics.Rect().also { firstKey.getBoundsInScreen(it) }
                assertTrue(awaitNode { it.contentDescription?.toString() == "Keyboard Overview" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(awaitNode { it.text?.toString() == "$firstLabel ▾" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                clickMenu(emptyLabel)
                awaitNode { it.text?.toString() == "$emptyLabel ▾" }
                awaitNode { it.text?.toString() == context.getString(R.string.empty_group_ime) }
                assertEquals(empty, PageGroupManager.active(context).id)
                assertNull(PageGroupManager.activeFilename(context))
                assertEquals(SettingsConstants.DEFAULT_LAYOUT, LayoutFileManager.activeLayout(context))
                assertTrue(awaitNode { it.contentDescription?.toString() == "Return to Keyboard" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val fallbackHeight = (300 * context.resources.displayMetrics.density / SettingsConstants.DEFAULT_LAYOUT.rows.size).toInt()
                awaitNode { node ->
                    val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    node.contentDescription?.toString() == "Row 1, key 1: unassigned" && bounds.height() == fallbackHeight
                }
                assertTrue(awaitNode { it.contentDescription?.toString() == "Keyboard Overview" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(awaitNode { it.text?.toString() == "$emptyLabel ▾" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                clickMenu(firstLabel)
                assertTrue(awaitNode { it.contentDescription?.toString() == "group-ime-test, Current Page" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                // Accessibility bounds can briefly describe clipped keys while the IME resizes.
                val restored = awaitNode { node ->
                    val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    node.contentDescription?.toString() == "Row 1, key 1: Ω" && node.isClickable && bounds.height() == firstBounds.height()
                }
                val restoredBounds = android.graphics.Rect().also { restored.getBoundsInScreen(it) }
                assertEquals(firstBounds.height(), restoredBounds.height())
                assertTrue(restored.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                it.onActivity { editor -> assertEquals("Ω", editor.editor.text.toString()) }
            }
        } finally {
            shell("ime set $oldIme")
            if (!alreadyEnabled) shell("ime disable $ime")
            file?.let { LayoutFileManager.deleteLayout(context, it) }
            snapshot.close()
            ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
        }
    }
}
