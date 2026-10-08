package com.example.ipa_board

import com.example.ipa_board.ime.PhraseContinuation
import com.example.ipa_board.ime.PredictionContext
import org.junit.Assert.*
import org.junit.Test

class PhraseContinuationTest {
    private val corpus = """
        可以	500
        我們	400
        天氣預報	100
        謝謝	300
        謝謝大家	200
        謝謝你	150
        謝謝老師	30
        老師們	75
        謝謝忽略	0
    """.trimIndent()

    @Test fun continuesTheLongestMatchingPhraseWithoutRepeatingContext() {
        val engine = PhraseContinuation(corpus)
        assertEquals(listOf("大家", "你", "老師"), engine.suggest("我想說謝謝").map { it.text })
        assertEquals("預報", engine.suggest("天氣 123！").first().text)
        assertEquals("們", engine.suggest("謝謝老師").first().text)
    }

    @Test fun unrelatedContextUsesRealFrequencyRankedWords() {
        val engine = PhraseContinuation(corpus)
        assertEquals("可以", engine.suggest("hello").first().text)
        assertTrue(engine.suggest("").zipWithNext().all { (a, b) -> a.frequency >= b.frequency })
        assertFalse(engine.suggest("").any { it.text == "謝謝忽略" })
    }

    @Test fun nativeJapaneseContextIncludesKanaAndSkipsTrailingNoise() {
        assertEquals("私は", PredictionContext.japanese("hello 私は123!? "))
        assertEquals("東京", PredictionContext.japanese("東京！"))
        assertEquals("", PredictionContext.japanese("日本語hello"))
        assertEquals("", PredictionContext.chinese("私は"))
    }

    @Test fun handlesSupplementaryHanWithoutSplittingSurrogatePairs() {
        val rare = String(Character.toChars(0x20000))
        val engine = PhraseContinuation("${rare}語\t80\n謝謝大家\t90\n")
        assertEquals("語", engine.suggest(rare).first().text)
    }
}
