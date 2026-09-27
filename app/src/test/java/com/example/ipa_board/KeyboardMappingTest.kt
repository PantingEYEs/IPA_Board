package com.example.ipa_board

import org.junit.Assert.*
import org.junit.Test

class KeyboardMappingTest {
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
