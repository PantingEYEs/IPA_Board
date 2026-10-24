package com.example.ipa_board

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Offline IPC fixtures exercise live policy changes without starting any native engine. */
class RemoteKeyboardEnginePolicyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun activationClearsCandidatesRejectsLateRepliesAndKeepsLiteralComposition() {
        val fixture = Fixture()
        try {
            instrumentation.runOnMainSync {
                fixture.start(setOf(EngineFeature.CHINESE_CONVERSION, EngineFeature.ENGLISH_COMPLETION,
                    EngineFeature.RANKING, EngineFeature.CALCULATOR, EngineFeature.SMS_OTP))
                fixture.controller.input("hel")
                fixture.context.connectPending()
            }
            instrumentation.waitForIdleSync()
            val english = fixture.context.latest("EnglishService")
            val pendingChinese = fixture.context.latest("RimeService")
            instrumentation.runOnMainSync { fixture.context.reply(english, "hello", "EN", CandidateKind.COMPLETION) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(listOf("hello"), fixture.controller.candidates.map { it.text })
                assertEquals(RemoteKeyboardMode.Result.ENABLED, fixture.mode.toggle())
                assertTrue(EngineFeature.entries.none { EngineSettings.enabled(fixture.prefs, it) })
                assertEquals(2, fixture.context.bindings.count { it.unbound })
                assertTrue(fixture.controller.candidates.isEmpty())
                assertEquals("hel", fixture.controller.raw)
                assertEquals("hel", fixture.editor.text.toString())
                assertEquals(EngineRuntimeState.UNKNOWN, fixture.coordinator!!.runtimeSnapshot()[EngineBackend.LATINIME])
                // The Chinese query was already delivered before activation but has not replied yet.
                fixture.context.reply(pendingChinese, "好", "简", CandidateKind.CONVERSION)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue("A disabled engine's queued response must not repopulate candidates", fixture.controller.candidates.isEmpty())
                assertEquals("hel", fixture.controller.raw)
                assertTrue(fixture.controller.literal())
                assertEquals("hel", fixture.editor.text.toString())
                assertEquals("", fixture.controller.raw)
                assertEquals(2, fixture.context.bindings.size)
            }
        } finally { fixture.close() }
    }

    @Test fun manualReenableCanQueryAndRestorationAppliesTheOriginalPolicyOnce() {
        val fixture = Fixture()
        val original = setOf(EngineFeature.CHINESE_CONVERSION, EngineFeature.ENGLISH_COMPLETION,
            EngineFeature.RANKING, EngineFeature.SMS_OTP)
        try {
            instrumentation.runOnMainSync {
                fixture.start(original)
                fixture.controller.input("hel")
                fixture.context.connectPending()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(RemoteKeyboardMode.Result.ENABLED, fixture.mode.toggle())
                EngineSettings.setEnabled(fixture.context, EngineFeature.ENGLISH_COMPLETION, true)
                EngineSettings.setEnabled(fixture.context, EngineFeature.ENGLISH_CORRECTION, true)
                assertTrue(fixture.mode.active)
                fixture.context.connectPending()
            }
            instrumentation.waitForIdleSync()
            val manualRequest = fixture.context.latest("EnglishService")
            instrumentation.runOnMainSync {
                assertTrue(manualRequest.data.getBoolean(EngineFeature.ENGLISH_COMPLETION.key))
                assertTrue(manualRequest.data.getBoolean(EngineFeature.ENGLISH_CORRECTION.key))
                fixture.context.reply(manualRequest, "hello", "EN", CandidateKind.COMPLETION)
            }
            instrumentation.waitForIdleSync()
            val englishRequestsBefore = fixture.context.requests.count { it.service.endsWith("EnglishService") }
            instrumentation.runOnMainSync {
                assertEquals(listOf("hello"), fixture.controller.candidates.map { it.text })
                assertTrue("A manual choice must remain enabled throughout active mode",
                    EngineSettings.enabled(fixture.prefs, EngineFeature.ENGLISH_CORRECTION))
                assertEquals(RemoteKeyboardMode.Result.RESTORED, fixture.mode.toggle())
                assertFalse(fixture.mode.active)
                EngineFeature.entries.forEach {
                    assertEquals("Original ${it.name} state must be restored", it in original, EngineSettings.enabled(fixture.prefs, it))
                }
                assertEquals("hel", fixture.controller.raw)
                // Chinese was originally enabled; restoring it creates a binding, without native work.
                assertEquals(1, fixture.context.bindings.count { !it.unbound && it.service.endsWith("RimeService") })
                assertEquals(1, fixture.context.bindings.count { !it.unbound && it.service.endsWith("EnglishService") })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("One atomic restoration must issue only one request to an existing backend",
                    englishRequestsBefore + 1, fixture.context.requests.count { it.service.endsWith("EnglishService") })
                val restored = fixture.context.latest("EnglishService")
                assertTrue(restored.data.getBoolean(EngineFeature.ENGLISH_COMPLETION.key))
                assertFalse(restored.data.getBoolean(EngineFeature.ENGLISH_CORRECTION.key))
                fixture.context.reply(restored, "hello", "EN", CandidateKind.COMPLETION)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertEquals(listOf("hello"), fixture.controller.candidates.map { it.text }) }
        } finally { fixture.close() }
    }

    @Test fun pagePresetChangesRejectOldEngineRepliesAndKeepTheComposingText() {
        val fixture=Fixture()
        try {
            instrumentation.runOnMainSync {
                fixture.start(setOf(EngineFeature.CHINESE_CONVERSION,EngineFeature.ENGLISH_COMPLETION))
                fixture.controller.input("hel")
                fixture.context.connectPending()
            }
            instrumentation.waitForIdleSync()
            val old=fixture.context.latest("EnglishService")
            instrumentation.runOnMainSync {
                val pages=PageEngineBindingController(fixture.prefs)
                val off=PageEngineBinding(EngineFeature.entries.associate { it.key to false })
                assertEquals(PageEngineBindingController.Result.APPLIED,pages.onPage("off",false) { off })
                fixture.context.reply(old,"hello","EN",CandidateKind.COMPLETION)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(fixture.controller.candidates.isEmpty())
                assertEquals("hel",fixture.controller.raw)
                assertTrue(fixture.controller.literal())
                assertEquals("hel",fixture.editor.text.toString())
            }
        } finally { fixture.close() }
    }

    private inner class Fixture : AutoCloseable {
        val context = EngineContext(instrumentation.targetContext)
        val prefs = EngineSettings.preferences(context)
        val mode = RemoteKeyboardMode(prefs)
        lateinit var controller: CompositionController
        lateinit var editor: EditText
        var coordinator: EngineCoordinator? = null

        fun start(enabled: Set<EngineFeature>) {
            assertTrue(prefs.edit().apply {
                EngineFeature.entries.forEach { putBoolean(it.key, it in enabled) }
            }.commit())
            editor = EditText(context)
            val input = editor.onCreateInputConnection(EditorInfo())!!
            controller = CompositionController({ input }, { revision, raw -> coordinator!!.query(revision, raw) }, {})
            coordinator = EngineCoordinator(context) { revision, candidates, _ -> controller.acceptResults(revision, candidates) }
            coordinator!!.start()
        }

        override fun close() {
            instrumentation.runOnMainSync { coordinator?.close() }
            context.close()
        }
    }

    private data class Request(val service: String, val data: Bundle, val replyTo: Messenger)

    private class EngineContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val preferencesName = "remote-policy-${UUID.randomUUID()}"
        val bindings = mutableListOf<Binding>()
        val requests = mutableListOf<Request>()

        inner class Binding(val connection: ServiceConnection, val component: ComponentName) {
            val service: String get() = component.className
            var connected = false
            var unbound = false
            val server = Messenger(Handler(Looper.getMainLooper()) { message ->
                if (message.what == 1) requests.add(Request(service, Bundle(message.data), message.replyTo))
                true
            })
        }

        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(preferencesName, mode)
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bindings.add(Binding(connection, intent.component!!))
            return true
        }
        override fun unbindService(connection: ServiceConnection) {
            bindings.last { it.connection === connection && !it.unbound }.unbound = true
        }
        fun connectPending() {
            bindings.filter { !it.connected && !it.unbound }.forEach {
                it.connected = true
                it.connection.onServiceConnected(it.component, it.server.binder)
            }
        }
        fun latest(serviceSuffix: String) = requests.last { it.service.endsWith(serviceSuffix) }
        fun reply(request: Request, text: String, language: String, kind: CandidateKind) {
            request.replyTo.send(Message.obtain(null, 1).apply {
                data = Bundle().apply {
                    putLong("revision", request.data.getLong("revision"))
                    putLong("requestId", request.data.getLong("requestId"))
                    putStringArrayList("text", arrayListOf(text))
                    putStringArrayList("language", arrayListOf(language))
                    putIntArray("rank", intArrayOf(0))
                    putIntArray("kind", intArrayOf(kind.ordinal))
                }
            })
        }
        override fun close() { baseContext.deleteSharedPreferences(preferencesName) }
    }
}
