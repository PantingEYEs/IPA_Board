package com.example.ipa_board

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.EngineVersionLookup
import com.example.ipa_board.ime.EngineVersionSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Public metadata behavior is tested offline with the production cache and callback path. */
@RunWith(AndroidJUnit4::class)
class EngineVersionLookupContractTest {
    private val source = EngineVersionSource.HELIBOARD

    @Test fun chineseUpstreamReleasesAndIntegrationSourceRemainSeparate() {
        val lookup = EngineVersionLookup({ requested ->
            when (requested) {
                EngineVersionSource.RIME -> release("1.99.0")
                EngineVersionSource.OPENCC -> release("ver.2.0.0")
                EngineVersionSource.CHINESE_BUILD -> """{"sha":"1234567890abcdef1234567890abcdef12345678"}"""
                else -> error("Unexpected source")
            }
        }, { 0L }, EngineVersionLookup.Cache())
        try {
            assertEquals(listOf("librime upstream: 1.99.0"), result(lookup, EngineVersionSource.RIME))
            assertEquals(listOf("OpenCC upstream: ver.2.0.0"), result(lookup, EngineVersionSource.OPENCC))
            assertEquals(listOf("IPA chinese source: revision 1234567890ab"), result(lookup, EngineVersionSource.CHINESE_BUILD))
        } finally { lookup.close() }
    }

    @Test fun unavailableChineseBuildDoesNotHideUpstreamReleaseAndCanRecover() {
        val clock = AtomicLong()
        val attempts = AtomicInteger()
        val lookup = EngineVersionLookup({ requested ->
            if (requested == EngineVersionSource.CHINESE_BUILD) {
                if (attempts.incrementAndGet() == 1) """{"sha":"truncated"}"""
                else """{"sha":"abcdef1234567890abcdef1234567890abcdef12"}"""
            } else release("1.17.0")
        }, clock::get, EngineVersionLookup.Cache())
        try {
            assertEquals(listOf("IPA chinese source: unavailable"), result(lookup, EngineVersionSource.CHINESE_BUILD))
            assertEquals(listOf("librime upstream: 1.17.0"), result(lookup, EngineVersionSource.RIME))
            clock.set(29_999)
            assertEquals(listOf("IPA chinese source: unavailable"), result(lookup, EngineVersionSource.CHINESE_BUILD))
            assertEquals(1, attempts.get())
            clock.set(30_000)
            assertEquals(listOf("IPA chinese source: revision abcdef123456"), result(lookup, EngineVersionSource.CHINESE_BUILD))
        } finally { lookup.close() }
    }
    @Test fun mozcUsesUpstreamCommitMetadataWithoutRequiringAReleaseTag() {
        val revision = "1234567890abcdef1234567890abcdef12345678"
        val lookup = EngineVersionLookup({ requested ->
            assertEquals(EngineVersionSource.MOZC, requested)
            """{"sha":"$revision","commit":{"message":"Offline upstream fixture"}}"""
        }, { 0L }, EngineVersionLookup.Cache())
        try {
            assertEquals(listOf("Mozc upstream: revision 1234567890ab"), result(lookup, EngineVersionSource.MOZC))
        } finally { lookup.close() }
    }

    @Test fun malformedMozcCommitMetadataIsUnavailableAndCanRecover() {
        val clock = AtomicLong(0)
        val loads = AtomicInteger()
        val lookup = EngineVersionLookup({
            if (loads.incrementAndGet() == 1) """{"sha":"not-a-full-commit"}"""
            else """{"sha":"abcdef1234567890abcdef1234567890abcdef12"}"""
        }, clock::get, EngineVersionLookup.Cache())
        try {
            assertEquals(listOf("Mozc upstream: unavailable"), result(lookup, EngineVersionSource.MOZC))
            clock.set(29_999)
            assertEquals(listOf("Mozc upstream: unavailable"), result(lookup, EngineVersionSource.MOZC))
            assertEquals(1, loads.get())
            clock.set(30_000)
            assertEquals(listOf("Mozc upstream: revision abcdef123456"), result(lookup, EngineVersionSource.MOZC))
        } finally { lookup.close() }
    }

    @Test fun successfulMetadataIsSharedForFiveMinutesAndThenRefetched() {
        val clock = AtomicLong(0)
        val loads = AtomicInteger()
        val cache = EngineVersionLookup.Cache()
        val loader: (EngineVersionSource) -> String = { release("v${loads.incrementAndGet()}") }
        val first = EngineVersionLookup(loader, clock::get, cache)
        val recreated = EngineVersionLookup(loader, clock::get, cache)
        try {
            assertEquals(listOf("${source.label}: v1"), result(first))
            first.close()
            clock.set(299_999)
            assertEquals(listOf("${source.label}: v1"), result(recreated))
            assertEquals(1, loads.get())
            clock.set(300_000)
            assertEquals(listOf("${source.label}: v2"), result(recreated))
            assertEquals(2, loads.get())
        } finally {
            first.close()
            recreated.close()
        }
    }

    @Test fun failedMetadataShowsUnavailableAndRetriesAfterThirtySeconds() {
        val clock = AtomicLong(0)
        val loads = AtomicInteger()
        val lookup = EngineVersionLookup({
            if (loads.incrementAndGet() == 1) throw IOException("Offline fixture")
            release("v2")
        }, clock::get, EngineVersionLookup.Cache())
        try {
            assertEquals(listOf("${source.label}: unavailable"), result(lookup))
            clock.set(29_999)
            assertEquals(listOf("${source.label}: unavailable"), result(lookup))
            assertEquals(1, loads.get())
            clock.set(30_000)
            assertEquals(listOf("${source.label}: v2"), result(lookup))
            assertEquals(2, loads.get())
        } finally { lookup.close() }
    }

    @Test fun closingDuringMetadataLoadReturnsImmediatelyAndSuppressesTheOldViewCallback() {
        val started = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val callback = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val lookup = EngineVersionLookup({
            started.countDown()
            try {
                check(releaseLoader.await(5, TimeUnit.SECONDS)) { "Fixture was not released" }
                release("v1")
            } catch (exception: InterruptedException) {
                interrupted.set(true)
                throw exception
            } finally { finished.countDown() }
        }, { 0L }, EngineVersionLookup.Cache())
        try {
            lookup.query(listOf(source)) { callback.countDown() }
            assertTrue("Metadata loader never started", started.await(5, TimeUnit.SECONDS))
            val closeDuration = AtomicLong()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val before = System.nanoTime()
                lookup.close()
                closeDuration.set(System.nanoTime() - before)
            }
            assertTrue("Closing blocked the UI while metadata was in flight",
                closeDuration.get() < TimeUnit.MILLISECONDS.toNanos(500))
            releaseLoader.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertFalse("Closing interrupted the metadata transport", interrupted.get())
            assertFalse("Closed view received metadata", callback.await(250, TimeUnit.MILLISECONDS))
        } finally {
            releaseLoader.countDown()
            lookup.close()
        }
    }

    @Test fun requestsAfterCloseAndConcurrentShutdownAreSafelyIgnored() {
        val loads = AtomicInteger()
        val lookup = EngineVersionLookup({
            loads.incrementAndGet()
            release("v1")
        }, { 0L }, EngineVersionLookup.Cache())
        lookup.close()
        repeat(10) { lookup.query(listOf(source)) { fail("Closed lookup invoked a callback") } }
        assertEquals(0, loads.get())

        val racing = EngineVersionLookup({ release("v1") }, { 0L }, EngineVersionLookup.Cache())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val submitter = Thread {
            try {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(200) { racing.query(listOf(source)) { } }
            } catch (exception: Throwable) { failure.set(exception) }
            finally { done.countDown() }
        }
        val closer = Thread {
            try {
                check(start.await(5, TimeUnit.SECONDS))
                racing.close()
            } catch (exception: Throwable) { failure.set(exception) }
            finally { done.countDown() }
        }
        try {
            submitter.start()
            closer.start()
            start.countDown()
            assertTrue("Concurrent lookup shutdown did not finish", done.await(5, TimeUnit.SECONDS))
            assertNull("Concurrent close must not reject a query", failure.get())
        } finally {
            start.countDown()
            racing.close()
        }
    }

    private fun result(lookup: EngineVersionLookup, requested: EngineVersionSource = source): List<String> {
        val arrived = CountDownLatch(1)
        val value = AtomicReference<List<String>>()
        lookup.query(listOf(requested)) { result ->
            value.set(result)
            arrived.countDown()
        }
        assertTrue("Version result never reached the UI callback", arrived.await(5, TimeUnit.SECONDS))
        return requireNotNull(value.get())
    }

    private fun release(tag: String) = """{"tag_name":"$tag","draft":false,"prerelease":false}"""
}
