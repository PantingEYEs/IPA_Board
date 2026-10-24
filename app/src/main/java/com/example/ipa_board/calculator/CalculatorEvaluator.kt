package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayDeque

/**
 * Calculator evaluator capable of evaluating basic four arithmetic operations
 * (+, -, *, /) and nested (), [], {} groups with precision up to 4 decimal places
 * using BigDecimal. Bracket styles do not introduce different precedence levels.
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
        data class Operator(val op: Char) : Token() // '+', '-', '×', '÷', '~' (unary minus)
        data class OpenGroup(val bracket: Char) : Token()
        data class CloseGroup(val bracket: Char) : Token()
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

            if (c.isDigit() || c == '.') {
                val sb = StringBuilder()
                while (i < len && (clean[i].isDigit() || clean[i] == '.')) {
                    sb.append(clean[i])
                    i++
                }
                tokens.add(Token.Number(BigDecimal(sb.toString())))
            } else if (c == '+' || c == '-' || c == '×' || c == '÷') {
                val isUnaryMinus = c == '-' &&
                    (tokens.isEmpty() || tokens.last() is Token.Operator || tokens.last() is Token.OpenGroup)
                tokens.add(Token.Operator(if (isUnaryMinus) '~' else c))
                i++
            } else if (c in "([{") {
                tokens.add(Token.OpenGroup(c))
                i++
            } else if (c in ")]}") {
                tokens.add(Token.CloseGroup(c))
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
        '~' -> 3
        '×', '÷' -> 2
        '+', '-' -> 1
        else -> 0
    }

    private fun shuntingYard(tokens: List<Token>): List<Token> {
        val output = mutableListOf<Token>()
        val opStack = ArrayDeque<Char>()
        var expectingOperand = true

        for (token in tokens) {
            when (token) {
                is Token.Number -> {
                    require(expectingOperand) { "Missing operator" }
                    output.add(token)
                    expectingOperand = false
                }
                is Token.Operator -> {
                    require(expectingOperand == (token.op == '~')) { "Missing operand" }
                    // Unary minus associates right-to-left, including before a group.
                    while (true) {
                        val top = opStack.peek() ?: break
                        val shouldPop = top !in "([{" &&
                            (precedence(top) > precedence(token.op) ||
                                (token.op != '~' && precedence(top) == precedence(token.op)))
                        if (!shouldPop) break
                        output.add(Token.Operator(opStack.pop()))
                    }
                    opStack.push(token.op)
                    expectingOperand = true
                }
                is Token.OpenGroup -> {
                    require(expectingOperand) { "Missing operator before group" }
                    opStack.push(token.bracket)
                }
                is Token.CloseGroup -> {
                    require(!expectingOperand) { "Empty or incomplete group" }
                    while (true) {
                        val top = opStack.peek() ?: break
                        if (top in "([{") break
                        output.add(Token.Operator(opStack.pop()))
                    }
                    val opening = when (token.bracket) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    require(opStack.isNotEmpty() && opStack.pop() == opening) { "Mismatched group" }
                }
            }
        }

        require(!expectingOperand) { "Missing operand" }
        while (opStack.isNotEmpty()) {
            val op = opStack.pop()
            require(op !in "([{") { "Unclosed group" }
            output.add(Token.Operator(op))
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
                    if (token.op == '~') {
                        require(stack.isNotEmpty()) { "Missing operand" }
                        stack.push(stack.pop().negate())
                        continue
                    }
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
                else -> throw IllegalArgumentException("Unexpected group in RPN")
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
