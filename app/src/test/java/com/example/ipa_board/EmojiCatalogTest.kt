package com.example.ipa_board

import com.example.ipa_board.emoji.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EmojiCatalogTest {
    private val sample = """
        # Version: 18.0
        # group: People & Body
        # subgroup: person-role
        1F469 1F3FD 200D 1F4BB ; fully-qualified # 👩🏽‍💻 E4.0 woman technologist: medium skin tone
        # group: Symbols
        # subgroup: keycap
        0031 FE0F 20E3 ; fully-qualified # 1️⃣ E0.6 keycap: 1
        0031 20E3 ; unqualified # 1⃣ E0.6 keycap: 1
        # group: Component
        # subgroup: skin-tone
        1F3FD ; component # 🏽 E1.0 medium skin tone
    """.trimIndent()

    @Test fun parsesWholeSequencesAndSkipsDuplicatePresentationForms() {
        val catalog = EmojiCatalogParser.parse(sample.reader())
        assertEquals("18.0", catalog.version)
        assertEquals(listOf("👩🏽‍💻", "1️⃣", "🏽"), catalog.entries.map { it.text })
        assertEquals(listOf("People & Body", "Symbols", "Component"), catalog.groups)
        assertTrue(catalog.entries.last().isComponent)
        assertEquals("woman technologist: medium skin tone", catalog.entries.first().name)
    }

    @Test fun bundledCatalogHasEveryOfficialQualifiedEmojiAndComponent() {
        val file = listOf(File("src/main/assets/emoji/emoji-test.txt"), File("app/src/main/assets/emoji/emoji-test.txt")).first { it.isFile }
        val catalog = file.reader(Charsets.UTF_8).use(EmojiCatalogParser::parse)
        assertEquals("18.0", catalog.version)
        assertEquals(3963, catalog.entries.count { !it.isComponent })
        assertEquals(9, catalog.entries.count { it.isComponent })
        assertEquals(3972, catalog.entries.map { it.text }.toSet().size)
        assertTrue(catalog.entries.any { it.name == "cracking face" && it.emojiVersion == "18.0" })
        assertTrue(catalog.entries.any { it.text == "🇨🇳" })
        assertTrue(catalog.entries.any { it.text == "👩🏽‍💻" })
        assertEquals("flag: Wales", catalog.entries.last().name)
    }

    @Test fun repositoryCachesAndSupportsFutureUpdateInvalidation() {
        var loads = 0
        val repository = EmojiCatalogRepository(EmojiCatalogSource {
            loads++
            EmojiCatalogParser.parse(sample.replace("18.0", if (loads == 1) "18.0" else "19.0").reader())
        })
        assertSame(repository.load(), repository.load())
        assertEquals(1, loads)
        repository.invalidate()
        assertEquals("19.0", repository.load().version)
        assertEquals(2, loads)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateSequences() { EmojiCatalogParser.parse((sample + "\n1F3FD ; component # 🏽 E1.0 medium skin tone").reader()) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingVersion() { EmojiCatalogParser.parse(sample.replace("# Version: 18.0", "").reader()) }
}
