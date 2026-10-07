package com.example.ipa_board

/** Comma-separated candidates with escaped literal commas and backslashes. */
object LongPressTextCodec {
    fun parse(input: String): List<String> {
        if (input.isEmpty()) return emptyList()
        // A comma on its own is a candidate, not an empty list.
        if (input == ",") return listOf(",")
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var hasSeparator = false
        while (index < input.length) {
            val char = input[index]
            when {
                char == '\\' && index + 1 < input.length &&
                    (input[index + 1] == ',' || input[index + 1] == '\\') -> {
                    current.append(input[index + 1])
                    index++
                }
                char == ',' -> {
                    parts.add(current.toString())
                    current.setLength(0)
                    hasSeparator = true
                }
                else -> current.append(char)
            }
            index++
        }
        parts.add(current.toString())
        return if (hasSeparator) parts.map { it.trim() }.filter { it.isNotEmpty() } else parts
    }

    fun format(items: List<String>): String = items.joinToString(", ") {
        it.replace("\\", "\\\\").replace(",", "\\,")
    }
}
