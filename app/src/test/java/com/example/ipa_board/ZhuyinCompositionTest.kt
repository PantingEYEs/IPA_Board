package com.example.ipa_board

import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test

class ZhuyinCompositionTest {
    @Test fun symbolsAndTonesMapToNativeKeysWithoutConsumingLiteralPunctuation() {
        assertEquals("su3cl3", ZhuyinInput.encode("ㄋㄧˇㄏㄠˇ"))
        assertEquals("cl ", ZhuyinInput.encode("ㄏㄠˉ"))
        assertEquals("2k7", ZhuyinInput.encode("ㄉㄜ˙"))
        assertEquals(ZhuyinInput.encode("ㄉㄜ˙"), ZhuyinInput.encode("˙ㄉㄜ"))
        assertNull(ZhuyinInput.encode("ㄋㄧ123!"))
        assertNull(ZhuyinInput.encode("hello"))
        assertNull(ZhuyinInput.encode("ˇ"))
        assertNull(ZhuyinInput.encode("ˊㄋㄧ"))
        assertNull(ZhuyinInput.encode("ㄋㄧˇˋ"))
        assertNull(ZhuyinInput.encode("ㄋㄧ'ˇㄏㄠ"))
    }

    @Test fun allBasicSymbolsAndFiveToneMarksAreAccepted() {
        val symbols = ('ㄅ'..'ㄩ').joinToString("")
        assertEquals(37, symbols.length)
        assertTrue(symbols.all { ZhuyinInput.isSymbol(it) })
        assertEquals(37, ZhuyinInput.encode(symbols)!!.length)
        for (tone in "ˉˊˇˋ˙") assertNotNull(ZhuyinInput.encode("ㄇㄚ$tone"))
        assertFalse(ZhuyinInput.isSymbol('汉'))
        assertFalse(ZhuyinInput.isInputCharacter('1'))
    }

    @Test fun nativeNonRomanSegmentsReplaceOnlyTheirSpansAndKeepEveryLiteral() {
        val requested = mutableListOf<String>()
        val engine = object : QueryEngine {
            override val segmentPattern = Regex("[ㄅ-ㄩˉˊˇˋ˙]+")
            override fun acceptsInput(raw: String) = ZhuyinInput.encode(raw) != null
            override fun query(raw: String): List<Candidate> {
                requested.add(raw)
                return if (raw == "ㄋㄧˇㄏㄠˇ") listOf(Candidate("你好", "繁")) else emptyList()
            }
        }
        val plain = MixedCompositionCandidates.query(engine, "ㄋㄧˇㄏㄠˇ", "", "", false)
        assertEquals("你好", plain.single().text)
        val mixed = MixedCompositionCandidates.query(engine, "中文🙂ㄋㄧˇㄏㄠˇ123!", "", "", false)
        assertEquals("中文🙂你好123!", mixed.single().text)
        assertEquals(listOf("ㄋㄧˇㄏㄠˇ", "ㄋㄧˇㄏㄠˇ"), requested)
        // English/Japanese services must not reinterpret raw Zhuyin as roman key sequences.
        val romanEngine = object : QueryEngine {
            override fun query(raw: String) = error("Unexpected query: $raw")
        }
        assertTrue(MixedCompositionCandidates.query(romanEngine, "ㄋㄧˇㄏㄠˇ", "", "", false).isEmpty())
    }
}
