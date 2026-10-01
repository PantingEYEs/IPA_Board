package com.example.ipa_board.emoji

import java.io.Reader

data class EmojiEntry(
    val text: String,
    val name: String,
    val group: String,
    val subgroup: String,
    val emojiVersion: String,
    val isComponent: Boolean
)

data class EmojiCatalog(val version: String, val entries: List<EmojiEntry>) {
    val groups: List<String> = entries.map { it.group }.distinct()
}

/** Parses Unicode emoji-test.txt, preserving complete ZWJ, selector, flag and modifier sequences. */
object EmojiCatalogParser {
    fun parse(reader: Reader): EmojiCatalog {
        var version = ""
        var group = ""
        var subgroup = ""
        val entries = mutableListOf<EmojiEntry>()
        val unique = mutableSetOf<String>()
        reader.buffered().forEachLine { line ->
            when {
                line.startsWith("# Version:") -> version = line.substringAfter(':').trim()
                line.startsWith("# group:") -> group = line.substringAfter(':').trim()
                line.startsWith("# subgroup:") -> subgroup = line.substringAfter(':').trim()
                line.isBlank() || line.startsWith('#') -> Unit
                else -> {
                    val status = line.substringAfter(';').substringBefore('#').trim()
                    // Other statuses are alternate presentations of the same emoji, not additional entries.
                    if (status == "fully-qualified" || status == "component") {
                        val codepoints = line.substringBefore(';').trim().split(Regex("\\s+")).map { it.toInt(16) }
                        require(codepoints.all { it in 0..0x10FFFF && it !in 0xD800..0xDFFF }) { "Invalid emoji code point" }
                        val text = StringBuilder().apply { codepoints.forEach { appendCodePoint(it) } }.toString()
                        val comment = line.substringAfter('#').trim()
                        val description = Regex("^\\S+ E([0-9.]+) (.+)$").matchEntire(comment)
                            ?: error("Invalid emoji description")
                        require(group.isNotBlank() && subgroup.isNotBlank() && unique.add(text)) { "Invalid or duplicate emoji" }
                        entries += EmojiEntry(text, description.groupValues[2], group, subgroup,
                            description.groupValues[1], status == "component")
                    }
                }
            }
        }
        require(version.matches(Regex("[0-9]+\\.[0-9]+")) && entries.isNotEmpty()) { "Missing emoji version or entries" }
        return EmojiCatalog(version, entries.toList())
    }
}
