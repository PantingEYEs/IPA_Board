package com.example.ipa_board

import android.content.*
import android.os.*
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Focused on-device checks of actual inference, opt-out and editor context snapshots. */
class SemanticContextIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun optOutDoesNotDeployOrBindSemanticModel() {
        val prefs = ContextRankingSettings.preferences(context)
        val old = ContextRankingSettings.isEnabled(context)
        val existing = context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("e5-small-") }.map { it.name }.toSet()
        lateinit var coordinator: EngineCoordinator
        val ready = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                ContextRankingSettings.setEnabled(context, false)
                coordinator = EngineCoordinator(context) { _, items, state ->
                    if (state.isEmpty() && items.any { it.language.contains("EN") }) ready.countDown()
                }
                coordinator.start(); coordinator.query(1, "co", "我想喝咖啡", "提提神")
            }
            assertTrue("Native candidates timed out", ready.await(30, TimeUnit.SECONDS))
            assertEquals(existing, context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("e5-small-") }.map { it.name }.toSet())
            assertFalse(context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100)
                .any { it.service.className.endsWith("SemanticService") })
        } finally {
            instrumentation.runOnMainSync { coordinator.close(); ContextRankingSettings.setEnabled(context, old) }
        }
    }

    @Test fun offlineMeaningAndFollowingContextCanRerankWithoutDiscardingLanguages() {
        val bound = CountDownLatch(1)
        var remote: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { remote = Messenger(service); bound.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        assertTrue(context.bindService(Intent(context, SemanticService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(bound.await(10, TimeUnit.SECONDS))
            val candidates = listOf(Candidate("错", "简", 0), Candidate("子", "日", 0),
                Candidate("code", "EN", 0), Candidate("copy", "EN", 1), Candidate("coffee", "EN", 4))
            fun query(before: String, after: String, token: Long): Map<String, Float> {
                val received = CountDownLatch(1)
                var result = Bundle()
                val response = Messenger(Handler(Looper.getMainLooper()) { message -> result = message.data; received.countDown(); true })
                remote!!.send(Message.obtain(null, 1).apply {
                    replyTo = response
                    data = Bundle().apply {
                        putLong("revision", token); putLong("token", token)
                        putString("beforeCursor", before); putString("afterCursor", after)
                        putStringArrayList("id", ArrayList(candidates.map { it.id }))
                        putStringArrayList("text", ArrayList(candidates.map { it.text }))
                    }
                })
                assertTrue("Semantic result timed out", received.await(45, TimeUnit.SECONDS))
                assertFalse("Semantic inference failed", result.getBoolean("error"))
                assertEquals(token, result.getLong("token"))
                val scores = result.getFloatArray("similarity")!!
                assertEquals(candidates.size, scores.size); assertTrue(scores.all { it.isFinite() })
                return candidates.mapIndexed { i, c -> c.id to scores[i] }.toMap()
            }
            val started = SystemClock.elapsedRealtime()
            val drink = query("I would like to drink ", "", 1)
            assertTrue(drink.getValue(candidates.last().id) > drink.getValue(candidates[2].id))
            val following = query("", "coffee", 2)
            assertTrue(following.getValue(candidates.last().id) > 0.85f)
            val chinese = query("我想喝杯咖啡", "来提提神", 3)
            assertTrue(chinese.getValue(candidates.last().id) > chinese.getValue(candidates.first().id))
            val unrelated = query("请问现在几点，想知道", "", 4)
            val base = CandidateRanker.merge("co", candidates)
            assertEquals(base, CandidateRanker.contextual("co", base, unrelated))
            val ranked = CandidateRanker.contextual("co", base, following)
            assertEquals("coffee", ranked.first().text)
            assertTrue(ranked.take(6).any { it.language == "简" }); assertTrue(ranked.take(6).any { it.language == "日" })
            File(context.cacheDir, "semantic-context-probe.txt").writeText("Cold plus warm inference ms: ${SystemClock.elapsedRealtime() - started}\nBefore: $drink\nAfter: $following\nChinese: $chinese\nUnrelated: $unrelated\nOrder: ${ranked.map { it.text }}")
        } finally { context.unbindService(connection) }
    }

    @Test fun liveOptOutRestoresBaseOrderAndRejectsPendingSemanticUpdates() {
        val old = ContextRankingSettings.isEnabled(context)
        lateinit var coordinator: EngineCoordinator
        val ranked = CountDownLatch(1)
        val disabled = CountDownLatch(1)
        var base: List<Candidate>? = null
        var afterDisable: List<Candidate>? = null
        try {
            instrumentation.runOnMainSync {
                ContextRankingSettings.setEnabled(context, true)
                coordinator = EngineCoordinator(context) { _, items, state ->
                    if (ContextRankingSettings.isEnabled(context)) {
                        if (state.isEmpty() && items.isNotEmpty()) {
                            if (base == null) base = items
                            if (items.first().text == "whale") ranked.countDown()
                        }
                    } else if (items.isNotEmpty()) { afterDisable = items; disabled.countDown() }
                }
                coordinator.start(); coordinator.query(10, "wha", "", "whale")
            }
            val completed = ranked.await(20, TimeUnit.SECONDS)
            assertTrue("Coordinator semantic ranking timed out; base: ${base?.map { it.text }}", completed)
            assertNotNull(base)
            assertEquals("what", base!!.first().text)
            instrumentation.runOnMainSync { ContextRankingSettings.setEnabled(context, false) }
            assertTrue("Live opt-out timed out", disabled.await(5, TimeUnit.SECONDS))
            assertEquals(base, afterDisable)
            // Drain any reply already in flight; preference change must not let it replace base.
            instrumentation.waitForIdleSync()
            assertEquals(base, afterDisable)
        } finally {
            instrumentation.runOnMainSync { coordinator.close(); ContextRankingSettings.setEnabled(context, old) }
        }
    }

    @Test fun composingReadsBothSidesAndRejectsChangedFollowingText() {
        instrumentation.runOnMainSync {
            val editor = EditText(context).apply { setText("左侧right"); setSelection(2) }
            val input = editor.onCreateInputConnection(EditorInfo())!!
            val controller = CompositionController({ input }, { _, _ -> }, {})
            controller.start(); controller.input("hel")
            assertEquals("左侧", controller.beforeCursor); assertEquals("right", controller.afterCursor)
            val candidate = Candidate("hello", "EN", kind = CandidateKind.COMPLETION)
            controller.acceptResults(controller.revision, listOf(candidate))
            editor.text.replace(5, editor.text.length, "changed")
            assertFalse(controller.select(candidate))
            assertEquals("左侧helchanged", editor.text.toString())
            assertEquals("changed", controller.afterCursor)
        }
    }
}
