package com.example.ipa_board.ime

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Byte offsets belong to the encoded Rime input (UTF-8), not displayed Zhuyin or UTF-16. */
internal data class RimeNativeCandidate(val text: String, val rank: Int, val startByte: Int, val endByte: Int, val prefixEndByte: Int = 0, val committedInputBytes: Int = 0)

/** Bounded C packet decoder. Reject malformed metadata instead of committing partial input. */
internal object RimeCandidateBatch {
    const val LIMIT = 40
    const val MAX_BYTES = 1024 * 1024
    private const val HEADER_BYTES = 32
    private const val ENTRY_BYTES = 20
    fun decode(bytes: ByteArray, expectedInputBytes: Int): List<RimeNativeCandidate> {
        require(bytes.size in HEADER_BYTES..MAX_BYTES)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(data.int == 0x52494d45 && data.int == 3) { "Unsupported Rime batch packet" }
        val status = data.int
        val count = data.int
        val inputBytes = data.int
        val totalBytes = data.int
        val prefixEnd = data.int
        val committedPrefix = data.int
        check(totalBytes == bytes.size && count in 0..LIMIT && prefixEnd in 0..expectedInputBytes) { "Invalid Rime batch size" }
        if (status == 2) {
            check(count == 0 && bytes.size == HEADER_BYTES && inputBytes == expectedInputBytes)
            return emptyList() // Preserve the ignored/invalid-spelling fallback, including leading tones.
        }
        check(status == 0) { "Rime batch failure status=$status" }
        check(committedPrefix in 0..expectedInputBytes && inputBytes == expectedInputBytes - committedPrefix)
        check(prefixEnd <= inputBytes && bytes.size >= HEADER_BYTES + count * ENTRY_BYTES)
        var textPosition = HEADER_BYTES + count * ENTRY_BYTES
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return List(count) { index ->
            val rank = data.int; val start = data.int; val end = data.int
            val offset = data.int; val length = data.int
            check(rank == index && start in 0..end && end <= inputBytes)
            check(offset == textPosition && length in 0..16384 && length <= bytes.size - offset)
            textPosition += length
            val text = decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
            if (index == count - 1) check(textPosition == bytes.size)
            RimeNativeCandidate(text, rank, start, end, prefixEnd, committedPrefix)
        }.also { if (count == 0) check(bytes.size == HEADER_BYTES) }
    }
}
