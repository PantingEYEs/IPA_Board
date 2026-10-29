package com.example.ipa_board.ipa

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

internal data class IpaReading(val ipa: String, val region: String, val derived: Boolean, val frequencyScore: Int)
internal data class IpaLookupResult(val readings: List<IpaReading>, val truncated: Boolean = false)
internal class UnsupportedIpaDictionary : IOException("Unsupported IPA dictionary format")

/** Read-only IPADWG01 v2/v3 format adapter. No native engine or source database is loaded.
 * Format reference: IPA_Engine/source/cli_tests/scripts/compiled_lexicon.py.
 * No persistent reverse cache: each request owns its immutable active dictionary mapping.
 */
internal class IpaDictionaryLookup(file: File) : AutoCloseable {
    private val channel = RandomAccessFile(file, "r").channel
    private val bytes: ByteBuffer
    private val offsets: IntArray
    private val width: Int
    private val nodeCount: Int
    private val arcCount: Int
    private val keyCount: Int
    private val wordCount: Int
    private val root: Int
    private val alphabet: IntArray
    private val finalBit: Int

    init {
        try {
            val size = channel.size()
            require(size in 108L..39_999_999L) { "Invalid dictionary size" }
            bytes = channel.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8).also { bytes.duplicate().get(it) }.toString(Charsets.US_ASCII)
            val version = bytes.getInt(8)
            if (magic != "IPADWG01" || version !in 2..3) throw UnsupportedIpaDictionary()
            width = if (version == 2) 3 else 4
            finalBit = if (width == 3) 0x800000 else Int.MIN_VALUE
            nodeCount = bytes.getInt(12); arcCount = bytes.getInt(16)
            keyCount = bytes.getInt(20); wordCount = bytes.getInt(24)
            val alphabetSize = bytes.getInt(28)
            root = bytes.getInt(32)
            require(nodeCount > 0 && arcCount >= 0 && keyCount > 0 && wordCount > 0)
            require(alphabetSize in 1..255 && root in 0 until nodeCount)
            offsets = IntArray(9) { i -> bytes.getLong(36 + i * 8).also { require(it in 108..size) }.toInt() }
            require(offsets.first() == 108 && offsets.last() == size.toInt())
            require(offsets.asList().zipWithNext().all { it.first <= it.second })
            val expected = mapOf(0 to alphabetSize * 4L, 1 to (nodeCount.toLong() + 1) * width,
                2 to nodeCount.toLong() * width, 3 to arcCount.toLong() * (width + 1),
                4 to (keyCount.toLong() + 1) * width, 6 to ((wordCount.toLong() + 15) / 16 + 1) * width)
            expected.forEach { (section, length) -> require(offsets[section + 1].toLong() - offsets[section] == length) }
            alphabet = IntArray(alphabetSize) { bytes.getInt(offsets[0] + it * 4).also { cp ->
                require(cp in 1..0x10ffff && cp !in 0xd800..0xdfff)
            } }
        } catch (error: Throwable) { channel.close(); throw error }
    }

    private fun integer(at: Int): Int {
        var result = 0
        repeat(width) { result = result or ((bytes.get(at + it).toInt() and 255) shl (it * 8)) }
        return result
    }
    private fun index(section: Int, number: Int): Int {
        val at = offsets[section].toLong() + number.toLong() * width
        require(number >= 0 && at + width <= offsets[section + 1])
        return integer(at.toInt())
    }
    private class Cursor(var at: Int, val end: Int)
    private fun variable(cursor: Cursor): Long {
        var value = 0L
        for (shift in 0..28 step 7) {
            require(cursor.at < cursor.end)
            val byte = bytes.get(cursor.at++).toInt() and 255
            value = value or ((byte and 127).toLong() shl shift)
            if (byte < 128) { require(value <= 0xffffffffL); return value }
        }
        error("Invalid dictionary integer")
    }
    private fun boundedSection(section: Int, start: Int, end: Int): Cursor {
        require(start >= 0 && end >= start && end.toLong() <= offsets[section + 1].toLong() - offsets[section])
        return Cursor(offsets[section] + start, offsets[section] + end)
    }
    private fun word(id: Int): String {
        val block = id / 16
        val cursor = boundedSection(7, index(6, block), index(6, block + 1))
        var value = byteArrayOf()
        repeat(id % 16 + 1) {
            val shared = variable(cursor); val length = variable(cursor)
            require(shared <= value.size && length <= cursor.end - cursor.at && shared + length <= 65536)
            val next = value.copyOf(shared.toInt() + length.toInt())
            val source = bytes.duplicate()
            source.position(cursor.at)
            source.get(next, shared.toInt(), length.toInt())
            cursor.at += length.toInt(); value = next
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString()
    }
    /** The pool is ordered by Unicode scalar values, not Java's UTF-16 surrogate ordering. */
    private fun compareWords(first: String, second: String): Int {
        var a = 0; var b = 0
        while (a < first.length && b < second.length) {
            val left = first.codePointAt(a); val right = second.codePointAt(b)
            if (left != right) return left.compareTo(right)
            a += Character.charCount(left); b += Character.charCount(right)
        }
        return (first.length - a).compareTo(second.length - b)
    }
    private fun ipa(key: Int): String {
        var rank = key; var node = root
        val output = StringBuilder()
        repeat(65) {
            val info = index(2, node)
            if (info and finalBit != 0) {
                if (rank == 0) return output.toString()
                rank--
            }
            val start = index(1, node); val end = index(1, node + 1)
            require(start >= 0 && end >= start && end <= arcCount)
            var childNode = -1
            for (arc in start until end) {
                val at = offsets[3] + arc * (width + 1)
                val label = bytes.get(at).toInt() and 255
                val child = integer(at + 1)
                require(label in 1..alphabet.size && child in 0 until node)
                val count = index(2, child) and finalBit.inv()
                if (rank < count) { output.appendCodePoint(alphabet[label - 1]); childNode = child; break }
                rank -= count
            }
            require(childNode >= 0) { "Invalid IPA key rank" }
            node = childNode
        }
        error("IPA key exceeds dictionary limit")
    }

    fun lookup(input: String, limit: Int = 500): IpaLookupResult {
        require(input.length in 1..2000 && limit in 1..500)
        val text = Normalizer.normalize(input, Normalizer.Form.NFC)
        var low = 0; var high = wordCount
        while (low < high) {
            checkCancellation()
            val middle = low + (high - low) / 2
            if (compareWords(word(middle), text) < 0) low = middle + 1 else high = middle
        }
        if (low == wordCount || word(low) != text) return IpaLookupResult(emptyList())
        val results = ArrayList<IpaReading>()
        for (key in 0 until keyCount) {
            if (key % 1024 == 0) checkCancellation()
            val cursor = boundedSection(5, index(4, key), index(4, key + 1))
            val count = variable(cursor)
            require(count <= (cursor.end - cursor.at) / 2)
            var wordId = 0L
            var reading: String? = null
            for (record in 0 until count.toInt()) {
                if (record % 128 == 0) checkCancellation()
                val code = variable(cursor)
                wordId += code shr 4
                val region = (code and 7).toInt()
                require(wordId < wordCount && region in REGIONS.indices && cursor.at < cursor.end)
                val score = bytes.get(cursor.at++).toInt() and 255
                require(score <= 180)
                if (wordId > low) break
                if (wordId == low.toLong()) {
                    if (results.size == limit) return IpaLookupResult(results, truncated = true)
                    if (reading == null) reading = ipa(key)
                    results += IpaReading(reading, REGIONS[region], code and 8 != 0L, score)
                }
            }
        }
        return IpaLookupResult(results)
    }
    private fun checkCancellation() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
    override fun close() { channel.close() }
    companion object { val REGIONS = listOf("en-US", "en-GB", "zh-CN", "zh-TW", "ja-JP") }
}
