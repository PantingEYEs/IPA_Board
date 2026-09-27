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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteWeights() {
        KeyboardLayout("invalid", listOf(RowLayout(Float.NaN, listOf(KeySlot(1f)))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedMapping() {
        KeyboardLayout("invalid", listOf(RowLayout(1f, listOf(KeySlot(1f, "x".repeat(1001))))))
    }
}
