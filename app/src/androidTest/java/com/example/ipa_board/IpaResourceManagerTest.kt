package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ipa.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Offline transactions: staged binaries never replace usable resources until both integrity and runtime checks pass. */
class IpaResourceManagerTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var metadata: JSONObject
    private lateinit var dictionary: ByteArray
    private val preferences = mutableSetOf<String>()
    private val requests = mutableListOf<String>()
    private var networkFailure = false
    private var validationFailure = false
    private var corruptDownload = false
    private var validated: IpaPackageSnapshot? = null
    private val commit = "a".repeat(40)
    private val dex = "fixture executable".toByteArray()
    private val native = "fixture native".toByteArray()

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "ipa-update-test-${UUID.randomUUID()}-"
        root = File(target.cacheDir, prefix).apply { assertTrue(mkdirs()) }
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(root, "private").apply { assertTrue(mkdirs() || isDirectory) }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                preferences.add(prefix + name)
                return target.getSharedPreferences(prefix + name, mode)
            }
        }
        metadata = context.assets.open("engines/ipa/bundled/client.json").use { JSONObject(String(it.readBytes())) }
        dictionary = InstrumentationRegistry.getInstrumentation().context.assets.open("ipa/replacement.ipad").use { it.readBytes() }
        metadata.put("dictionaryVersion", "dictionary-next").put("engineVersion", "engine-next").put("artifactCommit", commit)
        for ((prefix, bytes) in listOf("dictionary" to dictionary, "dex" to dex, "native" to native)) {
            metadata.put(prefix + "Bytes", bytes.size).put(prefix + "Sha256", hash(bytes))
        }
    }
    @After fun tearDown() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        preferences.forEach { target.deleteSharedPreferences(it) }
        assertTrue(root.deleteRecursively())
    }
    private fun manager() = IpaResourceManager(context, transport = { url, output, _ ->
        requests.add(url)
        if (networkFailure) throw IOException("owned network failure")
        val bytes = when {
            url.endsWith("board-update.json") -> metadata.toString().toByteArray()
            url.endsWith("lexicon.ipad") -> if (corruptDownload) byteArrayOf(1, 2) else dictionary
            url.endsWith("classes.dex") -> dex
            url.endsWith("libipa_graph_android.so") -> native
            else -> error("Unexpected artifact")
        }
        if (url.endsWith("board-update.json")) assertTrue(url.contains("/refs/heads/codex/ipa-engine-v0.0.1-dev/"))
        else assertTrue("Pin all artifacts to the published commit", url.contains("/$commit/"))
        output.writeBytes(bytes)
    }, validator = { _, snapshot ->
        validated = snapshot
        if (validationFailure) throw IOException("owned incompatible dictionary")
    })

    @Test fun dictionaryUpdateUsesCurrentEngineAndRemovesOnlyPreviousDictionaryAfterPublication() {
        org.junit.Assume.assumeTrue(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val manager = manager()
        val old = manager.ensureInstalled()
        val next = manager.updateDictionary()
        assertEquals(old.engineVersion, next.engineVersion)
        assertEquals(old.codeFile, next.codeFile)
        assertEquals(old.nativeDirectory, next.nativeDirectory)
        assertEquals("dictionary-next", next.dictionaryVersion)
        assertNotEquals(old.generation, next.generation)
        assertEquals(next, validated)
        assertEquals(next, manager.snapshot())
        assertArrayEquals(dictionary, next.dictionaryFile.readBytes())
        assertFalse(old.dictionaryFile.exists())
        assertTrue(old.codeFile.isFile)
        assertFalse(requests.any { it.endsWith("classes.dex") || it.endsWith(".so") || it.contains("source") || it.endsWith(".aar") })
        assertEquals(next.generation, com.example.ipa_board.ime.EngineSettings.preferences(context).getString(IpaResourceManager.KEY_GENERATION, null))
    }

    @Test fun failedDownloadIntegrityOrCompatibilityKeepsOldPointerAndBinary() {
        org.junit.Assume.assumeTrue(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val manager = manager()
        val old = manager.ensureInstalled()
        for (reason in listOf(IpaFailure.NETWORK, IpaFailure.PACKAGE_INVALID, IpaFailure.VALIDATION)) {
            networkFailure = reason == IpaFailure.NETWORK
            corruptDownload = reason == IpaFailure.PACKAGE_INVALID
            validationFailure = reason == IpaFailure.VALIDATION
            try { manager.updateDictionary(); fail("Expected update rejection") }
            catch (error: IpaResourceException) { assertEquals(reason, error.reason) }
            assertEquals(old, manager.snapshot())
            assertTrue(old.dictionaryFile.isFile)
            assertEquals(listOf("bundled"), old.dictionaryFile.parentFile!!.parentFile!!.list()!!.toList())
        }
    }

    @Test fun engineUpdatePreservesDictionaryAndMakesExecutableReadOnly() {
        org.junit.Assume.assumeTrue(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val manager = manager()
        val old = manager.ensureInstalled()
        val next = manager.updateEngine()
        assertEquals("engine-next", next.engineVersion)
        assertEquals(old.dictionaryVersion, next.dictionaryVersion)
        assertEquals(old.dictionaryFile, next.dictionaryFile)
        assertArrayEquals(dex, next.codeFile.readBytes())
        assertFalse(next.codeFile.canWrite())
        assertEquals(next, validated)
        assertEquals(next, manager.snapshot())
        assertFalse(requests.any { it.endsWith("lexicon.ipad") })
        validationFailure = true
        try { manager.updateEngine(); fail("Expected validation failure") }
        catch (error: IpaResourceException) { assertEquals(IpaFailure.VALIDATION, error.reason) }
        assertEquals(next, manager.snapshot())
    }

    @Test fun invalidManifestPathsAndOversizedArtifactsAreRejectedBeforeArtifactDownload() {
        val manager = manager()
        val old = manager.ensureInstalled()
        for (path in listOf("../lexicon.ipad", "/lexicon.ipad", "a//b", "a?token=x")) {
            metadata.put("dictionaryPath", path)
            try { manager.checkRemote(); fail("Expected invalid manifest") }
            catch (error: IpaResourceException) { assertEquals(IpaFailure.PACKAGE_INVALID, error.reason) }
            assertEquals(old, manager.snapshot())
        }
        metadata.put("dictionaryPath", "lexicon.ipad").put("dictionaryBytes", 40_000_001L)
        try { manager.checkRemote(); fail("Expected size rejection") }
        catch (error: IpaResourceException) { assertEquals(IpaFailure.PACKAGE_INVALID, error.reason) }
        metadata.put("dictionaryBytes", dictionary.size)
        for (invalidCommit in listOf("main", "a".repeat(39), "A".repeat(40))) {
            metadata.put("artifactCommit", invalidCommit)
            try { manager.checkRemote(); fail("Expected unpinned artifact rejection") }
            catch (error: IpaResourceException) { assertEquals(IpaFailure.PACKAGE_INVALID, error.reason) }
            assertEquals(old, manager.snapshot())
        }
        assertFalse(requests.any { it.endsWith("lexicon.ipad") })
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
