package com.example.ipa_board

import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test

class CandidateRankerTest {
    @Test fun equivalentChineseFormsShareOneEntryWithoutDoubleCounting() {
        val result = CandidateRanker.merge("nihao", listOf(Candidate("你好", "简", 0), Candidate("你好", "繁", 0), Candidate("hello", "EN", 1)))
        assertEquals(2, result.size)
        assertEquals("简/繁", result[0].language)
    }
    @Test fun distinctScriptsRemainAndEveryEngineCanRankFirst() {
        val result = CandidateRanker.merge("han", listOf(Candidate("漢", "繁", 4), Candidate("汉", "简", 4), Candidate("hand", "EN", 1), Candidate("半", "日", 0)))
        assertEquals("半", result.first().text)
        assertTrue(result.any { it.text == "漢" }); assertTrue(result.any { it.text == "汉" })
    }
    @Test fun rawMatchAndEmptyCandidatesAreHandled() {
        val result = CandidateRanker.merge("hello", listOf(Candidate("", "日"), Candidate("hello", "EN"), Candidate("候補", "日")))
        assertEquals("hello", result.first().text)
        assertEquals(2, result.size)
    }
}
