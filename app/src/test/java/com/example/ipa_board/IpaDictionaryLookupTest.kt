package com.example.ipa_board

import com.example.ipa_board.ipa.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class IpaDictionaryLookupTest {
    private fun fixture(version: Int = 2) = requireNotNull(javaClass.getResource("/ipa/reverse-v$version.ipad")).readBytes()
    private fun <T> dictionary(bytes: ByteArray = fixture(), action: (IpaDictionaryLookup) -> T): T {
        val file = File.createTempFile("reverse-fixture", ".ipad")
        try { file.writeBytes(bytes); return IpaDictionaryLookup(file).use(action) }
        finally { file.delete() }
    }
    @Test fun preservesMultipleReadingsRegionsAndDerivedProvenanceInBothFormats() {
        for (version in 2..3) dictionary(fixture(version)) { reader ->
            val entries = reader.lookup("hello").readings
            assertEquals(3, entries.size)
            assertEquals(setOf("həˈloʊ", "həˈləʊ"), entries.map { it.ipa }.toSet())
            assertEquals(setOf("en-US", "en-GB"), entries.map { it.region }.toSet())
            assertTrue(entries.any { it.derived && it.frequencyScore == 60 })
            assertEquals(setOf("zh-CN", "zh-TW"), reader.lookup("学校").readings.map { it.region }.toSet())
        }
    }
    @Test fun nfcWordMatchingPreservesExactIpaAndUnicodeScalarPoolOrdering() = dictionary { reader ->
        assertEquals("ɑ\u0301ː", reader.lookup("cafe\u0301").readings.single().ipa)
        assertEquals(1, reader.lookup("\ue000").readings.size)
        assertEquals(1, reader.lookup("𐀀").readings.size)
        assertTrue(reader.lookup("HELLO").readings.isEmpty())
        assertTrue(reader.lookup(" hello").readings.isEmpty())
        assertTrue(reader.lookup("absent").readings.isEmpty())
    }
    @Test fun limitIsExplicitRatherThanSilentlyDroppingOtherReadings() = dictionary { reader ->
        assertTrue(reader.lookup("hello", 2).truncated)
        assertEquals(2, reader.lookup("hello", 2).readings.size)
        assertFalse(reader.lookup("hello", 3).truncated)
    }
    @Test fun malformedResourcesAndUnsupportedVersionsAreNotNoEntryResults() {
        assertThrows(UnsupportedIpaDictionary::class.java) { dictionary(fixture().also { it[8] = 4 }) {} }
        assertThrows(IllegalArgumentException::class.java) { dictionary(fixture().copyOf(109)) {} }
        assertThrows(Exception::class.java) { dictionary(fixture().also { it[36] = 1 }) {} }
    }
    @Test fun cancellationStopsScanningAndDoesNotChangeDictionary() = dictionary { reader ->
        try {
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { reader.lookup("hello") }
        } finally { Thread.interrupted() }
        assertEquals(3, reader.lookup("hello").readings.size)
    }
}
