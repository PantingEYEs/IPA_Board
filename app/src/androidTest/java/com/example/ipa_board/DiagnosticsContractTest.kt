package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.diagnostics.AppDiagnostics
import com.example.ipa_board.diagnostics.DebugDiagnosticsSettings
import com.example.ipa_board.diagnostics.DiagnosticComponent
import com.example.ipa_board.diagnostics.DiagnosticIssue
import com.example.ipa_board.diagnostics.DiagnosticKind
import com.example.ipa_board.diagnostics.DiagnosticStage
import com.example.ipa_board.diagnostics.DiagnosticStageException
import com.example.ipa_board.ime.Candidate
import com.example.ipa_board.ime.EngineCoordinator
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.SemanticModelFiles
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Offline preference/IPC contracts: no service installation, real binding or system state changes. */
@RunWith(AndroidJUnit4::class)
class DiagnosticsContractTest {
    private lateinit var context: DiagnosticContext

    @Before fun setUp() {
        context = DiagnosticContext(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun tearDown() {
        try {
            AppDiagnostics.configure(InstrumentationRegistry.getInstrumentation().targetContext)
        } finally {
            context.close()
        }
    }

    @Test fun aMissingPreferenceDefaultsOffWithoutPersistingOrEnablingSemantic() {
        val preferences = DebugDiagnosticsSettings.preferences(context)
        assertFalse(preferences.contains(DebugDiagnosticsSettings.KEY_ENABLED))
        assertFalse(DebugDiagnosticsSettings.enabled(preferences))
        AppDiagnostics.configure(context)
        assertFalse(preferences.contains(DebugDiagnosticsSettings.KEY_ENABLED))
        assertFalse(EngineSettings.enabled(preferences, EngineFeature.SEMANTIC))
        assertFalse(preferences.contains(EngineFeature.SEMANTIC.key))
    }

    @Test fun diagnosticsPersistIndependentlyWithoutChangingAnyEngineChoice() {
        val preferences = EngineSettings.preferences(context)
        preferences.edit().apply {
            EngineFeature.entries.forEachIndexed { index, feature -> putBoolean(feature.key, index % 2 == 0) }
            putString("future_engine_option", "retain")
        }.commit()
        val before = preferences.all
        DebugDiagnosticsSettings.setEnabled(context, true)
        assertTrue(DebugDiagnosticsSettings.enabled(DebugDiagnosticsSettings.preferences(context)))
        assertEquals(before, preferences.all.filterKeys { it != DebugDiagnosticsSettings.KEY_ENABLED })
        DebugDiagnosticsSettings.setEnabled(context, false)
        assertFalse(DebugDiagnosticsSettings.enabled(DebugDiagnosticsSettings.preferences(context)))
        assertTrue(preferences.contains(DebugDiagnosticsSettings.KEY_ENABLED))
        assertEquals(before, preferences.all.filterKeys { it != DebugDiagnosticsSettings.KEY_ENABLED })
    }

    @Test fun invalidPreferenceTypesStayOffAndRemainUntouchedUntilExplicitlyChanged() {
        val preferences = DebugDiagnosticsSettings.preferences(context)
        preferences.edit().putBoolean(EngineFeature.CHINESE_CONVERSION.key, false).commit()
        val writes: List<(SharedPreferences.Editor) -> Unit> = listOf(
            { it.putString(DebugDiagnosticsSettings.KEY_ENABLED, "true") },
            { it.putInt(DebugDiagnosticsSettings.KEY_ENABLED, 1) },
            { it.putLong(DebugDiagnosticsSettings.KEY_ENABLED, 1L) },
            { it.putFloat(DebugDiagnosticsSettings.KEY_ENABLED, 1f) },
            { it.putStringSet(DebugDiagnosticsSettings.KEY_ENABLED, setOf("true")) }
        )
        writes.forEach { write ->
            preferences.edit().also(write).commit()
            val original = preferences.all
            assertFalse(DebugDiagnosticsSettings.enabled(preferences))
            AppDiagnostics.configure(preferences)
            assertEquals(original, preferences.all)
            assertFalse(EngineSettings.enabled(preferences, EngineFeature.CHINESE_CONVERSION))
        }
        DebugDiagnosticsSettings.setEnabled(context, true)
        assertTrue(DebugDiagnosticsSettings.enabled(preferences))
        assertFalse(EngineSettings.enabled(preferences, EngineFeature.CHINESE_CONVERSION))
    }

    @Test fun wireEnvelopesFreezeTheExplicitDiagnosticsChoiceAndRejectUntrustedIssues() {
        val preferences = DebugDiagnosticsSettings.preferences(context)
        val defaultOff = Bundle().also { DebugDiagnosticsSettings.writeTo(it, preferences) }
        assertTrue(defaultOff.containsKey(DebugDiagnosticsSettings.KEY_ENABLED))
        assertFalse(defaultOff.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED))
        DebugDiagnosticsSettings.setEnabled(context, true)
        val optedIn = Bundle().also { DebugDiagnosticsSettings.writeTo(it, preferences) }
        DebugDiagnosticsSettings.setEnabled(context, false)
        val optedOut = Bundle().also { DebugDiagnosticsSettings.writeTo(it, preferences) }
        assertTrue(optedIn.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED))
        assertFalse(optedOut.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED))
        assertFalse(defaultOff.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED))
        assertEquals(setOf(DebugDiagnosticsSettings.KEY_ENABLED), optedIn.keySet())

        val issue = DiagnosticIssue(DiagnosticComponent.RIME, DiagnosticStage.ASSETS, DiagnosticKind.IO)
        val reply = Bundle().apply { putString("error", issue.code) }
        assertEquals(issue, DiagnosticIssue.fromWire(reply.getString("error")))
        val invalid = listOf<String?>(
            null, "", "/private/raw-input.txt", "RIME/ASSETS/IO/private input",
            "UNKNOWN/ASSETS/IO", "RIME/UNKNOWN/IO", "RIME/ASSETS/UNKNOWN", "rime/ASSETS/IO",
            "RIME/ASSETS/IO\nprivate input", "x".repeat(101)
        )
        invalid.forEach { value ->
            reply.putString("error", value)
            assertNull("Untrusted error envelope must be rejected", DiagnosticIssue.fromWire(reply.getString("error")))
        }
    }

    @Test fun rejectedBindingsKeepQueriesEmptyWithAFixedUnavailableStageAndCloseSafely() {
        verifyBindingFallback(throwSecurity = false)
    }

    @Test fun securityFailuresDuringBindingDoNotEscapeStartOrQueryOrCleanup() {
        verifyBindingFallback(throwSecurity = true)
    }

    @Test fun semanticDeploymentDirectoryFailureKeepsItsStageEvenWhenDiagnosticsAreOff() {
        val root = File(context.cacheDir, "diagnostics-deployment-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        try {
            val blocker = File(root, "not-a-directory").apply { writeText("test-owned obstruction") }
            val deploymentContext = object : ContextWrapper(context) {
                override fun getNoBackupFilesDir(): File = blocker
            }
            assertFalse(DebugDiagnosticsSettings.enabled(DebugDiagnosticsSettings.preferences(context)))
            AppDiagnostics.configure(context)
            try {
                SemanticModelFiles.deploy(deploymentContext)
                fail("Deployment through a regular file must fail for the existing fallback")
            } catch (error: DiagnosticStageException) {
                assertEquals(DiagnosticComponent.SEMANTIC, error.issue.component)
                assertEquals(DiagnosticStage.ASSETS, error.issue.stage)
                assertEquals(DiagnosticKind.INVALID_DATA, error.issue.kind)
                assertTrue(error.cause is IllegalStateException)
                assertFalse(error.message.orEmpty().contains(blocker.absolutePath))
            }
            assertEquals("test-owned obstruction", blocker.readText())
            assertEquals(listOf(blocker), root.listFiles()!!.toList())
        } finally {
            assertTrue("Delete the test-owned deployment obstruction", root.deleteRecursively())
        }
    }

    @Test fun bindingDeathReleasesOnceAndLateCallbacksAfterCloseCannotPublish() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = EngineSettings.preferences(context)
        preferences.edit().apply {
            EngineFeature.entries.forEach { putBoolean(it.key, it == EngineFeature.ENGLISH_COMPLETION) }
        }.commit()
        context.acceptBindings = true
        val states = mutableListOf<Result>()
        var coordinator: EngineCoordinator? = null
        try {
            instrumentation.runOnMainSync {
                coordinator = EngineCoordinator(context) { revision, items, state -> states.add(Result(revision, items, state)) }
                coordinator!!.start()
                coordinator!!.query(28L, "test-owned-input")
                assertEquals(1, context.connections.size)
                val connection = context.connections.single()
                val component = ComponentName(context.packageName, context.bindAttempts.single()!!)
                connection.onBindingDied(component)
                assertEquals(1, context.unbindCalls)
                assertTrue(states.last().items.isEmpty())
                assertTrue(states.last().state.contains("service connection"))
                coordinator!!.close()
                assertEquals(1, context.unbindCalls)
                val delivered = states.size
                connection.onBindingDied(component)
                connection.onNullBinding(component)
                connection.onServiceDisconnected(component)
                assertEquals(delivered, states.size)
                assertEquals(1, context.unbindCalls)
            }
        } finally {
            instrumentation.runOnMainSync { coordinator?.close() }
        }
    }

    @Test fun liveRequestsCarryTheModeSnapshotAndTurningOffSendsAContentFreeControlEnvelope() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        prepareEnglishOnlyBinding()
        DebugDiagnosticsSettings.setEnabled(context, true)
        val envelopes = mutableListOf<Envelope>()
        val firstRequest = CountDownLatch(1)
        val offControl = CountDownLatch(1)
        val offRequest = CountDownLatch(1)
        var coordinator: EngineCoordinator? = null
        var workerHandler: Handler? = null
        try {
            instrumentation.runOnMainSync {
                workerHandler = Handler(Looper.getMainLooper()) { message ->
                    val envelope = Envelope(message.what, Bundle(message.data))
                    envelopes.add(envelope)
                    val enabled = envelope.data.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED)
                    when {
                        message.what == 1 && enabled -> firstRequest.countDown()
                        message.what == 1 && !enabled -> offRequest.countDown()
                        message.what == 2 && !enabled -> offControl.countDown()
                    }
                    true
                }
                coordinator = EngineCoordinator(context) { _, _, _ -> }
                coordinator!!.start()
                val connection = context.connections.single()
                val component = ComponentName(context.packageName, context.bindAttempts.single()!!)
                connection.onServiceConnected(component, Messenger(workerHandler!!).binder)
                coordinator!!.query(41L, "test-owned-input", "test-owned-before", "test-owned-after")
            }
            assertTrue("The opted-in request reaches the fake worker", firstRequest.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                DebugDiagnosticsSettings.setEnabled(context, false)
            }
            assertTrue("Turning off reaches the live worker", offControl.await(5, TimeUnit.SECONDS))
            assertTrue("The refreshed request uses the off snapshot", offRequest.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val initial = envelopes.first { it.what == 1 }
                val controlIndex = envelopes.indexOfFirst { it.what == 2 }
                val control = envelopes[controlIndex]
                val refreshedIndex = envelopes.indexOfFirst {
                    it.what == 1 && !it.data.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED)
                }
                val refreshed = envelopes[refreshedIndex]
                assertTrue(initial.data.containsKey(DebugDiagnosticsSettings.KEY_ENABLED))
                assertTrue(initial.data.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED))
                assertFalse(control.data.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED))
                assertEquals(setOf(DebugDiagnosticsSettings.KEY_ENABLED), control.data.keySet())
                listOf("raw", "text", "beforeCursor", "afterCursor").forEach { key ->
                    assertFalse("Mode changes must not carry user content", control.data.containsKey(key))
                }
                assertTrue("The mode control precedes the refreshed query", refreshedIndex > controlIndex)
                assertTrue(refreshed.data.containsKey(DebugDiagnosticsSettings.KEY_ENABLED))
                assertFalse(refreshed.data.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED))
                assertEquals(41L, refreshed.data.getLong("revision"))
                assertTrue(refreshed.data.getLong("requestId") > initial.data.getLong("requestId"))
                assertFalse(DebugDiagnosticsSettings.enabled(DebugDiagnosticsSettings.preferences(context)))
            }
        } finally {
            instrumentation.runOnMainSync {
                coordinator?.close()
                workerHandler?.removeCallbacksAndMessages(null)
            }
        }
    }

    @Test fun liveErrorRepliesAcceptFixedStagesAndRejectPrivateOrForeignComponentErrors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        prepareEnglishOnlyBinding()
        val privateWire = "/private/customer/raw-input.txt private input"
        val replies = listOf(
            DiagnosticIssue(DiagnosticComponent.ENGLISH, DiagnosticStage.DICTIONARY, DiagnosticKind.IO).code to DiagnosticStage.DICTIONARY,
            privateWire to DiagnosticStage.RESPONSE,
            DiagnosticIssue(DiagnosticComponent.RIME, DiagnosticStage.ASSETS, DiagnosticKind.IO).code to DiagnosticStage.RESPONSE
        )
        val envelopes = mutableListOf<Envelope>()
        var pending: ResponseWait? = null
        var coordinator: EngineCoordinator? = null
        var workerHandler: Handler? = null
        try {
            instrumentation.runOnMainSync {
                workerHandler = Handler(Looper.getMainLooper()) { message ->
                    if (message.what == 1) {
                        val request = Bundle(message.data)
                        envelopes.add(Envelope(message.what, request))
                        val index = (request.getLong("revision") - 50L).toInt()
                        message.replyTo!!.send(Message.obtain(null, 1).apply {
                            data = Bundle().apply {
                                putLong("revision", request.getLong("revision"))
                                putLong("requestId", request.getLong("requestId"))
                                putStringArrayList("text", arrayListOf())
                                putStringArrayList("language", arrayListOf())
                                putIntArray("rank", intArrayOf())
                                putIntArray("kind", intArrayOf())
                                putIntArray("score", intArrayOf())
                                putFloatArray("affinity", floatArrayOf())
                                putString("error", replies[index].first)
                            }
                        })
                    }
                    true
                }
                coordinator = EngineCoordinator(context) { revision, items, state ->
                    pending?.let {
                        it.result = Result(revision, items, state)
                        it.latch.countDown()
                    }
                }
                coordinator!!.start()
            }
            replies.forEachIndexed { index, (_, expectedStage) ->
                val response = ResponseWait(CountDownLatch(1))
                val revision = 50L + index
                instrumentation.runOnMainSync {
                    pending = null
                    coordinator!!.query(revision, "test-owned-input")
                    // The query's immediate publication is excluded; the fake worker replies on the next turn.
                    pending = response
                    if (index == 0) {
                        val component = ComponentName(context.packageName, context.bindAttempts.single()!!)
                        context.connections.single().onServiceConnected(component, Messenger(workerHandler!!).binder)
                    }
                }
                assertTrue("The current fake-worker reply is consumed", response.latch.await(5, TimeUnit.SECONDS))
                val result = response.result!!
                assertEquals(revision, result.revision)
                assertTrue(result.items.isEmpty())
                assertEquals("ENUnavailable (${expectedStage.label})", result.state)
                assertFalse(result.state.contains(privateWire))
                assertFalse(result.state.contains("test-owned-input"))
                assertFalse(result.state.contains("RIME"))
            }
            instrumentation.runOnMainSync {
                assertEquals(3, envelopes.size)
                assertTrue(envelopes.all { it.data.containsKey(DebugDiagnosticsSettings.KEY_ENABLED) })
                assertTrue(envelopes.all { !it.data.getBoolean(DebugDiagnosticsSettings.KEY_ENABLED) })
            }
        } finally {
            instrumentation.runOnMainSync {
                pending = null
                coordinator?.close()
                workerHandler?.removeCallbacksAndMessages(null)
            }
        }
    }

    private fun prepareEnglishOnlyBinding() {
        assertTrue(EngineSettings.preferences(context).edit().apply {
            EngineFeature.entries.forEach { putBoolean(it.key, it == EngineFeature.ENGLISH_COMPLETION) }
        }.commit())
        context.acceptBindings = true
    }

    private fun verifyBindingFallback(throwSecurity: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context.throwSecurity = throwSecurity
        val states = mutableListOf<Result>()
        var coordinator: EngineCoordinator? = null
        try {
            instrumentation.runOnMainSync {
                assertFalse(EngineSettings.enabled(context, EngineFeature.SEMANTIC))
                coordinator = EngineCoordinator(context) { revision, items, state ->
                    states.add(Result(revision, items, state))
                }
                coordinator!!.start()
                coordinator!!.query(27L, "test-owned-input")
                assertEquals(4, context.bindAttempts.size)
                assertTrue(states.isNotEmpty())
                val result = states.last()
                assertEquals(27L, result.revision)
                assertTrue(result.items.isEmpty())
                assertTrue(result.state.contains("service binding"))
                assertFalse(result.state.contains("test-owned-input"))
                assertFalse(result.state.contains("private permission message"))
                coordinator!!.close()
                coordinator!!.close()
                assertEquals(0, context.unbindCalls)
            }
        } finally {
            instrumentation.runOnMainSync { coordinator?.close() }
        }
    }

    private data class Result(val revision: Long, val items: List<Candidate>, val state: String)
    private data class Envelope(val what: Int, val data: Bundle)
    private data class ResponseWait(val latch: CountDownLatch, var result: Result? = null)

    private class DiagnosticContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val prefix = "diagnostics-contract-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()
        val bindAttempts = mutableListOf<String?>()
        val connections = mutableListOf<ServiceConnection>()
        var throwSecurity = false
        var acceptBindings = false
        var unbindCalls = 0

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bindAttempts.add(service.component?.className)
            connections.add(connection)
            if (throwSecurity) throw SecurityException("private permission message")
            return acceptBindings
        }

        override fun unbindService(connection: ServiceConnection) { unbindCalls++ }

        override fun close() {
            names.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }
}
