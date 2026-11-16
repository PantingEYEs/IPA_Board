package com.example.ipa_board.ime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Owned LATN v1 bytes; reject malformed output before it enters candidate ranking or IPC. */
internal object LatinImePacket {
    data class Suggestion(val text: String, val score: Int, val flags: Int)

    fun decode(bytes: ByteArray): List<Suggestion> {
        require(bytes.size in 24..16_384) { "IPA_LATINIME_PACKET_SIZE" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == 0x4c41544e && buffer.int == 1) { "IPA_LATINIME_PACKET_VERSION" }
        val count = buffer.int
        require(count in 0..18 && buffer.int == bytes.size && buffer.int == 16 && buffer.int == 0) {
            "IPA_LATINIME_PACKET_HEADER"
        }
        var textStart = 24 + count * 16
        require(textStart <= bytes.size) { "IPA_LATINIME_PACKET_ENTRIES" }
        val result = List(count) {
            val score = buffer.int
            val flags = buffer.int
            val offset = buffer.int
            val size = buffer.int
            require(offset == textStart && size in 1..192 && size <= bytes.size - textStart) {
                "IPA_LATINIME_PACKET_TEXT_RANGE"
            }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, size)).toString()
            require('\u0000' !in text && text.codePointCount(0, text.length) in 1..48) {
                "IPA_LATINIME_PACKET_TEXT"
            }
            textStart += size
            Suggestion(text, score, flags)
        }
        require(textStart == bytes.size) { "IPA_LATINIME_PACKET_TRAILING_BYTES" }
        return result
    }
}
