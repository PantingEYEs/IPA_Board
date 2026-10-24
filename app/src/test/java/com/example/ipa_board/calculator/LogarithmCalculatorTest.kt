package com.example.ipa_board.calculator

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class LogarithmCalculatorTest {
    private fun forms(input: String, vararg expected: String) =
        assertEquals(input, expected.toList(), CalculatorEvaluator.resultForms(input))

    @Test fun logarithmsRequireAnExplicitBaseAndSimplifyProvenRationalResults() {
        forms("log(8,2)", "3", "3")
        forms("log(100,10)", "2", "2")
        forms("log(2,4)", "0.5", "1/2")
        forms("log(4,8)", "0.6667…", "2/3")
        forms("log(1/8,2)", "-3", "-3")
        forms("log(8,1/2)", "-3", "-3")
        forms("log(1,2)", "0", "0")
        forms("log(e,e)", "1", "1")
        forms("log(φ,φ)", "1", "1", "1")
    }

    @Test fun logarithmsComposeWithExistingFunctionsGroupsAndPostfixOperations() {
        forms("log ([2+2],{1+1})", "2", "2")
        forms("log(perm(4,2)+comb(4,2)-2,2)", "4", "4")
        forms("pow(2,log(8,2))", "8", "8")
        forms("log(8,pow(2,1))", "3", "3")
        forms("log(8,2)!", "6", "6")
        forms("log(100,10)%", "0.02", "1/50")
        forms("2log(8,2)", "6", "6")
        forms("log(log(256,2),2)", "3", "3")
    }

    @Test fun principalComplexValuesPreserveParenthesizedImaginaryCoefficients() {
        forms("log(-1,e)", "(3.1416…)i", "(π)i")
        forms("log(i,e)", "(1.5708…)i", "(π/2)i")
        forms("log(-i,e)", "(-1.5708…)i", "(-π/2)i")
        forms("log(-1,i)", "2", "2")
        forms("log(i,-1)", "0.5", "1/2")
        forms("log(-1,10)", "(1.3644…)i", "log(-1,10)")
        forms("log(2,e)", "0.6931…", "log(2,e)")
        // Do not simplify log(z^w,b) to w*log(z,b) across a principal branch cut.
        forms("log((-1)^2,e)", "0", "0")
        forms("2*log(-1,e)", "(6.2832…)i", "(2π)i")
    }

    @Test fun formalLogarithmsRemainReenterableThroughMixedArithmetic() {
        for (input in listOf("log(2,e)", "log(1+i,2)", "log(-2-3i,1-2i)", "log(2+3i,-2+i)",
            "log(2,e)+1", "sqrt(log(2,e))", "log(2,e)^i", "1/log(2,e)", "log(φ,e)", "log(2,φ)")) {
            val result = CalculatorEvaluator.resultForms(input)
            assertTrue(input, result.size >= 2)
            if ('φ' in input) assertEquals(input, 3, result.size)
            for (exact in result.drop(1)) {
                assertFalse(exact, '#' in exact)
                assertEquals("$input => $exact", result.first(), CalculatorEvaluator.evaluate(exact))
            }
        }
    }

    @Test fun malformedCallsAndUndefinedDomainsProduceNoCandidatesAndClearOldOverlay() {
        for (input in listOf("log", "log()", "log(2)", "log(2,)", "log(,2)", "log(2,3,4)",
            "log(2,(3,4))", "log(2,3", "log[2,3]", "log(0,2)", "log(2,0)", "log(2,1)",
            "log(1,1)", "log(2,i*i+2)", "log(0,0)")) {
            assertTrue(input, CalculatorEvaluator.resultForms(input).isEmpty())
            assertTrue(input, CalculatorCandidates.forInput(input, true).isEmpty())
        }
        val previous = CalculatorCandidates.forInput("log(8,2)", true)
        assertEquals(listOf("3", "3"), previous.map { it.text })
        assertTrue(CalculatorCandidates.forInput("log(8,2)", false).isEmpty())
        assertTrue(CalculatorCandidates.merge("log(2)", true, previous).isEmpty())
    }

    @Test fun numericalLogarithmsAgreeWithIndependentRealAndComplexOracles() {
        fun value(real: String, imaginary: String = "0") = DecimalPowers.Value(BigDecimal(real), BigDecimal(imaginary))
        for (x in listOf("0.001", "0.5", "2", "10", "123.45")) for (b in listOf("0.1", "2", "10")) {
            val actual = DecimalPowers.logarithm(value(x), value(b))
            assertEquals(Math.log(x.toDouble())/Math.log(b.toDouble()), actual.real.toDouble(), 1e-12)
            assertEquals(0, actual.imaginary.signum())
        }
        // Fixed independent Python cmath references in multiple argument quadrants.
        for (s in listOf(
            listOf("2","3","-2","1","0.46859150151595236","-0.338091409518686"),
            listOf("-2","-3","1","-2","1.8267496953074176","-0.16939497497725636"))) {
            val actual = DecimalPowers.logarithm(value(s[0],s[1]), value(s[2],s[3]))
            assertEquals(s[4].toDouble(), actual.real.toDouble(), 1e-12)
            assertEquals(s[5].toDouble(), actual.imaginary.toDouble(), 1e-12)
        }
        val upper = DecimalPowers.logarithm(value("-2"), value("2"))
        val lower = DecimalPowers.logarithm(value("-2", "-0.000000000000000000000000000001"), value("2"))
        assertEquals(upper.real.toDouble(), lower.real.toDouble(), 1e-12)
        assertEquals(upper.imaginary.toDouble(), -lower.imaginary.toDouble(), 1e-12)
    }
}
