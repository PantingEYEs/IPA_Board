package com.example.ipa_board

import java.text.Normalizer

/** Swaps Unicode width variants without changing unrelated compatibility characters. */
object CharacterWidthConverter {
    // UnicodeData.txt <wide>/<narrow> pairs. Do not use NFKC: it also changes
    // circled numbers, ligatures, Hangul jamo and other unrelated user input.
    // https://www.unicode.org/Public/UCD/latest/ucd/UnicodeData.txt
    private val widthPairs: Map<Char, Char> = mutableMapOf<Char, Char>().apply {
        fun pair(first: Char, second: Char) {
            put(first, second)
            put(second, first)
        }

        fun range(first: Int, last: Int, targetFirst: Int) {
            for (code in first..last) pair(code.toChar(), (targetFirst + code - first).toChar())
        }

        pair(' ', '\u3000')
        range(0x21, 0x7E, 0xFF01)
        pair('\uFF5F', '\u2985')
        pair('\uFF60', '\u2986')

        val kana = "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン\u3099\u309A"
        kana.forEachIndexed { index, full -> pair((0xFF61 + index).toChar(), full) }

        pair('\uFFA0', '\u3164')
        range(0xFFA1, 0xFFBE, 0x3131)
        range(0xFFC2, 0xFFC7, 0x314F)
        range(0xFFCA, 0xFFCF, 0x3155)
        range(0xFFD2, 0xFFD7, 0x315B)
        range(0xFFDA, 0xFFDC, 0x3161)

        "¢£¬¯¦¥₩".forEachIndexed { index, narrow -> pair((0xFFE0 + index).toChar(), narrow) }
        "│←↑→↓■○".forEachIndexed { index, full -> pair((0xFFE8 + index).toChar(), full) }
    }

    // Decompose only voiced katakana with an actual halfwidth base. For example,
    // ガ becomes ｶﾞ; ヸ has no halfwidth base and therefore remains unchanged.
    private val voicedKana: Map<Char, String> = mutableMapOf<Char, String>().apply {
        for (code in 0x30A0..0x30FF) {
            val original = code.toChar()
            val decomposed = Normalizer.normalize(original.toString(), Normalizer.Form.NFD)
            if (decomposed.length == 2 && decomposed[1] in '\u3099'..'\u309A') {
                val base = widthPairs[decomposed[0]]
                val mark = widthPairs[decomposed[1]]
                if (base != null && base in '\uFF66'..'\uFF9D' && mark != null) {
                    put(original, "$base$mark")
                }
            }
        }
    }

    fun swap(text: String): String {
        if (text.isEmpty()) return text
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val original = text[index]
            val keycapEnd = keycapEnd(text, index)
            if (keycapEnd > index) {
                // The ASCII base belongs to the emoji, not a standalone digit or
                // punctuation character. Keep its optional VS16 and enclosing mark.
                result.append(text, index, keycapEnd)
                index = keycapEnd
                continue
            }

            val next = text.getOrNull(index + 1)
            if (original in '\uFF66'..'\uFF9D' && next != null && next in '\uFF9E'..'\uFF9F') {
                // Normalize this converted kana pair only. Uncomposable marks and
                // isolated marks retain the canonical U+3099/U+309A mapping.
                val fullPair = "${widthPairs.getValue(original)}${widthPairs.getValue(next)}"
                result.append(Normalizer.normalize(fullPair, Normalizer.Form.NFC))
                index += 2
                continue
            }

            val voiced = voicedKana[original]
            if (voiced != null) result.append(voiced)
            else result.append(widthPairs[original] ?: original)
            // Read only original input; never swap a newly converted character again.
            index++
        }
        return result.toString()
    }

    private fun keycapEnd(text: String, index: Int): Int {
        val base = text[index]
        if (base != '#' && base != '*' && base !in '0'..'9') return index
        var end = index + 1
        if (text.getOrNull(end) == '\uFE0F') end++
        return if (text.getOrNull(end) == '\u20E3') end + 1 else index
    }
}
