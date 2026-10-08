package com.example.ipa_board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongPressTextCodecTest {
    @Test fun loneCommaBindsInsteadOfDisappearing() {
        assertEquals(listOf(","), LongPressTextCodec.parse(","))
        val slot = KeySlot(1f, longPressText = ",")
        assertTrue(slot.hasLongPress)
        assertEquals(listOf(LongPressItem(text = ",")), slot.effectiveLongPressItems)
    }

    @Test fun escapedCommaCanBeMixedWithOtherCandidates() {
        assertEquals(listOf("ɑ", ",", "æ"), LongPressTextCodec.parse("ɑ, \\,, æ"))
        assertEquals(listOf("hello, world", ","), LongPressTextCodec.parse("hello\\, world, \\,"))
    }

    @Test fun punctuationSurvivesReopeningAndSaving() {
        val candidates = listOf(",", "\\", ";", "\"", "a,b", "\\,", "😀")
        assertEquals(candidates, LongPressTextCodec.parse(LongPressTextCodec.format(candidates)))
        candidates.forEach { text ->
            assertEquals(listOf(text), LongPressTextCodec.parse(LongPressTextCodec.format(listOf(text))))
        }
    }

    @Test fun existingListsAndSingleWhitespaceBindingsStillWork() {
        assertEquals(listOf("ɑ", "æ", "ɒ"), LongPressTextCodec.parse("ɑ, æ, ɒ"))
        assertEquals(emptyList<String>(), LongPressTextCodec.parse(""))
        assertEquals(listOf(" "), LongPressTextCodec.parse(" "))
        assertEquals(listOf("\n"), LongPressTextCodec.parse("\n"))
        assertEquals(listOf("\\"), LongPressTextCodec.parse("\\"))
        assertEquals(listOf("\\x"), LongPressTextCodec.parse("\\x"))
        assertEquals(listOf(LongPressItem(text = "ɑ"), LongPressItem(text = "æ")),
            KeySlot(1f, longPressText = "ɑ, æ").effectiveLongPressItems)
    }
}
