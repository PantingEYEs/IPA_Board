package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.emoji.BundledEmojiCatalogSource
import com.example.ipa_board.emoji.EmojiCatalogRepository
import com.example.ipa_board.emoji.InstalledEmojiCatalogSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Exercise the production installed-file source, including usable offline fallback. */
@RunWith(AndroidJUnit4::class)
class InstalledEmojiCatalogTest {
    private lateinit var context: EmojiStorageContext
    private lateinit var source: InstalledEmojiCatalogSource

    @Before fun setUp() {
        context = EmojiStorageContext(InstrumentationRegistry.getInstrumentation().targetContext)
        source = InstalledEmojiCatalogSource(context)
        assertEquals(context.filesDir, source.installedFile.parentFile)
    }

    @After fun tearDown() {
        context.close()
    }

    @Test fun missingInstalledFileLoadsTheCompleteBundledCatalog() {
        assertFalse(source.installedFile.exists())
        val bundled = BundledEmojiCatalogSource(context).load()
        assertEquals(bundled, source.load())
        assertFalse("Reading fallback must not create an installed update", source.installedFile.exists())
    }

    @Test fun malformedIncompleteOrDuplicateInstalledDataFallsBackWithoutLosingBundledEmoji() {
        val bundled = BundledEmojiCatalogSource(context).load()
        val invalid = listOf(
            "",
            "Downloaded network error page",
            "# Version: 19.0\n# group: Symbols\n# subgroup: other-symbol",
            sample().replace("# Version: 19.0", ""),
            sample().replace("1F600 ;", "110000 ;"),
            sample() + "\n1F600 ; fully-qualified # 😀 E1.0 duplicate grinning face"
        )
        for ((index, content) in invalid.withIndex()) {
            source.installedFile.writeText(content)
            assertEquals("Invalid installed catalog $index must fall back", bundled, source.load())
            assertEquals("Loading must not overwrite the user's installed file", content, source.installedFile.readText())
        }
    }

    @Test fun validInstalledCatalogTakesPrecedenceAndRemovingItRestoresBundledData() {
        source.installedFile.writeText(sample())
        val installed = source.load()
        assertEquals("19.0", installed.version)
        assertEquals(listOf("😀"), installed.entries.map { it.text })
        assertEquals("grinning face", installed.entries.single().name)
        assertTrue(source.installedFile.delete())
        assertEquals(BundledEmojiCatalogSource(context).load(), source.load())
    }

    @Test fun cacheInvalidationReadsTheActualReplacementFileAndCanRecoverFromCorruption() {
        source.installedFile.writeText(sample())
        val repository = EmojiCatalogRepository(source)
        val first = repository.load()
        source.installedFile.writeText(sample().replace("19.0", "20.0"))
        assertSame(first, repository.load())
        repository.invalidate()
        assertEquals("20.0", repository.load().version)
        source.installedFile.writeText("partial download")
        repository.invalidate()
        assertEquals(BundledEmojiCatalogSource(context).load(), repository.load())
    }

    private fun sample() = """
        # Version: 19.0
        # group: Smileys & Emotion
        # subgroup: face-smiling
        1F600 ; fully-qualified # 😀 E1.0 grinning face
    """.trimIndent()

    private class EmojiStorageContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val root = Files.createTempDirectory(base.cacheDir.toPath(), "emoji-installed-contract-").toFile()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun close() { root.deleteRecursively() }
    }
}
