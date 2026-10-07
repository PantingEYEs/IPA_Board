package com.example.ipa_board

import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test

class MixedCompositionCandidatesTest {
    private data class Request(val raw: String, val before: String, val after: String)
    private class Engine(private val answer: (String) -> List<Candidate>) : QueryEngine {
        val requests = mutableListOf<Request>()
        override fun query(raw: String) = answer(raw)
        override fun query(raw: String, beforeCursor: String, afterCursor: String): List<Candidate> {
            requests.add(Request(raw, beforeCursor, afterCursor))
            return answer(raw)
        }
    }
    private fun query(engine: Engine, raw: String, before: String = "", after: String = "", prediction: Boolean = false) =
        MixedCompositionCandidates.query(engine, raw, before, after, prediction)

    @Test fun ordinaryWordsRetainNativeCandidatesOrderingAndMetadata() {
        val native = listOf(Candidate("don't", "EN", 3, CandidateKind.EXACT, 109, 0.25f),
            Candidate("doesn't", "EN", 8, CandidateKind.CORRECTION, 78))
        val engine = Engine { native }
        assertEquals(native, query(engine, "don't", "I ", " know"))
        assertEquals(listOf(Request("don't", "I ", " know")), engine.requests)
    }

    @Test fun terminalRomajiApostropheRetainsNativeInput() {
        val native = listOf(Candidate("ん", "日"))
        val engine = Engine { native }
        assertEquals(native, query(engine, "n'"))
        assertEquals("n'", engine.requests.single().raw)
    }

    @Test fun preservesChineseEmojiNumbersAndLeadingAndTrailingPunctuation() {
        val engine = Engine { listOf(Candidate("hello", "EN", 0, CandidateKind.COMPLETION, 112, 0.5f)) }
        val candidates = query(engine, "\"中文😀hel123,\\\n\"")
        assertEquals("\"中文😀hello123,\\\n\"", candidates.single().text)
        assertEquals(CandidateKind.COMPLETION, candidates.single().kind)
        assertEquals(112, candidates.single().nativeScore)
        assertEquals(0.5f, candidates.single().contextAffinity)
        assertEquals("hel", engine.requests.single().raw)
    }

    @Test fun convertsSeveralRunsWithoutMixingSimplifiedAndTraditionalForms() {
        val engine = Engine { raw -> when (raw) {
            "hanyu" -> listOf(Candidate("汉语", "简"), Candidate("漢語", "繁"))
            "xuexi" -> listOf(Candidate("学习", "简"), Candidate("學習", "繁"))
            else -> emptyList()
        } }
        val candidates = query(engine, "hanyu123+xuexi!")
        assertEquals(setOf("汉语123+学习!", "漢語123+學習!"), candidates.map { it.text }.toSet())
        assertTrue(candidates.all { it.kind == CandidateKind.CONVERSION })
        assertEquals(listOf("hanyu", "xuexi"), engine.requests.map { it.raw })
    }

    @Test fun unsupportedRunsStayLiteralWhileRecognizedRunsProduceCandidates() {
        val engine = Engine { if (it == "hel") listOf(Candidate("hello", "EN")) else emptyList() }
        assertEquals("xyz+hello!", query(engine, "xyz+hel!").single().text)
    }

    @Test fun internalApostrophesAreQueriedButSurroundingQuotesAreLiteral() {
        val engine = Engine { listOf(Candidate("don't", "EN", kind = CandidateKind.EXACT)) }
        assertEquals("['don't']", query(engine, "['don't']").single().text)
        assertEquals("don't", engine.requests.single().raw)
    }

    @Test fun eachWordReceivesItsLocalCompositionContextWithinTheCursorBudget() {
        val engine = Engine { listOf(Candidate(it.uppercase(), "EN")) }
        query(engine, "thank123you!", "x".repeat(300), "y".repeat(300))
        assertEquals(listOf("thank", "you"), engine.requests.map { it.raw })
        assertEquals("x".repeat(256), engine.requests[0].before)
        assertEquals(("123you!" + "y".repeat(300)).take(256), engine.requests[0].after)
        assertEquals(("x".repeat(300) + "thank123").takeLast(256), engine.requests[1].before)
        assertEquals(("!" + "y".repeat(300)).take(256), engine.requests[1].after)
    }

    @Test fun numericAndSymbolOnlyCompositionsDoNotTriggerWordPredictions() {
        val engine = Engine { listOf(Candidate("hello", "EN", kind = CandidateKind.PREDICTION)) }
        assertTrue(query(engine, "100/4😀中文", prediction = true).isEmpty())
        assertTrue(engine.requests.isEmpty())
        assertTrue(query(engine, "x".repeat(65)).isEmpty())
        assertTrue(engine.requests.isEmpty())
    }

    @Test fun emptyInputUsesTheEnginePredictionCapabilityAndPreservesCursorContext() {
        val native = listOf(Candidate("you", "EN", kind = CandidateKind.PREDICTION))
        val engine = Engine { native }
        assertTrue(query(engine, "", "thank ", "", false).isEmpty())
        assertTrue(engine.requests.isEmpty())
        assertEquals(native, query(engine, "", "thank ", "", true))
        assertEquals(Request("", "thank ", ""), engine.requests.single())
    }

    @Test fun segmentAndBeamBudgetsBoundWorkWithoutDroppingAnyLiteralText() {
        val engine = Engine { listOf(Candidate("A", "EN")) }
        val raw = "a1".repeat(12)
        assertEquals("a1".repeat(4) + "A1".repeat(8), query(engine, raw).single().text)
        assertEquals(8, engine.requests.size)
        val many = Engine { word -> (0..30).map { Candidate("$word$it", "EN", it) } }
        val candidates = query(many, "a+b+c!")
        assertTrue(candidates.size <= 60)
        assertEquals("a0+b0+c0!", candidates.first().text)
        assertTrue(candidates.all { it.text.endsWith("!") && it.text.count { c -> c == '+' } == 2 })
        assertEquals(candidates.indices.toList(), candidates.map { it.rank })
    }

    @Test fun singleMixedWordRetainsCandidatesBeyondTheMultiwordBeamChoiceBudget() {
        val engine = Engine { (0..20).map { Candidate("word$it", "EN", it) } }
        assertEquals(21, query(engine, "hel!").size)
        assertTrue(query(engine, "hel!").any { it.text == "word20!" })
    }

    @Test fun invalidSegmentCandidatesCannotBecomeCompositionReplacements() {
        val engine = Engine { listOf(Candidate("next", "EN", kind = CandidateKind.PREDICTION),
            Candidate("", "EN"), Candidate("bad", "EN", -1), Candidate("x".repeat(1001), "EN")) }
        assertTrue(query(engine, "hel!").isEmpty())
    }
}
