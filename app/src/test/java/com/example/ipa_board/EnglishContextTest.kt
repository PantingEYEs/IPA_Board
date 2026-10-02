package com.example.ipa_board

import com.example.ipa_board.ime.EnglishContext
import org.junit.Assert.*
import org.junit.Test

class EnglishContextTest {
    @Test fun contextStopsAtOtherScriptsAndSentenceBoundaries() {
        assertEquals(listOf("you", "thank"), EnglishContext.previousWords("你好 thank you "))
        assertEquals(listOf("don't", "i"), EnglishContext.previousWords("日本語 I don't "))
        assertEquals(emptyList<String>(), EnglishContext.previousWords("hello. "))
        assertEquals(emptyList<String>(), EnglishContext.previousWords("hello你好"))
        assertEquals(listOf("four", "three", "two"), EnglishContext.previousWords("one two three four "))
    }
    @Test fun predictionSeparatesAnEnglishWordWithoutAddingSpaceAfterChineseOrWhitespace() {
        assertEquals(" ", EnglishContext.insertionPrefix("hello"))
        assertEquals("", EnglishContext.insertionPrefix("hello "))
        assertEquals("", EnglishContext.insertionPrefix("你好"))
    }
}
