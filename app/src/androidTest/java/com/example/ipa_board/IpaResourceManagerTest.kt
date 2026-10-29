package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ipa.*
import org.json.JSONObject
import org.json.JSONArray
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
    private var releasePre = false
    private var paginate = false
    private var multipleVersions = false
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
        metadata.put("dictionaryVersion", "v0.0.2-lexicon").put("engineVersion", "v0.0.5").put("artifactCommit", commit)
        metadata.put("dexPath", "classes.dex").put("nativePath", "libipa_graph_android.so").put("dictionaryPath", "lexicon.ipad")
        for ((prefix, bytes) in listOf("dictionary" to dictionary, "dex" to dex, "native" to native)) {
            metadata.put(prefix + "Bytes", bytes.size).put(prefix + "Sha256", hash(bytes))
        }
    }
    @After fun tearDown() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        preferences.forEach { target.deleteSharedPreferences(it) }
        assertTrue(root.deleteRecursively())
    }
    private fun published(): Pair<JSONObject, JSONObject> {
        val tag = metadata.getString("engineVersion")
        val manifest = JSONObject(metadata.toString()).put("manifestType", "ipa-engine.release")
            .put("tag", tag).put("releaseVersion", tag).put("prerelease", releasePre).put("dictionaryIndexBits", 24)
        val descriptors = JSONArray()
        val assets = JSONObject()
        fun descriptor(name: String, size: Long) = JSONObject().put("name", name).put("state", "uploaded")
            .put("size", size).put("browser_download_url", "https://github.com/PantingEYEs/IPA_Engine/releases/download/$tag/$name")
        for (key in listOf("dex", "native", "dictionary")) {
            val name = metadata.getString(key + "Path")
            val asset = descriptor(name, metadata.getLong(key + "Bytes"))
            descriptors.put(asset)
            assets.put(key, JSONObject().put("name", name).put("url", asset.getString("browser_download_url"))
                .put("bytes", metadata.getLong(key + "Bytes")).put("sha256", metadata.getString(key + "Sha256")))
        }
        manifest.put("assets", assets)
        descriptors.put(descriptor("client.json", manifest.toString().toByteArray().size.toLong()))
        val release = JSONObject().put("tag_name", tag).put("prerelease", releasePre).put("draft", false).put("assets", descriptors)
        return manifest to release
    }
    private fun manager() = IpaResourceManager(context, transport = { url, output, _ ->
        requests.add(url)
        if (networkFailure) throw IOException("owned network failure")
        val (manifest, release) = published()
        val bytes = when {
            url.startsWith(IpaReleaseManifest.API) -> {
                val list = JSONArray()
                if (paginate && url.endsWith("page=1")) repeat(100) { list.put(JSONObject(release.toString()).put("prerelease", true)) }
                else {
                    if (multipleVersions) list.put(JSONObject(release.toString()).put("tag_name", "v0.0.4"))
                    list.put(release)
                }
                list.toString().toByteArray()
            }
            url.endsWith("client.json") -> manifest.toString().toByteArray()
            url.endsWith("lexicon.ipad") -> if (corruptDownload) byteArrayOf(1, 2) else dictionary
            url.endsWith("classes.dex") -> dex
            url.endsWith("libipa_graph_android.so") -> native
            else -> error("Unexpected artifact")
        }
        if (!url.startsWith(IpaReleaseManifest.API)) assertTrue(url.contains("/releases/download/${metadata.getString("engineVersion")}/"))
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
        assertEquals("v0.0.2-lexicon", next.dictionaryVersion)
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
        assertEquals("v0.0.5", next.engineVersion)
        assertEquals(old.dictionaryVersion, next.dictionaryVersion)
        assertEquals(old.dictionaryFile, next.dictionaryFile)
        assertArrayEquals(dex, next.codeFile.readBytes())
        assertFalse(next.codeFile.canWrite())
        assertEquals(next, validated)
        assertEquals(next, manager.snapshot())
        assertFalse(requests.any { it.endsWith("lexicon.ipad") })
        validationFailure = true
        metadata.put("engineVersion", "v0.0.6")
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
        assertFalse(requests.any { it.endsWith("lexicon.ipad") })
    }

    @Test fun prereleaseOptInPersistsAndStableOnlyNeverDowngradesInstalledCode() {
        org.junit.Assume.assumeTrue(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val manager = manager(); manager.ensureInstalled()
        releasePre = true; metadata.put("engineVersion", "v0.0.6-rc.1")
        try { manager.checkRemote(); fail("Pre-release must be hidden by default") }
        catch (e: IpaResourceException) { assertEquals(IpaFailure.NO_RELEASE, e.reason) }
        com.example.ipa_board.ime.EngineSettings.preferences(context).edit().putBoolean(IpaResourceManager.KEY_ALLOW_PRERELEASE, true).commit()
        assertEquals("v0.0.6-rc.1", manager.checkRemote().engineVersion)
        val installed = manager.updateEngine()
        com.example.ipa_board.ime.EngineSettings.preferences(context).edit().putBoolean(IpaResourceManager.KEY_ALLOW_PRERELEASE, false).commit()
        assertEquals(installed, manager.snapshot())
        releasePre = false; metadata.put("engineVersion", "v0.0.5")
        requests.clear()
        assertEquals(installed, manager.updateEngine())
        assertFalse(requests.any { it.endsWith("classes.dex") || it.endsWith(".so") })
    }

    @Test fun releaseDiscoveryPaginatesAndSelectsVersionRatherThanFirstArrayItem() {
        val manager = manager()
        paginate = true
        assertEquals("v0.0.5", manager.checkRemote().engineVersion)
        assertTrue(requests.any { it.endsWith("page=2") })
        paginate = false; multipleVersions = true
        assertEquals("v0.0.5", manager.checkRemote().engineVersion)
        val (manifest, release) = published()
        manifest.getJSONObject("assets").getJSONObject("dex").put("url", "https://example.com/classes.dex")
        assertThrows(IllegalArgumentException::class.java) { IpaReleaseManifest.normalize(manifest, release) }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
