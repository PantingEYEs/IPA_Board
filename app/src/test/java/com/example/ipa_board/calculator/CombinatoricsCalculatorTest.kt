package com.example.ipa_board.calculator

import org.junit.Assert.*
import org.junit.Test

class CombinatoricsCalculatorTest {
    private fun forms(input: String, vararg expected: String) =
        assertEquals(input, expected.toList(), CalculatorEvaluator.resultForms(input))

    @Test fun factorialHandlesZeroGroupsAndPostfixPrecedence() {
        forms("0!", "1")
        forms("1!", "1")
        forms("5!", "120")
        forms("(2+3)!", "120")
        forms("-3!", "-6")
        forms("2^3!", "64")
        forms("(2^3)!", "40320")
        forms("3!%", "0.06")
        forms("3!2", "12")
        forms("3!(1+1)", "12")
        forms("sqrt(4)!", "2", "2")
    }

    @Test fun permutationsAndCombinationsAcceptExpressionsAndNestedFunctions() {
        forms("perm(5,2)", "20")
        forms("comb(5,2)", "10")
        forms("perm(0,0)", "1")
        forms("comb(0,0)", "1")
        forms("perm(5,0)", "1")
        forms("comb(5,5)", "1")
        forms("comb ([2+3],{1+1})", "10")
        forms("perm(comb(4,2),2)", "30")
        forms("pow(comb(5,2),2)", "100")
        forms("comb(5,2)!", "3628800")
        forms("2comb(5,2)", "20")
        forms("comb(100,50)", "100891344545564193334812497256")
        forms("perm(10000,1)", "10000")
    }

    @Test fun exactSymbolicArgumentsAndCandidateCountContractsArePreserved() {
        forms("comb(√4+3,2)", "10", "10")
        forms("(i*i+2)!", "1", "1")
        forms("comb(5,2)+i", "10+(1)i", "10+(1)i")
        assertEquals(3, CalculatorEvaluator.resultForms("comb(5,2)+φ").size)
        assertEquals(listOf("1"), CalculatorCandidates.forInput("1!", true).map { it.text })
        assertEquals(listOf("1"), CalculatorCandidates.forInput("comb(5,0)", true).map { it.text })
        assertTrue(CalculatorCandidates.forInput("5!", false).isEmpty())
    }

    @Test fun invalidArgumentsIncompleteCallsAndExcessiveWorkReturnNoCandidates() {
        for (input in listOf("!", "!5", "(-1)!", "0.5!", "i!", "π!", "φ!", "comb(5,-1)",
            "perm(-5,2)", "comb(2,3)", "perm(5,1.5)", "comb(5,i)", "comb(5)", "perm()",
            "comb(5,)", "perm(,2)", "comb(5,2,1)", "comb(5,(2,1))", "perm(5,2", "comb",
            "perm[5,2]", "10000!", "10001!", "comb(10000,5000)")) {
            assertTrue(input, CalculatorEvaluator.resultForms(input).isEmpty())
            assertTrue(input, CalculatorCandidates.forInput(input, true).isEmpty())
        }
        forms("5!+", "120")
        assertTrue(CalculatorCandidates.merge("comb(5,)", true, CalculatorCandidates.forInput("5!", true)).isEmpty())
    }

    @Test fun smallDomainMatchesIndependentFactorialIdentities() {
        fun factorial(n: Int): Long = (1..n).fold(1L) { a, b -> a * b }
        for (n in 0..12) for (r in 0..n) {
            forms("perm($n,$r)", (factorial(n) / factorial(n-r)).toString())
            forms("comb($n,$r)", (factorial(n) / factorial(r) / factorial(n-r)).toString())
        }
    }
}
