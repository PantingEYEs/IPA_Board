package com.example.ipa_board

import org.junit.Assert.assertEquals
import org.junit.Test

class CharacterWidthConverterTest {
    @Test fun swapsLettersDigitsPunctuationAndSpacesBothWays() {
        val narrow = "Abc123 !,.-~"
        val full = "Ａｂｃ１２３　！，．－～"
        assertEquals(full, CharacterWidthConverter.swap(narrow))
        assertEquals(narrow, CharacterWidthConverter.swap(full))
    }

    @Test fun mixedWidthsConvertOnceInTheirOriginalDirection() {
        assertEquals("ＡB１2，,　 ", CharacterWidthConverter.swap("AＢ1２,， 　"))
        assertEquals("", CharacterWidthConverter.swap(""))
    }

    @Test fun voicedKanaAndJapanesePunctuationRetainTheirMeaning() {
        val full = "ガパヴヷヺ カタカナ、。「」・ー"
        val half = "ｶﾞﾊﾟｳﾞﾜﾞｦﾞ　ｶﾀｶﾅ､｡｢｣･ｰ"
        assertEquals(half, CharacterWidthConverter.swap(full))
        assertEquals(full, CharacterWidthConverter.swap(half))
        assertEquals("ｶﾞガﾊﾟパ", CharacterWidthConverter.swap("ガｶﾞパﾊﾟ"))
        assertEquals("ｶﾞﾊﾟ", CharacterWidthConverter.swap("カ\u3099ハ\u309A"))
        assertEquals("ガパ", CharacterWidthConverter.swap("ｶﾞﾊﾟ"))
    }

    @Test fun isolatedAndUncomposableKanaMarksAreNotDroppedOrGuessed() {
        assertEquals("\u3099\u309A", CharacterWidthConverter.swap("ﾞﾟ"))
        assertEquals("ﾞﾟ", CharacterWidthConverter.swap("\u3099\u309A"))
        assertEquals("ア\u3099\u3099\u309A", CharacterWidthConverter.swap("ｱﾞﾞﾟ"))
        assertEquals("カﾞ", CharacterWidthConverter.swap("ｶ\u3099"))
    }

    @Test fun unrelatedUnicodeAndCompatibilityCharactersRemainUnchanged() {
        val original = "汉字ㄅㄆㄇがぱヰヱヸヹɑɪʃé①Ⅳ㍑ﬁ\u0301\n\t\r👨‍👩‍👧‍👦🏳️‍🌈🧑🏽‍💻"
        assertEquals(original, CharacterWidthConverter.swap(original))
        assertEquals("ｅ\u0301E\u0301ɑ\u0303", CharacterWidthConverter.swap("e\u0301Ｅ\u0301ɑ\u0303"))
    }

    @Test fun preservesEmojiKeycapsButConvertsStandaloneBases() {
        assertEquals("1️⃣#️⃣*⃣0⃣　１＃＊", CharacterWidthConverter.swap("1️⃣#️⃣*⃣0⃣ 1#*"))
        assertEquals("1️⃣#️⃣*⃣0⃣ 1#*", CharacterWidthConverter.swap("1️⃣#️⃣*⃣0⃣　１＃＊"))
    }

    @Test fun symbolsUseTheirWidthCounterpartsWithoutFurtherCompatibilityConversion() {
        val ordinary = "¢£¬¯¦¥₩│←↑→↓■○⦅⦆"
        val variants = "\uFFE0\uFFE1\uFFE2\uFFE3\uFFE4\uFFE5\uFFE6\uFFE8\uFFE9\uFFEA\uFFEB\uFFEC\uFFED\uFFEE\uFF5F\uFF60"
        assertEquals(variants, CharacterWidthConverter.swap(ordinary))
        assertEquals(ordinary, CharacterWidthConverter.swap(variants))
    }

    @Test fun halfwidthHangulMapsToCompatibilityJamoAndLeavesSyllablesAlone() {
        val half = "\uFFA0\uFFA1\uFFBE\uFFC2\uFFCC\uFFDA\uFFDC"
        val full = "\u3164ㄱㅎㅏㅗㅡㅣ"
        assertEquals(full, CharacterWidthConverter.swap(half))
        assertEquals(half, CharacterWidthConverter.swap(full))
        assertEquals("한글가\uFFBF\uFFC0\uFFC1", CharacterWidthConverter.swap("한글가\uFFBF\uFFC0\uFFC1"))
    }
}
