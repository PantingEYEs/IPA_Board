package com.example.ipa_board

import com.example.ipa_board.ime.RimeBuildIdentity
import com.example.ipa_board.ime.RimeBundle
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure JVM guard: a present library is insufficient unless ABI, packet and source build agree. */
class RimeBuildIdentityTest {
    private val expected = "ipa-rime;abi=${RimeBundle.ABI_VERSION};packet=${RimeBundle.PACKET_VERSION};" +
        "librime=${RimeBundle.UPSTREAM_VERSION};opencc=${RimeBundle.OPENCC_VERSION};build=${RimeBundle.BUILD_ID}"

    @Test fun matchingBuildIsAccepted() {
        RimeBuildIdentity.validate(RimeBundle.ABI_VERSION, expected.toByteArray())
    }

    @Test fun staleAbiPacketBuildAndComponentVersionsAreRejected() {
        assertThrows(IllegalStateException::class.java) {
            RimeBuildIdentity.validate(RimeBundle.ABI_VERSION + 1, expected.toByteArray())
        }
        for ((field, value) in mapOf("abi" to RimeBundle.ABI_VERSION.toString(),
            "packet" to RimeBundle.PACKET_VERSION.toString(), "librime" to RimeBundle.UPSTREAM_VERSION,
            "opencc" to RimeBundle.OPENCC_VERSION, "build" to RimeBundle.BUILD_ID)) {
            assertThrows("Stale $field", IllegalStateException::class.java) {
                RimeBuildIdentity.validate(RimeBundle.ABI_VERSION,
                    expected.replace("$field=$value", "$field=stale").toByteArray())
            }
        }
    }

    @Test fun truncatedExtraDuplicateAndMalformedIdentityBytesAreRejected() {
        val malformed = listOf(expected.dropLast(1).toByteArray(), (expected + ";future=1").toByteArray(),
            (expected + ";abi=${RimeBundle.ABI_VERSION}").toByteArray(), expected.toByteArray() + 0.toByte(),
            expected.toByteArray() + byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf())
        malformed.forEach { bytes -> assertThrows(IllegalStateException::class.java) {
            RimeBuildIdentity.validate(RimeBundle.ABI_VERSION, bytes)
        } }
    }
}
