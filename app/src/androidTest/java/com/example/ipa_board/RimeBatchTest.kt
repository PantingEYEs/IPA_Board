package com.example.ipa_board

import com.example.ipa_board.ime.*
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/**
 * Real packet coverage and repeatability, with one native batch per spelling.
 * The old pointer-table replay oracle and timing benchmark remain in Git history: their
 * Trime API is absent from the new runtime and cannot serve as its executable reference.
 */
class RimeBatchTest {
    companion object {
        private lateinit var fixture: RimeOwnedFixture
        @JvmStatic @BeforeClass fun prepare() { fixture = RimeOwnedFixture(PinyinScheme.entries.toSet()) }
        @JvmStatic @AfterClass fun cleanup() { fixture.close() }
    }

    @Test fun oneNativeBatchPerSpellingKeepsPinyinZhuyinAndPartialCoverageWithoutReplay() {
        val engine = fixture.engine
        var partialCandidates = 0
        val cases = listOf("yes", "shide", "soudesu", "nihao", "hanyu", "zhongguo", "nihaozhongguo",
            "nihaozzz", "xian", "xi'an", "ni'hao", "NIHAO", "a", "zzz", "nihao'", "'nihao", "ni''hao",
            "ㄋㄧˇㄏㄠˇ", "ㄏㄢˋㄩˇ", "ㄓㄨㄥㄨㄣˊ", "ㄋㄏ", "ㄋㄧㄏㄠ", "ㄋㄧˇ ㄏㄠˇ", "˙ㄇㄚ", "ㄇㄚ˙",
            "ˊㄋㄧ", "ˇ", "ㄋㄧˇ", "ㄋㄧˇ'ㄏㄠˇ")
        try {
            for (conversion in listOf(false, true)) {
                engine.configure(EngineQueryPolicy(mapOf(EngineFeature.SCRIPT_CONVERSION to conversion)))
                for (raw in cases) {
                    val before = fixture.queries.calls.size
                    val actual = engine.query(raw)
                    val calls = fixture.queries.calls.drop(before)
                    if (!engine.acceptsInput(raw)) {
                        assertTrue(actual.isEmpty())
                        assertTrue(calls.isEmpty())
                        continue
                    }
                    assertEquals("One native query, no replay: $raw", 1, calls.size)
                    val call = calls.single()
                    val rows = RimeCandidateBatch.decode(requireNotNull(call.packet), call.input.toByteArray().size)
                    assertTrue(rows.size <= call.limit)
                    rows.forEachIndexed { index, row ->
                        assertEquals(index, row.rank)
                        assertTrue(row.startByte in 0..row.endByte)
                        assertTrue(row.endByte + row.committedInputBytes <= call.input.length)
                    }
                    partialCandidates += rows.count { it.endByte + it.committedInputBytes < call.input.length }
                    actual.forEach { candidate -> candidate.consumption?.let { assertTrue(it.validFor(raw)) } }
                    if (raw in listOf("nihao", "ni'hao", "NIHAO", "ㄋㄧˇㄏㄠˇ", "ㄋㄧㄏㄠ"))
                        assertTrue("Greeting must remain available: $raw", actual.any { it.text == "你好" })
                    if (raw == "'nihao") {
                        assertTrue(actual.isNotEmpty())
                        assertTrue(actual.all { it.text.first() in "‘’" })
                        assertEquals(1, actual.map { it.text.first() }.distinct().size)
                        assertTrue(actual.any { it.text.drop(1) == "你好" })
                    }
                }
            }
            assertTrue("Exercise real partial candidates", partialCandidates > 0)
        } finally { engine.configure(EngineQueryPolicy()) }
    }

    @Test fun staleInputPacketsFailSafelyAndInterleavedQueriesDoNotLeavePriorComposition() {
        val engine = fixture.engine
        val baseline = engine.query("nihao")
        assertTrue(baseline.any { it.text == "你好" })
        val packet = requireNotNull(fixture.queries.calls.last().packet)
        assertThrows(IllegalStateException::class.java) { RimeCandidateBatch.decode(packet, "unexpected".length) }
        for (raw in listOf("nihaozzz", "ㄇㄚˇ", "ˊㄋㄧ", "zzz")) engine.query(raw)
        assertEquals(baseline, engine.query("nihao"))
        assertArrayEquals(packet, fixture.queries.calls.last().packet)
        assertTrue(engine.query("ˊㄋㄧ").isEmpty())
    }
}
