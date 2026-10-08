package com.example.ipa_board.kaomoji

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class KaomojiRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: KaomojiRepository

    @Before
    fun setUp() {
        val storageDir = tempFolder.newFolder("kaomoji_test")
        repository = KaomojiRepository(storageDirOverride = storageDir)
        // Explicit fixtures for editing/search/import tests, independent of app initialization.
        repository.addKaomoji("(^_^)v", listOf("Happy", "Red"))
        repository.addKaomoji("(*^▽^*)", listOf("Happy", "Yellow"))
        repository.addKaomoji("(;﹏;)", listOf("Sad", "Blue"))
        repository.addKaomoji("(>_<)", listOf("Sad", "Purple"))
        repository.addKaomoji("(╯°□°)╯︵ ┻━┻", listOf("Angry", "Orange"))
    }

    @Test
    fun testFirstRunStartsEmptyAndPreservesSavedConfiguration() {
        val storageDir = tempFolder.newFolder("fresh")
        val fresh = KaomojiRepository(storageDirOverride = storageDir)
        assertTrue(fresh.getAllKaomojis().isEmpty())
        fresh.addKaomoji("Custom", listOf("Local"))
        val reloaded = KaomojiRepository(storageDirOverride = storageDir)
        assertEquals(listOf("Custom"), reloaded.getAllKaomojis().map { it.text })
        assertEquals(listOf("Local"), reloaded.getAllKaomojis().single().tags)
    }

    @Test
    fun testVariableConfigurationCountUsesNormalUnionMerge() {
        val directory = tempFolder.newFolder("variable-configurations")
        val fresh = KaomojiRepository(storageDirOverride = directory)
        assertTrue(fresh.getAllKaomojis().isEmpty())
        val configurations = listOf(
            """{"version":1,"items":[]}""",
            """{"version":1,"items":[{"text":"A","tags":["first"],"usageCount":2}]}""",
            """{"version":1,"items":[{"text":"A","tags":["second"],"usageCount":7},{"text":"B","tags":[],"usageCount":0}]}"""
        )
        configurations.forEach { content -> content.byteInputStream().use { fresh.importJson(it) } }
        val reloaded = KaomojiRepository(storageDirOverride = directory).getAllKaomojis()
        assertEquals(listOf("A", "B"), reloaded.map { it.text })
        assertEquals(listOf("first", "second"), reloaded.first().tags)
        assertEquals(7, reloaded.first().usageCount)
    }

    @Test
    fun testAddKaomojiDuplicatePrevention() {
        val initialCount = repository.getAllKaomojis().size

        // Attempting to add exact duplicate kaomoji
        val successFirst = repository.addKaomoji("(^_^)v")
        assertFalse("Exact duplicate kaomoji should be rejected", successFirst)
        assertEquals(initialCount, repository.getAllKaomojis().size)

        // Adding new multi-line kaomoji with arbitrary characters
        val multilineText = "⊂(・▽・)⊃\nLine2"
        val successNew = repository.addKaomoji(multilineText, listOf("Hug"))
        assertTrue("New unique kaomoji should be added", successNew)
        assertEquals(initialCount + 1, repository.getAllKaomojis().size)
    }

    @Test
    fun testAddTagToSingleKaomojiAndPreventDuplicateTag() {
        val item = repository.getAllKaomojis().first { it.text == "(^_^)v" }

        // Add custom text tag
        val added1 = repository.addTagToKaomoji(item.id, "Favorite")
        assertTrue(added1)

        // Add color tag
        val added2 = repository.addTagToKaomoji(item.id, "Purple")
        assertTrue(added2)

        // Attempting to add duplicate tag
        val addedDuplicate = repository.addTagToKaomoji(item.id, "Favorite")
        assertFalse("Duplicate tag on same kaomoji should be rejected", addedDuplicate)

        val updatedItem = repository.getAllKaomojis().first { it.id == item.id }
        assertTrue(updatedItem.tags.contains("Favorite"))
        assertTrue(updatedItem.tags.contains("Purple"))
    }

    @Test
    fun testSearchByTextAndTagAndColor() {
        // Search by text
        val textResults = repository.search("(;﹏;)")
        assertEquals(1, textResults.size)
        assertEquals("(;﹏;)", textResults[0].text)

        // Search by text tag "Happy"
        val tagResults = repository.search("Happy")
        assertTrue(tagResults.all { it.tags.contains("Happy") })

        // Search by color tag "Red"
        val colorResults = repository.search("Red")
        assertTrue(colorResults.any { it.tags.contains("Red") })
    }

    @Test
    fun testUsageCountIncrementAndSorting() {
        val item = repository.getAllKaomojis().first { it.text == "(>_<)" }

        assertEquals(0, item.usageCount)

        // Increment usage count
        repository.incrementUsageCount(item.id)
        repository.incrementUsageCountByText("(>_<)")

        val updated = repository.getAllKaomojis().first { it.id == item.id }
        assertEquals(2, updated.usageCount)

        // Check search sorting order (highest usage count first)
        val sortedList = repository.search("")
        assertEquals("(>_<)", sortedList.first().text)

        // Check reverse sorting
        val reversedList = repository.search("", isReversed = true)
        assertEquals("(>_<)", reversedList.last().text)
    }

    @Test
    fun testBatchAddTagAndBatchDelete() {
        val items = repository.getAllKaomojis().take(2)
        val selectedIds = items.map { it.id }.toSet()

        // Batch add tag
        val updatedCount = repository.addTagToMultipleKaomojis(selectedIds, listOf("BatchTag", "Green"))
        assertEquals(2, updatedCount)

        for (id in selectedIds) {
            val kaomoji = repository.getAllKaomojis().first { it.id == id }
            assertTrue(kaomoji.tags.contains("BatchTag"))
            assertTrue(kaomoji.tags.contains("Green"))
        }

        // Batch delete
        val beforeDeleteSize = repository.getAllKaomojis().size
        val deletedCount = repository.batchDelete(selectedIds)
        assertEquals(2, deletedCount)
        assertEquals(beforeDeleteSize - 2, repository.getAllKaomojis().size)
        assertFalse(repository.getAllKaomojis().any { selectedIds.contains(it.id) })
    }

    @Test
    fun testExportAndImportUnionMerge() {
        // Add unique tag and unique kaomoji to repository before export
        val item1 = repository.getAllKaomojis().first { it.text == "(^_^)v" }
        repository.addTagToKaomoji(item1.id, "ExportTag")
        repository.addKaomoji("o(*^▽^*)o New", listOf("NewTag"))

        // Export current repo state
        val os = ByteArrayOutputStream()
        repository.exportJson(os)
        val exportedJson = os.toString("UTF-8")

        assertTrue(exportedJson.contains("version"))
        assertTrue(exportedJson.contains("(^_^)v"))
        assertTrue(exportedJson.contains("o(*^▽^*)o New"))

        // Create a new repository in another folder
        val newStorageDir = tempFolder.newFolder("kaomoji_test_2")
        val newRepo = KaomojiRepository(storageDirOverride = newStorageDir)
        newRepo.addKaomoji("(^_^)v", listOf("Happy", "Red"))

        // Modify tags for (^_^)v in newRepo to have a unique local tag
        val existingItem = newRepo.getAllKaomojis().first { it.text == "(^_^)v" }
        newRepo.addTagToKaomoji(existingItem.id, "LocalTag")

        // Now import exportedJson into newRepo
        val isStream = ByteArrayInputStream(exportedJson.toByteArray(Charsets.UTF_8))
        val result = newRepo.importJson(isStream)

        assertTrue(result.addedCount > 0)
        assertTrue(result.mergedCount > 0)

        // Verify union of tags for (^_^)v
        val mergedItem = newRepo.getAllKaomojis().first { it.text == "(^_^)v" }
        assertTrue("Existing tag preserved", mergedItem.tags.contains("LocalTag"))
        assertTrue("Imported tag merged", mergedItem.tags.contains("ExportTag"))
        assertTrue("Imported color tag merged", mergedItem.tags.contains("Red"))

        // Verify new item added
        assertTrue(newRepo.getAllKaomojis().any { it.text == "o(*^▽^*)o New" })
    }
}
