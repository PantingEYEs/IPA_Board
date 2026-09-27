package com.example.ipa_board

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardWindowTest {
    @Test fun unassignedPlaceholderAppearsOnlyInEditorPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val keyboard = LayoutInflater.from(context).inflate(R.layout.keyboard_view, null) as ViewGroup
            val layout = KeyboardLayout("Unassigned", listOf(RowLayout(1f, listOf(
                KeySlot(1f), KeySlot(1f, "∅"), KeySlot(1f, action = KeyAction.BACKSPACE)
            ))))

            fun labels(): List<String> {
                val row = keyboard.getChildAt(0) as ViewGroup
                return (0 until row.childCount).map { index ->
                    ((row.getChildAt(index) as ViewGroup).getChildAt(0) as TextView).text.toString()
                }
            }

            KeyboardRenderer.render(context, keyboard, layout, 210, Color.WHITE)
            assertEquals(listOf("", "∅", "⌫"), labels())
            val row = keyboard.getChildAt(0) as ViewGroup
            assertEquals("Row 1, key 1: unassigned", row.getChildAt(0).contentDescription)
            assertEquals("Row 1, key 2: ∅", row.getChildAt(1).contentDescription)

            KeyboardRenderer.render(context, keyboard, layout, 210, Color.WHITE,
                showUnassignedPlaceholders = true)
            assertEquals(listOf("∅", "∅", "⌫"), labels())
            val previewRow = keyboard.getChildAt(0) as ViewGroup
            assertEquals("Row 1, key 1: unassigned", previewRow.getChildAt(0).contentDescription)
        }
    }

    @Test fun attachedKeyboardCanRefreshAfterMappingAndHeightChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val host = FrameLayout(context)
            val keyboard = LayoutInflater.from(context)
                .inflate(R.layout.keyboard_view, host, false) as ViewGroup
            val originalParams = keyboard.layoutParams as FrameLayout.LayoutParams
            originalParams.gravity = Gravity.BOTTOM
            originalParams.leftMargin = 7
            host.addView(keyboard)
            var layout = SettingsConstants.DEFAULT_LAYOUT
            var committed = ""
            for ((index, height) in listOf(210, 300, 150).withIndex()) {
                layout = KeyboardLayout.fromJson(layout.withKeyText(0, 0, "t͡ʃ$index").toJson())
                keyboard.updateKeyboardHeight(height)
                KeyboardRenderer.render(context, keyboard, layout, height, Color.WHITE) { _, _, key ->
                    committed = key.text
                }
                host.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
                )
                host.layout(0, 0, host.measuredWidth, host.measuredHeight)
                assertSame(originalParams, keyboard.layoutParams)
                assertEquals(Gravity.BOTTOM, originalParams.gravity)
                assertEquals(7, originalParams.leftMargin)
                assertEquals(height, keyboard.measuredHeight)
                val key = (keyboard.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
                assertEquals("t͡ʃ$index", (key.getChildAt(0) as TextView).text.toString())
                key.performClick()
                assertEquals("t͡ʃ$index", committed)
            }
        }
    }

    @Test fun rendererShowsFunctionLabelsAndActiveModifiers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val keyboard = LayoutInflater.from(context).inflate(R.layout.keyboard_view, null) as ViewGroup
            val layout = KeyboardLayout("Functions", listOf(RowLayout(1f, listOf(
                KeySlot(1f, "a"), KeySlot(1f, action = KeyAction.SHIFT),
                KeySlot(1f, action = KeyAction.CTRL), KeySlot(1f, action = KeyAction.BACKSPACE)
            ))))
            KeyboardRenderer.render(context, keyboard, layout, 210, Color.WHITE, true, true)
            val row = keyboard.getChildAt(0) as ViewGroup
            fun label(index: Int) = ((row.getChildAt(index) as ViewGroup).getChildAt(0) as TextView).text.toString()
            assertEquals("A", label(0))
            assertEquals("Shift •", label(1))
            assertEquals("Ctrl •", label(2))
            assertEquals("⌫", label(3))
            assertTrue(row.getChildAt(1).isSelected)
            assertTrue(row.getChildAt(2).isSelected)
            assertFalse(row.getChildAt(3).isSelected)
        }
    }

    @Test fun unattachedKeyboardCanBeAddedToImeHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val keyboard = LayoutInflater.from(context).inflate(R.layout.keyboard_view, null)
            keyboard.updateKeyboardHeight(210)
            val host = FrameLayout(context)
            host.addView(keyboard)
            keyboard.updateKeyboardHeight(300)
            host.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
            )
            assertEquals(300, keyboard.measuredHeight)
        }
    }
}
