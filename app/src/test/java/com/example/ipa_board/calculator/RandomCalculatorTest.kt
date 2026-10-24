package com.example.ipa_board.calculator

import com.example.ipa_board.ime.Candidate
import java.math.BigDecimal
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class RandomCalculatorTest {
    private class CountingRandom(seed: Int) : Random() {
        private val delegate = Random(seed)
        var calls = 0
        override fun nextBits(bitCount: Int): Int { calls++; return delegate.nextBits(bitCount) }
    }
    private fun lower(input: String) = CalculatorEvaluator.resultForms(input, CalculatorSession(object : Random() {
        override fun nextBits(bitCount: Int) = 0
    }))

    @Test fun emptyOptionalAndReversedArgumentsHaveExplicitDefaults() {
        for (input in listOf("rand()", "rand(,)", "rand(5)", "rand(5,)", "rand(,5)"))
            assertEquals(input,listOf("0"),lower(input))
        for (input in listOf("rand(-5)", "rand(-5,)", "rand(,-5)"))
            assertEquals(input,listOf("-5"),lower(input))
        assertEquals(listOf("2"),lower("rand(2,5)"))
        assertEquals(listOf("2"),lower("rand(5,2)"))
        assertEquals(listOf("5"),lower("rand(5,5)"))
        assertEquals(listOf("0"),lower("rand(0,0)"))
    }

    @Test fun optionalArgumentsNestWithoutStealingOtherFunctionCommasOrOperations() {
        for ((input,expected) in mapOf(
            "rand(2+3)" to "0", "rand(2+3,)" to "0", "rand(,2+3)" to "0",
            "rand(-5+2)" to "-3", "rand(pow(2,3),comb(5,2))" to "8",
            "rand(rand(2,3),rand(4,5))" to "2", "rand(5,5)+rand(2,3)" to "7",
            "rand(2,3)+rand(4,5)" to "6", "2rand(2,3)" to "4",
            "pow(rand(2,3),2)" to "4", "rand([1+1],{2+1})" to "2")) {
            assertEquals(input,listOf(expected),lower(input))
        }
    }

    @Test fun samplesStayInsideHalfOpenIntervalsIncludingNegativeAndNarrowRanges() {
        val ranges = listOf("0" to "1", "-5" to "-2", "-1.2345" to "2.3456", "0.00001" to "0.00002")
        for ((a,b) in ranges) for (seed in 0..31) {
            val result = CalculatorEvaluator.resultForms("rand($b,$a)",CalculatorSession(Random(seed)))
            assertEquals(1,result.size)
            val value = BigDecimal(result.single())
            assertTrue(result.toString(),value >= BigDecimal(a) && value < BigDecimal(b))
        }
        assertEquals(listOf("0.00001"),lower("rand(0.00001,0.00002)"))
        assertEquals(listOf("-0.00002"),lower("rand(-0.00002,-0.00001)"))
        val constantRange = lower("rand(e,π)").single().toBigDecimal()
        assertTrue(constantRange >= BigDecimal("2.718281828459045") && constantRange < BigDecimal("3.141592653589794"))
    }

    @Test fun realOnlyArgumentsAndFinalResultsSuppressEveryExactAndConstantVariant() {
        assertEquals(listOf("2"),lower("rand(2,2)+sqrt(0)"))
        assertEquals(listOf("1.618…"),lower("rand(φ,φ)"))
        assertEquals(1,lower("rand(0,0)+φ").size)
        assertEquals(listOf("0"),lower("sin(rand(0,0))"))
        assertEquals(1,lower("asin(rand(0,0))").size)
        assertEquals(listOf("-1"),lower("rand(0,0)+i*i"))
        for (input in listOf("rand(i)", "rand(0,i)", "rand(i,i)", "rand(sqrt(-1),2)",
            "rand(0,1)+i", "sqrt(rand(-2,-1))", "rand(0,0)+log(-1,e)")) {
            assertTrue(input,lower(input).isEmpty())
        }
    }

    @Test fun candidateRefreshDoesNotRerollButEditsDisableAndSessionResetDo() {
        val source = CountingRandom(123)
        val session = CalculatorSession(source)
        val raw = " rand(2,5) "
        val first = CalculatorCandidates.forInput(raw,true,session)
        assertEquals(1,first.size)
        val afterFirst = source.calls
        assertTrue(afterFirst > 0)
        repeat(8) {
            assertEquals(first,CalculatorCandidates.forInput(raw,true,session))
            assertEquals(afterFirst,source.calls)
        }
        val language = Candidate("word","EN")
        assertEquals(first + language,CalculatorCandidates.merge(raw,true,listOf(language),session))
        assertEquals(afterFirst,source.calls)
        CalculatorCandidates.forInput("rand(2,5)+0",true,session)
        assertTrue(source.calls > afterFirst)
        val afterEdit = source.calls
        CalculatorCandidates.forInput(raw,false,session)
        CalculatorCandidates.forInput(raw,true,session)
        assertTrue(source.calls > afterEdit)
        val beforeReset = source.calls
        session.reset()
        CalculatorCandidates.forInput(raw,true,session)
        assertTrue(source.calls > beforeReset)
    }

    @Test fun sessionsAreIsolatedAndMalformedCallsClearOnlyTheirOwnOverlay() {
        val session = CalculatorSession(Random(77))
        val other = CalculatorSession(Random(77))
        val first = CalculatorCandidates.forInput("rand()",true,session)
        assertEquals(first,CalculatorCandidates.forInput("rand()",true,other))
        for (input in listOf("rand", "rand(1,2,3)", "rand(1,,2)", "rand(1,+)", "rand(,1+)",
            "rand(+)", "rand(())", "rand(1,(2,3))", "rand(1,2", "rand[1,2]", "rand(1°,2)")) {
            assertTrue(input,CalculatorCandidates.forInput(input,true,session).isEmpty())
        }
        assertEquals(first,CalculatorCandidates.forInput("rand()",true,other))
        assertTrue(CalculatorCandidates.merge("rand(1,+)",true,first,session).isEmpty())
        // Optional rand parameters must not accidentally become optional for existing functions.
        for (input in listOf("pow()", "pow(,)", "log(1,)", "comb(,2)", "sin()"))
            assertTrue(input,CalculatorEvaluator.resultForms(input).isEmpty())
    }
}
