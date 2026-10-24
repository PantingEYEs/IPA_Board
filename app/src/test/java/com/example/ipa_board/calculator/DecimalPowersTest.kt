package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.MathContext
import org.junit.Assert.*
import org.junit.Test

class DecimalPowersTest {
    private fun value(real: String, imaginary: String = "0") = DecimalPowers.Value(BigDecimal(real), BigDecimal(imaginary))

    @Test fun positiveRealPowersAgreeWithIndependentPlatformMathAcrossScales() {
        for (base in listOf("0.1", "0.5", "2", "10")) for (exponent in listOf("-2.5", "-0.2", "0.3", "1.25", "2.4")) {
            val expected = Math.pow(base.toDouble(), exponent.toDouble())
            val actual = DecimalPowers.power(value(base), value(exponent), null)
            assertEquals("$base^$exponent", expected, actual.real.toDouble(), kotlin.math.abs(expected)*1e-12 + 1e-12)
            assertEquals(0, actual.imaginary.signum())
        }
    }

    @Test fun independentComplexReferenceValuesCoverAllArgumentQuadrants() {
        // Fixed reference values from Python cmath, used only as an independent host oracle.
        val samples = listOf(
            listOf("2", "3", "-0.5", "1.2", "0.08091248521577872", "0.1402660483275154"),
            listOf("-2", "3", "0.3", "-0.7", "6.451398670261631", "-1.6479488683116663"),
            listOf("-2", "-3", "0.3", "-0.7", "0.00824151083854418", "-0.32408803672667447"),
            listOf("0.1", "0.2", "-1.5", "0.75", "-3.861811154189183", "-1.4424525653643654")
        )
        for (s in samples) {
            val actual = DecimalPowers.power(value(s[0], s[1]), value(s[2], s[3]), null)
            assertEquals(s.toString(), s[4].toDouble(), actual.real.toDouble(), 1e-12)
            assertEquals(s.toString(), s[5].toDouble(), actual.imaginary.toDouble(), 1e-12)
        }
    }

    @Test fun negativeRealAxisUsesUpperPrincipalValueAndRetainsLowerSideDiscontinuity() {
        val third = BigDecimal.ONE.divide(BigDecimal(3), MathContext(204))
        val exponent = DecimalPowers.Value(third, BigDecimal.ZERO)
        val upper = DecimalPowers.power(value("-2"), exponent, null)
        val lower = DecimalPowers.power(value("-2", "-0.000000000000000000000000000000000000000000000000000000000001"), exponent, null)
        assertEquals(0.6299605249474367, upper.real.toDouble(), 1e-12)
        assertEquals(1.0911236359717214, upper.imaginary.toDouble(), 1e-12)
        assertEquals(upper.real.toDouble(), lower.real.toDouble(), 1e-12)
        assertEquals(-upper.imaginary.toDouble(), lower.imaginary.toDouble(), 1e-12)
    }
}
