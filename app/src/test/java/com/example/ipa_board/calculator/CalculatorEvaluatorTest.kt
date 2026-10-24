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

    @Test
    fun bracketStylesOverrideArithmeticPrecedenceEqually() {
        assertEquals("1.6667…", CalculatorEvaluator.evaluate("1+2/3"))
        for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
            assertEquals("1", CalculatorEvaluator.evaluate("${open}1+2$close/3"))
            assertEquals("9", CalculatorEvaluator.evaluate("3*${open}1+2$close"))
            assertEquals("7", CalculatorEvaluator.evaluate("${open}1+2*3$close"))
        }
    }

    @Test
    fun nestingDeterminesOrderAcrossAllBracketStyleCombinations() {
        val styles = listOf('(' to ')', '[' to ']', '{' to '}')
        for ((outerOpen, outerClose) in styles) {
            for ((innerOpen, innerClose) in styles) {
                assertEquals("1", CalculatorEvaluator.evaluate(
                    "$outerOpen${innerOpen}1+2$innerClose*3$outerClose/9"
                ))
                assertEquals("-1", CalculatorEvaluator.evaluate(
                    "10-2*$outerOpen${innerOpen}3+8$innerClose/2$outerClose"
                ))
            }
        }
        assertEquals("15", CalculatorEvaluator.evaluate("{2+[3*(4+1)]}-2"))
    }

    @Test
    fun unaryMinusWorksBeforeAndInsideGroups() {
        for ((expression, expected) in listOf(
            "-(1+2)/3" to "-1", "2*[-3+1]" to "-4",
            "10/-{2+3}" to "-2", "-(-2-3)" to "5",
            "-(1+2)-4" to "-7", "1-(-2)" to "3",
            "2*-[-(1+2)]" to "6", "- ( - 2.5 + 1 )" to "1.5"
        )) {
            assertEquals(expression, expected, CalculatorEvaluator.evaluate(expression))
        }
    }

    @Test
    fun groupsPreserveDecimalsFormattingAndDivisionByZero() {
        assertEquals("0.3333…", CalculatorEvaluator.evaluate("[1+1]/(3*2)"))
        assertEquals("1", CalculatorEvaluator.evaluate("(0.25+0.75)"))
        assertEquals("除数不能为0", CalculatorEvaluator.evaluate("1/[2-2]"))
        assertEquals("除数不能为0", CalculatorEvaluator.evaluate("{1/0}+2"))
        assertEquals("2", CalculatorEvaluator.evaluate("8/(2*2)"))
        assertEquals("1", CalculatorEvaluator.evaluate("8/2/4"))
    }

    @Test
    fun malformedGroupsDoNotSilentlyProduceUngroupedResults() {
        for (expression in listOf(
            "(1+2", "1+2)", "[1+2)", "{[1+2}]", "()", "1+[]",
            "(1+)", "(1*)", "(1 2)", "2(3+4)", "(1)(2)", "(-)", "{()}"
        )) {
            assertEquals(expression, "Error", CalculatorEvaluator.evaluate(expression))
        }
    }

    @Test
    fun completedGroupsRetainTrailingOperatorPreviewBehavior() {
        assertEquals("3", CalculatorEvaluator.evaluate("(1+2)+"))
        assertEquals("-3", CalculatorEvaluator.evaluate("-[1+2]* "))
        assertEquals("Error", CalculatorEvaluator.evaluate("1+(2*"))
    }

    @Test
    fun deeplyNestedGroupsDoNotRequireRecursiveParsing() {
        val expression = "(".repeat(2000) + "1+2" + ")".repeat(2000)
        assertEquals("3", CalculatorEvaluator.evaluate(expression))
    }
}
