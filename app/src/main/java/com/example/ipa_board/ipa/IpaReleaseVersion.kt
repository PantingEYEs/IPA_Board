package com.example.ipa_board.ipa

import java.math.BigInteger

/** SemVer ordering, including numeric prerelease identifiers; timestamps never select a version. */
internal data class IpaReleaseVersion(val core: List<BigInteger>, val pre: List<String>) : Comparable<IpaReleaseVersion> {
    override fun compareTo(other: IpaReleaseVersion): Int {
        core.zip(other.core).forEach { (a,b) -> a.compareTo(b).let { if (it != 0) return it } }
        if (pre.isEmpty() || other.pre.isEmpty()) return when { pre == other.pre -> 0; pre.isEmpty() -> 1; else -> -1 }
        pre.zip(other.pre).forEach { (a,b) ->
            val x = a.toBigIntegerOrNull(); val y = b.toBigIntegerOrNull()
            val order = when { x != null && y != null -> x.compareTo(y); x != null -> -1; y != null -> 1; else -> a.compareTo(b) }
            if (order != 0) return order
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        fun parse(value: String): IpaReleaseVersion? {
            val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$").matchEntire(value) ?: return null
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return IpaReleaseVersion((1..3).map { match.groupValues[it].toBigInteger() }, pre)
        }
        fun newer(remote: String, installed: String): Boolean {
            if (remote == installed) return false
            val next = parse(remote) ?: throw IllegalArgumentException("Unsupported resource version")
            val old = parse(installed) ?: throw IllegalArgumentException("Unsupported installed version")
            return next > old
        }
    }
}
