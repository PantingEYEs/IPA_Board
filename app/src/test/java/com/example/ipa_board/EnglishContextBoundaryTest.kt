package com.example.ipa_board

import com.example.ipa_board.ime.EnglishContext
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class EnglishContextBoundaryTest {
    @Test fun followingContextUsesTheAdjacentEnglishWordWithoutSearchingAcrossOtherScripts() {
        assertEquals("don't", EnglishContext.followingWord(" \tDON'T stop"))
        assertEquals("next", EnglishContext.followingWord("next 中文"))
        for (context in listOf("\nnext", "。next", "中文 next", "123 next", "😀 next")) {
            assertNull("Context $context must not be treated as an adjacent English word", EnglishContext.followingWord(context))
        }
    }

    @Test fun contextNormalizationDoesNotDependOnTheDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i'd", EnglishContext.followingWord("I'D choose"))
            assertEquals(listOf("i'd", "if"), EnglishContext.previousWords("中文 IF I'D "))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun oversizedWordsAndDistantFollowingTextAreNotSentToTheDictionary() {
        assertEquals("x".repeat(47), EnglishContext.followingWord("x".repeat(47)))
        assertNull(EnglishContext.followingWord("x".repeat(48)))
        assertEquals(listOf("next"), EnglishContext.previousWords("old " + "x".repeat(48) + " next "))
        assertNull(EnglishContext.followingWord(" ".repeat(256) + "next"))
    }
}
