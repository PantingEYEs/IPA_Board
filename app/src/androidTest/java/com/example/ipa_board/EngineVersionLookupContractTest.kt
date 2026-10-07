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

    private fun result(lookup: EngineVersionLookup): List<String> {
        val arrived = CountDownLatch(1)
        val value = AtomicReference<List<String>>()
        lookup.query(listOf(source)) { result ->
            value.set(result)
            arrived.countDown()
        }
        assertTrue("Version result never reached the UI callback", arrived.await(5, TimeUnit.SECONDS))
        return requireNotNull(value.get())
    }

    private fun release(tag: String) = """{"tag_name":"$tag","draft":false,"prerelease":false}"""
}
