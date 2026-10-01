package com.example.ipa_board

import com.example.ipa_board.emoji.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EmojiUsageTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleCatalog = EmojiCatalog(
        version = "18.0",
        entries = (1..30).map { i ->
            EmojiEntry(
                text = "emoji_$i",
                name = "Emoji $i",
                group = if (i <= 15) "Group A" else "Group B",
                subgroup = "subgroup",
                emojiVersion = "18.0",
                isComponent = false
            )
        }
    )

    @Test
    fun incrementalUsageRecordFileOnlyStoresUsedEmojis() {
        val dir = tempFolder.newFolder()
        val repo = EmojiUsageRepository(storageDirOverride = dir)
        
        assertTrue(repo.getUsageRecords().isEmpty())
        val file = File(dir, EmojiUsageRepository.FILE_NAME)
        assertFalse("Usage file should not exist before recording any emoji", file.exists())

        repo.recordUsage("emoji_1")
        assertTrue("Usage file should be created after recording an emoji", file.exists())
        assertEquals(1, repo.getUsageRecords().size)
        assertEquals("emoji_1", repo.getUsageRecords()[0].text)
        assertEquals(1, repo.getUsageRecords()[0].count)

        // Ensure reloading from disk retrieves the exact recorded usage
        val repo2 = EmojiUsageRepository(storageDirOverride = dir)
        assertEquals(1, repo2.getUsageRecords().size)
        assertEquals("emoji_1", repo2.getUsageRecords()[0].text)
    }

    @Test
    fun getMostCommonlyUsedSelectsTop20InDescendingOrder() {
        val dir = tempFolder.newFolder()
        val repo = EmojiUsageRepository(storageDirOverride = dir)

        // Record usage for 25 emojis with varying counts
        for (i in 1..25) {
            repeat(i) {
                repo.recordUsage("emoji_$i", timestamp = 1000L + i)
            }
        }

        val mostCommonly = repo.getMostCommonlyUsed(sampleCatalog, limit = 20)
        assertEquals(20, mostCommonly.size)
        // emoji_25 has count 25, emoji_24 has count 24, ..., emoji_6 has count 6
        assertEquals("emoji_25", mostCommonly[0].text)
        assertEquals("emoji_24", mostCommonly[1].text)
        assertEquals("emoji_6", mostCommonly[19].text)

        // Check ties broken by lastUsed timestamp
        val repoTies = EmojiUsageRepository(storageDirOverride = tempFolder.newFolder())
        repoTies.recordUsage("emoji_1", timestamp = 100L)
        repoTies.recordUsage("emoji_2", timestamp = 200L)
        val tiesList = repoTies.getMostCommonlyUsed(sampleCatalog, limit = 20)
        assertEquals("emoji_2", tiesList[0].text)
        assertEquals("emoji_1", tiesList[1].text)
    }
}
