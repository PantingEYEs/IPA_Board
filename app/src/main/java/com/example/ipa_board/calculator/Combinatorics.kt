package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.BigInteger

/** Bounded exact integer arithmetic; invalid arguments fall back through the evaluator. */
internal object Combinatorics {
    private fun integer(value: BigDecimal): Int {
        val n = value.stripTrailingZeros()
        require(n.scale() <= 0 && n.signum() >= 0 && n <= BigDecimal(10000)) { "Integer argument outside 0..10000" }
        return n.toInt()
    }

    private fun bounded(value: BigInteger): BigInteger {
        require(value.bitLength() <= 3321 && value.toString().length <= 1000) { "Combinatorial result limit" }
        return value
    }

    fun factorial(value: BigDecimal): BigDecimal {
        val n = integer(value)
        var result = BigInteger.ONE
        for (i in 2..n) result = bounded(result.multiply(BigInteger.valueOf(i.toLong())))
        return BigDecimal(result)
    }

    fun choose(nValue: BigDecimal, rValue: BigDecimal, permutation: Boolean): BigDecimal {
        val n = integer(nValue)
        val r = integer(rValue)
        require(r <= n) { "r must not exceed n" }
        val count = if (permutation) r else minOf(r, n - r)
        var result = BigInteger.ONE
        for (i in 1..count) {
            result = result.multiply(BigInteger.valueOf((n - i + 1).toLong()))
            if (!permutation) result = result.divide(BigInteger.valueOf(i.toLong()))
            result = bounded(result)
        }
        return BigDecimal(result)
    }
}
