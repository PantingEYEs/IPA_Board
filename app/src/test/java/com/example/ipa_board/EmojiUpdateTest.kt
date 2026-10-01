package com.example.ipa_board

import com.example.ipa_board.emoji.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EmojiUpdateTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleData1 = """
        # Version: 18.0
        # group: People
        # subgroup: person
        1F600 ; fully-qualified # 😀 E1.0 grinning face
    """.trimIndent()

    private val sampleData2 = """
        # Version: 19.0
        # group: People
        # subgroup: person
        1F600 ; fully-qualified # 😀 E1.0 grinning face
        1F601 ; fully-qualified # 😁 E1.0 beaming face with smiling eyes
    """.trimIndent()

    @Test
    fun installedCatalogSourceLoadsFromFileWhenPresentAndFallsBack() {
        val root = tempFolder.newFolder()
        val installedFile = File(root, InstalledEmojiCatalogSource.INSTALLED_FILE_NAME)

        // Mock source with directory override
        var bundledLoads = 0
        val bundledSource = EmojiCatalogSource {
            bundledLoads++
            EmojiCatalogParser.parse(sampleData1.reader())
        }

        val testSource = object : EmojiCatalogSource {
            override fun load(): EmojiCatalog {
                if (installedFile.exists()) {
                    return installedFile.reader(Charsets.UTF_8).use(EmojiCatalogParser::parse)
                }
                return bundledSource.load()
            }
        }

        // Before update file exists -> falls back to bundled
        val initial = testSource.load()
        assertEquals("18.0", initial.version)
        assertEquals(1, initial.entries.size)
        assertEquals(1, bundledLoads)

        // Write updated data to installed file
        installedFile.writeText(sampleData2)
        assertTrue(installedFile.exists())

        // Load updated source
        val updated = testSource.load()
        assertEquals("19.0", updated.version)
        assertEquals(2, updated.entries.size)
    }

    @Test
    fun repositoryInvalidationClearsCachedCatalogAndReloads() {
        var loads = 0
        var currentData = sampleData1
        val source = EmojiCatalogSource {
            loads++
            EmojiCatalogParser.parse(currentData.reader())
        }

        val repo = EmojiCatalogRepository(source)
        val catalog1 = repo.load()
        assertEquals("18.0", catalog1.version)
        assertEquals(1, loads)

        // Calling load again should return cached instance
        assertSame(catalog1, repo.load())
        assertEquals(1, loads)

        // Invalidate and switch underlying data
        currentData = sampleData2
        repo.invalidate()

        val catalog2 = repo.load()
        assertEquals("19.0", catalog2.version)
        assertEquals(2, loads)
    }
}
