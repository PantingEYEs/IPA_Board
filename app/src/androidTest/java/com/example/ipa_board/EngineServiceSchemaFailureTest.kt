package com.example.ipa_board

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.CandidateKind
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineQueryPolicy
import com.example.ipa_board.ime.EngineRuntimeReporting
import com.example.ipa_board.ime.EngineRuntimeState
import com.example.ipa_board.ime.PinyinScheme
import com.example.ipa_board.ime.SchemaFailureTestService
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One continuous IPC sequence in a disposable debug process; no real engines, files or settings. */
class EngineServiceSchemaFailureTest {
    @Test fun schemaFailureKeepsSharedRuntimeUsableWhileGenericFailureTripsCircuitBreaker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        var remote: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = Messenger(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        val bound = context.bindService(Intent(context, SchemaFailureTestService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        try {
            assertTrue("Owned service must connect", connected.await(5, TimeUnit.SECONDS))
            val service = requireNotNull(remote)
            val instance = runtimeState(service).getString("instance")

            val schemaFailure = query(service, 1, PinyinScheme.TERRA, "ni<")
            // The exact ASSETS/IO issue must survive the real atStage wrapper; QUERY/RUNTIME would
            // reveal that the specialized exception escaped and poisoned the process-wide breaker.
            assertEquals("RIME/ASSETS/IO", schemaFailure.getString("error"))
            assertTrue(schemaFailure.getStringArrayList("text").orEmpty().isEmpty())
            assertState(service, instance, EngineRuntimeState.UNAVAILABLE)

            val luna = query(service, 2, PinyinScheme.LUNA, "ni")
            assertFalse(luna.containsKey("error"))
            assertEquals(listOf("朙月恢复"), luna.getStringArrayList("text"))
            assertState(service, instance, EngineRuntimeState.READY)

            // Even while Terra is selected, the separate Bopomofo route remains usable.
            val bopomofo = query(service, 3, PinyinScheme.TERRA, "ㄋㄧˇ")
            assertFalse(bopomofo.containsKey("error"))
            assertEquals(listOf("注音恢复"), bopomofo.getStringArrayList("text"))
            assertState(service, instance, EngineRuntimeState.READY)

            // Empty composition is a prediction request even when the selected schema rejects it
            // as spelling and segmentation is disabled in the request policy.
            val prediction = query(service, 4, PinyinScheme.TERRA, "", beforeCursor = "测试上文")
            assertFalse(prediction.containsKey("error"))
            assertEquals(listOf("续写恢复"), prediction.getStringArrayList("text"))
            assertArrayEquals(intArrayOf(CandidateKind.PREDICTION.ordinal), prediction.getIntArray("kind"))
            assertState(service, instance, EngineRuntimeState.READY)

            val genericFailure = query(service, 5, PinyinScheme.LUNA, "fatal")
            assertEquals("RIME/QUERY/RUNTIME", genericFailure.getString("error"))
            assertState(service, instance, EngineRuntimeState.UNAVAILABLE)
            val afterGenericFailure = query(service, 6, PinyinScheme.LUNA, "ni")
            assertEquals(genericFailure.getString("error"), afterGenericFailure.getString("error"))
            assertTrue(afterGenericFailure.getStringArrayList("text").orEmpty().isEmpty())
            assertState(service, instance, EngineRuntimeState.UNAVAILABLE)
        } finally {
            context.unbindService(connection)
        }
    }

    private fun query(service: Messenger, requestId: Long, scheme: PinyinScheme, raw: String,
        beforeCursor: String = ""): Bundle {
        val done = CountDownLatch(1)
        var response: Bundle? = null
        val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
            response = Bundle(message.data)
            done.countDown()
            true
        })
        service.send(Message.obtain(null, 1).apply {
            replyTo = receiver
            data = Bundle().apply {
                putLong("revision", 217L)
                putLong("requestId", requestId)
                putString("raw", raw)
                putString("beforeCursor", beforeCursor)
                EngineQueryPolicy(mapOf(EngineFeature.SEGMENTATION to false), pinyinScheme = scheme).writeTo(this)
            }
        })
        assertTrue("Owned query must complete", done.await(5, TimeUnit.SECONDS))
        return requireNotNull(response).also {
            assertEquals(requestId, it.getLong("requestId"))
            assertEquals(217L, it.getLong("revision"))
        }
    }

    private fun runtimeState(service: Messenger): Bundle {
        val done = CountDownLatch(1)
        var snapshot: Bundle? = null
        val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == EngineRuntimeReporting.STATUS && snapshot == null) {
                snapshot = Bundle(message.data)
                done.countDown()
            }
            true
        })
        // Subscribe after the query reply, obtaining a current snapshot instead of racing queued
        // BUSY/UNAVAILABLE/READY callbacks from two different Binder receivers.
        service.send(Message.obtain(null, EngineRuntimeReporting.SUBSCRIBE).apply { replyTo = receiver })
        assertTrue("Owned runtime state must arrive", done.await(5, TimeUnit.SECONDS))
        return requireNotNull(snapshot)
    }

    private fun assertState(service: Messenger, instance: String?, expected: EngineRuntimeState) {
        val state = runtimeState(service)
        assertEquals("All queries must share the same service instance", instance, state.getString("instance"))
        assertEquals(expected.name, state.getString("runtimeState"))
    }
}
