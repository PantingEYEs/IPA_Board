package com.example.ipa_board

import com.example.ipa_board.ime.RimeCandidateBatch
import com.example.ipa_board.ime.RimeNativeCandidate
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RimeCandidateBatchTest {
    @Test fun preservesNativeOrderPartialRangesAndUnicodeText() {
        val rows = listOf(RimeNativeCandidate("你😀", 0, 0, 2), RimeNativeCandidate("你好", 1, 0, 5))
        assertEquals(rows, RimeCandidateBatch.decode(packet(5, rows), 5))
        assertEquals(listOf(rows[1]), RimeCandidateBatch.decode(packet(5, rows), 5).filter { it.startByte == 0 && it.endByte == 5 })
    }
    @Test fun retainsNativeOffsetsAndAccountsForAlreadyCommittedPrefix() {
        val row = RimeNativeCandidate("‘你好", 0, 0, 5, committedInputBytes = 1)
        val decoded = RimeCandidateBatch.decode(packet(5, listOf(row)), 6).single()
        assertEquals(row, decoded)
        assertEquals(6, decoded.endByte + decoded.committedInputBytes)
        val segmented = RimeNativeCandidate("‘你好", 0, 1, 6, prefixEndByte = 1)
        assertEquals(segmented, RimeCandidateBatch.decode(packet(6, listOf(segmented)), 6).single())
    }
    @Test fun mismatchAndEmptyResultsHaveExplicitFallbacks() {
        assertTrue(RimeCandidateBatch.decode(packet(5, emptyList()), 5).isEmpty())
        assertTrue(RimeCandidateBatch.decode(packet(5, emptyList(), status = 2), 5).isEmpty())
        assertThrows(IllegalStateException::class.java) { RimeCandidateBatch.decode(packet(0, emptyList(), status = 1), 5) }
    }
    @Test fun rejectsWrongInputUnitsAndOutOfRangeSpans() {
        val rows = listOf(RimeNativeCandidate("你", 0, 0, 4))
        assertThrows(IllegalStateException::class.java) { RimeCandidateBatch.decode(packet(3, rows), 3) }
        assertThrows(IllegalStateException::class.java) { RimeCandidateBatch.decode(packet(4, rows), 2) }
        assertThrows(IllegalStateException::class.java) { RimeCandidateBatch.decode(packet(4, rows.copyRow(start = -1)), 4) }
    }
    @Test fun rejectsBrokenOwnershipBoundsVersionsRanksAndUtf8() {
        val valid = packet(5, listOf(RimeNativeCandidate("你好", 0, 0, 5)))
        for ((offset, value) in listOf(0 to 0, 4 to 4, 12 to 41, 20 to 1000000, 24 to -1, 28 to -1, 32 to 99, 44 to -1, 48 to 99999)) {
            val broken = valid.copyOf()
            ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertThrows("offset=$offset", IllegalStateException::class.java) { RimeCandidateBatch.decode(broken, 5) }
        }
        assertThrows(IllegalArgumentException::class.java) { RimeCandidateBatch.decode(valid.copyOf(12), 5) }
        val malformedText = valid.copyOf().also { it[52] = 0xff.toByte() }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { RimeCandidateBatch.decode(malformedText, 5) }
        assertThrows(IllegalStateException::class.java) { RimeCandidateBatch.decode(valid + byteArrayOf(0), 5) }
    }
    private fun List<RimeNativeCandidate>.copyRow(start: Int) = listOf(single().copy(startByte = start))
    private fun packet(input: Int, rows: List<RimeNativeCandidate>, status: Int = 0): ByteArray {
        val texts = rows.map { it.text.toByteArray(Charsets.UTF_8) }
        val size = 32 + 20 * rows.size + texts.sumOf { it.size }
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x52494d45).putInt(3).putInt(status).putInt(rows.size).putInt(input).putInt(size).putInt(rows.firstOrNull()?.prefixEndByte ?: 0).putInt(rows.firstOrNull()?.committedInputBytes ?: 0)
        var textOffset = 32 + rows.size * 20
        rows.forEachIndexed { i, row ->
            b.putInt(row.rank).putInt(row.startByte).putInt(row.endByte).putInt(textOffset).putInt(texts[i].size)
            textOffset += texts[i].size
        }
        texts.forEach { b.put(it) }
        return b.array()
    }
}
