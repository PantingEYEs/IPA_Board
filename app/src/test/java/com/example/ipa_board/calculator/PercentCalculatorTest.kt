package com.example.ipa_board.calculator

import com.example.ipa_board.ime.Candidate
import org.junit.Assert.*
import org.junit.Test

class PercentCalculatorTest {
    private fun forms(input: String, vararg expected: String) =
        assertEquals(input, expected.toList(), CalculatorEvaluator.resultForms(input))

    @Test fun percentagesArePostfixDivisionRatherThanContextualRelativeChanges() {
        forms("50%", "0.5")
        forms("200*10%", "20")
        forms("200+10%", "200.1")
        forms("200-10%", "199.9")
        forms("(1+2)%", "0.03")
        forms("50%%", "0.005")
        forms("-50%", "-0.5")
        forms("1/50%", "2")
        forms("0%", "0")
    }

    @Test fun postfixPrecedenceAndImplicitProductsComposeWithPowersAndRoots() {
        forms("2^50%", "1.4142…", "√2")
        forms("pow(4,50%)", "2", "2")
        forms("(2^3)%", "0.08")
        forms("sqrt(4)%", "0.02", "1/50")
        forms("√4%", "0.2", "1/5")
        forms("50%(1+1)", "1")
        forms("50%2", "1")
        forms("50%√4", "1", "1")
    }

    @Test fun constantsAndComplexNumbersKeepExistingCandidateForms() {
        forms("100%π", "3.1416…", "π")
        forms("100%φ", "1.618…", "(1+√5)/2", "φ")
        forms("(100i)%", "(1)i", "(1)i")
        forms("(1+i)%", "0.01+(0.01)i", "1/100+(1/100)i")
    }

    @Test fun invalidOperandsFallBackAndCandidateOverlayRemainsConsistent() {
        for (input in listOf("%", "%50", "1+%", "(%)", "pow(%,2)", "2^%", "50%+%")) {
            assertTrue(input, CalculatorEvaluator.resultForms(input).isEmpty())
            assertTrue(input, CalculatorCandidates.forInput(input, true).isEmpty())
        }
        assertEquals(listOf("0"), CalculatorCandidates.forInput("0%", true).map { it.text })
        assertEquals(listOf("0.5"), CalculatorCandidates.forInput("50%", true).map { it.text })
        assertTrue(CalculatorCandidates.forInput("50%", false).isEmpty())
        val previous = CalculatorCandidates.forInput("50%", true)
        val language = Candidate("word", "en")
        assertEquals(listOf(language), CalculatorCandidates.merge("%", true, previous + language))
        forms("50%+", "0.5")
        assertEquals("除数不能为0", CalculatorEvaluator.evaluate("1/0%"))
    }
}
