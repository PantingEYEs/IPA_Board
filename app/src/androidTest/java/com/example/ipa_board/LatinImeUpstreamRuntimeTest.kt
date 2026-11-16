package com.example.ipa_board

import android.content.ContextWrapper
import android.content.res.AssetManager
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.diagnostics.DiagnosticStage
import com.example.ipa_board.diagnostics.DiagnosticStageException
import com.example.ipa_board.ime.*
import org.ipaboard.latinime.LatinImeNative
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only synthetic inputs and owned temporary resources; run through tools/regression.py. */
class LatinImeUpstreamRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun directory() = File(context.cacheDir, "latinime-${UUID.randomUUID()}-𐐀").apply { check(mkdirs()) }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    private fun withEngine(body: (EnglishEngine, File) -> Unit) {
        val dir = directory()
        try { EnglishEngine(context, dir).use { body(it, dir) } }
        finally { dir.deleteRecursively() }
    }
    private fun expectFailure(body: () -> Unit) {
        try { body(); fail("Native invalid input/owner must fail safely") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun realRuntimeIdentityPageSizeAndImmutableDictionaryAreObserved() {
        withEngine { engine, dir ->
            LatinImeBundle.verifyIdentity(LatinImeNative.identity())
            assertEquals(1, LatinImeNative.abiVersion())
            assertTrue(engine.query("hel").any { it.text == "hello" })
            assertEquals(LatinImeBundle.DICTIONARY_SHA256, hash(File(dir, "main_en-US.dict")))
            instrumentation.sendStatus(2, Bundle().apply {
                putString("latinime_runtime", org.json.JSONObject()
                    .put("architecture", System.getProperty("os.arch"))
                    .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
                    .put("identity", String(LatinImeNative.identity(), Charsets.UTF_8)).toString())
            })
        }
    }
    @Test fun completionCorrectionAndPredictionsDoNotDependOnFixedRanks() {
        withEngine { engine, _ ->
            assertTrue(engine.query("hel").any { it.text == "hello" && it.kind == CandidateKind.COMPLETION })
            assertTrue(engine.query("helo").any { it.text == "hello" && it.kind == CandidateKind.CORRECTION })
            assertTrue(engine.query("", "thank ").any { it.text == "you" && it.kind == CandidateKind.PREDICTION })
            assertTrue(engine.query("", "日本語").isNotEmpty())
            assertTrue(engine.query("", "").isEmpty())
            assertTrue(engine.query("1+2").isEmpty())
        }
    }
    @Test fun casingApostrophesAndMixedLiteralContentKeepTheirExistingBehavior() {
        withEngine { engine, _ ->
            assertTrue(engine.query("HEL").any { it.text == "HELLO" })
            assertTrue(engine.query("Hel").any { it.text == "Hello" })
            assertTrue(engine.query("don't").any { it.text.equals("don't", true) })
            assertTrue(MixedCompositionCandidates.query(engine, "中文123hel!", "", "", true)
                .any { it.text == "中文123hello!" })
            assertTrue(MixedCompositionCandidates.query(engine, "12.5+3.5", "", "", true).isEmpty())
        }
    }
    @Test fun independentFeatureSwitchesFilterOnlyTheirOwnCandidateKinds() {
        withEngine { engine, _ ->
            engine.configure(EngineQueryPolicy(mapOf(EngineFeature.ENGLISH_COMPLETION to false)))
            assertTrue(engine.query("hel").none { it.kind == CandidateKind.EXACT || it.kind == CandidateKind.COMPLETION })
            assertTrue(engine.query("helo").any { it.text == "hello" })
            assertTrue(engine.query("", "thank ").isNotEmpty())
            engine.configure(EngineQueryPolicy(mapOf(EngineFeature.ENGLISH_CORRECTION to false)))
            assertTrue(engine.query("helo").none { it.kind == CandidateKind.CORRECTION })
            assertTrue(engine.query("hel").any { it.text == "hello" })
            engine.configure(EngineQueryPolicy(mapOf(EngineFeature.ENGLISH_PREDICTION to false)))
            assertTrue(engine.query("", "thank ").isEmpty())
            engine.configure(EngineQueryPolicy(EngineFeature.entries.associateWith { false }))
            assertTrue(engine.query("hel").isEmpty())
            assertTrue(engine.query("helo").isEmpty())
        }
    }
    @Test fun repeatedQueriesKeepScoreOrderingAndDoNotLearnOrChangeFiles() {
        withEngine { engine, dir ->
            val dictionary = File(dir, "main_en-US.dict")
            val before = dictionary.readBytes()
            val first = engine.query("helo", "I ")
            repeat(8) {
                engine.query("unrecordedpersonalword", "private ")
                engine.query("", "thank ")
                assertEquals(first, engine.query("helo", "I "))
            }
            assertTrue(first.zipWithNext().all { (a, b) -> a.nativeScore >= b.nativeScore })
            assertEquals(first.indices.toList(), first.map { it.rank })
            assertArrayEquals(before, dictionary.readBytes())
            assertEquals(listOf("main_en-US.dict"), dir.listFiles()!!.map { it.name })
        }
    }
    @Test fun corruptDeploymentIsReplacedFromUnchangedBundledBytesBeforeNativeOpen() {
        val dir = directory()
        try {
            val dictionary = File(dir, "main_en-US.dict")
            dictionary.writeBytes(ByteArray(LatinImeBundle.DICTIONARY_SIZE.toInt()))
            EnglishEngine(context, dir).use { assertTrue(it.query("hel").any { candidate -> candidate.text == "hello" }) }
            assertEquals(LatinImeBundle.DICTIONARY_SHA256, hash(dictionary))
            assertEquals(listOf("main_en-US.dict"), dir.listFiles()!!.map { it.name })
        } finally { dir.deleteRecursively() }
    }
    @Test fun unreadableAssetsFailAtAssetsStageAndLaterValidOwnerCanStillOpen() {
        val dir = directory()
        try {
            val unavailable = object : ContextWrapper(context) {
                override fun getAssets(): AssetManager = throw IOException("test-owned resource unavailable")
            }
            try { EnglishEngine(unavailable, dir).close(); fail("Missing assets accepted") }
            catch (error: DiagnosticStageException) { assertEquals(DiagnosticStage.ASSETS, error.issue.stage) }
            assertTrue(dir.listFiles()!!.isEmpty())
            EnglishEngine(context, dir).use { assertTrue(it.query("hel").isNotEmpty()) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun nativeInvalidInputsAndStaleHandlesFailWithoutBreakingSubsequentQueries() {
        withEngine { _, dir ->
            val owner = LatinImeNative.open(File(dir, "main_en-US.dict").absolutePath.toByteArray())
            try {
                for (invalid in listOf("中文".toByteArray(), "a".repeat(48).toByteArray(), byteArrayOf(0), byteArrayOf(0xff.toByte())))
                    expectFailure { LatinImeNative.query(owner, invalid, emptyArray(), false) }
                expectFailure { LatinImeNative.query(owner, "hel".toByteArray(), arrayOf("thank".toByteArray()), true) }
                assertTrue(LatinImePacket.decode(LatinImeNative.query(owner, "hel".toByteArray(), emptyArray(), true)).isNotEmpty())
                assertTrue(LatinImeNative.probability(owner, emptyArray(), false, "hello".toByteArray()) >= 0)
                assertEquals(-1, LatinImeNative.probability(owner, emptyArray(), false, "𐐀".toByteArray()))
            } finally { LatinImeNative.close(owner) }
            expectFailure { LatinImeNative.query(owner, "hel".toByteArray(), emptyArray(), true) }
            expectFailure { LatinImeNative.close(owner) }
        }
    }
    @Test fun failedNativeOpenReleasesResourcesAndPacketSurvivesLaterQueriesAndClose() {
        val dir = directory()
        try {
            val invalid = File(dir, "invalid.dict").apply { writeBytes(ByteArray(100)) }
            repeat(10) { expectFailure { LatinImeNative.open(invalid.absolutePath.toByteArray()) } }
            EnglishEngine(context, dir).use { assertTrue(it.query("hel").isNotEmpty()) }
            val owner = LatinImeNative.open(File(dir, "main_en-US.dict").absolutePath.toByteArray())
            val packet = try {
                val result = LatinImeNative.query(owner, "hel".toByteArray(), emptyArray(), true)
                LatinImeNative.query(owner, "whe".toByteArray(), emptyArray(), true)
                result
            } finally { LatinImeNative.close(owner) }
            assertTrue(LatinImePacket.decode(packet).any { it.text == "hello" })
        } finally { dir.deleteRecursively() }
    }
    @Test fun boundedOwnersCanAllBeReleasedAndOpenedAgain() {
        val dir = directory()
        val owners = mutableListOf<Long>()
        try {
            EnglishEngine(context, dir).close()
            val path = File(dir, "main_en-US.dict").absolutePath.toByteArray()
            repeat(8) { owners += LatinImeNative.open(path) }
            expectFailure { LatinImeNative.open(path) }
            val previous = owners.toList()
            owners.forEach(LatinImeNative::close); owners.clear()
            val fresh = LatinImeNative.open(path)
            owners += fresh
            assertFalse(previous.contains(fresh))
            assertTrue(LatinImePacket.decode(LatinImeNative.query(fresh, "hel".toByteArray(), emptyArray(), true)).isNotEmpty())
        } finally { owners.forEach(LatinImeNative::close); dir.deleteRecursively() }
    }

    @Test fun higherLevelCloseIsIdempotentAndDoesNotLeaveAnUsableClosedOwner() {
        withEngine { engine, _ ->
            engine.close(); engine.close()
            expectFailure { engine.query("hel") }
        }
    }
    @Test fun nativeQueryAndCloseRaceIsSerializedSafely() {
        withEngine { _, dir ->
            val owner = LatinImeNative.open(File(dir, "main_en-US.dict").absolutePath.toByteArray())
            val ready = CountDownLatch(1)
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            val thread = Thread {
                ready.countDown()
                try { LatinImePacket.decode(LatinImeNative.query(owner, "hel".toByteArray(), emptyArray(), true)) }
                catch (_: IllegalStateException) { /* Close acquired the native mutex first. */ }
                catch (error: Throwable) { failure = error }
                finally { done.countDown() }
            }
            thread.start()
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
            } finally { LatinImeNative.close(owner) }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertNull(failure)
        }
    }
}
