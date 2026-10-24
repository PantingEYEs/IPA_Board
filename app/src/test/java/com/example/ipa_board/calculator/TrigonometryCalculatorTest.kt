package com.example.ipa_board.calculator

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class TrigonometryCalculatorTest {
    private fun forms(input: String, vararg expected: String) =
        assertEquals(input, expected.toList(), CalculatorEvaluator.resultForms(input))

    private fun slope(input: String, vararg expected: String) {
        val result = CalculatorEvaluator.resultForms(input)
        assertEquals(input, 6, result.size)
        assertEquals(input, expected.toList(), result.takeLast(2))
    }

    @Test fun exactSpecialAnglesAndInversePrincipalRanges() {
        forms("sin(π/6)", "0.5", "1/2")
        forms("cos(π/3)", "0.5", "1/2")
        forms("sin(-π/2)", "-1", "-1")
        forms("cos(π)", "-1", "-1")
        slope("tan(π/4)", "1", "1")
        slope("tan(π)", "0", "0")
        forms("asin(1/2)", "30°", "30°", "0.5236…", "π/6", "0.5774…", "√3/3")
        forms("acos(-1/2)", "120°", "120°", "2.0944…", "2π/3", "-1.7321…", "-√3")
        forms("atan(1)", "45°", "45°", "0.7854…", "π/4", "1", "1")
        forms("atan(-1)", "-45°", "-45°", "-0.7854…", "-π/4", "-1", "-1")
        forms("asin(0)", "0°", "0°", "0", "0", "0", "0")
    }

    @Test fun functionsComposeWithPowersPostfixOperatorsAndExistingFunctions() {
        forms("pow(sin(π/2),2)+cos(π)", "0", "0")
        forms("sin(π/2)!", "1", "1")
        forms("sin(π/2)%", "0.01", "1/100")
        forms("2sin(π/6)", "1", "1")
        forms("sin(asin(1/2))", "0.5", "1/2")
        slope("tan(atan(1))", "1", "1")
        slope("tan(atan(2))", "2", "2")
        slope("tan(atan(2i))", "(2)i", "(2)i")
        forms("sin(log(1,2))", "0", "0")
    }

    @Test fun complexFunctionsPreserveExactFormulaAndExistingCandidateCounts() {
        for (input in listOf("sin(1+i)", "cos(i)", 
            "sin(φ)", "cos(φ)+i", "sin(sqrt(asin(2))^2)",  "cos(1)^2", "log(sin(1),e)")) {
            val result = CalculatorEvaluator.resultForms(input)
            assertEquals(input, if ('φ' in input) 3 else 2, result.size)
            for (exact in result.drop(1)) {
                assertFalse(exact, '#' in exact)
                assertEquals("$input -> $exact", result.first(), CalculatorEvaluator.evaluate(exact))
            }
        }
        forms("sin(i)", "(1.1752…)i", "sin((1)i)")
        forms("cos(i)", "1.5431…", "cos((1)i)")
    }

    @Test fun undefinedPolesAndMalformedCallsNeverLeaveOldCandidates() {
        for (input in listOf("sin", "sin()", "sin(1,2)", "cos[0]", "atan(i)", "atan(-i)",
            "tan(π/2)", "tan(-π/2)", "tan(3π/2)", "asin(1,)", "sin(1", "sin(1001i)")) {
            assertTrue(input, CalculatorEvaluator.resultForms(input).isEmpty())
            assertTrue(input, CalculatorCandidates.forInput(input,true).isEmpty())
        }
        val previous = CalculatorCandidates.forInput("sin(0)",true)
        assertEquals(listOf("0","0"),previous.map { it.text })
        assertTrue(CalculatorCandidates.merge("tan(π/2)",true,previous).isEmpty())
        assertTrue(CalculatorCandidates.forInput("cos(0)",false).isEmpty())
    }

    @Test fun numericRealFunctionsMatchIndependentPlatformOracles() {
        for (x in listOf(-3.0,-0.5,0.0,0.25,1.0,3.0)) {
            val z = DecimalPowers.Value(BigDecimal.valueOf(x),BigDecimal.ZERO)
            val functions = mapOf("sin" to Math.sin(x),"cos" to Math.cos(x),"tan" to Math.tan(x),"atan" to Math.atan(x))
            for ((name,expected) in functions) {
                val actual = DecimalTrigonometry.evaluate(name,z)
                assertEquals("$name($x)",expected,actual.real.toDouble(),1e-12)
                assertEquals("$name($x)",0.0,actual.imaginary.toDouble(),1e-12)
            }
            if (x in -1.0..1.0) for ((name,expected) in mapOf("asin" to Math.asin(x),"acos" to Math.acos(x))) {
                assertEquals("$name($x)",expected,DecimalTrigonometry.evaluate(name,z).real.toDouble(),1e-12)
            }
        }
    }

    @Test fun complexNumericsMatchIndependentCmathReferenceAndCutLimits() {
        // Fixed Python cmath references; cover all six functions away from the cuts.
        val expected = listOf(
            Triple("sin",0.494485780933195,0.22168816414957482),
            Triple("cos",0.9051501505596068,-0.12110879604381165),
            Triple("tan",0.504500702698564,0.31242069250258875),
            Triple("asin",0.5016088532755008,0.28139605624529274),
            Triple("acos",1.069187473519396,-0.28139605624529274),
            Triple("atan",0.4842544903299662,0.20058661813123432))
        for ((name,re,im) in expected) {
            val actual = DecimalTrigonometry.evaluate(name,DecimalPowers.Value(BigDecimal("0.5"),BigDecimal("0.25")))
            assertEquals(name,re,actual.real.toDouble(),1e-12)
            assertEquals(name,im,actual.imaginary.toDouble(),1e-12)
        }
        val upper = DecimalTrigonometry.evaluate("asin",DecimalPowers.Value(BigDecimal(2),BigDecimal.ZERO))
        val lower = DecimalTrigonometry.evaluate("asin",DecimalPowers.Value(BigDecimal(2),BigDecimal("-0.000000000000000000000000000001")))
        assertEquals(Math.PI/2,upper.real.toDouble(),1e-12)
        assertEquals(1.3169578969248166,upper.imaginary.toDouble(),1e-12)
        assertEquals(-upper.imaginary.toDouble(),lower.imaginary.toDouble(),1e-12)
        val at = DecimalTrigonometry.evaluate("atan",DecimalPowers.Value(BigDecimal.ZERO,BigDecimal(2)))
        assertEquals(Math.PI/2,at.real.toDouble(),1e-12)
        assertEquals(0.5493061443340549,at.imaginary.toDouble(),1e-12)
    }
}
