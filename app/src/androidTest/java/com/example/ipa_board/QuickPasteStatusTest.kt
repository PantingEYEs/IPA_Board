package com.example.ipa_board

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.ImeChromeView
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class QuickPasteStatusTest {
    @Test fun exhaustedShortcutClearsClipboardCallbackAndCannotPasteAgain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefName = "quick-paste-test-${UUID.randomUUID()}"
        try {
            instrumentation.runOnMainSync {
                val service = IpaBoardService()
                val view = ImeChromeView(context)
                view.showPanel(ImeChromeView.Panel.CLIPBOARD)
                fun field(name: String, value: Any?) {
                    IpaBoardService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
                }
                field("chrome", view)
                field("prefs", context.getSharedPreferences(prefName, Context.MODE_PRIVATE))
                val stateType = IpaBoardService::class.java.declaredClasses.single { it.simpleName == "QuickPasteState" }
                val state = stateType.getDeclaredConstructor(String::class.java, Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).apply { isAccessible = true }
                    .newInstance("Shortcut", System.currentTimeMillis(), 0)
                field("quickPasteState", state)
                var staleClicks = 0
                view.status.text = "Shortcut"
                view.onStatusClick = { staleClicks++ }
                val update = IpaBoardService::class.java.getDeclaredMethod("updateQuickPasteStatus")
                    .apply { isAccessible = true }
                update.invoke(service)
                assertNull(view.onStatusClick)
                assertFalse(view.status.isClickable)
                assertEquals("Clipboard", view.status.text.toString())
                view.status.performClick()
                assertEquals(0, staleClicks)
                // A subsequent refresh with no state must also remove any stale handler.
                view.onStatusClick = { staleClicks++ }
                update.invoke(service)
                assertNull(view.onStatusClick)
            }
        } finally {
            context.deleteSharedPreferences(prefName)
        }
    }
}
