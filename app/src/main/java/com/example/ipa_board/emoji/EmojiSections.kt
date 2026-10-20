package com.example.ipa_board.emoji

/** One continuous data set. Menu positions identify headers, never filtered subsets. */
internal class EmojiSections(catalog: EmojiCatalog, commonTitle: String, common: List<EmojiEntry>) {
    sealed interface Item {
        val category: Int
        data class Header(override val category: Int, val title: String) : Item
        data class Entry(override val category: Int, val emoji: EmojiEntry) : Item
        data class Empty(override val category: Int) : Item
        data class End(override val category: Int) : Item
    }
    val categories = listOf(commonTitle) + catalog.groups
    val headers = mutableListOf<Int>()
    val items: List<Item> = buildList {
        val grouped = catalog.entries.groupBy { it.group }
        categories.forEachIndexed { category, title ->
            headers += size
            add(Item.Header(category, title))
            val entries = if (category == 0) common else grouped[title].orEmpty()
            if (entries.isEmpty()) add(Item.Empty(category))
            else entries.forEach { add(Item.Entry(category, it)) }
        }
        add(Item.End(categories.lastIndex))
    }
    val lastSectionEntryCount = items.count { it is Item.Entry && it.category == categories.lastIndex }
}
