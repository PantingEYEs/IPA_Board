package com.example.ipa_board.calculator

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculatorEvaluatorTest {

    @Test
    fun testBasicArithmetic() {
        assertEquals("3", CalculatorEvaluator.evaluate("1 + 2"))
        assertEquals("7", CalculatorEvaluator.evaluate("10 - 3"))
        assertEquals("24", CalculatorEvaluator.evaluate("6 × 4"))
        assertEquals("2.5", CalculatorEvaluator.evaluate("10 ÷ 4"))
    }

    @Test
    fun testFourDecimalPlacesPrecision() {
        assertEquals("0.3333…", CalculatorEvaluator.evaluate("1 ÷ 3"))
        assertEquals("0.1235…", CalculatorEvaluator.evaluate("0.123456"))
        assertEquals("0.0001…", CalculatorEvaluator.evaluate("0.00006"))
        assertEquals("12.3456…", CalculatorEvaluator.evaluate("12.34561"))
        assertEquals("12.3457…", CalculatorEvaluator.evaluate("12.34567"))
    }

    @Test
    fun testNegativeNumbers() {
        assertEquals("-2", CalculatorEvaluator.evaluate("-5 + 3"))
        assertEquals("-25", CalculatorEvaluator.evaluate("10 × -2.5"))
        assertEquals("5", CalculatorEvaluator.evaluate("-10 ÷ -2"))
        assertEquals("-3.1416…", CalculatorEvaluator.evaluate("-3.14159"))
    }

    @Test
    fun testOperatorPrecedence() {
        assertEquals("14", CalculatorEvaluator.evaluate("2 + 3 × 4"))
        assertEquals("20", CalculatorEvaluator.evaluate("5 + 3 × 10 ÷ 2"))
        assertEquals("3.5", CalculatorEvaluator.evaluate("2 + 3 ÷ 2"))
    }

    @Test
    fun testDivideByZero() {
        assertEquals("除数不能为0", CalculatorEvaluator.evaluate("10 ÷ 0"))
        assertEquals("除数不能为0", CalculatorEvaluator.evaluate("0 ÷ 0"))
    }

    @Test
    fun testIncompleteExpression() {
        assertEquals("5", CalculatorEvaluator.evaluate("5 +"))
        assertEquals("10", CalculatorEvaluator.evaluate("10 × "))
        assertEquals("", CalculatorEvaluator.evaluate(""))
    }
}
