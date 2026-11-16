package com.example.ipa_board

import com.example.ipa_board.ime.LatinImeBundle
import com.example.ipa_board.ime.LatinImePacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LatinImePacketTest {
    private fun packet(vararg texts: String): ByteArray {
        val encoded = texts.map { it.toByteArray(Charsets.UTF_8) }
        val start = 24 + texts.size * 16
        val buffer = ByteBuffer.allocate(start + encoded.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x4c41544e).putInt(1).putInt(texts.size).putInt(buffer.capacity()).putInt(16).putInt(0)
        var offset = start
        encoded.forEachIndexed { i, text ->
            buffer.putInt(-10 + i).putInt(i).putInt(offset).putInt(text.size)
            offset += text.size
        }
        encoded.forEach { buffer.put(it) }
        return buffer.array()
    }
    private fun changed(bytes: ByteArray, offset: Int, value: Int) = bytes.copyOf().also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
    }
    private fun rejected(bytes: ByteArray) {
        try { LatinImePacket.decode(bytes); fail("Malformed native packet accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: java.nio.charset.CharacterCodingException) { }
    }
    @Test fun emptyAndUnicodePacketsKeepScoresFlagsAndOrder() {
        assertTrue(LatinImePacket.decode(packet()).isEmpty())
        val decoded = LatinImePacket.decode(packet("hello", "café", "𐐀"))
        assertEquals(listOf("hello", "café", "𐐀"), decoded.map { it.text })
        assertEquals(listOf(-10, -9, -8), decoded.map { it.score })
        assertEquals(listOf(0, 1, 2), decoded.map { it.flags })
    }
    @Test fun unknownVersionsSizesCountsAndReservedValuesAreRejected() {
        val bytes = packet("hello")
        for ((offset, value) in listOf(0 to 0, 4 to 2, 8 to -1, 8 to 19, 12 to 0, 16 to 8, 20 to 1))
            rejected(changed(bytes, offset, value))
        rejected(ByteArray(23)); rejected(ByteArray(16_385)); rejected(bytes.copyOf(30))
    }
    @Test fun overlappingGappedNegativeAndOverflowingTextRangesAreRejected() {
        val bytes = packet("hello", "world")
        for ((offset, value) in listOf(32 to 0, 32 to -1, 32 to Int.MAX_VALUE,
                36 to -1, 36 to Int.MAX_VALUE, 48 to 56, 48 to 60)) rejected(changed(bytes, offset, value))
        rejected(changed(packet("hello") + byteArrayOf(0), 12, 46))
    }
    @Test fun invalidUtf8NulEmptyAndOverlongWordsAreRejected() {
        rejected(packet("")); rejected(packet("a\u0000b")); rejected(packet("a".repeat(49)))
        rejected(packet("hello").also { it[40] = 0xc0.toByte(); it[41] = 0x80.toByte() })
        rejected(packet("hello").also { it[it.lastIndex] = 0xff.toByte() })
        assertEquals("𐐀".repeat(48), LatinImePacket.decode(packet("𐐀".repeat(48))).single().text)
    }
    @Test fun maximumCountWorksAndDecodedResultsOwnTheirText() {
        val bytes = packet(*(1..18).map { "word$it" }.toTypedArray())
        val decoded = LatinImePacket.decode(bytes)
        bytes.fill(0)
        assertEquals(18, decoded.size)
        assertEquals("word18", decoded.last().text)
    }
    @Test fun identityMustMatchEveryFieldAndMayNotContainTrailingBytes() {
        val bytes = LatinImeBundle.identity.toByteArray()
        LatinImeBundle.verifyIdentity(bytes)
        for (invalid in listOf(bytes + byteArrayOf(0), "other".toByteArray(),
                LatinImeBundle.identity.replace(";packet=1", ";packet=2").toByteArray())) {
            try { LatinImeBundle.verifyIdentity(invalid); fail("Mismatched build accepted") }
            catch (_: IllegalStateException) { }
        }
    }
}
