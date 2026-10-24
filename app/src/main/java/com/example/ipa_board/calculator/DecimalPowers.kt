package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Bounded high precision principal powers, without Double or external runtime dependencies. */
internal object DecimalPowers {
    data class Value(val real: BigDecimal, val imaginary: BigDecimal)
    private val mc = MathContext(204, RoundingMode.HALF_UP)
    private val epsilon = BigDecimal.ONE.scaleByPowerOfTen(-202)
    private val two = BigDecimal(2)
    private val pi get() = MathematicalConstants.pi.round(mc)
    private fun checked(v: Value): Value {
        for (part in listOf(v.real, v.imaginary)) {
            require(part.precision() - part.scale() <= 1000 && part.scale() <= 10000) { "Power magnitude limit" }
        }
        return v
    }
    private fun multiply(a: Value, b: Value) = checked(Value(
        a.real.multiply(b.real, mc).subtract(a.imaginary.multiply(b.imaginary, mc), mc),
        a.real.multiply(b.imaginary, mc).add(a.imaginary.multiply(b.real, mc), mc)))
    fun power(base: Value, exponent: Value, integer: Int?): Value {
        if (integer != null) {
            var n = kotlin.math.abs(integer)
            var factor = base
            var result = Value(BigDecimal.ONE, BigDecimal.ZERO)
            while (n != 0) {
                if (n and 1 != 0) result = multiply(result, factor)
                n = n ushr 1
                if (n != 0) factor = multiply(factor, factor)
            }
            if (integer < 0) {
                val norm = result.real.multiply(result.real, mc).add(result.imaginary.multiply(result.imaginary, mc), mc)
                result = Value(BigDecimal.ONE.divide(norm, mc).multiply(result.real, mc), result.imaginary.negate().divide(norm, mc))
            }
            return checked(result)
        }
        // z^w = exp(w Log(z)), Arg(z) in (-π,π], including the upper negative-real cut.
        val norm = base.real.multiply(base.real, mc).add(base.imaginary.multiply(base.imaginary, mc), mc)
        require(norm.signum() > 0) { "Undefined zero power" }
        val logRadius = log(norm).divide(two, mc)
        val angle = atan2(base.imaginary, base.real)
        val real = exponent.real.multiply(logRadius, mc).subtract(exponent.imaginary.multiply(angle, mc), mc)
        val imaginary = exponent.real.multiply(angle, mc).add(exponent.imaginary.multiply(logRadius, mc), mc)
        val radius = exp(real)
        val (sin, cos) = sinCos(imaginary)
        return checked(Value(radius.multiply(cos, mc), radius.multiply(sin, mc)))
    }
    private fun logSmall(value: BigDecimal): BigDecimal {
        var reduced = value
        repeat(8) { reduced = reduced.sqrt(mc) }
        val t = reduced.subtract(BigDecimal.ONE, mc).divide(reduced.add(BigDecimal.ONE, mc), mc)
        val square = t.multiply(t, mc)
        var power = t
        var sum = t
        var denominator = 3
        repeat(1024) {
            power = power.multiply(square, mc)
            val term = power.divide(BigDecimal(denominator), mc)
            sum = sum.add(term, mc)
            if (term.abs() < epsilon) return sum.multiply(BigDecimal(512), mc)
            denominator += 2
        }
        error("Logarithm convergence limit")
    }
    private val logTen by lazy { logSmall(BigDecimal.TEN) }
    private fun log(value: BigDecimal): BigDecimal {
        require(value.signum() > 0) { "Non-positive logarithm" }
        val exponent = value.precision() - value.scale() - 1
        require(exponent in -10000..10000) { "Logarithm magnitude limit" }
        return logSmall(value.scaleByPowerOfTen(-exponent)).add(logTen.multiply(BigDecimal(exponent), mc), mc)
    }
    private fun exp(value: BigDecimal): BigDecimal {
        require(value <= BigDecimal(2300)) { "Exponential magnitude limit" }
        if (value < BigDecimal(-2300)) return BigDecimal.ZERO
        var reduced = value
        var squares = 0
        while (reduced.abs() > BigDecimal("0.125")) { reduced = reduced.divide(two, mc); squares++ }
        var sum = BigDecimal.ONE
        var term = BigDecimal.ONE
        for (n in 1..2048) {
            term = term.multiply(reduced, mc).divide(BigDecimal(n), mc)
            sum = sum.add(term, mc)
            if (term.abs() < epsilon) {
                repeat(squares) { sum = sum.multiply(sum, mc) }
                return sum
            }
        }
        error("Exponential convergence limit")
    }
    private fun atan(value: BigDecimal): BigDecimal {
        if (value.signum() < 0) return atan(value.negate()).negate()
        if (value > BigDecimal.ONE) return pi.divide(two, mc).subtract(atan(BigDecimal.ONE.divide(value, mc)), mc)
        var reduced = value
        repeat(4) {
            reduced = reduced.divide(BigDecimal.ONE.add(BigDecimal.ONE.add(reduced.multiply(reduced, mc), mc).sqrt(mc), mc), mc)
        }
        val square = reduced.multiply(reduced, mc).negate()
        var power = reduced
        var sum = reduced
        var denominator = 3
        repeat(1024) {
            power = power.multiply(square, mc)
            val term = power.divide(BigDecimal(denominator), mc)
            sum = sum.add(term, mc)
            if (term.abs() < epsilon) return sum.multiply(BigDecimal(16), mc)
            denominator += 2
        }
        error("Argument convergence limit")
    }
    private fun atan2(y: BigDecimal, x: BigDecimal): BigDecimal = when {
        x.signum() > 0 -> atan(y.divide(x, mc))
        x.signum() < 0 -> atan(y.divide(x, mc)).add(if (y.signum() < 0) pi.negate() else pi, mc)
        y.signum() > 0 -> pi.divide(two, mc)
        y.signum() < 0 -> pi.divide(two, mc).negate()
        else -> error("Undefined argument")
    }
    private fun sinCos(angle: BigDecimal): Pair<BigDecimal, BigDecimal> {
        require(angle.precision() - angle.scale() <= 128) { "Angular reduction limit" }
        var x = angle.remainder(pi.multiply(two, mc), mc)
        if (x > pi) x = x.subtract(pi.multiply(two, mc), mc)
        if (x < pi.negate()) x = x.add(pi.multiply(two, mc), mc)
        val square = x.multiply(x, mc).negate()
        var sin = x
        var cos = BigDecimal.ONE
        var sinTerm = x
        var cosTerm = BigDecimal.ONE
        for (n in 1..2048) {
            sinTerm = sinTerm.multiply(square, mc).divide(BigDecimal((2*n)*(2*n+1)), mc)
            cosTerm = cosTerm.multiply(square, mc).divide(BigDecimal((2*n-1)*(2*n)), mc)
            sin = sin.add(sinTerm, mc)
            cos = cos.add(cosTerm, mc)
            if (sinTerm.abs() < epsilon && cosTerm.abs() < epsilon) return sin to cos
        }
        error("Trigonometric convergence limit")
    }
}
