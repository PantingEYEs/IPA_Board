package com.example.ipa_board.calculator

import java.math.BigInteger

/** Prove small rational logarithms by exact equality; numerical coincidence is never proof. */
internal object ExactLogarithms {
    fun rational(value: Rational, base: Rational): Rational? {
        if (value.numerator.signum() <= 0 || base.numerator.signum() <= 0 || base == Rational.ONE) return null
        val powers = mutableMapOf(Rational.ONE to 0)
        var positive = Rational.ONE
        for (n in 1..512) {
            // The normal exact coefficient cap also bounds this optional simplification search.
            val next = try { positive * base } catch (_: IllegalArgumentException) { break }
            positive = next
            powers[positive] = n
            powers[Rational.ONE / positive] = -n
        }
        var powered = Rational.ONE
        for (q in 1..64) {
            powered = try { powered * value } catch (_: IllegalArgumentException) { break }
            powers[powered]?.let { return Rational.of(BigInteger.valueOf(it.toLong()), BigInteger.valueOf(q.toLong())) }
        }
        return null
    }
}
