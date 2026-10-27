package com.example.ipa_board

import android.content.*
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.*
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.diagnostics.AppDiagnostics
import com.example.ipa_board.ime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real IPC/engines; synthetic composition and production candidate view, no external editor/clipboard. */
class CandidateTimingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun profileFirstWarmAndRapidQueries() {
        val fixture = Fixture()
        try {
            // First observed query: reports actual initialization, not a claim of cold OS/disk caches.
            fixture.submit("yes", "first_query", "yes").await()
            for (round in 1..5) for (raw in listOf("yes", "shide", "soudesu")) {
                fixture.submit(raw, "warm", "$raw-$round").await()
            }
            for (raw in listOf("yes", "shide", "soudesu")) {
                var last: Pending? = null
                for (length in 1..raw.length) {
                    last = fixture.submit(raw.take(length), "rapid", "$raw-$length")
                    // Fixed synthetic typing interval. No polling in the measured engine/main threads.
                    Thread.sleep(40)
                }
                last!!.await() // Coalesced intermediate requests are allowed to have no replies.
            }
            // Stress coalescing independently of the 40ms typing samples.
            var burst: Pending? = null
            for (length in 1..7) burst = fixture.submit("soudesu".take(length), "burst", "soudesu-$length")
            burst!!.await()
            fixture.submit("100/4", "empty_results", "math").await(requireCandidates = false)
            if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                fixture.setFeatures(setOf(EngineFeature.IPA_CONVERSION, EngineFeature.RANKING, EngineFeature.SEGMENTATION))
                fixture.submit("həˈloʊ", "ipa", "hello-ipa", setOf("IPA")).await()
            }
            fixture.setFeatures(emptySet())
            fixture.submit("yes", "all_disabled", "literal", emptySet()).await(requireCandidates = false)
            assertTrue("Events use one monotonic clock", fixture.events.all { it.endNs >= it.startNs })
            for (engine in listOf("ZH", "JA", "EN")) {
                for (stage in listOf("outbound_ipc", "worker_queue", "initialize", "engine_query", "serialize", "inbound_ipc", "decode")) {
                    assertTrue("Missing $engine/$stage", fixture.events.any { it.engine == engine && it.stage == stage })
                }
            }
        } finally {
            fixture.closeAndReport()
        }
    }

    @Test fun workerTimingIsOptInAndPreservesCandidates() {
        val context = instrumentation.targetContext
        val connected = CountDownLatch(1)
        var remote: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = Messenger(binder); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        assertTrue(context.bindService(Intent(context, EnglishService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(30, TimeUnit.SECONDS))
            fun query(timed: Boolean): Bundle {
                val done = CountDownLatch(1)
                var result: Bundle? = null
                val reply = Messenger(Handler(Looper.getMainLooper()) { result = Bundle(it.data); done.countDown(); true })
                remote!!.send(Message.obtain(null, 1).apply {
                    replyTo = reply
                    data = Bundle().apply {
                        putLong("revision", 51L); putLong("requestId", 1L); putString("raw", "hel")
                        if (timed) putLong(CandidateTiming.KEY, CandidateTiming.now())
                    }
                })
                assertTrue(done.await(180, TimeUnit.SECONDS))
                return checkNotNull(result).also { assertFalse(it.containsKey("error")) }
            }
            val plain = query(false)
            val timed = query(true)
            assertFalse(plain.containsKey(CandidateTiming.KEY))
            assertEquals(plain.getStringArrayList("text"), timed.getStringArrayList("text"))
            assertEquals(plain.getStringArrayList("language"), timed.getStringArrayList("language"))
            val stamps = checkNotNull(timed.getBundle(CandidateTiming.KEY))
            assertEquals(setOf("sent", "received", "worker", "initializeStart", "initializeEnd",
                "queryStart", "queryEnd", "serializeStart", "serializeEnd", "reply"), stamps.keySet())
            assertTrue(stamps.keySet().all { key -> stamps.getLong(key) > 0L })
            assertTrue(stamps.getLong("queryEnd") >= stamps.getLong("queryStart"))
            assertFalse(stamps.containsKey("raw")); assertFalse(stamps.containsKey("text"))
        } finally { context.unbindService(connection) }
    }

    @Test fun releaseWorkerIgnoresProbeAndConsumerFailureIsIsolated() {
        val base = instrumentation.targetContext
        val release = object : ContextWrapper(base) {
            override fun getApplicationInfo() = ApplicationInfo(base.applicationInfo).apply {
                flags = flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            }
        }
        val envelope = Bundle().apply { putLong(CandidateTiming.KEY, CandidateTiming.now()) }
        assertNull(EngineQueryTiming.begin(release, envelope))
        assertNull(EngineQueryTiming.begin(base, Bundle()))
        // Broken diagnostics cannot interrupt candidate code using the probe.
        CandidateTiming { throw IllegalStateException("test-owned observer") }
            .record(1, 1, "EN", "reply", 1, 2)
    }

    private inner class Pending(val revision: Long, val expected: Set<String>, val sampleView: () -> Unit) {
        val done = CountDownLatch(if (expected.isEmpty()) 0 else 1)
        val replies = mutableSetOf<String>()
        var hasCandidates = false
        var error = false
        fun await(requireCandidates: Boolean = true) {
            assertTrue("Query $revision did not finish; partial timing events are retained", done.await(180, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertFalse("Engine failure for query $revision", error)
                if (requireCandidates) assertTrue("No candidates for query $revision", hasCandidates)
                sampleView()
            }
        }
    }

    private inner class Fixture {
        private val base = instrumentation.targetContext
        private val prefix = "candidate-timing-${UUID.randomUUID()}"
        private val prefs = base.getSharedPreferences(prefix, Context.MODE_PRIVATE)
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        val events = mutableListOf<CandidateTimingEvent>() // main-thread confined
        private val labels = mutableMapOf<Long, Pair<String, String>>()
        private val pending = mutableMapOf<Long, Pending>()
        private var nextLabel = "setup" to "setup"
        private var nextExpected = setOf("ZH", "JA", "EN")
        private var composeStarted = 0L
        private var currentRequest = 0L
        private lateinit var coordinator: EngineCoordinator
        private lateinit var controller: CompositionController
        private lateinit var chrome: ImeChromeView
        private var closed = false
        private val probe = CandidateTiming { event ->
            events.add(event)
            if (event.stage == "query_started") currentRequest = event.requestId
            if (event.stage == "reply" && event.outcome != "stale") pending[event.revision]?.let { p ->
                p.replies.add(event.engine)
                p.error = p.error || event.outcome in setOf("error", "invalid")
                if (p.replies.containsAll(p.expected)) p.done.countDown()
            }
        }

        init {
            setFeatures(EngineFeature.entries.filter { it !in setOf(EngineFeature.IPA_CONVERSION,
                EngineFeature.SEMANTIC, EngineFeature.CALCULATOR, EngineFeature.SMS_OTP) }.toSet())
            instrumentation.runOnMainSync {
                chrome = ImeChromeView(base).apply { setKeyboardHeight(300) }
                val connection = BaseInputConnection(EditText(base), true)
                controller = CompositionController({ connection }, { revision, raw ->
                    labels[revision] = nextLabel
                    pending[revision] = Pending(revision, nextExpected, ::sampleLayoutDraw)
                    val queryTime = CandidateTiming.now()
                    coordinator.query(revision, raw)
                    probe.record(currentRequest, revision, "ALL", "compose_to_query", composeStarted, queryTime)
                }, {
                    val start = CandidateTiming.now()
                    chrome.render(controller.raw, controller.candidates, "", controller.revision)
                    probe.record(currentRequest, controller.revision, "UI", "render", start, count = controller.candidates.size)
                }, contextAllowed = { false })
                createCoordinator()
            }
        }

        private fun createCoordinator() {
            coordinator = EngineCoordinator(context) { revision, candidates, _ ->
                controller.acceptResults(revision, candidates)
                pending[revision]?.hasCandidates = candidates.isNotEmpty()
            }.apply { timing = probe }
        }

        fun setFeatures(features: Set<EngineFeature>) {
            instrumentation.runOnMainSync {
                val recreate = ::coordinator.isInitialized
                if (recreate) coordinator.close()
                check(prefs.edit().apply { EngineFeature.entries.forEach { putBoolean(it.key, it in features) } }.commit())
                if (recreate) createCoordinator()
            }
        }

        fun submit(raw: String, scenario: String, label: String, expected: Set<String> = setOf("ZH", "JA", "EN")): Pending {
            lateinit var result: Pending
            instrumentation.runOnMainSync {
                nextLabel = scenario to label; nextExpected = expected
                composeStarted = CandidateTiming.now()
                controller.replaceRaw(raw)
                coordinator.start() // Idempotent; first query includes service binding and initialization.
                result = pending.getValue(controller.revision)
            }
            return result
        }

        private fun sampleLayoutDraw() {
            // Separate controlled layout/software draw sample; no attached window or GPU claim.
            if (::chrome.isInitialized) {
                val start = CandidateTiming.now()
                chrome.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
                chrome.layout(0, 0, chrome.measuredWidth, chrome.measuredHeight)
                probe.record(currentRequest, controller.revision, "UI", "layout", start)
                val bitmap = Bitmap.createBitmap(chrome.width, chrome.height, Bitmap.Config.ARGB_8888)
                try {
                    val drawStarted = CandidateTiming.now()
                    chrome.draw(Canvas(bitmap))
                    probe.record(currentRequest, controller.revision, "UI", "software_draw", drawStarted)
                } finally { bitmap.recycle() }
            }
        }

        fun closeAndReport() {
            if (closed) return
            closed = true
            try {
                instrumentation.runOnMainSync {
                    if (::coordinator.isInitialized) coordinator.close()
                }
                // Emit after measurement, not from timed callbacks or through private device logcat.
                for (event in events) {
                    val label = labels[event.revision] ?: ("setup" to "setup")
                    val row = JSONObject().apply {
                        put("schema", 1); put("scenario", label.first); put("case", label.second)
                        put("request_id", event.requestId); put("revision", event.revision)
                        put("engine", event.engine); put("stage", event.stage)
                        put("start_ns", event.startNs); put("end_ns", event.endNs)
                        put("count", event.count); put("outcome", event.outcome)
                        put("expected_engines", pending[event.revision]?.expected?.sorted()?.joinToString(",") ?: "")
                    }
                    instrumentation.sendStatus(2, Bundle().apply { putString("candidate_timing", row.toString()) })
                }
            } finally {
                instrumentation.runOnMainSync {
                    if (::coordinator.isInitialized) coordinator.close()
                    AppDiagnostics.configure(base)
                    base.deleteSharedPreferences(prefix)
                }
            }
        }
    }
}
