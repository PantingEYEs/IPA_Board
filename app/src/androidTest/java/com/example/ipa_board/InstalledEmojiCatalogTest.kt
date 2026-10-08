package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.diagnostics.DiagnosticComponent
import com.example.ipa_board.diagnostics.DiagnosticStage
import com.example.ipa_board.diagnostics.DiagnosticStageException
import com.example.ipa_board.emoji.BundledEmojiCatalogSource
import com.example.ipa_board.emoji.EmojiCatalogRepository
import com.example.ipa_board.emoji.EmojiUpdateManager
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

    @Test fun downloadedCatalogAtomicallyReplacesTheInstalledCatalogAndCleansTemporaryData() {
        source.installedFile.writeText(sample())
        val replacement = sample().replace("19.0", "20.0")

        val catalog = EmojiUpdateManager.installCatalog(context, replacement)

        assertEquals("20.0", catalog.version)
        assertEquals(replacement, source.installedFile.readText())
        assertEquals(catalog, source.load())
        assertOnlyInstalledCatalogRemains()
    }

    @Test fun invalidDownloadedCatalogKeepsTheInstalledCatalogAndCreatesNoTemporaryData() {
        val original = sample()
        source.installedFile.writeText(original)
        val invalid = listOf("", "Downloaded network error page", sample().replace("# Version: 19.0", ""))

        invalid.forEach { content ->
            val error = assertThrows(DiagnosticStageException::class.java) {
                EmojiUpdateManager.installCatalog(context, content)
            }
            assertEquals(DiagnosticComponent.EMOJI, error.issue.component)
            assertEquals(DiagnosticStage.INTEGRITY, error.issue.stage)
            assertEquals(original, source.installedFile.readText())
            assertEquals("19.0", source.load().version)
            assertOnlyInstalledCatalogRemains()
        }
    }

    @Test fun failedPublicationKeepsTheExistingDirectoryAndCleansTemporaryData() {
        assertTrue(source.installedFile.mkdir())
        val marker = File(source.installedFile, "owned-marker.txt")
        marker.writeText("test-owned publication obstruction")

        val error = assertThrows(DiagnosticStageException::class.java) {
            EmojiUpdateManager.installCatalog(context, sample())
        }

        assertEquals(DiagnosticComponent.EMOJI, error.issue.component)
        assertEquals(DiagnosticStage.PUBLISH, error.issue.stage)
        assertTrue(source.installedFile.isDirectory)
        assertEquals("test-owned publication obstruction", marker.readText())
        assertEquals(setOf(marker), source.installedFile.listFiles().orEmpty().toSet())
        assertOnlyInstalledCatalogRemains()
    }

    private fun assertOnlyInstalledCatalogRemains() {
        assertEquals("Installing must leave no temporary catalog data",
            setOf(source.installedFile), context.filesDir.listFiles().orEmpty().toSet())
    }

    private fun sample() = """
        # Version: 19.0
        # group: Smileys & Emotion
        # subgroup: face-smiling
        1F600 ; fully-qualified # 😀 E1.0 grinning face
    """.trimIndent()

    private class EmojiStorageContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val root = Files.createTempDirectory(base.cacheDir.toPath(), "emoji-installed-contract-").toFile()
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "${root.name}-$name"
            preferenceNames.add(isolatedName)
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        override fun close() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }
}
