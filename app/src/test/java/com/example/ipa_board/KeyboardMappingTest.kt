package com.example.ipa_board

import org.junit.Assert.*
import org.junit.Test

class KeyboardMappingTest {
    @Test fun longPressEditingPreservesOtherMappingsAndClearRemovesBoth() {
        val original = KeyboardLayout("Long press", listOf(RowLayout(1f, listOf(
            KeySlot(1f, "a"), KeySlot(2f, action = KeyAction.BACKSPACE)
        ))))
        val edited = original.withKeyMapping(0, 1, "", KeyAction.BACKSPACE, longPressText = "t͡ʃ😀")
        assertEquals(original.rows[0].slots[0], edited.rows[0].slots[0])
        assertEquals(KeyAction.BACKSPACE, edited.rows[0].slots[1].action)
        assertEquals("t͡ʃ😀", edited.rows[0].slots[1].longPressText)
        assertEquals("t͡ʃ😀", edited.withKeyText(0, 1, "b").rows[0].slots[1].longPressText)
        assertTrue(edited.cleared().rows[0].slots.all { it.longPressText.isEmpty() && it.text.isEmpty() && it.longPressAction == KeyAction.TEXT })
        assertEquals("", original.rows[0].slots[1].longPressText)
    }

    @Test fun longPressFunctionKeyActionBinding() {
        val slotWithFunctionLongPress = KeySlot(
            widthWeight = 1f,
            text = "a",
            action = KeyAction.TEXT,
            longPressAction = KeyAction.BACKSPACE
        )
        assertTrue(slotWithFunctionLongPress.hasLongPress)
        assertEquals(KeyAction.BACKSPACE, slotWithFunctionLongPress.longPressAction)

        val layout = KeyboardLayout("TestLP", listOf(RowLayout(1f, listOf(slotWithFunctionLongPress))))
        val edited = layout.withKeyMapping(0, 0, "a", KeyAction.TEXT, longPressAction = KeyAction.ENTER)
        assertEquals(KeyAction.ENTER, edited.rows[0].slots[0].longPressAction)

        val clearedLayout = edited.cleared()
        assertEquals(KeyAction.TEXT, clearedLayout.rows[0].slots[0].longPressAction)
        assertFalse(clearedLayout.rows[0].slots[0].hasLongPress)
    }

    @Test fun panelFunctionKeyActionsBindingAndDisplayText() {
        val candidatesSlot = KeySlot(1f, action = KeyAction.CANDIDATES)
        val clipboardSlot = KeySlot(1f, action = KeyAction.CLIPBOARD)
        val pagesSlot = KeySlot(1f, action = KeyAction.PAGES)
        val emojiSlot = KeySlot(1f, action = KeyAction.EMOJI)
        val kaomojiSlot = KeySlot(1f, action = KeyAction.KAOMOJI)
        val calculatorSlot = KeySlot(1f, action = KeyAction.CALCULATOR)

        assertEquals("⋯", candidatesSlot.displayText())
        assertEquals("⧉", clipboardSlot.displayText())
        assertEquals("⊞", pagesSlot.displayText())
        assertEquals("☺", emojiSlot.displayText())
        assertEquals("顔", kaomojiSlot.displayText())
        assertEquals("∑", calculatorSlot.displayText())

        assertEquals("Candidates", candidatesSlot.action.title)
        assertEquals("Clipboard", clipboardSlot.action.title)
        assertEquals("Keyboard Pages", pagesSlot.action.title)
        assertEquals("Emoji", emojiSlot.action.title)
        assertEquals("顔文字", kaomojiSlot.action.title)
        assertEquals("计算器", calculatorSlot.action.title)

        val layout = KeyboardLayout("PanelKeys", listOf(RowLayout(1f, listOf(candidatesSlot, clipboardSlot, pagesSlot, emojiSlot, kaomojiSlot, calculatorSlot))))
        assertEquals(KeyAction.CANDIDATES, layout.rows[0].slots[0].action)
        assertEquals(KeyAction.CLIPBOARD, layout.rows[0].slots[1].action)
        assertEquals(KeyAction.PAGES, layout.rows[0].slots[2].action)
        assertEquals(KeyAction.EMOJI, layout.rows[0].slots[3].action)
        assertEquals(KeyAction.KAOMOJI, layout.rows[0].slots[4].action)
        assertEquals(KeyAction.CALCULATOR, layout.rows[0].slots[5].action)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedLongPressMapping() {
        KeyboardLayout("invalid", listOf(RowLayout(1f, listOf(KeySlot(1f, longPressText = "x".repeat(1001))))))
    }

    @Test fun editsOnlySelectedKeyAndCanClearMapping() {
        val original = KeyboardLayout("IPA", listOf(
            RowLayout(2f, listOf(KeySlot(3f, "a"), KeySlot(1f, "b"))),
            RowLayout(1f, listOf(KeySlot(1f, "c")))
        ))
        val edited = original.withKeyText(0, 1, "t͡ʃ😀")
        assertEquals("t͡ʃ😀", edited.rows[0].slots[1].text)
        assertEquals(original.rows[1], edited.rows[1])
        assertEquals(original.rows[0].slots[0], edited.rows[0].slots[0])
        assertEquals(2f, edited.rows[0].heightWeight)
        assertEquals("b", original.rows[0].slots[1].text)
        assertEquals("", edited.withKeyText(0, 1, "").rows[0].slots[1].text)
    }

    @Test fun functionKeyLabelsAndShiftedTextAreIndependentOfDeviceLocale() {
        val previousLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
            assertEquals("IPA", KeySlot(1f, "ipa").displayText(true))
            assertEquals("ipa", KeySlot(1f, "ipa").displayText(false))
            assertEquals("⌫", KeySlot(1f, "old text", KeyAction.BACKSPACE).displayText(true))
            assertEquals("∅", KeySlot(1f).displayText())
        } finally {
            java.util.Locale.setDefault(previousLocale)
        }
    }

    @Test fun clearingRemovesEveryMappingWithoutChangingLayoutGeometryOrSource() {
        val original = KeyboardLayout("Custom", listOf(
            RowLayout(2f, listOf(KeySlot(3f, "t͡ʃ"), KeySlot(1f, action = KeyAction.CTRL))),
            RowLayout(1f, listOf(KeySlot(2f, action = KeyAction.BACKSPACE)))
        ))
        val cleared = original.cleared()
        assertEquals("Custom", cleared.name)
        assertEquals(listOf(2f, 1f), cleared.rows.map { it.heightWeight })
        assertEquals(listOf(listOf(3f, 1f), listOf(2f)), cleared.rows.map { row -> row.slots.map { it.widthWeight } })
        assertTrue(cleared.rows.flatMap { it.slots }.all { it.text.isEmpty() && it.action == KeyAction.TEXT })
        assertEquals("t͡ʃ", original.rows[0].slots[0].text)
        assertEquals(KeyAction.CTRL, original.rows[0].slots[1].action)
        assertEquals(cleared, cleared.cleared())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteWeights() {
        KeyboardLayout("invalid", listOf(RowLayout(Float.NaN, listOf(KeySlot(1f)))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedMapping() {
        KeyboardLayout("invalid", listOf(RowLayout(1f, listOf(KeySlot(1f, "x".repeat(1001))))))
    }
}
