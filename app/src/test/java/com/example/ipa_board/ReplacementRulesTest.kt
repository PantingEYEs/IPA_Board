package com.example.ipa_board

import com.example.ipa_board.ime.*
import com.example.ipa_board.shortcuts.*
import org.junit.Assert.*
import org.junit.Test

class ReplacementRulesTest {
    @Test fun exactMatchesAppendInRuleOrderWithDuplicatesAndNoRecursion() {
        val original = listOf(Candidate("鞥", "简"), Candidate("嗯", "简"))
        val rules = listOf(ReplacementRule("1", "鞥", "嗯"), ReplacementRule("2", "鞥", "恩"),
            ReplacementRule("3", "嗯", "好"), ReplacementRule("4", "鞥", "嗯"))
        val result = ReplacementRules.append(original, rules)
        assertEquals(listOf("鞥", "嗯", "嗯", "恩", "好", "嗯"), result.map { it.text })
        assertEquals(6, result.map { it.id }.distinct().size)
        assertEquals(original, result.take(2))
        assertEquals(listOf("鞥", "嗯", "恩", "嗯"), ReplacementRules.append(original.take(1), rules).map { it.text })
        assertEquals(listOf("鞥啊"), ReplacementRules.append(listOf(Candidate("鞥啊", "简")), rules).map { it.text })
    }
    @Test fun refreshNeverAccumulatesAndAbsentSourcesRemoveAdditions() {
        val rules = listOf(ReplacementRule("1", "a", "b"))
        val base = listOf(Candidate("a", "EN"))
        val once = ReplacementRules.append(base, rules)
        assertEquals(once, ReplacementRules.append(once, rules))
        assertEquals(emptyList<Candidate>(), ReplacementRules.append(once.drop(1), rules))
        assertEquals(base, ReplacementRules.append(once, emptyList()))
    }
    @Test fun preferWholeCandidateOtherwiseKeepFirstNativeConsumption() {
        val prefix = Candidate("你好", "简", consumption = CandidateConsumption("nihaoshijie", 5, "Rime", "0"))
        val other = prefix.copy(consumption = prefix.consumption!!.copy(end = 2, selection = "1"))
        val whole = Candidate("你好", "日")
        val rules = listOf(ReplacementRule("1", "你好", "您好"))
        val full = ReplacementRules.append(listOf(prefix, whole), rules).last()
        assertNull(full.consumption); assertEquals("日", full.language)
        val partial = ReplacementRules.append(listOf(prefix, other, prefix), rules)
        assertEquals(4, partial.size); assertEquals(prefix.consumption, partial.last().consumption)
    }
    @Test fun originalReorderingRebuildsAfterTheNewLastCandidate() {
        val rules = listOf(ReplacementRule("1", "a", "replacement"))
        val a = Candidate("a", "EN"); val b = Candidate("b", "EN")
        assertEquals(listOf(b, a), ReplacementRules.append(listOf(b, a), rules).take(2))
        assertEquals("replacement", ReplacementRules.append(listOf(b, a), rules).last().text)
    }
}
