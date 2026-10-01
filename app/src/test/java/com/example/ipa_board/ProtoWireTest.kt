package com.example.ipa_board

import com.example.ipa_board.ime.ProtoWire
import com.example.ipa_board.ime.ProtoWire.blob
import com.example.ipa_board.ime.ProtoWire.value
import org.junit.Assert.*
import org.junit.Test

class ProtoWireTest {
    @Test fun roundTripLargeSessionIdAndNestedUnicode() {
        val input = ProtoWire.number(1, 9123456789123L) + ProtoWire.message(2, "日本語".toByteArray())
        val parsed = ProtoWire.read(input)
        assertEquals(9123456789123L, parsed.value(1))
        assertEquals("日本語", parsed.blob(2).toString(Charsets.UTF_8))
    }
    @Test fun preeditGroupCanBeReadAlongsideUnknownFields() {
        val encoded = byteArrayOf(19) + ProtoWire.message(4, "かな".toByteArray()) + byteArrayOf(20) + ProtoWire.number(90, 12)
        val parsed = ProtoWire.read(encoded)
        assertEquals("かな", parsed.first().group.blob(4).toString(Charsets.UTF_8))
        assertEquals(12L, parsed.value(90))
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedPayloadRejected() { ProtoWire.read(byteArrayOf(10, 100, 1)) }
}
