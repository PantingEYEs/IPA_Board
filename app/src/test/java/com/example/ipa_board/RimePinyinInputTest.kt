package com.example.ipa_board

import com.example.ipa_board.ime.PinyinScheme
import com.example.ipa_board.ime.RimePinyinInput
import org.junit.Assert.*
import org.junit.Test

class RimePinyinInputTest {
    @Test fun terraAcceptsTonesOmissionsApostrophesAndCaseWithoutChangingRawOffsets() {
        val spelling = RimePinyinInput(PinyinScheme.TERRA)
        for (raw in listOf("ma-", "ma/", "ma<", "ma\\", "ni<hao<", "nihao", "ni'hao", "ni<'hao<", "ni<'", "NI<HAO<", "h<")) {
            val encoded = requireNotNull(spelling.encode(raw)) { raw }
            assertEquals(raw.lowercase(java.util.Locale.ROOT), encoded)
            assertEquals(raw.length, encoded.length)
        }
    }

    @Test fun onlyTheSelectedSchemaAndExplicitAlternativeOptionAcceptToneAliases() {
        val primary = RimePinyinInput(PinyinScheme.TERRA)
        val alternative = RimePinyinInput(PinyinScheme.TERRA, true)
        val luna = RimePinyinInput(PinyinScheme.LUNA, true)
        for (raw in listOf("ma;", "ma,", "ma.")) {
            assertFalse(primary.accepts(raw))
            assertEquals(raw, alternative.encode(raw))
            assertFalse(luna.accepts(raw))
        }
        assertFalse(alternative.accepts("ma>"))
        for (raw in listOf("ma-", "ma/", "ma<", "ma\\")) assertFalse(luna.accepts(raw))
        for (raw in listOf("", "ni'hao", "'nihao", "nihao'", "ni''hao")) assertTrue(luna.accepts(raw))
    }

    @Test fun invalidTonePositionsAndUnrelatedCharactersAreNeverNativeSpelling() {
        val spelling = RimePinyinInput(PinyinScheme.TERRA)
        for (raw in listOf("", "-", "/", "<", "\\", "/hao", "hao<<", "hao-/", "ni3hao3", "1/2", "3-2", "nǐhǎo", "ü", "a:b", "你好")) {
            assertNull(raw, spelling.encode(raw))
        }
    }

    @Test fun mixedSegmentsKeepToneKeysTogetherAndLeaveSurroundingLiteralsUntouched() {
        val spelling = RimePinyinInput(PinyinScheme.TERRA)
        val raw = "中文😀ni<hao<123!"
        val match = spelling.segmentPattern.findAll(raw).single()
        assertEquals("ni<hao<", match.value)
        assertEquals("中文😀", raw.take(match.range.first))
        assertEquals("123!", raw.substring(match.range.last + 1))
        assertEquals(listOf("hao"), spelling.segmentPattern.findAll("hao,").map { it.value }.toList())
        assertEquals(listOf("hao,"), RimePinyinInput(PinyinScheme.TERRA, true).segmentPattern.findAll("hao,").map { it.value }.toList())
        assertTrue(spelling.segmentPattern.findAll("hao<<").none())
        for (literal in listOf("https://example.com/path", "person@example.com", "1/2", "3-2")) {
            assertTrue(literal, spelling.segmentPattern.findAll(literal).none())
        }
    }
}
