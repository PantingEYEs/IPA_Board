package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayDeque

/**
 * Calculator evaluator for arithmetic, powers, percentages, combinatorics, square roots, constants and nested (), [], {}
 * groups, displaying up to 4 decimal places.
 * using BigDecimal. Bracket styles do not introduce different precedence levels.
 */
object CalculatorEvaluator {
    private val unaryFunctions = listOf("asin", "acos", "atan", "sin", "cos", "tan")
    private fun unaryCode(name: String) = (0xE000 + unaryFunctions.indexOf(name)).toChar()
    private fun unaryName(code: Char): String? = unaryFunctions.getOrNull(code.code - 0xE000)

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
            listOfNotNull(result.decimal, result.exact, result.symbolic) + result.additional
        }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private data class Result(val decimal: String, val exact: String? = null, val symbolic: String? = null, val additional: List<String> = emptyList())

    private fun evaluateResult(expression: String): Result? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null

        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return null
        val rpn = shuntingYard(tokens)
        return if (tokens.any { it is Token.Degree || it is Token.UnaryFunction || it is Token.Constant || (it is Token.BinaryFunction && it.operation in "^L") || (it is Token.Operator && it.op in "√^") }) {
            val expanded = SymbolicArithmetic()
            val evaluated = evalSymbolicRpn(rpn, expanded, expandPhi = true)
            val value = evaluated.value
            if (evaluated.anglePower == Rational.ONE) {
                val forms = expanded.angleForms(value)
                return Result(forms[0], forms[1], forms[2], forms.drop(3))
            }
            require(evaluated.anglePower == Rational.ZERO) { "Unsupported angular dimension" }
            if (evaluated.isSlope) {
                val forms = expanded.slopeForms(value)
                return Result(forms[0], forms[1], forms[2], forms.drop(3))
            }
            val decimal = expanded.decimalText(value)
            val exact = if (expanded.nonIntegerPower || tokens.any { it is Token.UnaryFunction } || tokens.any { it is Token.BinaryFunction && it.operation == 'L' } || tokens.any { it is Token.Constant || (it is Token.Operator && it.op == '√') })
                expanded.exactText(value) else null
            val symbolic = if (tokens.any { it is Token.Constant && it.name == 'φ' }) {
                val preserved = SymbolicArithmetic()
                preserved.exactText(evalSymbolicRpn(rpn, preserved, expandPhi = false).value)
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
        data class UnaryFunction(val name: String) : Token()
        data class BinaryFunction(val operation: Char) : Token()
        data object Comma : Token()
        data object Degree : Token()
        data object Factorial : Token()
        data object Percent : Token() // Postfix division by 100; never a contextual percentage.
        data class Operator(val op: Char) : Token() // '+', '-', '×', '÷', '~' (unary minus), '√'
        data class OpenGroup(val bracket: Char) : Token()
        data class CloseGroup(val bracket: Char) : Token()
    }

    private fun tokenize(expr: String): List<Token> {
        val clean = expr.replace('*', '×').replace('/', '÷')
        val special = unaryFunctions.any { clean.contains(it) } || clean.contains('√') || clean.contains("sqrt") || clean.contains("pow") || clean.contains("perm") || clean.contains("comb") || clean.contains("log") || clean.contains('!') || clean.contains('°') || clean.contains('^') || clean.contains('%') || clean.any { it in "eπφi" }
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
                if (special && (tokens.lastOrNull() is Token.Constant || tokens.lastOrNull() is Token.Percent || tokens.lastOrNull() is Token.Factorial || tokens.lastOrNull() is Token.Degree)) tokens.add(Token.Operator('×'))
                val sb = StringBuilder()
                while (i < len && (clean[i].isDigit() || clean[i] == '.')) {
                    sb.append(clean[i])
                    i++
                }
                tokens.add(Token.Number(BigDecimal(sb.toString())))
            } else if (unaryFunctions.any { clean.startsWith(it, i) }) {
                val name = unaryFunctions.first { clean.startsWith(it, i) }
                i += name.length
                while (i < len && clean[i].isWhitespace()) i++
                require(i < len && clean[i] == '(') { "$name requires parentheses" }
                if (tokens.lastOrNull().let { it is Token.Number || it is Token.CloseGroup || it is Token.Constant || it is Token.Percent || it is Token.Factorial || it is Token.Degree }) {
                    tokens.add(Token.Operator('×'))
                }
                tokens.add(Token.UnaryFunction(name))
            } else if (listOf("pow", "perm", "comb", "log").any { clean.startsWith(it, i) }) {
                val name = listOf("pow", "perm", "comb", "log").first { clean.startsWith(it, i) }
                i += name.length
                while (i < len && clean[i].isWhitespace()) i++
                require(i < len && clean[i] == '(') { "$name requires parentheses" }
                if (tokens.lastOrNull() is Token.Number || tokens.lastOrNull() is Token.CloseGroup || tokens.lastOrNull() is Token.Constant || tokens.lastOrNull() is Token.Percent || tokens.lastOrNull() is Token.Factorial || tokens.lastOrNull() is Token.Degree) {
                    tokens.add(Token.Operator('×'))
                }
                tokens.add(Token.BinaryFunction(when (name) { "perm" -> 'P'; "comb" -> 'C'; "log" -> 'L'; else -> '^' }))
            } else if (c == '°') {
                tokens.add(Token.Degree)
                i++
            } else if (c == '!') {
                tokens.add(Token.Factorial)
                i++
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
                if (tokens.lastOrNull() is Token.Number || tokens.lastOrNull() is Token.CloseGroup || tokens.lastOrNull() is Token.Constant || tokens.lastOrNull() is Token.Percent || tokens.lastOrNull() is Token.Factorial || tokens.lastOrNull() is Token.Degree) {
                    tokens.add(Token.Operator('×'))
                }
                tokens.add(Token.Operator('√'))
            } else if (c in "eπφi") {
                if (tokens.lastOrNull() is Token.Number || tokens.lastOrNull() is Token.CloseGroup || tokens.lastOrNull() is Token.Constant || tokens.lastOrNull() is Token.Percent || tokens.lastOrNull() is Token.Factorial || tokens.lastOrNull() is Token.Degree) {
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
                if (special && (tokens.lastOrNull() is Token.Number || tokens.lastOrNull() is Token.CloseGroup || tokens.lastOrNull() is Token.Constant || tokens.lastOrNull() is Token.Percent || tokens.lastOrNull() is Token.Factorial || tokens.lastOrNull() is Token.Degree)) {
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
        data class Group(val bracket: Char, val function: Char?, var commas: Int = 0)
        val groups = ArrayDeque<Group>()
        var expectingOperand = true

        for (token in tokens) {
            when (token) {
                is Token.Number, is Token.Constant -> {
                    require(expectingOperand) { "Missing operator" }
                    output.add(token)
                    expectingOperand = false
                }
                Token.Percent, Token.Factorial, Token.Degree -> {
                    require(!expectingOperand) { "Missing postfix operand" }
                    // Postfix binds directly to the completed operand, including a closed group.
                    output.add(token)
                }
                is Token.UnaryFunction -> {
                    require(expectingOperand) { "Missing operator before function" }
                    opStack.push(unaryCode(token.name))
                }
                is Token.BinaryFunction -> {
                    require(expectingOperand) { "Missing operator before function" }
                    opStack.push(when (token.operation) { '^' -> 'p'; else -> token.operation })
                }
                Token.Comma -> {
                    val group = groups.peek()
                    require(!expectingOperand && group != null && group.function != null && unaryName(group.function) == null && group.commas == 0) { "Invalid binary function arguments" }
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
                    groups.push(Group(token.bracket, opStack.peek()?.takeIf { it in "pPCL" || unaryName(it) != null }))
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
                    if (group.function != null) {
                        require(group.commas == (if (unaryName(group.function) != null) 0 else 1) && opStack.pop() == group.function) { "Invalid function argument count" }
                        output.add(Token.Operator(if (group.function == 'p') '^' else group.function))
                    } else if (opStack.peek() == '√') {
                        // A grouped root is complete before a following postfix operation.
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
                Token.Factorial -> {
                    require(stack.isNotEmpty()) { "Missing factorial operand" }
                    stack.push(Combinatorics.factorial(stack.pop()))
                }
                Token.Percent -> {
                    require(stack.isNotEmpty()) { "Missing postfix operand" }
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
                        'P' -> Combinatorics.choose(a, b, permutation = true)
                        'C' -> Combinatorics.choose(a, b, permutation = false)
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

    private data class Quantity(val value: SymbolicArithmetic.Complex, val anglePower: Rational = Rational.ZERO, val isSlope: Boolean = false)

    private fun evalSymbolicRpn(tokens: List<Token>, arithmetic: SymbolicArithmetic, expandPhi: Boolean): Quantity {
        val stack = ArrayDeque<Quantity>()
        fun scalar(q: Quantity) { require(q.anglePower == Rational.ZERO) { "Dimensionless operand required" } }
        for (token in tokens) {
            when (token) {
                is Token.Number -> stack.push(Quantity(arithmetic.number(token.value)))
                is Token.Constant -> stack.push(Quantity(arithmetic.constant(token.name, expandPhi)))
                Token.Degree -> {
                    require(stack.isNotEmpty()) { "Missing degree operand" }
                    val q = stack.pop()
                    require(q.anglePower == Rational.ZERO || q.anglePower == Rational.ONE) { "Invalid degree operand" }
                    // ° interprets the preceding numerical magnitude as degrees; internally store radians.
                    stack.push(Quantity(arithmetic.divide(arithmetic.multiply(q.value, arithmetic.constant('π', true)),
                        arithmetic.number(BigDecimal(180))), Rational.ONE))
                }
                Token.Factorial -> {
                    require(stack.isNotEmpty()) { "Missing factorial operand" }
                    val q = stack.pop(); scalar(q)
                    stack.push(Quantity(arithmetic.number(Combinatorics.factorial(arithmetic.nonNegativeInteger(q.value)))))
                }
                Token.Percent -> {
                    require(stack.isNotEmpty()) { "Missing postfix operand" }
                    val q = stack.pop()
                    stack.push(q.copy(value = arithmetic.divide(q.value, arithmetic.number(BigDecimal(100)))))
                }
                is Token.Operator -> {
                    require(stack.isNotEmpty()) { "Missing operand" }
                    val b = stack.pop()
                    val function = unaryName(token.op)
                    val result = when {
                        function != null -> {
                            val inverse = function.startsWith("a")
                            if (inverse) scalar(b) else require(b.anglePower == Rational.ZERO || b.anglePower == Rational.ONE) { "Invalid trigonometric angle" }
                            Quantity(arithmetic.trigonometric(function, b.value), if (inverse) Rational.ONE else Rational.ZERO, isSlope = function == "tan")
                        }
                        token.op == '~' -> b.copy(value = arithmetic.negate(b.value))
                        token.op == '√' -> Quantity(arithmetic.sqrt(b.value), b.anglePower / Rational.of(BigDecimal(2)), b.isSlope)
                        else -> {
                            require(stack.isNotEmpty()) { "Missing operand" }
                            val a = stack.pop()
                            when (token.op) {
                                '+', '-' -> {
                                    require(a.anglePower == b.anglePower) { "Incompatible angular dimensions" }
                                    Quantity(if (token.op == '+') arithmetic.add(a.value, b.value) else arithmetic.subtract(a.value, b.value), a.anglePower, a.isSlope || b.isSlope)
                                }
                                '×' -> Quantity(arithmetic.multiply(a.value, b.value), a.anglePower + b.anglePower, a.isSlope || b.isSlope)
                                '÷' -> Quantity(arithmetic.divide(a.value, b.value), a.anglePower - b.anglePower, a.isSlope || b.isSlope)
                                '^' -> {
                                    scalar(b)
                                    val dimension = if (a.anglePower == Rational.ZERO) Rational.ZERO else
                                        a.anglePower * requireNotNull(arithmetic.rationalReal(b.value)) { "Rational exponent required for angle" }
                                    Quantity(arithmetic.power(a.value, b.value), dimension, a.isSlope)
                                }
                                'L', 'P', 'C' -> {
                                    scalar(a); scalar(b)
                                    Quantity(if (token.op == 'L') arithmetic.logarithm(a.value, b.value) else
                                        arithmetic.number(Combinatorics.choose(arithmetic.nonNegativeInteger(a.value), arithmetic.nonNegativeInteger(b.value), token.op == 'P')))
                                }
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
