package com.example.ipa_board.calculator

import com.example.ipa_board.ime.Candidate
import org.junit.Assert.*
import org.junit.Test

class RadicalCalculatorTest {
    private fun forms(input: String, decimal: String, exact: String) =
        assertEquals(input, listOf(decimal, exact), CalculatorEvaluator.resultForms(input))

    @Test fun bothRootNotationsAndAllGroupStylesShareSemantics() {
        for (input in listOf("√2", "sqrt(2)", "sqrt (2)", "√(1+1)", "√[1+1]", "√{1+1}")) {
            forms(input, "1.4142…", "√2")
        }
        forms("√12", "3.4641…", "2√3")
        forms("√2+3", "4.4142…", "3+√2")
        forms("sqrt(2+3)", "2.2361…", "√5")
    }

    @Test fun rationalResultsStillHaveTwoRepresentations() {
        forms("√4", "2", "2")
        forms("√0", "0", "0")
        forms("√2*√2", "2", "2")
        forms("√2-√2", "0", "0")
        forms("√(1/4)", "0.5", "1/2")
        forms("√4/3", "0.6667…", "2/3")
    }

    @Test fun squareFactorsLikeTermsProductsAndFractionsAreSimplified() {
        forms("√8+√2", "4.2426…", "3√2")
        forms("√2*√3", "2.4495…", "√6")
        forms("√6-√2*√3", "0", "0")
        forms("√72", "8.4853…", "6√2")
        forms("√0.5", "0.7071…", "√2/2")
        forms("√(8/9)", "0.9428…", "2√2/3")
        forms("2√8-3sqrt(2)", "1.4142…", "√2")
        forms("-√2*3", "-4.2426…", "-3√2")
    }

    @Test fun conjugatesRationalizeSingleAndMultipleRadicalDenominators() {
        forms("1/√2", "0.7071…", "√2/2")
        forms("1/(1+√2)", "0.4142…", "-1+√2")
        forms("1/(√2+√3)", "0.3178…", "-√2+√3")
        forms("(√2+√3)/(√2+√3)", "1", "1")
        forms("1/(√2+√3+√5)*(√2+√3+√5)", "1", "1")
    }

    @Test fun nestedRootsAreRetainedOrDenestedWhenTheyAreSquaresInTheExistingField() {
        forms("√(2+√2)", "1.8478…", "√(2+√2)")
        forms("sqrt(2+sqrt(2))", "1.8478…", "√(2+√2)")
        forms("√(3+2√2)", "2.4142…", "1+√2")
        forms("√(3-2√2)", "0.4142…", "-1+√2")
        forms("√(5+2√6)", "3.1463…", "√2+√3")
        forms("√(2+√3)", "1.9319…", "(√2+√6)/2")
        forms("√(2-√3)", "0.5176…", "(-√2+√6)/2")
        forms("√(2+√3)*√2", "2.7321…", "1+√3")
        forms("√(2+√3)/√2", "1.366…", "(1+√3)/2")
        forms("√(8+4√2)", "3.6955…", "2√(2+√2)")
        forms("√(8+8√2)", "4.3947…", "2√(2+2√2)")
        forms("√(2+√2)*√(2+√2)", "3.4142…", "2+√2")
        forms("1/√(2+√2)*√(2+√2)", "1", "1")
        forms("√√16", "2", "2")
        forms("√(√2-√2+4)", "2", "2")
    }

    @Test fun returnedExactFormsRemainUsableAsExpressions() {
        for (input in listOf("√8+√2", "1/(√2+√3)", "√0.5", "√(2+√2)", "1/√(2+√2)")) {
            val result = CalculatorEvaluator.resultForms(input)
            assertEquals(input, 2, result.size)
            assertEquals(input, result.first(), CalculatorEvaluator.evaluate(result.last()))
        }
        forms("(1+√2)(1-√2)", "-1", "-1")
    }

    @Test fun invalidAndIncompleteRootsDoNotBecomeMisleadingNumbers() {
        for (input in listOf("√", "sqrt", "sqrt2", "sqrt()", "√()", "√(2", "√[2)",
            "√2+√", "√(2+)")) {
            assertEquals(input, "Error", CalculatorEvaluator.evaluate(input))
            assertTrue(input, CalculatorEvaluator.resultForms(input).isEmpty())
        }
        assertEquals("除数不能为0", CalculatorEvaluator.evaluate("√2/(√2-√2)"))
        assertTrue(CalculatorEvaluator.resultForms("√2/0").isEmpty())
        forms("√2+", "1.4142…", "√2")
    }

    @Test fun expansionAndNumberLimitsRetainLiteralInputWithoutCrashing() {
        assertTrue(CalculatorEvaluator.resultForms("√2+√3+√5+√7+√11+√13+√17+√19+√23").isEmpty())
        assertTrue(CalculatorEvaluator.resultForms("√" + "9".repeat(130)).isEmpty())
        assertTrue(CalculatorEvaluator.resultForms("√(" + "1+".repeat(2500) + "1)").isEmpty())
        val n = "1".repeat(110)
        val d = "9".repeat(124) + "7"
        val longResult = listOf(2, 3, 5, 7, 11, 13, 17, 19).joinToString("+") { "$n/$d*√$it" }
        assertTrue(CalculatorEvaluator.resultForms(longResult).isEmpty())
    }

    @Test fun productsUseOneSquareFreeBasisAcrossDifferentFactorizationOrders() {
        for (a in 2..10) for (b in 2..10) {
            val expected = (a * b).toString()
            forms("√$a*√$b*√${a*b}", expected, expected)
        }
        forms("√(2+√2)*√(2-√2)", "1.4142…", "√2")
        forms("1/(√(2+√2)+√(2-√2))*(√(2+√2)+√(2-√2))", "1", "1")
    }

    @Test fun candidateOverlayKeepsBothEqualTextsAndRemovesOldResults() {
        val equal = CalculatorCandidates.forInput("√4", true)
        assertEquals(listOf("2", "2"), equal.map { it.text })
        assertNotEquals(equal[0], equal[1])
        val base = listOf(Candidate("other", "EN"), Candidate("√2", "IPA"))
        val initial = CalculatorCandidates.merge("√2", true, base)
        assertEquals(listOf("1.4142…", "√2", "other"), initial.map { it.text })
        assertEquals(listOf(Candidate("other", "EN")), CalculatorCandidates.merge("√", true, initial))
        assertEquals(listOf(Candidate("other", "EN")), CalculatorCandidates.merge("√2", false, initial))
        assertEquals(listOf("2", "2", "other"), CalculatorCandidates.merge("√4", true, initial).map { it.text })
        assertEquals(initial, CalculatorCandidates.merge("√2", true, initial))
    }

    @Test fun ordinaryArithmeticKeepsOneCandidateAndDisabledModeKeepsLanguageResults() {
        assertEquals(listOf("3"), CalculatorEvaluator.resultForms("1+2"))
        assertEquals(listOf("3"), CalculatorCandidates.forInput("1+2", true).map { it.text })
        for (raw in listOf("2", "-2", "abc", "1+")) assertTrue(raw, CalculatorCandidates.forInput(raw, true).isEmpty())
        val base = listOf(Candidate("literal", "EN"))
        assertEquals(base, CalculatorCandidates.merge("√2", false, base))
    }
}
