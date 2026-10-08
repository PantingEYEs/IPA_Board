package com.example.ipa_board.ime

/** Unicode Mandarin Zhuyin to Rime's official DaChien (bopomofo) keyboard spelling. */
internal object ZhuyinInput {
    private const val SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ"
    private const val KEYS = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-"
    private const val TONES = "ˉˊˇˋ˙"
    private const val TONE_KEYS = " 6347"

    fun isSymbol(char: Char): Boolean = char in SYMBOLS

    fun containsSymbols(raw: String): Boolean = raw.any(::isSymbol)

    fun isInputCharacter(char: Char): Boolean = isSymbol(char) || char in TONES || char == ' ' || char == '\''

    /**
     * Spaces explicitly mark the first tone; apostrophes separate syllables without choosing a tone.
     * A neutral-tone dot before a syllable is moved after it, as Rime expects a final tone key.
     * Reject literals here so that punctuation/numbers cannot trigger native key shortcuts or commits.
     */
    fun encode(raw: String): String? {
        if (!containsSymbols(raw) || raw.any { !isInputCharacter(it) }) return null
        // Ordinary tone marks end a syllable. A leading tone can otherwise become an ASCII
        // literal segment inside Rime's keyboard spelling; only the neutral dot may lead.
        if (raw.withIndex().any { (index, char) ->
            char in "ˉˊˇˋ " && (index == 0 || !isSymbol(raw[index - 1]))
        }) return null
        return buildString(raw.length) {
            var index = 0
            while (index < raw.length) {
                val char = raw[index]
                if (char == '˙' && (index == 0 || !isSymbol(raw[index - 1])) &&
                    index + 1 < raw.length && isSymbol(raw[index + 1])) {
                    // The written neutral mark is conventionally before its syllable, unlike other tones.
                    index++
                    while (index < raw.length && isSymbol(raw[index])) {
                        append(KEYS[SYMBOLS.indexOf(raw[index])])
                        index++
                    }
                    append('7')
                    continue
                }
                val symbol = SYMBOLS.indexOf(char)
                val tone = TONES.indexOf(char)
                append(when {
                    symbol >= 0 -> KEYS[symbol]
                    tone >= 0 -> TONE_KEYS[tone]
                    else -> char
                })
                index++
            }
        }
    }
}
