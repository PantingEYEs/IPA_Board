package com.example.ipa_board

import org.junit.Assert.*
import org.junit.Test

class LayoutFilenameTest {
    @Test fun preservesUnicodeSpacesAndExistingExtension() {
        assertEquals("音标 t͡ʃ 😀.json", LayoutFileManager.normalizeFilename("音标 t͡ʃ 😀.json"))
        assertEquals("My IPA.JSON", LayoutFileManager.normalizeFilename("My IPA.JSON"))
        assertEquals("My IPA.json", LayoutFileManager.normalizeFilename("  My IPA  "))
    }

    @Test fun producesSafeFallbackAndRemovesPathSyntax() {
        listOf("", " ", ".", "..", ".json").forEach {
            assertEquals("layout.json", LayoutFileManager.normalizeFilename(it))
        }
        val filename = LayoutFileManager.normalizeFilename("../../outside\\new\u0000:\u0085.json")
        assertTrue(filename.endsWith(".json"))
        assertFalse(filename.contains('/'))
        assertFalse(filename.contains('\\'))
        assertFalse(filename.contains(':'))
        assertFalse(filename.any { it.isISOControl() })
        assertFalse(filename.startsWith('.'))
    }

    @Test fun suffixesCollisionsWithoutReplacingTheOriginalName() {
        assertEquals("音标 (3).json", LayoutFileManager.availableFilename(
            "音标.json", listOf("音标.json", "音标 (2).JSON")
        ))
        assertEquals("My IPA (2).JSON", LayoutFileManager.availableFilename(
            "My IPA.JSON", listOf("my ipa.json")
        ))
        assertEquals("default (2).json", LayoutFileManager.availableFilename("default.json", emptyList()))
    }

    @Test fun longUnicodeNamesFitInFilesystemWithoutSplittingSurrogates() {
        val filename = LayoutFileManager.normalizeFilename("😀音标".repeat(100) + ".json")
        val collision = LayoutFileManager.availableFilename(filename, listOf(filename))
        listOf(filename, collision).forEach {
            assertTrue(it.toByteArray(Charsets.UTF_8).size <= 240)
            assertEquals(it, String(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertTrue(collision.endsWith(" (2).json"))
    }
}
