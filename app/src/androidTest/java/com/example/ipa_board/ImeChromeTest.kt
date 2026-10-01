package com.example.ipa_board

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test

class ImeChromeTest {
    @Test fun chromeStaysOutsideRenderedLayoutAndExpandedPanelsDoNotChangeHeight() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val chrome = ImeChromeView(context)
            chrome.setKeyboardHeight(600)
            fun measure() {
                chrome.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.AT_MOST))
                chrome.layout(0, 0, chrome.measuredWidth, chrome.measuredHeight)
            }
            KeyboardRenderer.render(context, chrome.keyboardHost, BuiltinLayouts.mixed, 600, Color.WHITE)
            measure()
            val height = chrome.measuredHeight
            val toolbar = chrome.getChildAt(0)
            assertTrue(height > 600)
            chrome.render("nihao", listOf(Candidate("你好", "简/繁")), "", 1)
            for (panel in ImeChromeView.Panel.entries) {
                chrome.showPanel(panel); measure()
                assertEquals(height, chrome.measuredHeight)
                assertEquals(if (panel == ImeChromeView.Panel.KEYBOARD) View.VISIBLE else View.INVISIBLE, chrome.keyboardHost.visibility)
            }
            KeyboardRenderer.render(context, chrome.keyboardHost, SettingsConstants.DEFAULT_LAYOUT, 600, Color.WHITE)
            assertSame(toolbar, chrome.getChildAt(0))
            assertEquals(3, chrome.childCount)
            // Render only this test-owned view; never capture unrelated application content.
            KeyboardRenderer.render(context, chrome.keyboardHost, BuiltinLayouts.mixed, 600, Color.WHITE)
            chrome.showPanel(ImeChromeView.Panel.KEYBOARD)
            measure()
            for (expanded in listOf(false, true)) {
                chrome.showPanel(if (expanded) ImeChromeView.Panel.CANDIDATES else ImeChromeView.Panel.KEYBOARD)
                measure()
                val bitmap = android.graphics.Bitmap.createBitmap(chrome.width, chrome.height, android.graphics.Bitmap.Config.ARGB_8888)
                chrome.draw(android.graphics.Canvas(bitmap))
                java.io.File(context.cacheDir, if (expanded) "ime-expanded.png" else "ime-keyboard.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }
    @Test fun statusCustomViewIsSetAndClearedWhenPanelChanges() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val chrome = ImeChromeView(context)
            val customView = EditText(context)
            chrome.showPanel(ImeChromeView.Panel.KAOMOJI)
            chrome.setStatusCustomView(customView)
            assertSame(customView.parent, chrome.statusContainer)
            chrome.showPanel(ImeChromeView.Panel.KEYBOARD)
            assertNull(customView.parent)
            assertSame(chrome.status.parent, chrome.statusContainer)
        }
    }

    @Test fun oldLayoutsRemainLiteralAndRoutingRoundTrips() {
        val old = KeyboardLayout.fromJson("""{"name":"old","rows":[{"heightWeight":1,"slots":[{"widthWeight":1,"text":"a"}]}]}""")
        assertEquals(TextBehavior.LITERAL, old.rows[0].slots[0].textBehavior)
        val newLayout = old.withKeyMapping(0, 0, "a", KeyAction.TEXT, TextBehavior.AUTO)
        assertEquals(newLayout, KeyboardLayout.fromJson(newLayout.toJson()))
        assertEquals(TextBehavior.LITERAL, newLayout.cleared().rows[0].slots[0].textBehavior)
    }
}
