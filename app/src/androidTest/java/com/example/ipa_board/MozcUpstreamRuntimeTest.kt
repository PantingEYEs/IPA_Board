package com.example.ipa_board

import android.content.*
import android.os.*
import android.system.Os
import android.system.OsConstants
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import java.util.UUID
import com.example.ipa_board.diagnostics.AppDiagnostics
import com.example.ipa_board.ime.ProtoWire.blob
import com.example.ipa_board.ime.ProtoWire.value
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real bundled Mozc and synthetic inputs; device execution requires the protected regression runner. */
class MozcUpstreamRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    companion object {
        private val engine by lazy { MozcEngine(InstrumentationRegistry.getInstrumentation().targetContext) }
        private val processArch: String get() = System.getProperty("os.arch").orEmpty().lowercase(java.util.Locale.ROOT)
        private val processAbi: String get() = when (processArch) {
            "aarch64", "arm64" -> "arm64-v8a"
            "amd64", "x86_64" -> "x86_64"
            else -> processArch
        }
    }

    @Test fun runtimeUsesTheExpectedPageSizeAndRealDictionaryData() {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        val realCandidates = engine.query("nihongo")
        val version = MozcJNI.getDataVersion()
        val observation = org.json.JSONObject().put("abi", processAbi).put("processArch", processArch)
            .put("pageSize", pageSize).put("dataVersion", version).toString()
        // Status 2 records test-owned evidence without counting an extra passed JUnit test.
        instrumentation.sendStatus(2, Bundle().apply { putString("mozc_runtime", observation) })
        assertTrue("Only the exercised 4 KB / 16 KB environments are covered", pageSize in setOf(4096L, 16384L))
        assertTrue(android.os.Process.is64Bit())
        assertTrue(realCandidates.any { it.text == "日本語" })
        assertEquals("The full paired OSS dataset must be loaded", MozcBundle.DATA_VERSION, version)
    }

    @Test fun realRomajiConversionIncludesKanjiAndKanaWithoutFixingCandidateRank() {
        assertTrue(engine.query("nihongo").any { it.text == "日本語" && it.language == "日" })
        assertTrue(engine.query("konnichiha").any { it.text == "こんにちは" })
        assertTrue(engine.query("NIHONGO").any { it.text == "日本語" })
    }

    @Test fun partialSelectionConsumesOnlyItsVerifiedRomajiPrefixAndKeepsAFullChoice() {
        val raw = "watashihanihonjin"
        val results = engine.query(raw)
        assertTrue("A complete-input candidate must remain available", results.any { it.consumption == null })
        val prefix = results.firstOrNull {
            it.consumption?.let { scope -> scope.validFor(raw) && raw.drop(scope.end) == "nihonjin" } == true
        }
        assertNotNull("Native segmentation must identify watashiha, not estimate from output length", prefix)
        val selected = requireNotNull(prefix)
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            val controller = CompositionController({ connection }, { _, _ -> }, {})
            try {
                controller.input(raw)
                controller.acceptResults(controller.revision, results)
                assertTrue(controller.select(selected))
                assertEquals("nihonjin", controller.raw)
                assertEquals(selected.text + "nihonjin", editor.text.toString())
                assertEquals(selected.text.length, BaseInputConnection.getComposingSpanStart(editor.text))
                assertFalse("The old candidate cannot be selected twice", controller.select(selected))
            } finally { controller.finish() }
        }
    }

    @Test fun mixedInputPreservesDigitsAndPunctuationAndMathRemainsLiteral() {
        val mixed = MixedCompositionCandidates.query(engine, "nihongo123!", "", "", true)
        assertTrue(mixed.any { it.text == "日本語123!" && it.consumption == null })
        assertTrue(MixedCompositionCandidates.query(engine, "12.5+3.5", "", "", true).isEmpty())
        assertTrue(engine.query("nihongo").any { it.text == "日本語" })
    }

    @Test fun zeroQueryUsesTransientContextWithoutReinsertingItOrBreakingLaterConversion() {
        for (context in listOf("ありがとう", "ありがとう,")) {
            val next = engine.query("", context, "")
            assertTrue("Dictionary continuation must remain available for " + context,
                next.any { it.text == "ございます" })
            assertTrue(next.all { it.kind == CandidateKind.PREDICTION })
            assertFalse(next.any { it.text == context })
        }
        assertTrue(engine.query("", "", "").isEmpty())
        assertTrue(engine.query("konnichiha").any { it.text == "こんにちは" })
    }

    @Test fun conversionAndPredictionRemainIndependentAcrossTheRealMozcServiceBoundary() {
        val conversionOff = mapOf(EngineFeature.JAPANESE_CONVERSION to false)
        assertTrue(queryService("nihongo", "", conversionOff).getStringArrayList("text").isNullOrEmpty())
        val prediction = queryService("", "ありがとう", conversionOff)
        assertTrue(prediction.getStringArrayList("text").orEmpty().contains("ございます"))
        assertTrue(prediction.getIntArray("kind")!!.all { it == CandidateKind.PREDICTION.ordinal })
        val predictionOff = mapOf(EngineFeature.JAPANESE_PREDICTION to false)
        assertTrue(queryService("", "ありがとう", predictionOff).getStringArrayList("text").isNullOrEmpty())
        assertTrue(queryService("nihongo", "", predictionOff).getStringArrayList("text").orEmpty().contains("日本語"))
    }

    @Test fun conversionAndTransientPredictionRetainTheNativeNoLearningPolicy() {
        engine.query("nihongo")
        engine.query("", "ありがとう", "")
        // Read the real pinned protocol's effective config, not the adapter's request constants.
        // Input.GET_CONFIG=6, Output.config=9, Config.incognito_mode=20 / history_learning_level=50.
        val command = ProtoWire.message(1, ProtoWire.number(1, 6))
        val output = ProtoWire.read(ProtoWire.read(MozcJNI.evalCommand(command)).blob(2))
        val config = ProtoWire.read(output.blob(9))
        assertEquals("Conversion and continuation must remain incognito", 1L, config.value(20))
        assertEquals("NO_HISTORY must remain effective", 2L, config.value(50))
    }

    @Test fun realCoordinatorKeepsJapaneseCompatibleWithChineseEnglishAndMixedLiterals() {
        CoordinatorFixture().use { fixture ->
            for ((raw, expected, language) in listOf(
                Triple("nihongo", "日本語", "日"),
                Triple("nihao", "你好", "简"),
                Triple("hel", "hello", "EN"),
                Triple("中文əˈʊ🙂:nihongo123!", "中文əˈʊ🙂:日本語123!", "日"),
            )) {
                val candidates = fixture.query(raw).await()
                assertTrue("Missing $language candidate for synthetic input $raw", candidates.any {
                    it.text == expected && language in it.language.split('/')
                })
                assertEquals("Merged candidates must retain distinct identities",
                    candidates.map { it.id }.distinct().size, candidates.size)
                assertTrue(candidates.all { it.consumption?.validFor(raw) != false })
            }
            val raw = "watashihanihonjin"
            val segmented = fixture.query(raw).await()
            assertTrue("Mozc consumption must survive the service and mixed-ranking boundary", segmented.any {
                it.consumption?.let { scope ->
                    scope.source == "Mozc" && scope.validFor(raw) && raw.drop(scope.end) == "nihonjin"
                } == true
            })
            assertTrue(segmented.any { "日" in it.language.split('/') && it.consumption == null })

            // The bundled IPA runtime is ARM64-only. Do not turn an x86 run into an IPA-runtime claim.
            // Advertised ABIs can include ARM translation on an x86 emulator; JNI still runs in
            // this process's architecture. Do not select an ARM64 .so from that advertised list.
            val ipaAvailable = processAbi == "arm64-v8a"
            instrumentation.sendStatus(2, Bundle().apply {
                putString("mozc_mixed_ipa_runtime", if (ipaAvailable) "arm64-v8a: executing"
                    else "$processAbi: IPA unavailable isolation only")
            })
            if (ipaAvailable) {
                fixture.setFeatures(CoordinatorFixture.CONVERSION_FEATURES + EngineFeature.IPA_CONVERSION)
                assertTrue(fixture.query("həˈloʊ").await().any { it.text == "hello" && "EN" in it.language.split('/') })
                assertTrue(fixture.query("nihongo").await().any { it.text == "日本語" && "日" in it.language.split('/') })
            } else {
                fixture.setFeatures(CoordinatorFixture.CONVERSION_FEATURES + EngineFeature.IPA_CONVERSION)
                for ((raw, expected, language) in listOf(Triple("nihongo", "日本語", "日"),
                    Triple("nihao", "你好", "简"), Triple("hel", "hello", "EN"))) {
                    val candidates = fixture.query(raw, expectedUnavailable = setOf("IPA")).await()
                    assertTrue("Unavailable IPA must not prevent $language conversion", candidates.any {
                        it.text == expected && language in it.language.split('/')
                    })
                }
                fixture.setFeatures(CoordinatorFixture.CONVERSION_FEATURES)
                assertTrue(fixture.query("nihongo").await().any { it.text == "日本語" && "日" in it.language.split('/') })
            }
        }
    }

    @Test fun rapidCoordinatorRequestsDoNotPublishOldJapaneseResultsAsCurrentInput() {
        CoordinatorFixture().use { fixture ->
            fixture.query("nihongo").await() // Warm every native worker before the rapid revisions.
            val last = fixture.burst(listOf("watashihanihonjin", "nihongo", "nihao", "hel", "konnichiha"))
            assertTrue(last.await().any { it.text == "こんにちは" && "日" in it.language.split('/') })
            fixture.assertPublishedOnlyCurrentRevisions()
        }
    }

    @Test fun disablingJapanesePreservesOtherEnginesAndAllDisabledPreservesLiteralInput() {
        CoordinatorFixture().use { fixture ->
            assertTrue(fixture.query("nihongo").await().any { it.text == "日本語" })
            fixture.setFeatures(CoordinatorFixture.CONVERSION_FEATURES - EngineFeature.JAPANESE_CONVERSION)
            assertTrue(fixture.query("nihongo").await().none { "日" in it.language.split('/') })
            assertTrue(fixture.query("nihao").await().any { it.text == "你好" })
            assertTrue(fixture.query("hel").await().any { it.text == "hello" })
            fixture.setFeatures(emptySet())
            val raw = "nihongo🙂əˈʊ123!"
            val empty = fixture.query(raw).await()
            assertTrue(empty.isEmpty())
            instrumentation.runOnMainSync {
                val editor = EditText(instrumentation.targetContext)
                val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
                val controller = CompositionController({ connection }, { _, _ -> }, {})
                try {
                    controller.input(raw)
                    controller.acceptResults(controller.revision, empty)
                    assertTrue(controller.literal())
                    assertEquals(raw, editor.text.toString())
                } finally { controller.finish() }
            }
            fixture.setFeatures(CoordinatorFixture.CONVERSION_FEATURES)
            assertTrue(fixture.query("nihongo").await().any { it.text == "日本語" })
        }
    }

    /** Private preferences only; the transport itself is the production multi-process coordinator. */
    private class CoordinatorFixture : java.io.Closeable {
        companion object {
            val CONVERSION_FEATURES = setOf(EngineFeature.CHINESE_CONVERSION, EngineFeature.JAPANESE_CONVERSION,
                EngineFeature.ENGLISH_COMPLETION, EngineFeature.ENGLISH_CORRECTION, EngineFeature.SCRIPT_CONVERSION,
                EngineFeature.RANKING, EngineFeature.SEGMENTATION)
        }
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val base = instrumentation.targetContext
        private val preferenceName = "mozc-coordinator-test-${UUID.randomUUID()}"
        private val preferences = base.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        private val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        private lateinit var coordinator: EngineCoordinator
        private var revision = 0L
        private var current: Pending? = null
        private val publishedRevisions = mutableListOf<Pair<Long, Long>>()

        inner class Pending(val revision: Long, val expected: Set<String>, val expectedUnavailable: Set<String>) {
            val completed = CountDownLatch(if (expected.isEmpty()) 0 else 1)
            val replies = mutableSetOf<String>() // Main-thread confined with coordinator callbacks.
            val unavailable = mutableSetOf<String>()
            var candidates = emptyList<Candidate>()
            var failure = false
            fun await(): List<Candidate> {
                assertTrue("Native coordinator response timed out for synthetic revision $revision",
                    completed.await(180, TimeUnit.SECONDS))
                var snapshot = emptyList<Candidate>()
                instrumentation.runOnMainSync {
                    assertFalse("An enabled native engine failed", failure)
                    assertTrue("Every enabled engine must reply", replies.containsAll(expected))
                    assertEquals("Only explicitly expected unsupported runtimes may fail", expectedUnavailable, unavailable)
                    snapshot = candidates.toList()
                }
                return snapshot
            }
        }

        init {
            instrumentation.runOnMainSync {
                writeFeatures(CONVERSION_FEATURES)
                coordinator = EngineCoordinator(context) { responseRevision, candidates, state ->
                    publishedRevisions += revision to responseRevision
                    current?.takeIf { it.revision == responseRevision }?.let { pending ->
                        pending.candidates = candidates
                        if (state.contains("Unavailable") && pending.expectedUnavailable.isEmpty()) {
                            pending.failure = true
                            pending.completed.countDown()
                        }
                    }
                }.apply {
                    timing = CandidateTiming { event ->
                        if (event.stage == "reply" && event.outcome != "stale") {
                            current?.takeIf { it.revision == event.revision }?.let { pending ->
                                pending.replies += event.engine
                                if (event.outcome == "error") pending.unavailable += event.engine
                                pending.failure = pending.failure || event.outcome == "invalid" ||
                                    (event.outcome == "error" && event.engine !in pending.expectedUnavailable)
                                if (pending.failure || pending.replies.containsAll(pending.expected)) pending.completed.countDown()
                            }
                        }
                    }
                }
            }
        }

        private fun writeFeatures(features: Set<EngineFeature>) {
            check(preferences.edit().apply {
                EngineFeature.entries.forEach { putBoolean(it.key, it in features) }
            }.commit())
        }
        fun setFeatures(features: Set<EngineFeature>) = instrumentation.runOnMainSync { writeFeatures(features) }
        private fun submitOnMain(raw: String, expectedUnavailable: Set<String> = emptySet()): Pending {
            val policy = EngineSettings.snapshot(preferences)
            val expected = setOf("ZH", "JA", "EN", "IPA").filter { policy.wantsQuery(it, raw.isEmpty()) }.toSet()
            return Pending(++revision, expected, expectedUnavailable).also { pending ->
                current = pending
                coordinator.query(revision, raw)
                coordinator.start()
            }
        }
        fun query(raw: String, expectedUnavailable: Set<String> = emptySet()): Pending {
            lateinit var result: Pending
            instrumentation.runOnMainSync { result = submitOnMain(raw, expectedUnavailable) }
            return result
        }
        fun burst(inputs: List<String>): Pending {
            lateinit var result: Pending
            instrumentation.runOnMainSync { inputs.forEach { result = submitOnMain(it) } }
            return result
        }
        fun assertPublishedOnlyCurrentRevisions() = instrumentation.runOnMainSync {
            assertTrue("A superseded result was published", publishedRevisions.all { (requested, published) -> requested == published })
        }
        override fun close() = instrumentation.runOnMainSync {
            try { coordinator.close() }
            finally {
                AppDiagnostics.configure(base)
                assertTrue("Remove only test-owned preferences", base.deleteSharedPreferences(preferenceName))
            }
        }
    }

    private fun queryService(raw: String, before: String, options: Map<EngineFeature, Boolean>): Bundle {
        val context = instrumentation.targetContext
        val latch = CountDownLatch(1)
        var response: Bundle? = null
        val reply = Messenger(Handler(Looper.getMainLooper()) { response = it.data; latch.countDown(); true })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                Messenger(binder).send(Message.obtain(null, 1).apply {
                    replyTo = reply
                    data = Bundle().apply {
                        putLong("revision", 71)
                        putString("raw", raw)
                        putString("beforeCursor", before)
                        options.forEach { (feature, enabled) -> putBoolean(feature.key, enabled) }
                    }
                })
            }
            override fun onServiceDisconnected(name: ComponentName) { latch.countDown() }
        }
        assertTrue(context.bindService(Intent(context, MozcService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Mozc service response timed out", latch.await(180, TimeUnit.SECONDS))
            val result = requireNotNull(response) { "Mozc service disconnected" }
            assertFalse("Native/service initialization must not silently fall back", result.containsKey("error"))
            assertEquals(71L, result.getLong("revision"))
            return result
        } finally { context.unbindService(connection) }
    }
}
