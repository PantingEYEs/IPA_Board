package com.example.ipa_board

import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test

class SegmentedCandidatesTest {
    @Test fun equalTextWithDifferentCoverageOrSelectionIsNotMerged() {
        val first = Candidate("你好", "简", consumption = CandidateConsumption("nihaoshijie", 5, "Rime", "0"))
        val otherRange = first.copy(consumption = first.consumption!!.copy(end = 11))
        val otherSelection = first.copy(consumption = first.consumption!!.copy(selection = "1"))
        val full = Candidate("你好", "简")
        for (results in listOf(CandidateRanker.unranked(listOf(first, otherRange, otherSelection, full)),
            CandidateRanker.merge("nihaoshijie", listOf(first, otherRange, otherSelection, full)))) {
            assertEquals(4, results.size)
            assertEquals(4, results.map { it.id }.distinct().size)
        }
    }
    @Test fun mixedLiteralConversionNeverTreatsPrefixAsWholeSegment() {
        val engine = object : QueryEngine {
            override fun query(raw: String) = listOf(
                Candidate("你", "简", consumption = CandidateConsumption(raw, 2, "Rime", "0")),
                Candidate("你好", "简"))
        }
        val results = MixedCompositionCandidates.query(engine, "nihao123", "", "", false)
        assertEquals(listOf("你好123"), results.map { it.text })
        assertTrue(results.all { it.consumption == null })
    }
    @Test fun malformedAndStaleCoverageIsNotValid() {
        val range = CandidateConsumption("nihao", 2, "Rime", "0")
        assertTrue(range.validFor("nihao"))
        assertFalse(range.validFor("nihaox"))
        assertFalse(range.copy(end = 0).validFor("nihao"))
        assertFalse(range.copy(end = 6).validFor("nihao"))
        assertFalse(range.copy(source = "EN").validFor("nihao"))
    }
}
