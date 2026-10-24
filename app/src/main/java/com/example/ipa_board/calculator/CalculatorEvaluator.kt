package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayDeque

/**
 * Calculator evaluator for arithmetic, powers, percentages, square roots, constants and nested (), [], {}
 * groups, displaying up to 4 decimal places.
 * using BigDecimal. Bracket styles do not introduce different precedence levels.
 */
object CalculatorEvaluator {

    /**
     * Evaluates a mathematical expression string.
     * Returns a formatted result string accurate up to 4 decimal places,
     * or an error message if invalid or division by zero occurs.
     */
    fun evaluate(expression: String): String {
        return try {
            evaluateResult(expression)?.decimal.orEmpty()
        } catch (e: ArithmeticException) {
            "除数不能为0"
        } catch (e: Exception) {
            "Error"
        }
    }

    /** Decimal first, then applicable exact/constant forms, even when their texts are equal. */
    fun resultForms(expression: String): List<String> = try {
        evaluateResult(expression)?.let { result ->
            listOfNotNull(result.decimal, result.exact, result.symbolic)
        }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private data class Result(val decimal: String, val exact: String? = null, val symbolic: String? = null)

    private fun evaluateResult(expression: String): Result? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null

        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return null
        val rpn = shuntingYard(tokens)
        return if (tokens.any { it is Token.Constant || it is Token.PowFunction || (it is Token.Operator && it.op in "√^") }) {
            val expanded = SymbolicArithmetic()
            val value = evalSymbolicRpn(rpn, expanded, expandPhi = true)
            val decimal = expanded.decimalText(value)
            val exact = if (expanded.nonIntegerPower || tokens.any { it is Token.Constant || (it is Token.Operator && it.op == '√') })
                expanded.exactText(value) else null
            val symbolic = if (tokens.any { it is Token.Constant && it.name == 'φ' }) {
                val preserved = SymbolicArithmetic()
                preserved.exactText(evalSymbolicRpn(rpn, preserved, expandPhi = false))
            } else null
            require(listOfNotNull(decimal, exact, symbolic).all { it.length <= 1000 }) { "Result length limit" }
            Result(decimal, exact, symbolic)
        } else {
            Result(formatResult(evalRpn(rpn)))
        }
    }

    private sealed class Token {
        data class Number(val value: BigDecimal) : Token()
        data class Constant(val name: Char) : Token()
        data object PowFunction : Token()
        data object Comma : Token()
        data object Percent : Token() // Postfix division by 100; never a contextual percentage.
        data class Operator(val op: Char) : Token() // '+', '-', '×', '÷', '~' (unary minus), '√'
        data class OpenGroup(val bracket: Char) : Token()
        data class CloseGroup(val bracket: Char) : Token()
    }

    private fun tokenize(expr: String): List<Token> {
        val clean = expr.replace('*', '×').replace('/', '÷')
        val special = clean.contains('√') || clean.contains("sqrt") || clean.contains("pow") || clean.contains('^') || clean.contains('%') || clean.any { it in "eπφi" }
        if (special) require(clean.length <= 4096) { "Radical expression length limit" }
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
                if (special && (tokens.lastOrNull() is Token.Constant || tokens.lastOrNull() is Token.Percent)) tokens.add(Token.Operator('×'))
                val sb = StringBuilder()
                while (i < len && (clean[i].isDigit() || clean[i] == '.')) {
                    sb.append(clean[i])
                    i++
                }
                tokens.add(Token.Number(BigDecimal(sb.toString())))
            } else if (clean.startsWith("pow", i)) {
                i += 3
                while (i < len && clean[i].isWhitespace()) i++
                require(i < len && clean[i] == '(') { "pow requires parentheses" }
                if (tokens.lastOrNull() is Token.Number || tokens.lastOrNull() is Token.CloseGroup || tokens.lastOrNull() is Token.Constant || tokens.lastOrNull() is Token.Percent) {
                    tokens.add(Token.Operator('×'))
                }
                tokens.add(Token.PowFunction)
            } else if (c == '%') {
                tokens.add(Token.Percent)
                i++
            } else if (c == ',') {
                tokens.add(Token.Comma)
                i++
            } else if (c == '√' || clean.startsWith("sqrt", i)) {
                if (c != '√') {
                    i += 4
                    while (i < len && clean[i].isWhitespace()) i++
                    require(i < len && clean[i] == '(') { "sqrt requires parentheses" }
                } else i++
                if (tokens.lastOrNull() is Token.Number || tokens.lastOrNull() is Token.CloseGroup || tokens.lastOrNull() is Token.Constant || tokens.lastOrNull() is Token.Percent) {
                    tokens.add(Token.Operator('×'))
                }
                tokens.add(Token.Operator('√'))
            } else if (c in "eπφi") {
                if (tokens.lastOrNull() is Token.Number || tokens.lastOrNull() is Token.CloseGroup || tokens.lastOrNull() is Token.Constant || tokens.lastOrNull() is Token.Percent) {
                    tokens.add(Token.Operator('×'))
                }
                tokens.add(Token.Constant(c))
                i++
            } else if (c == '+' || c == '-' || c == '×' || c == '÷' || c == '^') {
                val isUnaryMinus = c == '-' &&
                    (tokens.isEmpty() || tokens.last() is Token.Operator || tokens.last() is Token.OpenGroup || tokens.last() is Token.Comma)
                tokens.add(Token.Operator(if (isUnaryMinus) '~' else c))
                i++
            } else if (c in "([{") {
                // Exact forms such as 2√2 and (1+√2)(1-√2) must remain reusable as input.
                if (special && (tokens.lastOrNull() is Token.Number || tokens.lastOrNull() is Token.CloseGroup || tokens.lastOrNull() is Token.Constant || tokens.lastOrNull() is Token.Percent)) {
                    tokens.add(Token.Operator('×'))
                }
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
        while (resultTokens.lastOrNull().let { it is Token.Operator && it.op !in "√^" }) {
            resultTokens.removeAt(resultTokens.size - 1)
        }

        return resultTokens
    }

    private fun precedence(op: Char): Int = when (op) {
        '√' -> 5
        '^' -> 4
        '~' -> 3
        '×', '÷' -> 2
        '+', '-' -> 1
        else -> 0
    }

    private fun shuntingYard(tokens: List<Token>): List<Token> {
        val output = mutableListOf<Token>()
        val opStack = ArrayDeque<Char>()
        data class Group(val bracket: Char, val pow: Boolean, var commas: Int = 0)
        val groups = ArrayDeque<Group>()
        var expectingOperand = true

        for (token in tokens) {
            when (token) {
                is Token.Number, is Token.Constant -> {
                    require(expectingOperand) { "Missing operator" }
                    output.add(token)
                    expectingOperand = false
                }
                Token.Percent -> {
                    require(!expectingOperand) { "Missing percentage operand" }
                    // Postfix binds directly to the completed operand, including a closed group.
                    output.add(token)
                }
                Token.PowFunction -> {
                    require(expectingOperand) { "Missing operator before pow" }
                    opStack.push('p')
                }
                Token.Comma -> {
                    val group = groups.peek()
                    require(!expectingOperand && group != null && group.pow && group.commas == 0) { "Invalid pow arguments" }
                    while (opStack.peek()?.let { it !in "([{" } == true) output.add(Token.Operator(opStack.pop()))
                    group.commas++
                    expectingOperand = true
                }
                is Token.Operator -> {
                    val unary = token.op == '~' || token.op == '√'
                    require(expectingOperand == unary) { "Missing operand" }
                    // Prefix operations associate right-to-left, including before a group.
                    while (true) {
                        val top = opStack.peek() ?: break
                        val shouldPop = !unary && top !in "([{" &&
                            (precedence(top) > precedence(token.op) ||
                                (token.op != '^' && precedence(top) == precedence(token.op)))
                        if (!shouldPop) break
                        output.add(Token.Operator(opStack.pop()))
                    }
                    opStack.push(token.op)
                    expectingOperand = true
                }
                is Token.OpenGroup -> {
                    require(expectingOperand) { "Missing operator before group" }
                    groups.push(Group(token.bracket, opStack.peek() == 'p'))
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
                    val group = groups.pop()
                    if (group.pow) {
                        require(group.commas == 1 && opStack.pop() == 'p') { "pow needs two arguments" }
                        output.add(Token.Operator('^'))
                    } else if (opStack.peek() == '√') {
                        // A grouped root is complete before a following postfix percentage.
                        output.add(Token.Operator(opStack.pop()))
                    }
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
                Token.Percent -> {
                    require(stack.isNotEmpty()) { "Missing percentage operand" }
                    stack.push(stack.pop().movePointLeft(2))
                }
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

    private fun evalSymbolicRpn(tokens: List<Token>, arithmetic: SymbolicArithmetic, expandPhi: Boolean): SymbolicArithmetic.Complex {
        val stack = ArrayDeque<SymbolicArithmetic.Complex>()
        for (token in tokens) {
            when (token) {
                is Token.Number -> stack.push(arithmetic.number(token.value))
                Token.Percent -> {
                    require(stack.isNotEmpty()) { "Missing percentage operand" }
                    stack.push(arithmetic.divide(stack.pop(), arithmetic.number(BigDecimal(100))))
                }
                is Token.Constant -> stack.push(arithmetic.constant(token.name, expandPhi))
                is Token.Operator -> {
                    require(stack.isNotEmpty()) { "Missing operand" }
                    val b = stack.pop()
                    val result = when (token.op) {
                        '~' -> arithmetic.negate(b)
                        '√' -> arithmetic.sqrt(b)
                        else -> {
                            require(stack.isNotEmpty()) { "Missing operand" }
                            val a = stack.pop()
                            when (token.op) {
                                '+' -> arithmetic.add(a, b)
                                '-' -> arithmetic.subtract(a, b)
                                '×' -> arithmetic.multiply(a, b)
                                '÷' -> arithmetic.divide(a, b)
                                '^' -> arithmetic.power(a, b)
                                else -> throw IllegalArgumentException("Unknown operator")
                            }
                        }
                    }
                    stack.push(result)
                }
                else -> throw IllegalArgumentException("Unexpected group in RPN")
            }
        }
        require(stack.size == 1) { "Invalid expression" }
        return stack.pop()
    }

    fun formatResult(value: BigDecimal): String {
        val scaled = value.setScale(4, RoundingMode.HALF_UP)
        val isApproximate = value.compareTo(scaled) != 0
        val baseStr = scaled.stripTrailingZeros().toPlainString()
        return if (isApproximate) "$baseStr…" else baseStr
    }
}
