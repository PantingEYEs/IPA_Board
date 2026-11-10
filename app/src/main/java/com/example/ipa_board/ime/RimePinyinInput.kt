package com.example.ipa_board.ime

import java.util.Locale

/** Each registered spelling owns its syntax; the shared Rime runtime only receives encoded keys. */
internal class RimePinyinInput(scheme: PinyinScheme, alternativeTones: Boolean = false) {
    private val spelling: Spelling = when (scheme) {
        PinyinScheme.LUNA -> LunaSpelling
        PinyinScheme.TERRA -> TerraSpelling(alternativeTones)
    }
    val segmentPattern: Regex get() = spelling.segmentPattern
    fun encode(raw: String): String? = spelling.encode(raw)
    fun accepts(raw: String): Boolean = encode(raw) != null

    private interface Spelling {
        val segmentPattern: Regex
        fun encode(raw: String): String?
    }

    /** Retain the compatibility schema's historical quote handling, including incomplete input. */
    private object LunaSpelling : Spelling {
        override val segmentPattern = Regex("[A-Za-z]+(?:'[A-Za-z]+)*")
        override fun encode(raw: String): String? = raw.takeIf {
            it.all { char -> char in 'a'..'z' || char in 'A'..'Z' || char == '\'' }
        }?.lowercase(Locale.ROOT)
    }

    private class TerraSpelling(alternatives: Boolean) : Spelling {
        private val tones = if (alternatives) "-/<\\;,." else "-/<\\"
        private val tone = "[${Regex.escape(tones)}]"
        // The native dictionary/algebra resolves syllables and abbreviations. Here a tone must
        // follow letters exactly once; the original spelling remains one-to-one ASCII input.
        private val syllables = "[A-Za-z]+(?:$tone[A-Za-z]+)*$tone?"
        private val expression = "$syllables(?:'$syllables)*'?"
        private val whole = Regex(expression)
        override val segmentPattern = Regex(
            "(?<![A-Za-z@:/\\\\.])$expression" +
                "(?![A-Za-z${Regex.escape(tones)}]|[.:@][A-Za-z/])"
        )
        override fun encode(raw: String): String? = raw.takeIf(whole::matches)?.lowercase(Locale.ROOT)
    }
}
