package com.example.ipa_board.ime

/** Script-specific context for dictionary continuations; trailing punctuation/numbers are harmless. */
internal object PredictionContext {
    fun chinese(text: String): String = tail(text) { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
    fun japanese(text: String): String = tail(text) {
        Character.UnicodeScript.of(it) in setOf(Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA) || it == 0x30fc || it == 0x3005
    }

    private fun tail(text: String, accepts: (Int) -> Boolean): String {
        val bounded = text.takeLast(256)
        var end = bounded.length
        while (end > 0) {
            val code = bounded.codePointBefore(end)
            if (accepts(code) || Character.isLetter(code)) break
            end -= Character.charCount(code)
        }
        var start = end
        var count = 0
        while (start > 0 && count < 16) {
            val code = bounded.codePointBefore(start)
            if (!accepts(code)) break
            start -= Character.charCount(code)
            count++
        }
        return bounded.substring(start, end)
    }
}

/** A compact, read-only prefix index over the already bundled Rime phrase/frequency corpus.
 * This is phrase continuation, not a statistical model of arbitrary next words.
 * Keep one corpus string plus line offsets rather than materializing a map for every prefix.
 */
internal class PhraseContinuation(source: String) {
    data class Suggestion(val text: String, val frequency: Int)
    private val corpus = source
    private val offsets: IntArray
    private val common: List<Suggestion>

    init {
        val starts = IntArray(source.count { it == '\n' } + 1)
        var size = 0
        var start = 0
        val frequent = mutableListOf<Suggestion>()
        while (start < source.length) {
            val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
            val tab = source.indexOf('\t', start)
            if (tab in (start + 1) until end) {
                val frequency = source.substring(tab + 1, end).trim().toIntOrNull() ?: 0
                if (frequency > 0 && source.codePointCount(start, tab) in 2..24) {
                    starts[size++] = start
                    if (frequent.size < LIMIT || frequency > frequent.last().frequency) {
                        keepBest(frequent, Suggestion(source.substring(start, tab), frequency))
                    }
                }
            }
            start = end + 1
        }
        val present = starts.copyOf(size)
        // The bundled corpus is sorted by code point. Accept small unsorted caller/test data too.
        offsets = if ((1 until size).all { compareWords(present[it - 1], present[it]) <= 0 }) present
            else present.toList().sortedWith { a, b -> compareWords(a, b) }.toIntArray()
        common = frequent
    }

    fun suggest(context: String): List<Suggestion> {
        val tail = PredictionContext.chinese(context)
        val count = tail.codePointCount(0, tail.length)
        // Prefer the longest suffix evidenced by actual phrases; never repeat the prefix itself.
        for (length in minOf(8, count) downTo 1) {
            val prefix = tail.substring(tail.offsetByCodePoints(tail.length, -length))
            val matches = mutableListOf<Suggestion>()
            var index = lowerBound(prefix)
            while (index < offsets.size) {
                val start = offsets[index++]
                if (!corpus.startsWith(prefix, start)) break
                val tab = corpus.indexOf('\t', start)
                val suffixStart = start + prefix.length
                if (tab <= suffixStart) continue
                val end = corpus.indexOf('\n', tab).let { if (it < 0) corpus.length else it }
                val frequency = corpus.substring(tab + 1, end).trim().toIntOrNull() ?: 0
                if (matches.size < LIMIT || frequency > matches.last().frequency) {
                    keepBest(matches, Suggestion(corpus.substring(suffixStart, tab), frequency))
                }
            }
            if (matches.isNotEmpty()) return matches
        }
        // A common-word fallback keeps the language available when surrounding text is unrelated.
        return common
    }

    private fun lowerBound(prefix: String): Int {
        var low = 0
        var high = offsets.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (compareWordToPrefix(offsets[middle], prefix) < 0) low = middle + 1 else high = middle
        }
        return low
    }

    private fun compareWordToPrefix(start: Int, prefix: String): Int {
        var left = start
        var right = 0
        while (right < prefix.length && left < corpus.length && corpus[left] != '\t') {
            val a = corpus.codePointAt(left)
            val b = prefix.codePointAt(right)
            if (a != b) return a.compareTo(b)
            left += Character.charCount(a)
            right += Character.charCount(b)
        }
        return if (right == prefix.length) 0 else -1
    }

    private fun compareWords(first: Int, second: Int): Int {
        var a = first
        var b = second
        while (corpus[a] != '\t' && corpus[b] != '\t') {
            val left = corpus.codePointAt(a)
            val right = corpus.codePointAt(b)
            if (left != right) return left.compareTo(right)
            a += Character.charCount(left)
            b += Character.charCount(right)
        }
        return (if (corpus[a] == '\t') 0 else 1).compareTo(if (corpus[b] == '\t') 0 else 1)
    }

    private fun keepBest(items: MutableList<Suggestion>, next: Suggestion) {
        items.add(next)
        items.sortWith(compareByDescending<Suggestion> { it.frequency }.thenBy { it.text })
        if (items.size > LIMIT) items.removeAt(items.lastIndex)
    }

    private companion object { const val LIMIT = 24 }
}
