package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayDeque

/**
 * Calculator evaluator capable of evaluating basic four arithmetic operations
 * (+, -, *, /) with precision up to 4 decimal places using BigDecimal.
 */
object CalculatorEvaluator {

    /**
     * Evaluates a mathematical expression string.
     * Returns a formatted result string accurate up to 4 decimal places,
     * or an error message if invalid or division by zero occurs.
     */
    fun evaluate(expression: String): String {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return ""

        return try {
            val tokens = tokenize(trimmed)
            if (tokens.isEmpty()) return ""
            val rpn = shuntingYard(tokens)
            val result = evalRpn(rpn)
            formatResult(result)
        } catch (e: ArithmeticException) {
            "除数不能为0"
        } catch (e: Exception) {
            "Error"
        }
    }

    private sealed class Token {
        data class Number(val value: BigDecimal) : Token()
        data class Operator(val op: Char) : Token() // '+', '-', '×', '÷'
    }

    private fun tokenize(expr: String): List<Token> {
        val clean = expr.replace('*', '×').replace('/', '÷')
        val tokens = mutableListOf<Token>()
        var i = 0
        val len = clean.length

        while (i < len) {
            val c = clean[i]
            if (c.isWhitespace()) {
                i++
                continue
            }

            // Check if '-' is a unary minus or binary subtraction
            val isUnaryMinus = c == '-' && (tokens.isEmpty() || tokens.last() is Token.Operator)

            if (c.isDigit() || c == '.' || isUnaryMinus) {
                val sb = StringBuilder()
                if (isUnaryMinus) {
                    sb.append('-')
                    i++
                    while (i < len && clean[i].isWhitespace()) i++
                }

                var hasDigitOrDot = false
                while (i < len && (clean[i].isDigit() || clean[i] == '.')) {
                    sb.append(clean[i])
                    hasDigitOrDot = true
                    i++
                }

                if (hasDigitOrDot) {
                    val numStr = sb.toString()
                    if (numStr == "-" || numStr == "." || numStr == "-.") {
                        throw IllegalArgumentException("Invalid number format")
                    }
                    tokens.add(Token.Number(BigDecimal(numStr)))
                } else {
                    if (isUnaryMinus) {
                        tokens.add(Token.Operator('-'))
                    }
                }
            } else if (c == '+' || c == '-' || c == '×' || c == '÷') {
                tokens.add(Token.Operator(c))
                i++
            } else {
                i++
            }
        }

        // Drop trailing operators for evaluation (e.g. "5 +" -> "5")
        val resultTokens = tokens.toMutableList()
        while (resultTokens.isNotEmpty() && resultTokens.last() is Token.Operator) {
            resultTokens.removeAt(resultTokens.size - 1)
        }

        return resultTokens
    }

    private fun precedence(op: Char): Int = when (op) {
        '×', '÷' -> 2
        '+', '-' -> 1
        else -> 0
    }

    private fun shuntingYard(tokens: List<Token>): List<Token> {
        val output = mutableListOf<Token>()
        val opStack = ArrayDeque<Char>()

        for (token in tokens) {
            when (token) {
                is Token.Number -> output.add(token)
                is Token.Operator -> {
                    while (opStack.isNotEmpty() && precedence(opStack.peek()!!) >= precedence(token.op)) {
                        output.add(Token.Operator(opStack.pop()))
                    }
                    opStack.push(token.op)
                }
            }
        }

        while (opStack.isNotEmpty()) {
            output.add(Token.Operator(opStack.pop()))
        }

        return output
    }

    private fun evalRpn(tokens: List<Token>): BigDecimal {
        if (tokens.isEmpty()) return BigDecimal.ZERO
        val stack = ArrayDeque<BigDecimal>()

        for (token in tokens) {
            when (token) {
                is Token.Number -> stack.push(token.value)
                is Token.Operator -> {
                    if (stack.size < 2) throw IllegalArgumentException("Invalid expression")
                    val b = stack.pop()
                    val a = stack.pop()
                    val res = when (token.op) {
                        '+' -> a.add(b)
                        '-' -> a.subtract(b)
                        '×' -> a.multiply(b)
                        '÷' -> {
                            if (b.compareTo(BigDecimal.ZERO) == 0) {
                                throw ArithmeticException("Divide by zero")
                            }
                            a.divide(b, 10, RoundingMode.HALF_UP)
                        }
                        else -> throw IllegalArgumentException("Unknown operator: ${token.op}")
                    }
                    stack.push(res)
                }
            }
        }

        if (stack.size != 1) throw IllegalArgumentException("Invalid expression evaluation")
        return stack.pop()
    }

    fun formatResult(value: BigDecimal): String {
        val scaled = value.setScale(4, RoundingMode.HALF_UP)
        val isApproximate = value.compareTo(scaled) != 0
        val baseStr = scaled.stripTrailingZeros().toPlainString()
        return if (isApproximate) "$baseStr…" else baseStr
    }
}
