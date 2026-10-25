package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.random.Random

/** Per-editor ephemeral samples: asynchronous candidate refreshes must not reroll the composition. */
class CalculatorSession(private val random: Random = Random.Default) {
    private var input: String? = null
    private val samples = mutableMapOf<Int, BigDecimal>()
    internal var resultScale = 4
        private set

    internal fun begin(expression: String) {
        if (input != expression) {
            reset()
            input = expression
        }
    }

    fun reset() {
        input = null
        samples.clear()
        resultScale = 4
    }

    internal fun sample(index: Int, lower: BigDecimal, upper: BigDecimal): BigDecimal {
        samples[index]?.let { return it }
        require(index in 0 until 512) { "Random sample limit" }
        // Uniform decimal grid, refined for narrow intervals. This keeps displayed values inside [a,b).
        var scale = 4
        var first: BigInteger
        var last: BigInteger
        while (true) {
            first = lower.movePointRight(scale).setScale(0, RoundingMode.CEILING).toBigIntegerExact()
            last = upper.movePointRight(scale).setScale(0, RoundingMode.CEILING).toBigIntegerExact() - BigInteger.ONE
            if (first <= last) break
            require(++scale <= 128) { "Random interval precision limit" }
        }
        val count = last - first + BigInteger.ONE
        require(count.bitLength() <= 1024) { "Random interval magnitude limit" }
        val bits = (count - BigInteger.ONE).bitLength()
        var offset = BigInteger.ZERO
        if (bits != 0) {
            var accepted = false
            repeat(128) {
                if (!accepted) {
                    val bytes = random.nextBytes((bits + 7) / 8)
                    bytes[0] = (bytes[0].toInt() and (255 ushr ((8 - bits % 8) % 8))).toByte()
                    offset = BigInteger(1, bytes)
                    accepted = offset < count
                }
            }
            require(accepted) { "Random rejection limit" }
        }
        val result = BigDecimal(first + offset, scale).stripTrailingZeros()
        resultScale = maxOf(resultScale, scale)
        samples[index] = result
        return result
    }
}
