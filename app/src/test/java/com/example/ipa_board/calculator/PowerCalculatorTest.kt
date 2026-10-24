package com.example.ipa_board.calculator

import org.junit.Assert.*
import org.junit.Test

class PowerCalculatorTest {
    private fun forms(input: String, vararg expected: String) =
        assertEquals(input, expected.toList(), CalculatorEvaluator.resultForms(input))

    @Test fun caretAndPowUseTheSameBinaryOperation() {
        for (input in listOf("2^3", "pow(2,3)", "pow ( 2 , 3 )")) forms(input, "8")
        forms("pow(2,pow(3,2))", "512")
        forms("pow(2+1,2*3)", "729")
        forms("pow([1+2],{2+1})", "27")
        forms("pow(2,3)^2", "64")
    }

    @Test fun powersAssociateRightAndBindAboveNegationBelowExplicitRootFunctions() {
        forms("2^3^2", "512")
        forms("(2^3)^2", "64")
        forms("-2^2", "-4")
        forms("(-2)^2", "4")
        forms("2^-3", "0.125")
        forms("-2^-2", "-0.25")
        forms("2^-3^2", "0.002…")
        forms("2*3^2+1", "19")
        forms("sqrt(-1)^2", "-1", "-1")
    }

    @Test fun zeroNegativeAndIdentityIntegerExponentsRemainExact() {
        forms("2^0", "1")
        forms("2^1", "2")
        forms("0^2", "0")
        forms("pow(-2,-3)", "-0.125")
        forms("(1+i)^2", "(2)i", "(2)i")
        forms("i^-1", "(-1)i", "(-1)i")
        assertEquals(listOf("2"), CalculatorCandidates.forInput("2^1", true).map { it.text })
    }

    @Test fun rationalPowersUseExactRootsBeforeNumericalFallback() {
        forms("2^(1/2)", "1.4142…", "√2")
        forms("pow(16,3/4)", "8", "8")
        forms("16^(-1/2)", "0.25", "1/4")
        forms("8^(1/3)", "2", "2")
        forms("(8/27)^(1/3)", "0.6667…", "2/3")
        forms("(-8)^(1/3)", "1+(1.7321…)i", "1+(√3)i")
        forms("(-1)^(1/2)", "(1)i", "(1)i")
        forms("(2^(1/3))^3", "2", "2")
    }

    @Test fun principalBranchesAreNotChangedByUnsafePowerReassociation() {
        forms("((-1)^2)^(1/2)", "1", "1")
        forms("(-1)^(2*(1/2))", "-1")
        forms("(-1)^(-1/2)", "(-1)i", "(-1)i")
    }

    @Test fun transcendentalAndComplexExponentsProduceWholeDecimalAndExactPowerForms() {
        forms("2^π", "8.825…", "(2)^(π)")
        forms("e^π", "23.1407…", "(e)^(π)")
        forms("2^i", "0.7692…+(0.639…)i", "(2)^((1)i)")
        forms("i^i", "0.2079…", "((1)i)^((1)i)")
        forms("(1+i)^(1+i)", "0.274…+(0.5837…)i", "(1+(1)i)^(1+(1)i)")
        forms("2^(1/3)", "1.2599…", "(2)^(1/3)")
        forms("1^π", "1", "1")
    }

    @Test fun eulerIdentityAndConstructiblePhasesAreExactRatherThanResidualImaginaryParts() {
        forms("e^(iπ)", "-1", "-1")
        forms("e^(iπ)+1", "0", "0")
        forms("e^(iπ/2)", "(1)i", "(1)i")
        forms("pow(e,-iπ/2)", "(-1)i", "(-1)i")
        forms("e^(iπ/3)", "0.5+(0.866…)i", "1/2+(√3/2)i")
    }

    @Test fun phiStillProvidesExactlyThreeFormsIncludingInsideAnExponent() {
        forms("φ^2", "2.618…", "(3+√5)/2", "1+φ")
        forms("2^φ", "3.0696…", "(2)^((1+√5)/2)", "(2)^(φ)")
        assertEquals(3, CalculatorCandidates.forInput("pow(φ,2)", true).size)
    }

    @Test fun formalPowerResultsStayReenterableThroughAllArithmeticAndRoots() {
        for (input in listOf("2^π", "i^i", "2^i+1", "(2^i)*i", "(2^π)/2", "-(2^i)",
            "sqrt(2^i)", "sqrt(2^π)", "2^φ", "(-2)^(1/3)", "(1+i)^(1+i)")) {
            val result = CalculatorEvaluator.resultForms(input)
            assertTrue(input, result.size >= 2)
            for (exact in result.drop(1)) {
                assertFalse(exact, exact.contains('#'))
                assertEquals("$input => $exact", result.first(), CalculatorEvaluator.evaluate(exact))
            }
        }
    }

    @Test fun incompleteFunctionsUndefinedValuesAndCapacityFailuresNeverSilentlyDropPower() {
        for (input in listOf("2^", "2^-", "^2", "2^^3", "2**3", "pow", "pow()", "pow(2)", "pow(2,)",
            "pow(,2)", "pow(2,3,4)", "pow(2,(3,4))", "pow(2,3", "0^0", "0^i", "2^1000000", "2^10000")) {
            assertTrue(input, CalculatorEvaluator.resultForms(input).isEmpty())
        }
        assertEquals("除数不能为0", CalculatorEvaluator.evaluate("0^-1"))
        forms("2^3+", "8")
        val old = CalculatorCandidates.forInput("2^π", true)
        assertTrue(CalculatorCandidates.merge("2^", true, old).isEmpty())
        assertTrue(CalculatorCandidates.merge("2^π", false, old).isEmpty())
    }
}
