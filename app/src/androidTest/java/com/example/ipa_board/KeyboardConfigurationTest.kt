package com.example.ipa_board

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardConfigurationTest {
    @Test fun unicodeMappingSurvivesExportAndImport() {
        val texts = listOf("t͡ʃ", "ã", "😀", " ", "line\nnext", "")
        val layout = KeyboardLayout("IPA", listOf(RowLayout(1f, texts.map { KeySlot(1f, it) })))
        assertEquals(layout, KeyboardLayout.fromJson(layout.toJson()))
    }

    @Test fun legacyLayoutLoadsWithUnassignedKeys() {
        val legacy = """{"name":"old","rows":[{"heightWeight":1,"slots":[{"widthWeight":2}]}]}"""
        val layout = KeyboardLayout.fromJson(legacy)
        assertEquals("", layout.rows[0].slots[0].text)
        assertEquals(2f, layout.rows[0].slots[0].widthWeight)
    }

    @Test fun editingOneKeyPreservesOtherMappingsAndGeometry() {
        val original = KeyboardLayout("IPA", listOf(RowLayout(2f, listOf(KeySlot(3f, "a"), KeySlot(1f, "b")))))
        val edited = original.withKeyText(0, 1, "ɪ")
        assertEquals("a", edited.rows[0].slots[0].text)
        assertEquals("ɪ", edited.rows[0].slots[1].text)
        assertEquals("b", original.rows[0].slots[1].text)
        assertEquals(2f, edited.rows[0].heightWeight)
        assertEquals(3f, edited.rows[0].slots[0].widthWeight)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidGeometry() {
        KeyboardLayout("invalid", listOf(RowLayout(1f, listOf(KeySlot(-1f)))))
    }
}
