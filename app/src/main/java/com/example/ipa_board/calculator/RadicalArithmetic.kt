package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/** Exact fractions: decimal input is never converted through Double. */
@ConsistentCopyVisibility
internal data class Rational private constructor(val numerator: BigInteger, val denominator: BigInteger) {
    operator fun plus(other: Rational) = of(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)
    operator fun unaryMinus() = of(-numerator, denominator)
    operator fun minus(other: Rational) = this + -other
    operator fun times(other: Rational) = of(numerator * other.numerator, denominator * other.denominator)
    operator fun div(other: Rational) = of(numerator * other.denominator, denominator * other.numerator)
    fun decimal(context: MathContext) = BigDecimal(numerator).divide(BigDecimal(denominator), context)

    companion object {
        val ZERO = of(BigInteger.ZERO)
        val ONE = of(BigInteger.ONE)
        fun of(n: BigInteger, d: BigInteger = BigInteger.ONE): Rational {
            if (d.signum() == 0) throw ArithmeticException("Divide by zero")
            val gcd = n.gcd(d)
            val sign = BigInteger.valueOf(d.signum().toLong())
            val numerator = n / gcd * sign
            val denominator = d / gcd * sign
            require(numerator.bitLength() <= 512 && denominator.bitLength() <= 512) { "Exact coefficient limit" }
            return Rational(numerator, denominator)
        }
        fun of(number: BigDecimal): Rational {
            require(number.precision() <= 128 && number.scale() in -128..128) { "Number limit" }
            return if (number.scale() < 0) of(number.toBigIntegerExact())
            else of(number.unscaledValue(), BigInteger.TEN.pow(number.scale()))
        }
    }
}

/**
 * An expression-local real quadratic extension tower. Each basis bit represents one positive
 * square root of a value in the preceding field. Multiplication reduces its square; conjugation
 * makes division exact and rationalizes denominators, including those containing nested roots.
 * No global cache or approximate value is used to establish algebraic equality.
 */
internal class RadicalArithmetic {
    data class Value(val terms: Map<Int, Rational>)
    private data class Generator(val square: Value, val prime: BigInteger?, val decimal: BigDecimal)
    private val generators = mutableListOf<Generator>()
    private val precision = MathContext(192, RoundingMode.HALF_UP)
    private var work = 0
    val zero = Value(emptyMap())
    val one = constant(Rational.ONE)
    private val two = constant(Rational.of(BigInteger.TWO))

    // Calculator work runs on the IME thread. Bound symbolic expansion/factorization rather than
    // allowing adversarial expressions to freeze typing; callers retain literal input on failure.
    private fun tick() { require(++work <= 50000) { "Symbolic work limit" } }
    private fun value(terms: Map<Int, Rational>): Value {
        tick()
        require(terms.size <= 256) { "Symbolic term limit" }
        return Value(terms.filterValues { it.numerator.signum() != 0 })
    }
    private fun constant(number: Rational) = if (number == Rational.ZERO) zero else Value(mapOf(0 to number))
    fun number(number: BigDecimal) = constant(Rational.of(number))
    fun negate(v: Value) = value(v.terms.mapValues { -it.value })
    fun add(a: Value, b: Value): Value {
        val terms = a.terms.toMutableMap()
        b.terms.forEach { (mask, coefficient) -> terms[mask] = (terms[mask] ?: Rational.ZERO) + coefficient }
        return value(terms)
    }
    fun subtract(a: Value, b: Value) = add(a, negate(b))
    fun multiply(a: Value, b: Value): Value {
        var result = zero
        for ((ma, ca) in a.terms) for ((mb, cb) in b.terms) {
            tick()
            var term = value(mapOf((ma xor mb) to ca * cb))
            var shared = ma and mb
            while (shared != 0) {
                val bit = Integer.numberOfTrailingZeros(shared)
                term = multiply(term, generators[bit].square)
                shared = shared and (shared - 1)
            }
            result = add(result, term)
        }
        return result
    }
    fun divide(a: Value, b: Value) = multiply(a, inverse(b))
    private fun split(v: Value, level: Int): Pair<Value, Value> {
        val bit = 1 shl level
        return value(v.terms.filterKeys { it and bit == 0 }) to
            value(v.terms.filterKeys { it and bit != 0 }.mapKeys { it.key xor bit })
    }
    private fun inverse(v: Value): Value {
        tick()
        if (v.terms.isEmpty()) throw ArithmeticException("Divide by zero")
        val mask = v.terms.keys.fold(0) { a, b -> a or b }
        if (mask == 0) return constant(Rational.ONE / v.terms.getValue(0))
        val level = 31 - Integer.numberOfLeadingZeros(mask)
        val (a, b) = split(v, level)
        val norm = subtract(multiply(a, a), multiply(multiply(b, b), generators[level].square))
        val conjugate = subtract(a, multiply(b, basis(level)))
        return multiply(conjugate, inverse(norm))
    }
    private fun basis(level: Int) = Value(mapOf((1 shl level) to Rational.ONE))
    fun rational(v: Value): Rational? = when {
        v.terms.isEmpty() -> Rational.ZERO
        v.terms.keys == setOf(0) -> v.terms.getValue(0)
        else -> null
    }
    /** Exact ordering in the chosen real embedding; all generators denote positive roots. */
    fun sign(v: Value): Int {
        tick()
        rational(v)?.let { return it.numerator.signum() }
        val mask = v.terms.keys.fold(0) { a, b -> a or b }
        val level = 31 - Integer.numberOfLeadingZeros(mask)
        val (a, b) = split(v, level)
        val sa = sign(a)
        val sb = sign(b)
        if (sa == 0) return sb
        if (sb == 0 || sa == sb) return sa
        val norm = subtract(multiply(a, a), multiply(multiply(b, b), generators[level].square))
        return sa * sign(norm)
    }
    private fun perfectRoot(r: Rational): Rational? {
        if (r.numerator.signum() < 0) return null
        val n = r.numerator.sqrt()
        val d = r.denominator.sqrt()
        return if (n * n == r.numerator && d * d == r.denominator) Rational.of(n, d) else null
    }

    /** Find an existing-field square before adjoining a new root (e.g. √(3+2√2)=1+√2). */
    private fun existingRoot(v: Value, level: Int): Value? {
        tick()
        if (v.terms.isEmpty()) return zero
        if (level < 0) return perfectRoot(requireNotNull(rational(v)))?.let(::constant)
        val (a, b) = split(v, level)
        val square = generators[level].square
        if (b.terms.isEmpty()) {
            existingRoot(a, level - 1)?.let { return it }
            return existingRoot(divide(a, square), level - 1)?.let { multiply(it, basis(level)) }
        }
        val norm = subtract(multiply(a, a), multiply(multiply(b, b), square))
        val normRoot = existingRoot(norm, level - 1) ?: return null
        for (signedRoot in listOf(normRoot, negate(normRoot))) {
            val c = existingRoot(divide(add(a, signedRoot), two), level - 1) ?: continue
            if (c.terms.isEmpty()) continue
            val d = divide(b, multiply(two, c))
            val candidate = add(c, multiply(d, basis(level)))
            if (multiply(candidate, candidate) == v) return candidate
        }
        return null
    }

    fun sqrt(v: Value): Value {
        tick()
        rational(v)?.let { r ->
            require(r.numerator.signum() >= 0) { "Non-real square root" }
            perfectRoot(r)?.let { return constant(it) }
            // √(n/d)=√(n*d)/d. Prime generators give √2*√3 and √6 the same basis.
            val factors = factorize(r.numerator * r.denominator)
            var result = constant(Rational.of(factors.first, r.denominator))
            for (prime in factors.second) {
                var index = generators.indexOfFirst { it.prime == prime }
                val square = constant(Rational.of(prime))
                val existing = if (index < 0 && generators.any { it.prime == null })
                    existingRoot(square, generators.lastIndex) else null
                if (existing != null) {
                    result = multiply(result, if (sign(existing) < 0) negate(existing) else existing)
                } else {
                    if (index < 0) index = introduce(square, prime)
                    result = multiply(result, basis(index))
                }
            }
            return result
        }
        require(sign(v) > 0) { "Non-real square root" }
        denestBinomial(v)?.let { return it }
        existingRoot(v, generators.lastIndex)?.let {
            return if (sign(it) < 0) negate(it) else it
        }
        // Extract a common rational square from nested radicands as well as ordinary integers.
        val commonDenominator = v.terms.values.fold(BigInteger.ONE) { d, c -> d / d.gcd(c.denominator) * c.denominator }
        val commonNumerator = v.terms.values.map { (it.numerator * (commonDenominator / it.denominator)).abs() }
            .reduce(BigInteger::gcd)
        val factor = Rational.of(factorize(commonNumerator).first, factorize(commonDenominator).first)
        if (factor != Rational.ONE) {
            val outside = constant(factor)
            return multiply(outside, sqrt(divide(v, multiply(outside, outside))))
        }
        val known = generators.indexOfFirst { it.square == v }
        return basis(if (known >= 0) known else introduce(v, null))
    }

    /** Return the extracted square factor and remaining odd prime factors. */
    private fun factorize(number: BigInteger): Pair<BigInteger, List<BigInteger>> {
        require(number.signum() > 0 && number.bitLength() <= 128) { "Radicand factorization limit" }
        var remainder = number
        var outside = BigInteger.ONE
        val primes = mutableListOf<BigInteger>()
        var divisor = BigInteger.TWO
        var checked: BigInteger? = null
        while (remainder > BigInteger.ONE) {
            tick()
            if (checked != remainder) {
                val root = remainder.sqrt()
                if (root * root == remainder) { outside *= root; break }
                if (remainder.isProbablePrime(100)) { primes += remainder; break }
                checked = remainder
            }
            if (divisor * divisor > remainder) { primes += remainder; break }
            var power = 0
            while (remainder.mod(divisor) == BigInteger.ZERO) { remainder /= divisor; power++ }
            outside *= divisor.pow(power / 2)
            if (power % 2 != 0) primes += divisor
            divisor = if (divisor == BigInteger.TWO) BigInteger.valueOf(3) else divisor + BigInteger.TWO
        }
        return outside to primes
    }

    /** √(a+b√d) can require a NEW rational root even if it denests, e.g. √(2+√3). */
    private fun denestBinomial(v: Value): Value? {
        if (v.terms.size != 2 || 0 !in v.terms) return null
        val (mask, b) = v.terms.entries.first { it.key != 0 }
        if (generators.indices.any { mask and (1 shl it) != 0 && generators[it].prime == null }) return null
        val a = v.terms.getValue(0)
        val d = generators.indices.filter { mask and (1 shl it) != 0 }
            .fold(BigInteger.ONE) { product, i -> product * requireNotNull(generators[i].prime) }
        val normRoot = perfectRoot(a * a - b * b * Rational.of(d)) ?: return null
        val c2 = (a + normRoot) / Rational.of(BigInteger.TWO)
        val d2 = (a - normRoot) / Rational.of(BigInteger.TWO)
        if (c2.numerator.signum() < 0 || d2.numerator.signum() < 0) return null
        val c = sqrt(constant(c2))
        val e = sqrt(constant(d2))
        val candidate = if (b.numerator.signum() < 0) subtract(c, e) else add(c, e)
        if (multiply(candidate, candidate) != v) return null
        return if (sign(candidate) < 0) negate(candidate) else candidate
    }
    private fun introduce(square: Value, prime: BigInteger?): Int {
        require(generators.size < 8) { "Quadratic extension limit" }
        val decimal = decimal(square)
        require(decimal.signum() > 0) { "Non-real square root" }
        generators += Generator(square, prime, decimal.sqrt(precision))
        return generators.lastIndex
    }
    fun decimal(v: Value): BigDecimal = v.terms.entries.fold(BigDecimal.ZERO) { sum, (mask, coefficient) ->
        var term = coefficient.decimal(precision)
        generators.indices.filter { mask and (1 shl it) != 0 }.forEach {
            term = term.multiply(generators[it].decimal, precision)
        }
        sum.add(term, precision)
    }
    fun decimalText(v: Value): String {
        val rounded = decimal(v).setScale(4, RoundingMode.HALF_UP)
        val approximate = rational(v)?.let { it != Rational.of(rounded) } ?: true
        return rounded.stripTrailingZeros().toPlainString() + if (approximate) "…" else ""
    }
    fun exactText(v: Value): String {
        tick()
        if (v.terms.isEmpty()) return "0"
        val denominator = v.terms.values.fold(BigInteger.ONE) { d, c ->
            (d / d.gcd(c.denominator) * c.denominator).also {
                require(it.bitLength() <= 2048) { "Exact output denominator limit" }
            }
        }
        val numerator = buildString {
            for ((mask, coefficient) in v.terms.toSortedMap()) {
                val n = coefficient.numerator * (denominator / coefficient.denominator)
                if (n.signum() < 0) append('-') else if (isNotEmpty()) append('+')
                val roots = mutableListOf<String>()
                var primeProduct = BigInteger.ONE
                generators.forEachIndexed { i, g ->
                    if (mask and (1 shl i) != 0) {
                        if (g.prime != null) primeProduct *= g.prime
                        else roots += "√(${exactText(g.square)})"
                    }
                }
                if (primeProduct != BigInteger.ONE) roots.add(0, "√$primeProduct")
                if (roots.isEmpty() || n.abs() != BigInteger.ONE) append(n.abs())
                append(roots.joinToString("×"))
                require(length <= 1000) { "Exact output length limit" }
            }
        }
        val result = if (denominator == BigInteger.ONE) numerator
        else (if (v.terms.size > 1) "($numerator)" else numerator) + "/$denominator"
        require(result.length <= 1000) { "Exact output length limit" }
        return result
    }
}
