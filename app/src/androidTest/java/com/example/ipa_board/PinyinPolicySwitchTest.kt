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
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Owned editor and fake IPC endpoints; no real service, clipboard or device setting is used. */
class PinyinPolicySwitchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(body: () -> Unit) = instrumentation.runOnMainSync(body)
    private fun drain() = instrumentation.waitForIdleSync()

    @Test fun changingSchemePreservesTextCaretAndSpansAndRejectsOldRepliesAndEqualOldButtons() {
        for (inline in listOf(true, false)) {
            Fixture(inline).use { f ->
                main { f.start(); f.controller.input("ni<hao<"); assertTrue(f.controller.moveCaret(-2)); f.context.connect() }
                drain()
                val old = f.context.requests.last()
                val same = Candidate("你好", "简", kind = CandidateKind.CONVERSION)
                main { f.context.reply(old, same.text) }
                drain()
                lateinit var editorBefore: EditorState
                var oldRevision = 0L
                main {
                    assertEquals(listOf(same), f.controller.candidates)
                    editorBefore = f.editorState()
                    oldRevision = f.controller.revision
                    f.connection.writes.clear()
                    assertTrue(f.prefs.edit().putString(PinyinSettings.KEY_SCHEME, PinyinScheme.TERRA.schemaId).commit())
                    assertEquals("ni<hao<", f.controller.raw)
                    assertEquals(5, f.controller.caret)
                    assertEquals(editorBefore, f.editorState())
                    assertTrue(f.connection.writes.isEmpty())
                    assertTrue(f.controller.candidates.isEmpty())
                    assertEquals(oldRevision + 1, f.controller.revision)
                }
                drain()
                val fresh = f.context.requests.last()
                assertEquals(PinyinScheme.TERRA, EngineQueryPolicy.from(fresh.data).pinyinScheme)
                assertEquals("ni<hao<", fresh.data.getString("raw"))
                assertTrue(fresh.data.getLong("requestId") > old.data.getLong("requestId"))
                main { f.context.reply(fresh, same.text) }
                drain()
                main {
                    assertEquals(listOf(same), f.controller.candidates)
                    assertFalse("An old button must fail even when the new candidate is equal", f.controller.select(same, oldRevision))
                    assertEquals(editorBefore, f.editorState())
                    assertTrue(f.connection.writes.isEmpty())
                    f.context.reply(old, "过期回复")
                }
                drain()
                main {
                    assertEquals(listOf(same), f.controller.candidates)
                    assertEquals(editorBefore, f.editorState())
                    assertTrue(f.controller.select(same, f.controller.revision))
                    assertEquals("前你好後", f.editor.text.toString())
                }
            }
        }
    }

    @Test fun atomicSchemeAndAliasChangesRequeryOnceAndSwitchingAwayNeverStripsToneMarks() {
        Fixture(false).use { f ->
            main { f.start(); f.controller.input("hao<"); f.context.connect() }
            drain()
            val beforeRequests = f.context.requests.size
            var beforeRevision = 0L
            main {
                beforeRevision = f.controller.revision
                f.connection.writes.clear()
                assertTrue(f.prefs.edit().putString(PinyinSettings.KEY_SCHEME, PinyinScheme.TERRA.schemaId)
                    .putBoolean(PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES, true).commit())
            }
            drain()
            assertEquals(beforeRequests + 1, f.context.requests.size)
            main {
                assertEquals(beforeRevision + 1, f.controller.revision)
                val policy = EngineQueryPolicy.from(f.context.requests.last().data)
                assertEquals(PinyinScheme.TERRA, policy.pinyinScheme)
                assertTrue(policy.terraAlternativeTones)
                assertTrue(f.prefs.edit().putString(PinyinSettings.KEY_SCHEME, PinyinScheme.LUNA.schemaId).commit())
                assertEquals("hao<", f.controller.raw)
                assertEquals(4, f.controller.caret)
                assertEquals("前後", f.editor.text.toString())
                assertTrue(f.connection.writes.isEmpty())
            }
            drain()
            assertEquals(PinyinScheme.LUNA, EngineQueryPolicy.from(f.context.requests.last().data).pinyinScheme)
            val beforeAlias = f.context.requests.size
            main { assertTrue(f.prefs.edit().putBoolean(PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES, false).commit()) }
            drain()
            assertEquals(beforeAlias + 1, f.context.requests.size)
            main { assertTrue(f.connection.writes.isEmpty()); assertEquals("hao<", f.controller.raw) }
        }
    }

    @Test fun spellingChangesNeverEnableDisabledChineseAndUnchangedSettingsDoNotInvalidate() {
        Fixture(true).use { f ->
            main { f.start(chinese = false); f.controller.input("ma<") }
            val before = f.context.requests.size
            var revision = 0L
            main {
                assertTrue(f.prefs.edit().putString(PinyinSettings.KEY_SCHEME, PinyinScheme.TERRA.schemaId).commit())
                revision = f.controller.revision
                assertTrue(f.prefs.edit().putString(PinyinSettings.KEY_SCHEME, PinyinScheme.TERRA.schemaId).commit())
                assertEquals(revision, f.controller.revision)
                assertEquals("ma<", f.controller.raw)
                assertTrue(f.controller.candidates.isEmpty())
                assertTrue(f.context.bindings.isEmpty())
            }
            drain()
            assertEquals(before, f.context.requests.size)
        }
    }

    private data class EditorState(val text: String, val start: Int, val end: Int, val composingStart: Int, val composingEnd: Int)
    private inner class Fixture(private val inline: Boolean) : AutoCloseable {
        val context = OwnedContext(instrumentation.targetContext)
        val prefs = EngineSettings.preferences(context)
        lateinit var editor: EditText
        lateinit var connection: CountingConnection
        lateinit var controller: CompositionController
        lateinit var coordinator: EngineCoordinator
        fun start(chinese: Boolean = true) {
            check(prefs.edit().apply {
                EngineFeature.entries.forEach { putBoolean(it.key, chinese && it == EngineFeature.CHINESE_CONVERSION) }
                putString(PinyinSettings.KEY_SCHEME, PinyinScheme.LUNA.schemaId)
            }.commit())
            editor = EditText(context).apply { setText("前後"); setSelection(1) }
            connection = CountingConnection(requireNotNull(editor.onCreateInputConnection(EditorInfo())))
            controller = CompositionController({ connection }, { revision, raw -> coordinator.query(revision, raw,
                controller.beforeCursor, controller.afterCursor) }, {}, initialInlineEnabled = inline)
            coordinator = EngineCoordinator(context) { revision, candidates, _ -> controller.acceptResults(revision, candidates) }
                .apply { onCompositionPolicyChanged = controller::invalidateCandidatesAndRequery }
            coordinator.start()
        }
        fun editorState() = EditorState(editor.text.toString(), editor.selectionStart, editor.selectionEnd,
            BaseInputConnection.getComposingSpanStart(editor.text), BaseInputConnection.getComposingSpanEnd(editor.text))
        override fun close() {
            main { if (::coordinator.isInitialized) coordinator.close() }
            context.close()
        }
    }

    private class CountingConnection(base: InputConnection) : InputConnectionWrapper(base, false) {
        val writes = mutableListOf<String>()
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            writes += "commit"; return super.commitText(text, newCursorPosition)
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            writes += "compose"; return super.setComposingText(text, newCursorPosition)
        }
        override fun setComposingRegion(start: Int, end: Int): Boolean {
            writes += "region"; return super.setComposingRegion(start, end)
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            writes += "selection"; return super.setSelection(start, end)
        }
        override fun finishComposingText(): Boolean { writes += "finish"; return super.finishComposingText() }
    }

    private data class Request(val data: Bundle, val reply: Messenger)
    private class OwnedContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val preferencesName = "pinyin-policy-${UUID.randomUUID()}"
        val requests = mutableListOf<Request>()
        val bindings = mutableListOf<Pair<ServiceConnection, ComponentName>>()
        private val server = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == 1) requests += Request(Bundle(message.data), message.replyTo)
            true
        })
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(preferencesName, mode)
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bindings += connection to requireNotNull(intent.component); return true
        }
        override fun unbindService(connection: ServiceConnection) { bindings.removeAll { it.first === connection } }
        fun connect() { bindings.toList().forEach { (connection, component) -> connection.onServiceConnected(component, server.binder) } }
        fun reply(request: Request, text: String) = request.reply.send(Message.obtain(null, 1).apply {
            data = Bundle().apply {
                putLong("revision", request.data.getLong("revision")); putLong("requestId", request.data.getLong("requestId"))
                putStringArrayList("text", arrayListOf(text)); putStringArrayList("language", arrayListOf("简"))
                putIntArray("rank", intArrayOf(0)); putIntArray("kind", intArrayOf(CandidateKind.CONVERSION.ordinal))
            }
        })
        override fun close() { baseContext.deleteSharedPreferences(preferencesName) }
    }
}
