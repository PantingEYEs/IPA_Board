package com.example.ipa_board.calculator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal class TrigonometricPole : IllegalArgumentException("Trigonometric pole")

/** Bounded principal complex trigonometry. Branch cuts use upper real / right imaginary limits. */
internal object DecimalTrigonometry {
    private val mc = MathContext(204, RoundingMode.HALF_UP)
    private val zero = BigDecimal.ZERO
    private val one = BigDecimal.ONE
    private val two = BigDecimal(2)
    private val e get() = DecimalPowers.Value(MathematicalConstants.e, zero)
    private fun value(x: BigDecimal, y: BigDecimal = zero) = DecimalPowers.Value(x, y)
    private fun add(a: DecimalPowers.Value, b: DecimalPowers.Value) = value(a.real.add(b.real,mc),a.imaginary.add(b.imaginary,mc))
    private fun negate(a: DecimalPowers.Value) = value(a.real.negate(), a.imaginary.negate())
    private fun multiply(a: DecimalPowers.Value, b: DecimalPowers.Value) = value(
        a.real.multiply(b.real,mc).subtract(a.imaginary.multiply(b.imaginary,mc),mc),
        a.real.multiply(b.imaginary,mc).add(a.imaginary.multiply(b.real,mc),mc))
    private fun divide(a: DecimalPowers.Value, b: DecimalPowers.Value): DecimalPowers.Value {
        val norm = b.real.multiply(b.real,mc).add(b.imaginary.multiply(b.imaginary,mc),mc)
        if (norm.signum() == 0) throw TrigonometricPole()
        return value(a.real.multiply(b.real,mc).add(a.imaginary.multiply(b.imaginary,mc),mc).divide(norm,mc),
            a.imaginary.multiply(b.real,mc).subtract(a.real.multiply(b.imaginary,mc),mc).divide(norm,mc))
    }
    private fun sqrt(z: DecimalPowers.Value): DecimalPowers.Value {
        val radius = z.real.multiply(z.real,mc).add(z.imaginary.multiply(z.imaginary,mc),mc).sqrt(mc)
        val realSquare = radius.add(z.real,mc).divide(two,mc).max(zero)
        val imaginarySquare = radius.subtract(z.real,mc).divide(two,mc).max(zero)
        val imaginary = imaginarySquare.sqrt(mc)
        return value(realSquare.sqrt(mc), if (z.imaginary.signum() < 0) imaginary.negate() else imaginary)
    }
    private fun log(z: DecimalPowers.Value) = DecimalPowers.logarithm(z,e)

    fun evaluate(name: String, z: DecimalPowers.Value): DecimalPowers.Value {
        require(z.real.precision()-z.real.scale() <= 128 && z.imaginary.abs() <= BigDecimal(1000)) { "Trigonometric magnitude limit" }
        if (name in listOf("sin","cos","tan")) {
            val (sin,cos) = DecimalPowers.sinCos(z.real)
            val positive = DecimalPowers.exp(z.imaginary)
            val negative = DecimalPowers.exp(z.imaginary.negate())
            val sinh = positive.subtract(negative,mc).divide(two,mc)
            val cosh = positive.add(negative,mc).divide(two,mc)
            val sine = value(sin.multiply(cosh,mc),cos.multiply(sinh,mc))
            val cosine = value(cos.multiply(cosh,mc),sin.multiply(sinh,mc).negate())
            return when (name) { "sin" -> sine; "cos" -> cosine; else -> divide(sine,cosine) }
        }
        if (name == "atan" && z.imaginary.signum() == 0) return value(DecimalPowers.atan(z.real))
        if (name == "atan") {
            val iz = multiply(value(zero,one),z)
            return multiply(value(zero,one.divide(two,mc)), add(log(add(value(one),negate(iz))),negate(log(add(value(one),iz)))))
        }
        require(name == "asin" || name == "acos") { "Unknown trigonometric function" }
        val radicand = add(value(one),negate(multiply(z,z)))
        var root = sqrt(radicand)
        // At x>1, approach from Im(z)>0: Im(1-z²)<0 even when represented as exact zero.
        if (z.imaginary.signum() == 0 && z.real > one) root = negate(root)
        val iz = multiply(value(zero,one),z)
        val sum = add(iz,root)
        val reciprocal = add(root,negate(iz))
        // (sqrt(1-z²)+iz)(sqrt(1-z²)-iz)=1. Avoid subtracting nearly equal large values.
        val logValue = if (sum.real.abs().add(sum.imaginary.abs()) < one &&
            reciprocal.real.abs().add(reciprocal.imaginary.abs()) > one) negate(log(reciprocal)) else log(sum)
        val asin = multiply(value(zero,one.negate()),logValue)
        return if (name == "asin") asin else add(value(MathematicalConstants.pi.divide(two,mc)),negate(asin))
    }
}
