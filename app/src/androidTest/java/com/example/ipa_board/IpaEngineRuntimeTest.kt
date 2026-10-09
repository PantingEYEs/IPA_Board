package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.Candidate
import com.example.ipa_board.ime.MixedCompositionCandidates
import com.example.ipa_board.ipa.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real bundled DEX/JNI and dictionary; all data belongs to this test, with no network/editor use. */
@RunWith(AndroidJUnit4::class)
class IpaEngineRuntimeTest {
    private lateinit var root: File
    private lateinit var context: Context
    private val preferences = mutableSetOf<String>()

    @Before fun setUp() {
        assumeTrue("The development IPA package currently supports arm64-v8a", Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "ipa-runtime-test-${UUID.randomUUID()}-"
        root = File(target.noBackupFilesDir, "${IpaResourceManager.DIRECTORY_NAME}/$prefix").apply { assertTrue(mkdirs()) }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = File(root, "private").apply { assertTrue(mkdirs() || isDirectory) }
            override fun getCodeCacheDir(): File = File(root, "code").apply { assertTrue(mkdirs() || isDirectory) }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolated = prefix + name
                preferences.add(isolated)
                return target.getSharedPreferences(isolated, mode)
            }
        }
    }

    @After fun tearDown() {
        if (::context.isInitialized) {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            preferences.forEach { assertTrue(target.deleteSharedPreferences(it)) }
        }
        if (::root.isInitialized) assertTrue("Remove only test-owned IPA resources", root.deleteRecursively())
    }

    @Test fun bundledEngineReturnsLanguageCompatibleCandidatesAndPreservesMixedLiterals() {
        val snapshot = IpaResourceManager(context).ensureInstalled()
        IpaGraphRuntime(context, snapshot).use { runtime ->
            for ((raw, output, language) in listOf(
                Triple("həˈloʊ", "hello", "EN"), Triple("həˈləʊ", "hello", "EN"),
                Triple("ni˨˩˦ xɑʊ̯˨˩˦", "你好", "简"), Triple("tʰaɪ˧˥.wan˥˥", "臺灣", "繁"),
                Triple("ɡakːoɯ", "学校", "日"))) {
                assertTrue("The complete IPA spelling is recognized", runtime.acceptsInput(raw))
                assertCandidate(runtime.query(raw), output, language)
            }
            assertCandidate(MixedCompositionCandidates.query(runtime, "中文:həˈloʊ123😀", "", "", false),
                "中文:hello123😀", "EN")
            assertTrue(runtime.query("").isEmpty())
            for (invalid in listOf("\uD800", "\uDC00", "a".repeat(65))) {
                assertFalse(runtime.acceptsInput(invalid))
                assertTrue(runtime.query(invalid).isEmpty())
            }
        }
    }

    @Test fun precompiledDictionaryReplacementReusesEngineAndKeepsOldHandleValidUntilClosed() {
        val snapshot = IpaResourceManager(context).ensureInstalled()
        val dictionary = File(snapshot.dictionaryFile.parentFile, "test-replacement.ipad")
        InstrumentationRegistry.getInstrumentation().context.assets.open("ipa/replacement.ipad").use { input ->
            dictionary.outputStream().use { input.copyTo(it) }
        }
        val updated = snapshot.copy(generation = "test-dictionary-only", dictionaryFile = dictionary)
        IpaRuntimeValidator.validate(context, updated)
        assertEquals(snapshot.codeFile, updated.codeFile)
        assertEquals(snapshot.nativeDirectory, updated.nativeDirectory)
        IpaGraphRuntime(context, snapshot).use { previous ->
            IpaGraphRuntime(context, updated).use { replacement ->
                assertCandidate(previous.query("həˈloʊ"), "hello", "EN")
                val result = replacement.query("həˈloʊ")
                assertCandidate(result, "development😀", "EN")
                assertCandidate(result, "development😀", "简")
                assertTrue(result.none { it.text == "hello" })
                assertCandidate(replacement.query("a\u0301"), "组合𠀀😀", "简")
                assertEquals(replacement.query("á"), replacement.query("a\u0301"))
            }
            // The replacement must not close or overwrite the old mapped dictionary.
            assertCandidate(previous.query("həˈloʊ"), "hello", "EN")
        }
    }

    private fun assertCandidate(candidates: List<Candidate>, text: String, language: String) {
        assertTrue("Expected candidate with compatible language", candidates.any {
            it.text == text && language in it.language.split('/')
        })
    }
}
