package com.example.ipa_board.calculator

import org.junit.Assert.*
import org.junit.Test

class ConstantComplexCalculatorTest {
    private fun forms(input: String, vararg expected: String) =
        assertEquals(input, expected.toList(), CalculatorEvaluator.resultForms(input))

    @Test fun naturalAndCircularConstantsHaveDecimalAndSymbolicCandidatesWithoutDigits() {
        forms("e", "2.7183…", "e")
        forms("π", "3.1416…", "π")
        forms("e+π", "5.8599…", "e+π")
        forms("2π", "6.2832…", "2π")
        forms("π/2", "1.5708…", "π/2")
        forms("π+√2", "4.5558…", "π+√2")
        assertEquals(2, CalculatorCandidates.forInput("e", true).size)
        assertEquals(2, CalculatorCandidates.forInput("π", true).size)
    }

    @Test fun goldenRatioAlwaysAddsExactlyOneSymbolicForm() {
        forms("φ", "1.618…", "(1+√5)/2", "φ")
        forms("φ+φ", "3.2361…", "1+√5", "2φ")
        forms("φ*φ", "2.618…", "(3+√5)/2", "1+φ")
        forms("φ*φ-φ", "1", "1", "1")
        forms("φ-φ", "0", "0", "0")
        forms("φ*√5", "3.618…", "(5+√5)/2", "√5×φ")
        assertEquals(3, CalculatorCandidates.forInput("φ", true).size)
        assertEquals(3, CalculatorCandidates.forInput("φ-φ", true).distinct().size)
    }

    @Test fun symbolArithmeticCombinesTermsAndCancelsProductsWithoutApproximation() {
        forms("e+e", "5.4366…", "2e")
        forms("e-e", "0", "0")
        forms("π/π", "1", "1")
        forms("(e*π)/e", "3.1416…", "π")
        forms("(e+π)-(π+e)", "0", "0")
        forms("2e+3e", "13.5914…", "5e")
        forms("1/(e+π)", "0.1707…", "1/(e+π)")
        val positive = CalculatorEvaluator.resultForms("√((-e)/(e-π))")
        assertEquals(2, positive.size)
        assertEquals("2.5341…", positive.first())
        assertEquals(positive.first(), CalculatorEvaluator.evaluate(positive.last()))
    }

    @Test fun rootsContainingConstantsStaySymbolicWhileTheWholeResultBecomesDecimal() {
        forms("√e", "1.6487…", "√e")
        forms("√(4π)", "3.5449…", "2√π")
        forms("√(π*π)", "3.1416…", "π")
        forms("1/√π", "0.5642…", "√π/π")
        assertEquals(3, CalculatorEvaluator.resultForms("(1+√φ)/2").size)
        assertFalse(CalculatorEvaluator.resultForms("(1+√φ)/2").first().contains('√'))
        assertFalse(CalculatorEvaluator.resultForms("(1+√φ)/2").first().contains('/'))
    }

    @Test fun negativeRealRootsUseOnlyImaginaryUnitAndSimplifiedPositiveRadicals() {
        forms("√(-1)", "(1)i", "(1)i")
        forms("sqrt(-4)", "(2)i", "(2)i")
        forms("√(-2)", "(1.4142…)i", "(√2)i")
        forms("√(-0.5)", "(0.7071…)i", "(√2/2)i")
        forms("√(-2)*√(-3)", "-2.4495…", "-√6")
        forms("√(-π)", "(1.7725…)i", "(√π)i")
        for ((input, decimal) in listOf("√(1-√2)" to "(0.6436…)i", "√(√(2+√2)-2)" to "(0.3902…)i")) {
            val result = CalculatorEvaluator.resultForms(input)
            assertEquals(input, 2, result.size)
            assertEquals(input, decimal, result.first())
            assertTrue(result.last(), result.last().endsWith(")i"))
            assertEquals(decimal, CalculatorEvaluator.evaluate(result.last()))
        }
    }

    @Test fun complexArithmeticAndPrincipalRootsSimplifyBothComponents() {
        forms("i", "(1)i", "(1)i")
        forms("i*i", "-1", "-1")
        forms("i*i*i*i", "1", "1")
        forms("(1+i)*(1-i)", "2", "2")
        forms("(1+i)/(1-i)", "(1)i", "(1)i")
        forms("i/2", "(0.5)i", "(1/2)i")
        forms("sqrt(i)", "0.7071…+(0.7071…)i", "√2/2+(√2/2)i")
        forms("sqrt(3+4i)", "2+(1)i", "2+(1)i")
        forms("sqrt(-3-4i)", "1-(2)i", "1-(2)i")
        forms("sqrt(sqrt(-16))", "1.4142…+(1.4142…)i", "√2+(√2)i")
        forms("ei", "(2.7183…)i", "(e)i")
    }

    @Test fun unknownLettersStayIgnoredIncludingInRadicalExpressions() {
        forms("abc1+2xyz", "3")
        forms("a√(-4)b", "(2)i", "(2)i")
        forms("aφb", "1.618…", "(1+√5)/2", "φ")
        forms("√2abc", "1.4142…", "√2")
        assertTrue(CalculatorEvaluator.resultForms("xyz").isEmpty())
        assertTrue(CalculatorCandidates.forInput("xyz", true).isEmpty())
    }

    @Test fun exactConstantAndComplexFormsRemainValidInputForFurtherArithmetic() {
        for (input in listOf("e+π", "φ", "√(-2)", "sqrt(i)", "√(-π)", "φi", "1/√π")) {
            val result = CalculatorEvaluator.resultForms(input)
            assertTrue(input, result.size >= 2)
            for (exact in result.drop(1)) {
                assertEquals("$input -> $exact", result.first(), CalculatorEvaluator.evaluate(exact))
            }
        }
    }

    @Test fun divisionByZeroInvalidSyntaxAndCapacityFailuresRetainLiteralInput() {
        for (input in listOf("e/(π-π)", "i/(i-i)", "φ/0", "1/(e-e)")) {
            assertEquals(input, "除数不能为0", CalculatorEvaluator.evaluate(input))
            assertTrue(CalculatorEvaluator.resultForms(input).isEmpty())
        }
        for (input in listOf("sqrt()", "√(φ", "√".repeat(20)+"e")) {
            assertTrue(input, CalculatorEvaluator.resultForms(input).isEmpty())
        }
        val previous = CalculatorCandidates.forInput("φ", true)
        assertTrue(CalculatorCandidates.merge("φ", false, previous).isEmpty())
        assertTrue(CalculatorCandidates.merge("√", true, previous).isEmpty())
    }
}
