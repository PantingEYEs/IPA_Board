package com.example.ipa_board.emoji

import org.junit.Assert.*
import org.junit.Test

class EmojiSectionsTest {
    private fun entry(text: String, group: String) = EmojiEntry(text, text, group, "test", "1.0", false)

    @Test fun continuousSectionsPreserveEveryOfficialEntryAndKeepCommonAsAnIndependentPrefix() {
        val entries = listOf(entry("👩🏽‍💻", "People & Body"), entry("🇨🇳", "Flags"), entry("❤️", "People & Body"))
        val sections = EmojiSections(EmojiCatalog("test", entries), "Most commonly", listOf(entries.last(), entries.first()))
        assertEquals(listOf("Most commonly", "People & Body", "Flags"), sections.categories)
        assertEquals(listOf(entries.last(), entries.first()), sections.items.filterIsInstance<EmojiSections.Item.Entry>()
            .filter { it.category == 0 }.map { it.emoji })
        val official = sections.items.filterIsInstance<EmojiSections.Item.Entry>().filter { it.category != 0 }
        assertEquals(listOf(entries[0], entries[2], entries[1]), official.map { it.emoji })
        sections.headers.forEachIndexed { category, position ->
            assertEquals(EmojiSections.Item.Header(category, sections.categories[category]), sections.items[position])
            val end = sections.headers.getOrNull(category + 1) ?: sections.items.lastIndex
            assertTrue(sections.items.subList(position, end).all { it.category == category })
        }
    }

    @Test fun emptyCommonAndAnEmptyCatalogStillHaveANavigableSection() {
        val entry = entry("😀", "Smileys & Emotion")
        val sections = EmojiSections(EmojiCatalog("test", listOf(entry)), "Most commonly", emptyList())
        assertTrue(sections.items[1] is EmojiSections.Item.Empty)
        assertEquals(entry, sections.items.filterIsInstance<EmojiSections.Item.Entry>().single().emoji)
        val empty = EmojiSections(EmojiCatalog("test", emptyList()), "Most commonly", emptyList())
        assertEquals(listOf("Most commonly"), empty.categories)
        assertEquals(listOf(0), empty.headers)
        assertEquals(0, empty.lastSectionEntryCount)
        assertEquals(listOf(EmojiSections.Item.Header(0, "Most commonly"), EmojiSections.Item.Empty(0), EmojiSections.Item.End(0)), empty.items)
    }
}
