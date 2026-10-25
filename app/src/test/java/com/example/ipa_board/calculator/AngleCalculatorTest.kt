package com.example.ipa_board.calculator

import org.junit.Assert.*
import org.junit.Test

class AngleCalculatorTest {
    private fun forms(input: String, vararg expected: String) =
        assertEquals(input, expected.toList(), CalculatorEvaluator.resultForms(input))

    @Test fun degreeAndInverseResultsProvideSixOrderedFormsIncludingDuplicateTexts() {
        val thirty = arrayOf("30°", "30°", "0.5236…", "π/6", "0.5774…", "√3/3")
        forms("30°", *thirty)
        forms("asin(1/2)", *thirty)
        forms("90°-60°", *thirty)
        forms("atan(1)", "45°", "45°", "0.7854…", "π/4", "1", "1")
        forms("0°", "0°", "0°", "0", "0", "0", "0")
        forms("-30°", "-30°", "-30°", "-0.5236…", "-π/6", "-0.5774…", "-√3/3")
        assertEquals("12.5\u00b0", CalculatorEvaluator.resultForms("12.5\u00b0")[0])
        val candidates = CalculatorCandidates.forInput("30°", true)
        assertEquals(thirty.toList(), candidates.map { it.text })
        assertEquals(6,candidates.map { it.rank }.toSet().size)
    }

    @Test fun angleUnitsAreConsumedAndCancelledRatherThanContaminatingScalarResults() {
        forms("sin(30°)", "0.5", "1/2")
        forms("cos(60°)", "0.5", "1/2")
        forms("tan(45°)", "45°", "45°", "0.7854…", "π/4", "1", "1")
        forms("sin(asin(1/2))", "0.5", "1/2")
        forms("90°/30°", "3")
        forms("(30°)^2/(30°)^2", "1")
        val rooted = CalculatorEvaluator.resultForms("sqrt((30°)^2)")
        assertEquals(6,rooted.size)
        assertEquals("30…°",rooted[0]) // Existing symbolic π powers retain an unevaluated exact root.
        assertEquals("0.5236…",rooted[2])
        assertEquals("0.5774…",rooted[4])
        assertEquals(CalculatorEvaluator.resultForms("30°"), CalculatorEvaluator.resultForms("60°*50%"))
        assertEquals(2, CalculatorEvaluator.resultForms("sin(30)").size) // Bare arguments remain radians.
        assertEquals(6,CalculatorEvaluator.resultForms("30°*φ").size)
        assertEquals(3,CalculatorEvaluator.resultForms("sin(φ)").size)
    }

    @Test fun tangentResultsAreSlopesWithSixConvertedCandidates() {
        val fortyFive = arrayOf("45°", "45°", "0.7854…", "π/4", "1", "1")
        forms("tan(45°)", *fortyFive)
        forms("tan(π/4)", *fortyFive)
        forms("tan(225°)", *fortyFive) // A slope determines the principal angle, not full turns.
        forms("-tan(π/4)", "-45°", "-45°", "-0.7854…", "-π/4", "-1", "-1")
        forms("tan(0)", "0°", "0°", "0", "0", "0", "0")
        for (input in listOf("tan(π/4)+1", "2*tan(π/4)", "sqrt(tan(π/4))", "tan(φ)", "tan(i)")) {
            assertEquals(input,6,CalculatorEvaluator.resultForms(input).size)
        }
        assertEquals(listOf("2","2"), CalculatorEvaluator.resultForms("tan(π/4)+1").takeLast(2))
        assertEquals(2,CalculatorEvaluator.resultForms("sin(tan(π/4))").size)
        assertEquals(2,CalculatorEvaluator.resultForms("log(tan(π/4)+1,2)").size)
        forms("tan(0)+i", "Undefined", "Undefined", "Undefined", "Undefined", "(1)i", "(1)i")
        assertEquals(6,CalculatorCandidates.forInput("tan(π/4)",true).size)
    }

    @Test fun slopePolesAreExplicitPlaceholdersAndDoNotRemoveValidAngleForms() {
        forms("90°", "90°", "90°", "1.5708…", "π/2", "Undefined", "Undefined")
        forms("asin(1)", "90°", "90°", "1.5708…", "π/2", "Undefined", "Undefined")
        forms("270°", "270°", "270°", "4.7124…", "3π/2", "Undefined", "Undefined")
        assertTrue(CalculatorEvaluator.resultForms("tan(90°)").isEmpty())
    }

    @Test fun complexAndNonSpecialAngleFormsCanBeSelectedAndContinuedAsExpressions() {
        for (input in listOf("12.5°", "atan(2)", "asin(2)", "acos(-2)", "atan(2i)", "30°*φ")) {
            val result = CalculatorEvaluator.resultForms(input)
            assertEquals(input,6,result.size)
            assertEquals("$input degree exact", result.first(), CalculatorEvaluator.evaluate(result[1]))
            // Consuming the exact radian or degree form must yield the same dimensionless sine.
            val sine = CalculatorEvaluator.evaluate("sin($input)")
            assertEquals("$input radians",sine,CalculatorEvaluator.evaluate("sin(${result[3]})"))
            assertEquals("$input degrees",sine,CalculatorEvaluator.evaluate("sin(${result[1]})"))
            assertFalse(input,result.any { '#' in it })
        }
    }

    @Test fun invalidDimensionsAndMalformedUnitInputsFallBackWithoutStaleMath() {
        for (input in listOf("°", "sin(°)", "30°+1", "30°+π", "asin(30°)", "log(30°,2)",
            "comb(30°,2)", "(30°)!", "(30°)^i", "(30°)^2")) {
            assertTrue(input,CalculatorEvaluator.resultForms(input).isEmpty())
        }
        assertTrue(CalculatorCandidates.forInput("30°",false).isEmpty())
        assertTrue(CalculatorCandidates.merge("°",true,CalculatorCandidates.forInput("30°",true)).isEmpty())
    }
}
