package com.example.ipa_board.ime

import java.util.Locale

/** Only the contiguous English suffix is useful to an English n-gram dictionary. */
object EnglishContext {
    fun previousWords(beforeCursor: String): List<String> {
        val suffix = beforeCursor.takeLast(256).takeLastWhile {
            it in 'a'..'z' || it in 'A'..'Z' || it == '\'' || it == ' ' || it == '\t'
        }
        return Regex("[A-Za-z]+(?:'[A-Za-z]+)*").findAll(suffix)
            .map { it.value.lowercase(Locale.ROOT) }.toList().takeLast(3).reversed()
            .takeWhile { it.length < 48 }
    }

    fun insertionPrefix(beforeCursor: String): String =
        if (beforeCursor.lastOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' } == true) " " else ""
}
