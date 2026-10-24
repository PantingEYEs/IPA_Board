package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/** Real symbolic expressions over the exact radical field; e, π and φ are positive real atoms. */
internal class SymbolicArithmetic {
    sealed interface Real
    data class Number(val value: RadicalArithmetic.Value) : Real
    data class Symbol(val name: Char) : Real
    data class Root(val radicand: Real) : Real
    data class Sum(val terms: List<Real>) : Real
    data class Product(val coefficient: Number, val factors: List<Real>) : Real
    data class Quotient(val numerator: Real, val denominator: Real) : Real
    sealed interface Formula
    data class PowerFormula(val base: Complex, val exponent: Complex, val integer: Int?) : Formula
    data class BinaryFormula(val operator: Char, val a: Complex, val b: Complex) : Formula
    data class UnaryFormula(val operator: Char, val a: Complex) : Formula
    data class TrigFormula(val name: String, val argument: Complex) : Formula
    data class TrigPart(val source: TrigFormula, val imaginary: Boolean) : Real
    data class LogFormula(val value: Complex, val base: Complex) : Formula
    data class LogPart(val source: LogFormula, val imaginary: Boolean) : Real
    data class PowerPart(val source: PowerFormula, val imaginary: Boolean) : Real
    data class Complex(val real: Real, val imaginary: Real, val formula: Formula? = null, val depth: Int = 0)
    private val field = RadicalArithmetic()
    private val context = MathContext(192, RoundingMode.HALF_UP)
    private val zero = Number(field.zero)
    private val one = Number(field.one)
    private val two = Number(field.number(BigDecimal(2)))
    var nonIntegerPower = false
        private set
    private val trigDecimals = mutableMapOf<TrigFormula, DecimalPowers.Value>()
    private val logDecimals = mutableMapOf<LogFormula, DecimalPowers.Value>()
    private val powerDecimals = mutableMapOf<PowerFormula, DecimalPowers.Value>()
    private var work = 0
    private val decimals = mutableMapOf<Real, BigDecimal>()
    private fun tick() { require(++work <= 20000) { "Symbolic expression work limit" } }
    fun number(n: BigDecimal) = Complex(Number(field.number(n)), zero)
    fun requireReal(value: Complex) {
        require(value.imaginary == zero) { "rand accepts and returns only real values" }
    }
    fun randomBetween(a: Complex, b: Complex, session: CalculatorSession, index: Int): Complex {
        requireReal(a); requireReal(b)
        val order = sign(addReal(a.real, negative(b.real)))
        if (order == 0) {
            require(a.real == b.real || a.real is Number && b.real is Number) { "Random interval precision limit" }
            return a
        }
        val lower = if (order < 0) a else b
        val upper = if (order < 0) b else a
        val sampled = number(session.sample(index, decimal(lower.real), decimal(upper.real)))
        require(sign(addReal(sampled.real, negative(lower.real))) >= 0 && sign(addReal(sampled.real, negative(upper.real))) < 0) { "Random interval precision limit" }
        return sampled
    }
    fun rationalReal(value: Complex): Rational? =
        if (value.formula == null && value.imaginary == zero && value.real is Number) field.rational(value.real.value) else null

    fun angleForms(value: Complex): List<String> {
        val degrees = divide(multiply(value, number(BigDecimal(180))), constant('π', true))
        fun degreeText(text: String): String =
            if (text.replace("\u2026", "").matches(Regex("-?[0-9]+([.][0-9]+)?"))) "$text\u00b0" else "($text)\u00b0"
        val forms = mutableListOf(degreeText(decimalText(degrees)), degreeText(exactText(degrees)), decimalText(value), exactText(value))
        val slope = try { trigonometric("tan", value) } catch (_: TrigonometricPole) { null }
        if (slope == null) forms.addAll(listOf("Undefined", "Undefined"))
        else forms.addAll(listOf(decimalText(slope), exactText(slope)))
        require(forms.all { it.length <= 1000 }) { "Angle result length limit" }
        return forms
    }
    /** A tangent result is a slope; derive principal angles without replacing its exact slope. */
    fun slopeForms(value: Complex): List<String> {
        val angle = try { trigonometric("atan", value) } catch (_: TrigonometricPole) { null }
        val angleEntries = if (angle == null) List(4) { "Undefined" } else angleForms(angle).take(4)
        return (angleEntries + listOf(decimalText(value), exactText(value))).also { forms ->
            require(forms.all { it.length <= 1000 }) { "Slope result length limit" }
        }
    }
    // Discrete operations require an exact real integer; never round approximate values.
    fun nonNegativeInteger(value: Complex): BigDecimal {
        require(value.formula == null && value.imaginary == zero && value.real is Number) { "Exact integer required" }
        val rational = field.rational(value.real.value)
        require(rational != null && rational.denominator == java.math.BigInteger.ONE && rational.numerator.signum() >= 0) { "Nonnegative integer required" }
        return BigDecimal(rational.numerator)
    }
    fun constant(name: Char, expandPhi: Boolean): Complex = when (name) {
        'i' -> Complex(zero, one)
        'φ' -> if (expandPhi) Complex(divideReal(addReal(one, Number(field.sqrt(field.number(BigDecimal(5))))), two), zero)
               else Complex(Symbol(name), zero)
        else -> Complex(Symbol(name), zero)
    }
    private fun decorate(value: Complex, formula: Formula, depth: Int): Complex {
        require(depth <= 16) { "Power expression depth limit" }
        return value.copy(formula = formula, depth = depth)
    }
    private fun binary(value: Complex, op: Char, a: Complex, b: Complex) =
        if (a.formula == null && b.formula == null) value
        else decorate(value, BinaryFormula(op, a, b), 1 + maxOf(a.depth, b.depth))
    fun negate(a: Complex): Complex {
        val value = Complex(negative(a.real), negative(a.imaginary))
        return if (a.formula == null) value else decorate(value, UnaryFormula('~', a), a.depth + 1)
    }
    fun add(a: Complex, b: Complex) = binary(Complex(addReal(a.real, b.real), addReal(a.imaginary, b.imaginary)), '+', a, b)
    fun subtract(a: Complex, b: Complex) = add(a, negate(b))
    fun multiply(a: Complex, b: Complex) = binary(Complex(
        addReal(multiplyReal(a.real, b.real), negative(multiplyReal(a.imaginary, b.imaginary))),
        addReal(multiplyReal(a.real, b.imaginary), multiplyReal(a.imaginary, b.real))), '×', a, b)
    fun divide(a: Complex, b: Complex) = binary(divideParts(a, b), '÷', a, b)
    private fun divideParts(a: Complex, b: Complex): Complex {
        if (b.imaginary == zero) return Complex(divideReal(a.real, b.real), divideReal(a.imaginary, b.real))
        val norm = addReal(multiplyReal(b.real, b.real), multiplyReal(b.imaginary, b.imaginary))
        return Complex(divideReal(addReal(multiplyReal(a.real, b.real), multiplyReal(a.imaginary, b.imaginary)), norm),
            divideReal(addReal(multiplyReal(a.imaginary, b.real), negative(multiplyReal(a.real, b.imaginary))), norm))
    }
    fun sqrt(a: Complex): Complex {
        val value = sqrtParts(a)
        return if (a.formula == null) value else decorate(value, UnaryFormula('√', a), a.depth + 1)
    }
    private fun sqrtParts(a: Complex): Complex {
        if (a.imaginary == zero) {
            return if (sign(a.real) < 0) Complex(zero, root(negative(a.real))) else Complex(root(a.real), zero)
        }
        val magnitude = root(addReal(multiplyReal(a.real, a.real), multiplyReal(a.imaginary, a.imaginary)))
        val real = root(divideReal(addReal(magnitude, a.real), two))
        val imag = root(divideReal(addReal(magnitude, negative(a.real)), two))
        return Complex(real, if (sign(a.imaginary) < 0) negative(imag) else imag)
    }
    /** Integer powers stay exact; other powers use available exact roots, then principal Log. */
    fun power(a: Complex, b: Complex): Complex {
        tick()
        val exponent = if (b.formula == null && b.imaginary == zero && b.real is Number) field.rational(b.real.value) else null
        if (exponent == null || exponent.denominator != BigInteger.ONE) nonIntegerPower = true
        if (a.real == zero && a.imaginary == zero) {
            require(b.imaginary == zero && sign(b.real) != 0) { "Undefined zero power" }
            if (sign(b.real) < 0) throw ArithmeticException("Zero to negative power")
            return Complex(zero, zero)
        }
        if (exponent?.denominator == BigInteger.ONE) {
            val n = boundedInteger(exponent.numerator)
            if (n == 0) return Complex(one, zero)
            if (n == 1) return a
            if (a.formula is PowerFormula) return power(a.formula.base, multiply(a.formula.exponent, b))
            return if (a.formula == null && algebraic(a.real) && algebraic(a.imaginary)) integerPower(a, n)
                   else formalPower(a, b, n)
        }
        if (a.formula == null && a.real == one && a.imaginary == zero) return Complex(one, zero)
        // Euler's identity and constructible roots of unity use exact radicals, not tiny residuals.
        if (a.formula == null && a.real == Symbol('e') && a.imaginary == zero && b.real == zero) {
            piMultiple(b.imaginary)?.let { unitCircle(it)?.let { result -> return result } }
        }
        if (exponent != null) {
            val numerator = boundedInteger(exponent.numerator)
            val d = exponent.denominator
            if (d.bitCount() == 1 && d <= BigInteger.valueOf(256)) {
                var root = a
                repeat(d.bitLength() - 1) { root = sqrt(root) }
                return if (root.formula == null && algebraic(root.real) && algebraic(root.imaginary)) integerPower(root, numerator)
                       else formalPower(a, b, null)
            }
            if (d <= BigInteger.valueOf(64) && a.formula == null && a.imaginary == zero && a.real is Number) {
                field.rational(a.real.value)?.let { r ->
                    val degree = d.toInt()
                    val nr = integerRoot(r.numerator.abs(), degree)
                    val dr = integerRoot(r.denominator, degree)
                    if (nr != null && dr != null) {
                        val magnitude = Complex(Number(field.divide(field.number(BigDecimal(nr)), field.number(BigDecimal(dr)))), zero)
                        if (r.numerator.signum() >= 0) return integerPower(magnitude, numerator)
                        unitCircle(Rational.of(BigInteger.ONE, d))?.let { phase ->
                            return integerPower(multiply(magnitude, phase), numerator)
                        }
                    }
                }
            }
        }
        return formalPower(a, b, null)
    }
    private fun boundedInteger(n: BigInteger): Int {
        require(n.abs() <= BigInteger.valueOf(10000)) { "Integer exponent limit" }
        return n.toInt()
    }
    private fun integerPower(base: Complex, exponent: Int): Complex {
        var n = kotlin.math.abs(exponent)
        var factor = base
        var result = Complex(one, zero)
        while (n != 0) {
            if (n and 1 != 0) result = multiply(result, factor)
            n = n ushr 1
            if (n != 0) factor = multiply(factor, factor)
        }
        return if (exponent < 0) divide(Complex(one, zero), result) else result
    }
    private fun integerRoot(n: BigInteger, degree: Int): BigInteger? {
        var low = BigInteger.ZERO
        var high = BigInteger.ONE.shiftLeft((n.bitLength() + degree - 1) / degree + 1)
        while (high - low > BigInteger.ONE) {
            tick()
            val middle = (low + high).shiftRight(1)
            if (middle.pow(degree) <= n) low = middle else high = middle
        }
        return if (low.pow(degree) == n) low else null
    }
    private fun algebraic(a: Real): Boolean = when (a) {
        is Number -> true
        is Symbol -> a.name == 'φ'
        is Root -> algebraic(a.radicand)
        is Sum -> a.terms.all(::algebraic)
        is Product -> a.factors.all(::algebraic)
        is Quotient -> algebraic(a.numerator) && algebraic(a.denominator)
        is PowerPart, is LogPart, is TrigPart -> false
    }
    private fun piMultiple(a: Real): Rational? = when {
        a == zero -> Rational.ZERO
        a == Symbol('π') -> Rational.ONE
        a is Product && a.factors == listOf(Symbol('π')) -> field.rational(a.coefficient.value)
        else -> null
    }
    private fun unitCircle(angle: Rational): Complex? {
        var denominator = angle.denominator
        if (denominator > BigInteger.valueOf(256)) return null
        var halves = 0
        while (denominator.mod(BigInteger.TWO) == BigInteger.ZERO) { denominator /= BigInteger.TWO; halves++ }
        var unit = when (denominator.toInt()) {
            1 -> Complex(Number(field.negate(field.one)), zero)
            3 -> Complex(Number(field.number(BigDecimal("0.5"))), Number(field.divide(field.sqrt(field.number(BigDecimal(3))), two.value)))
            5 -> {
                val re = divideReal(addReal(one, Number(field.sqrt(field.number(BigDecimal(5))))), Number(field.number(BigDecimal(4))))
                Complex(re, root(addReal(one, negative(multiplyReal(re, re)))))
            }
            else -> return null
        }
        repeat(halves) { unit = sqrt(unit) }
        val n = angle.numerator.mod(angle.denominator * BigInteger.TWO).toInt()
        return integerPower(unit, n)
    }
    fun trigonometric(name: String, a: Complex): Complex {
        tick()
        if (name == "atan" && a.real == zero && (a.imaginary == one || a.imaginary == negative(one))) {
            throw TrigonometricPole()
        }
        val inverse = a.formula as? TrigFormula
        if (inverse != null && name == "tan" && inverse.name == "atan") {
            // Slope after principal atan returns its argument; atan(tan(z)) is not globally z.
            return inverse.argument
        }
        if (a.formula == null && a.imaginary == zero) {
            if (name in listOf("sin", "cos", "tan")) {
                piMultiple(a.real)?.let { angle -> unitCircle(angle)?.let { unit ->
                    return when (name) { "sin" -> Complex(unit.imaginary, zero); "cos" -> Complex(unit.real, zero)
                        else -> { if (unit.real == zero) throw TrigonometricPole(); Complex(divideReal(unit.imaginary, unit.real), zero) } }
                } }
            } else {
                // Recognize a finite exact table by algebraic equality, never by decimal proximity.
                val denominators = listOf(1, 2, 3, 4, 6)
                for (q in denominators) for (p in -q..q) {
                    val ratio = Rational.of(BigInteger.valueOf(p.toLong()), BigInteger.valueOf(q.toLong()))
                    val inRange = when(name) { "asin" -> 2*p in -q..q; "acos" -> p in 0..q; else -> 2*p > -q && 2*p < q }
                    if (!inRange) continue
                    val unit = unitCircle(ratio) ?: continue
                    val target = when(name) { "asin" -> unit.imaginary; "acos" -> unit.real
                        else -> if (unit.real == zero) continue else divideReal(unit.imaginary, unit.real) }
                    if (target == a.real) return Complex(multiplyReal(Number(field.divide(field.number(BigDecimal(p)), field.number(BigDecimal(q)))), Symbol('π')), zero)
                }
            }
        }
        val source = TrigFormula(name, a)
        trigDecimal(source)
        val realOnly = a.real == zero && name == "cos" || a.imaginary == zero && (name in listOf("sin", "cos", "tan", "atan") || sign(addReal(a.real, one)) >= 0 && sign(addReal(one, negative(a.real))) >= 0)
        val pureImaginary = a.real == zero && name in listOf("sin", "tan", "asin", "atan") &&
            (name != "atan" || sign(addReal(one, negative(multiplyReal(a.imaginary, a.imaginary)))) > 0)
        return decorate(Complex(if (pureImaginary) zero else TrigPart(source, false),
            if (realOnly) zero else TrigPart(source, true)), source, a.depth + 1)
    }
    private fun trigDecimal(source: TrigFormula): DecimalPowers.Value = trigDecimals.getOrPut(source) {
        DecimalTrigonometry.evaluate(source.name, DecimalPowers.Value(decimal(source.argument.real), decimal(source.argument.imaginary)))
    }
    /** No logarithm product/power identities are applied across complex branch cuts. */
    fun logarithm(value: Complex, base: Complex): Complex {
        tick()
        val source = LogFormula(value, base)
        logDecimal(source) // Validate zero arguments, unit base, magnitude and convergence first.
        if (value == Complex(one, zero)) return Complex(zero, zero)
        if (value == base) return Complex(one, zero)
        if (value.formula == null && base.formula == null && value.imaginary == zero && base.imaginary == zero &&
            value.real is Number && base.real is Number) {
            val x = field.rational(value.real.value)
            val b = field.rational(base.real.value)
            if (x != null && b != null) ExactLogarithms.rational(x, b)?.let {
                return Complex(Number(field.divide(field.number(BigDecimal(it.numerator)), field.number(BigDecimal(it.denominator)))), zero)
            }
        }
        fun naturalExact(a: Complex): Complex? = when (a) {
            Complex(one, zero) -> Complex(zero, zero)
            Complex(Symbol('e'), zero) -> Complex(one, zero)
            Complex(negative(one), zero) -> Complex(zero, Symbol('π'))
            Complex(zero, one) -> Complex(zero, divideReal(Symbol('π'), two))
            Complex(zero, negative(one)) -> Complex(zero, negative(divideReal(Symbol('π'), two)))
            else -> null
        }
        val numerator = naturalExact(value)
        val denominator = naturalExact(base)
        if (numerator != null && denominator != null) return divide(numerator, denominator)
        val depth = 1 + maxOf(value.depth, base.depth)
        val realOnly = value.imaginary == zero && base.imaginary == zero && sign(value.real) > 0 && sign(base.real) > 0
        val purelyImaginary = numerator != null && numerator.real == zero &&
            base.imaginary == zero && sign(base.real) > 0
        return decorate(Complex(if (purelyImaginary) zero else LogPart(source, false),
            if (realOnly) zero else LogPart(source, true)), source, depth)
    }
    private fun logDecimal(source: LogFormula): DecimalPowers.Value = logDecimals.getOrPut(source) {
        DecimalPowers.logarithm(DecimalPowers.Value(decimal(source.value.real), decimal(source.value.imaginary)),
            DecimalPowers.Value(decimal(source.base.real), decimal(source.base.imaginary)))
    }
    private fun formalPower(a: Complex, b: Complex, integer: Int?): Complex {
        val depth = 1 + maxOf(a.depth, b.depth)
        require(depth <= 16) { "Power expression depth limit" }
        val source = PowerFormula(a, b, integer)
        powerDecimal(source) // Validate domain, numerical capacity and convergence before display.
        val realOnly = b.imaginary == zero && a.imaginary == zero && sign(a.real) > 0 ||
            b.real == zero && a.formula == null && a.real is Number && a.imaginary is Number &&
                field.add(field.multiply(a.real.value, a.real.value), field.multiply(a.imaginary.value, a.imaginary.value)) == field.one
        return decorate(Complex(PowerPart(source, false), if (realOnly) zero else PowerPart(source, true)), source, depth)
    }
    private fun powerDecimal(source: PowerFormula): DecimalPowers.Value = powerDecimals.getOrPut(source) {
        DecimalPowers.power(DecimalPowers.Value(decimal(source.base.real), decimal(source.base.imaginary)),
            DecimalPowers.Value(decimal(source.exponent.real), decimal(source.exponent.imaginary)), source.integer)
    }
    private fun formulaText(formula: Formula): String {
        val result = when (formula) {
            is TrigFormula -> "${formula.name}(${exactText(formula.argument)})"
            is LogFormula -> "log(${exactText(formula.value)},${exactText(formula.base)})"
            is PowerFormula -> "(${exactText(formula.base)})^(${exactText(formula.exponent)})"
            is BinaryFormula -> "(${exactText(formula.a)})${formula.operator}(${exactText(formula.b)})"
            is UnaryFormula -> if (formula.operator == '√') "sqrt(${exactText(formula.a)})" else "-(${exactText(formula.a)})"
        }
        require(result.length <= 1000) { "Power output limit" }
        return result
    }

    private fun negative(a: Real) = multiplyReal(Number(field.negate(field.one)), a)
    private fun coefficient(a: Real): Pair<Number, Real?> = when (a) {
        is Number -> a to null
        is Product -> a.coefficient to when (a.factors.size) { 1 -> a.factors.single(); else -> Product(one, a.factors) }
        else -> one to a
    }
    private fun addReal(a: Real, b: Real): Real {
        tick()
        if (a == zero) return b
        if (b == zero) return a
        if (a is Number && b is Number) return Number(field.add(a.value, b.value))
        val terms = mutableMapOf<Real?, RadicalArithmetic.Value>()
        val flat = (if (a is Sum) a.terms else listOf(a)) + (if (b is Sum) b.terms else listOf(b))
        for (term in flat) {
            val (c, body) = coefficient(term)
            terms[body] = field.add(terms[body] ?: field.zero, c.value)
        }
        val result = terms.filterValues { it != field.zero }.map { (body, c) ->
            if (body == null) Number(c) else multiplyReal(Number(c), body)
        }.sortedBy(::text)
        require(result.size <= 128) { "Symbolic sum limit" }
        return when (result.size) { 0 -> zero; 1 -> result.single(); else -> Sum(result) }
    }
    private fun multiplyReal(a: Real, b: Real): Real {
        tick()
        if (a == zero || b == zero) return zero
        if (a == one) return b
        if (b == one) return a
        if (a is Number && b is Number) return Number(field.multiply(a.value, b.value))
        if (a is Quotient) return divideReal(multiplyReal(a.numerator, b), a.denominator)
        if (b is Quotient) return divideReal(multiplyReal(a, b.numerator), b.denominator)
        if (a is Sum) return a.terms.map { multiplyReal(it, b) }.fold(zero as Real, ::addReal)
        if (b is Sum) return b.terms.map { multiplyReal(a, it) }.fold(zero as Real, ::addReal)
        var c = field.one
        val factors = mutableListOf<Real>()
        for (part in listOf(a, b)) when (part) {
            is Number -> c = field.multiply(c, part.value)
            is Product -> { c = field.multiply(c, part.coefficient.value); factors += part.factors }
            else -> factors += part
        }
        require(factors.size <= 32) { "Symbolic product limit" }
        for (factor in factors.distinct()) {
            if ((factor is Root || factor == Symbol('φ')) && factors.count { it == factor } >= 2) {
                factors.remove(factor); factors.remove(factor)
                val reduced = if (factor is Root) factor.radicand else addReal(one, Symbol('φ'))
                return factors.fold(multiplyReal(Number(c), reduced), ::multiplyReal)
            }
        }
        val sorted = factors.sortedBy(::text)
        return if (sorted.isEmpty()) Number(c) else if (c == field.one && sorted.size == 1) sorted.single()
               else Product(Number(c), sorted)
    }
    private fun divideReal(a: Real, b: Real): Real {
        tick()
        if (b == zero) throw ArithmeticException("Divide by zero")
        if (a == zero) return zero
        if (a == b) return one
        if (b is Number) return multiplyReal(a, Number(field.divide(field.one, b.value)))
        if (sign(b) < 0) return divideReal(negative(a), negative(b))
        if (a is Quotient) return divideReal(a.numerator, multiplyReal(a.denominator, b))
        if (b is Quotient) return divideReal(multiplyReal(a, b.denominator), b.numerator)
        if (b is Root) return divideReal(multiplyReal(a, b), b.radicand)
        val (ac, ab) = coefficient(a)
        val (bc, bb) = coefficient(b)
        val af = when (ab) { null -> mutableListOf(); is Product -> ab.factors.toMutableList(); else -> mutableListOf(ab) }
        val bf = when (bb) { null -> mutableListOf(); is Product -> bb.factors.toMutableList(); else -> mutableListOf(bb) }
        for (factor in af.toList()) if (bf.remove(factor)) af.remove(factor)
        val numerator = af.fold(Number(field.divide(ac.value, bc.value)) as Real, ::multiplyReal)
        val denominator = bf.fold(one as Real, ::multiplyReal)
        return if (denominator == one) numerator else Quotient(numerator, denominator)
    }
    private fun root(a: Real): Real {
        tick()
        if (a is Number) return Number(field.sqrt(a.value))
        require(sign(a) >= 0) { "Negative real root" }
        // All atoms are positive: extract constant and paired factors from a single product.
        if (a is Product) {
            val remaining = a.factors.toMutableList()
            var outside: Real = Number(field.sqrt(a.coefficient.value))
            for (factor in remaining.distinct()) while (remaining.count { it == factor } >= 2) {
                remaining.remove(factor); remaining.remove(factor)
                outside = multiplyReal(outside, factor)
            }
            val inside = remaining.fold(one as Real, ::multiplyReal)
            return multiplyReal(outside, if (inside == one) one else boundedRoot(inside))
        }
        if (a is Quotient) return divideReal(root(multiplyReal(a.numerator, a.denominator)), a.denominator)
        return boundedRoot(a)
    }
    private fun boundedRoot(a: Real): Real {
        fun depth(value: Real): Int = when (value) {
            is Root -> 1 + depth(value.radicand)
            is Sum -> value.terms.maxOf(::depth)
            is Product -> value.factors.maxOf(::depth)
            is Quotient -> maxOf(depth(value.numerator), depth(value.denominator))
            else -> 0
        }
        require(depth(a) < 8) { "Symbolic root depth limit" }
        return Root(a)
    }
    private fun sign(a: Real): Int = if (a is Number) field.sign(a.value) else decimal(a).signum()
    private fun decimal(a: Real): BigDecimal = decimals.getOrPut(a) {
        tick()
        when (a) {
            is Number -> field.decimal(a.value)
            is TrigPart -> trigDecimal(a.source).let { if (a.imaginary) it.imaginary else it.real }
            is LogPart -> logDecimal(a.source).let { if (a.imaginary) it.imaginary else it.real }
            is PowerPart -> powerDecimal(a.source).let { if (a.imaginary) it.imaginary else it.real }
            is Symbol -> when (a.name) {
                'e' -> MathematicalConstants.e
                'π' -> MathematicalConstants.pi
                'φ' -> BigDecimal(5).sqrt(context).add(BigDecimal.ONE).divide(BigDecimal(2), context)
                else -> error("Unknown real constant")
            }
            is Root -> decimal(a.radicand).sqrt(context)
            is Sum -> a.terms.fold(BigDecimal.ZERO) { sum, term -> sum.add(decimal(term), context) }
            is Product -> a.factors.fold(field.decimal(a.coefficient.value)) { product, factor -> product.multiply(decimal(factor), context) }
            is Quotient -> decimal(a.numerator).divide(decimal(a.denominator), context)
        }
    }
    private fun parenthesized(a: Real): String {
        val s = text(a)
        return if (a is Sum || a is Quotient || (a is Number && (s.contains('+') || s.drop(1).contains('-') || s.contains('/')))) "($s)" else s
    }
    private fun text(a: Real): String {
        tick()
        val result = when (a) {
            is Number -> field.exactText(a.value)
            // Internal sorting key only. A Complex containing these parts retains its Formula.
            is TrigPart -> "#T${if (a.imaginary) "I" else "R"}${formulaText(a.source)}"
            is LogPart -> "#L${if (a.imaginary) "I" else "R"}${formulaText(a.source)}"
            is PowerPart -> "#${if (a.imaginary) "I" else "R"}${formulaText(a.source)}"
            is Symbol -> a.name.toString()
            is Root -> if (a.radicand is Symbol) "√${text(a.radicand)}" else "√(${text(a.radicand)})"
            is Sum -> a.terms.joinToString("+") { text(it) }.replace("+-", "-")
            is Product -> {
                val r = field.rational(a.coefficient.value)
                val factors = a.factors.joinToString("×") { parenthesized(it) }
                if (r != null) {
                    val n = r.numerator
                    (if (n == BigInteger.ONE) "" else if (n == -BigInteger.ONE) "-" else n.toString()) + factors +
                        if (r.denominator == BigInteger.ONE) "" else "/${r.denominator}"
                } else parenthesized(a.coefficient) + "×" + factors
            }
            is Quotient -> parenthesized(a.numerator) + "/" + parenthesized(a.denominator)
        }
        require(result.length <= 1000) { "Symbolic output limit" }
        return result
    }
    fun exactText(a: Complex): String {
        a.formula?.let { return formulaText(it) }
        if (a.imaginary == zero) return text(a.real)
        if (a.real == zero) return "(${text(a.imaginary)})i"
        val isNegative = sign(a.imaginary) < 0
        val magnitude = if (isNegative) negative(a.imaginary) else a.imaginary
        return text(a.real) + (if (isNegative) "-" else "+") + "(${text(magnitude)})i"
    }
    fun decimalText(a: Complex, scale: Int = 4): String {
        fun rounded(r: Real) = decimal(r).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros()
        fun approximate(r: Real, n: BigDecimal) = if (r is Number) field.rational(r.value)?.let { it != Rational.of(n) } ?: true else true
        val re = rounded(a.real)
        val im = rounded(a.imaginary)
        val realText = re.toPlainString() + if (approximate(a.real, re)) "…" else ""
        val imaginaryApproximation = if (approximate(a.imaginary, im)) "…" else ""
        if (a.imaginary == zero) return realText
        if (a.real == zero) return "(${im.toPlainString()}$imaginaryApproximation)i"
        return realText + (if (im.signum() < 0) "-" else "+") +
            "(${im.abs().toPlainString()}$imaginaryApproximation)i"
    }
}

/** Computed once, offline, with guard digits; no binary floating-point constants. */
internal object MathematicalConstants {
    private val context = MathContext(204, RoundingMode.HALF_UP)
    private val epsilon = BigDecimal.ONE.scaleByPowerOfTen(-203)
    val e: BigDecimal by lazy {
        var sum = BigDecimal.ONE
        var term = BigDecimal.ONE
        var n = 1
        do { term = term.divide(BigDecimal(n++), context); sum = sum.add(term, context) } while (term.abs() > epsilon)
        sum
    }
    val pi: BigDecimal by lazy {
        fun arctan(reciprocal: Int): BigDecimal {
            val x = BigDecimal.ONE.divide(BigDecimal(reciprocal), context)
            val square = x.multiply(x, context).negate()
            var power = x
            var sum = x
            var n = 3
            do {
                power = power.multiply(square, context)
                val term = power.divide(BigDecimal(n), context)
                sum = sum.add(term, context)
                n += 2
            } while (term.abs() > epsilon)
            return sum
        }
        arctan(5).multiply(BigDecimal(16), context).subtract(arctan(239).multiply(BigDecimal(4), context), context)
    }
}
