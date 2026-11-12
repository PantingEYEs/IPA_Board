package com.example.ipa_board

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxLibrary
import ai.onnxruntime.extensions.OrtxPackage
import android.annotation.TargetApi
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.AssetFileDescriptor
import android.content.res.Configuration
import android.content.res.loader.AssetsProvider
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.diagnostics.DiagnosticComponent
import com.example.ipa_board.diagnostics.DiagnosticStage
import com.example.ipa_board.diagnostics.DiagnosticStageException
import com.example.ipa_board.ime.Candidate
import com.example.ipa_board.ime.ContextRankingSettings
import com.example.ipa_board.ime.EngineBackend
import com.example.ipa_board.ime.EngineRuntimeState
import com.example.ipa_board.ime.EngineRuntimeStates
import com.example.ipa_board.ime.SemanticEngine
import com.example.ipa_board.ime.SemanticModelFiles
import com.example.ipa_board.ime.SemanticRankerClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real packaged SentencePiece/E5 contracts, using captured synthetic reference outputs. */
@TargetApi(35)
@SdkSuppress(minSdkVersion = 35)
class OnnxExtensionsCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val environment get() = OrtEnvironment.getEnvironment()
    private val manifest by lazy {
        JSONObject(context.assets.open("engines/semantic/manifest.json").bufferedReader().use { it.readText() })
    }
    private val samples = listOf(
        "empty" to "",
        "query-prefix" to "query: ",
        "english" to "query: apple pie",
        "chinese" to "query: 我要吃苹果",
        "japanese" to "query: 林檎",
        "japanese-sentence" to "query: こんにちは、世界。",
        "accent-composed" to "☺ café",
        "accent-decomposed" to "☺ cafe\u0301",
        "fullwidth" to "query: \u3000全角Ａ１２３",
        "ipa" to "query: əˈʊ ɪŋ θ ð ɹ̠",
        "emoji" to "query: 🙂 👩‍💻 👨‍👩‍👧‍👦 🇯🇵",
        "whitespace" to "query:\t apple  pie\r\n\u00a0中文\n日本語",
        "mixed-languages" to "query: English中文日本語🙂 əˈʊ",
        "punctuation" to "query: 123.45 -6% / [a] {b} (c) !? @#&",
        "other-scripts" to "query: Ελληνικά русский العربية 한국어",
        "format-characters" to "query: a\u200Bb\u2060c\uFEFFd",
        "long-mixed" to "query: " + "apple 苹果 林檎🙂 ".repeat(40)
    )

    @Test fun nativeRegistrationAndPackagedSemanticAssetsAreUsable() {
        assertTrue("The packaged semantic libraries require a 64-bit process", Process.is64Bit())
        assertTrue("The public JNI registration entry point must resolve", OrtxLibrary.getNativeExtensionOperatorRegister() != 0L)
        val processArchitecture = when (val value = System.getProperty("os.arch")) {
            "aarch64", "arm64" -> "arm64-v8a"
            "x86_64", "amd64" -> "x86_64"
            else -> throw AssertionError("Unexpected 64-bit process architecture: $value")
        }
        assertEquals(MODEL_SHA256, manifest.getString("modelSha256"))
        assertEquals(TOKENIZER_SHA256, manifest.getString("tokenizerSha256"))
        emit("onnx_runtime_environment_b64", JSONObject()
            .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
            .put("processArchitecture", processArchitecture)
            .put("apiLevel", Build.VERSION.SDK_INT)
            .put("modelSha256", MODEL_SHA256)
            .put("tokenizerSha256", TOKENIZER_SHA256))
    }

    @Test fun tokenizerPreservesMultilingualUnicodeAndLongInputTokens() {
        val baseline = testAssetJson("tokenizer-baseline.json")
        assertBaselineIdentity(baseline)
        assertEquals(samples.size, baseline.getJSONArray("samples").length())
        val actualSamples = JSONArray()
        tokenizer().use { session ->
            samples.forEachIndexed { index, (id, text) ->
                val actual = tokenize(session, text)
                assertTrue("$id must produce token IDs", actual.isNotEmpty())
                assertTrue("$id must not produce negative token IDs", actual.all { it >= 0 })
                assertArrayEquals("$id must be deterministic", actual, tokenize(session, text))
                val expected = baseline.getJSONArray("samples").getJSONObject(index)
                assertEquals(id, expected.getString("id"))
                assertEquals(text, expected.getString("input"))
                val expectedTokens = expected.getJSONArray("ids")
                assertArrayEquals("$id differs from the captured tokenizer contract",
                    IntArray(expectedTokens.length()) { expectedTokens.getInt(it) }, actual)
                actualSamples.put(JSONObject().put("id", id).put("input", text).put("ids", JSONArray(actual.toList())))
            }
        }
        emit("onnx_tokenizer_output_b64", JSONObject()
            .put("formatVersion", 1)
            .put("modelSha256", MODEL_SHA256)
            .put("tokenizerSha256", TOKENIZER_SHA256)
            .put("samples", actualSamples))
    }

    @Test fun tokenizerCannotRunWithoutCustomOperatorRegistration() {
        val tokenizerFile = File(SemanticModelFiles.deploy(context), "tokenizer.onnx")
        OrtSession.SessionOptions().use { options ->
            try {
                environment.createSession(tokenizerFile.absolutePath, options).use { }
                fail("The production SentencePiece graph must require the Extensions custom operator")
            } catch (_: OrtException) {
                // The positive tokenizer test proves that registration makes this same graph work.
            }
        }
    }

    @Test fun realE5ScoresEnglishChineseJapaneseAndFollowingContext() {
        val probes = JSONArray()
        SemanticEngine(context).use { engine ->
            val words = listOf("错", "子", "code", "copy", "coffee")
            val drink = scores(engine, "I would like to drink ", "", words)
            assertTrue("English drink context must distinguish coffee from code", drink[4] > drink[2])
            probes.put(probe("english-before", drink))
            val chinese = scores(engine, "我想喝杯咖啡", "来提提神", words)
            assertTrue("Chinese context must retain the existing cross-language coffee relationship", chinese[4] > chinese[0])
            probes.put(probe("chinese-both-sides", chinese))
            listOf("coffee", "咖啡", "コーヒー").forEach { word ->
                val following = scores(engine, "", word, listOf(word, "software", "天气", "電車"))
                assertTrue("Following context must preserve semantic identity for $word", following[0] > 0.85f)
                probes.put(probe("following-$word", following))
            }
        }
        val report = JSONObject().put("formatVersion", 1).put("modelSha256", MODEL_SHA256)
            .put("tokenizerSha256", TOKENIZER_SHA256).put("probes", probes)
        val baseline = testAssetJson("e5-baseline.json")
        assertBaselineIdentity(baseline)
        val expected = baseline.getJSONArray("probes")
        assertEquals(expected.length(), probes.length())
        var maximumDifference = 0.0
        for (i in 0 until probes.length()) {
            val previous = expected.getJSONObject(i)
            val current = probes.getJSONObject(i)
            assertEquals(previous.getString("id"), current.getString("id"))
            val expectedScores = previous.getJSONArray("scores")
            val actualScores = current.getJSONArray("scores")
            assertEquals(expectedScores.length(), actualScores.length())
            for (j in 0 until actualScores.length()) {
                val difference = kotlin.math.abs(expectedScores.getDouble(j) - actualScores.getDouble(j))
                maximumDifference = maxOf(maximumDifference, difference)
                assertEquals("${current.getString("id")}[$j] differs from captured E5 inference",
                    expectedScores.getDouble(j), actualScores.getDouble(j), SCORE_TOLERANCE)
            }
        }
        report.put("maximumAbsoluteDifference", maximumDifference).put("scoreAbsoluteTolerance", SCORE_TOLERANCE)
        emit("onnx_e5_probe_b64", report)
    }

    @Test fun cancellationReturnsNoPartialResultAndNextQueryStillWorks() {
        SemanticEngine(context).use { engine ->
            val words = listOf("coffee", "code", "copy")
            assertNull(engine.score("drink", "", words) { true })
            var checks = 0
            val cancelled = engine.score("I would like to drink ", "", words) { ++checks >= 3 }
            assertEquals("Cancellation must be checked while processing candidates", 3, checks)
            assertNull("A partly calculated result must not escape", cancelled)
            val first = scores(engine, "", "coffee", words)
            val cached = scores(engine, "", "coffee", words)
            assertArrayEquals("Cancellation must not poison later cached queries", first, cached, 0f)
            assertTrue(first[0] > 0.85f)
        }
    }

    @Test fun emptyContextIsNeutralAndLongContextKeepsFollowingTextRelevant() {
        SemanticEngine(context).use { engine ->
            val words = listOf("coffee", "code", "林檎")
            assertArrayEquals(FloatArray(words.size), scores(engine, "", "", words), 0f)
            val longBefore = "This synthetic software discussion concerns code. ".repeat(40)
            val beforeOnly = scores(engine, longBefore, "", words)
            val bothSides = scores(engine, longBefore, "coffee", words)
            assertTrue("Long preceding context must not erase the effect of following text",
                beforeOnly.indices.any { kotlin.math.abs(beforeOnly[it] - bothSides[it]) > 0.00001f })
        }
    }

    @Test fun unavailableSemanticServicePreservesTheEntireNativeCandidateSet() {
        val preferences = ContextRankingSettings.preferences(context)
        val hadPreference = preferences.contains(ContextRankingSettings.KEY_ENABLED)
        val oldPreference = ContextRankingSettings.isEnabled(context)
        val candidates = listOf(Candidate("coffee", "EN", 4), Candidate("错", "简", 0), Candidate("子", "日", 0))
        val returned = CountDownLatch(1)
        val states = EngineRuntimeStates()
        var actual: List<Candidate>? = null
        var status = ""
        var revision = -1L
        var bindAttempts = 0
        val unavailableContext = object : ContextWrapper(context) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                bindAttempts++
                return false
            }
        }
        var client: SemanticRankerClient? = null
        try {
            instrumentation.runOnMainSync {
                ContextRankingSettings.setEnabled(context, true)
                client = SemanticRankerClient(unavailableContext, states) { receivedRevision, items, state ->
                    revision = receivedRevision
                    actual = items
                    status = state
                    returned.countDown()
                }
                client!!.query(91L, "co", "drink", "", candidates)
            }
            assertTrue("Rejected service binding must report its fallback", returned.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(91L, revision)
                assertEquals(candidates, actual)
                assertTrue(status.isNotEmpty())
                assertEquals(1, bindAttempts)
                assertEquals(EngineRuntimeState.UNAVAILABLE, states.snapshot()[EngineBackend.SEMANTIC])
            }
        } finally {
            instrumentation.runOnMainSync {
                client?.release()
                preferences.edit().apply {
                    if (hadPreference) putBoolean(ContextRankingSettings.KEY_ENABLED, oldPreference)
                    else remove(ContextRankingSettings.KEY_ENABLED)
                }.commit()
            }
        }
    }

    @Test fun controlledAssetAndMalformedModelFailuresRetainTheirStagesAndRecover() {
        val ownedRoot = File(context.cacheDir, "onnx-failure-${UUID.randomUUID()}")
        assertTrue(ownedRoot.mkdirs())
        try {
            val blocker = File(ownedRoot, "blocked-directory").apply { writeText("synthetic obstruction") }
            val blockedContext = object : ContextWrapper(context) {
                override fun getNoBackupFilesDir(): File = blocker
            }
            try {
                SemanticModelFiles.deploy(blockedContext)
                fail("A test-owned regular file must reject model deployment")
            } catch (error: DiagnosticStageException) {
                assertEquals(DiagnosticComponent.SEMANTIC, error.issue.component)
                assertEquals(DiagnosticStage.ASSETS, error.issue.stage)
            }
            assertEquals("synthetic obstruction", blocker.readText())
            withMalformedModelContext(ownedRoot) { malformedContext ->
                try {
                    SemanticEngine(malformedContext).use { }
                    fail("A malformed test-owned ONNX model must fail initialization")
                } catch (error: DiagnosticStageException) {
                    assertEquals(DiagnosticComponent.SEMANTIC, error.issue.component)
                    assertEquals(DiagnosticStage.MODEL, error.issue.stage)
                    assertTrue("The real registered tokenizer must initialize before model parsing fails", error.cause is OrtException)
                }
            }
            SemanticEngine(context).use { engine ->
                assertTrue("A model-init failure must leave the shared runtime usable",
                    scores(engine, "", "coffee", listOf("coffee"))[0] > 0.85f)
            }
        } finally {
            assertEquals(context.cacheDir.canonicalFile, ownedRoot.canonicalFile.parentFile)
            assertTrue("Remove only the fixture's private temporary directory", ownedRoot.deleteRecursively())
        }
    }

    private fun withMalformedModelContext(ownedRoot: File, action: (Context) -> Unit) {
        val badModel = File(ownedRoot, "malformed-model.onnx")
        instrumentation.context.assets.open("semantic-extensions/malformed-model.onnx").use { source ->
            badModel.outputStream().use { source.copyTo(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(badModel.readBytes())
            .joinToString("") { "%02x".format(it) }
        val badManifest = File(ownedRoot, "manifest.json").apply {
            writeText(JSONObject()
                .put("version", "onnx-test-invalid-model-v1")
                .put("modelSha256", digest).put("modelSize", badModel.length())
                .put("tokenizerSha256", TOKENIZER_SHA256)
                .put("parts", JSONArray().put(JSONObject().put("name", "test-model.part")))
                .toString())
        }
        // A separate configuration gives this test its own Resources instance. The density
        // override applies only to this context; no display or system setting is changed.
        val privateContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            densityDpi += 1
        })
        val privateResources = privateContext.resources
        assertNotSame("Never install fault assets into application Resources", context.resources, privateResources)
        val loader = ResourcesLoader()
        val provider = ResourcesProvider.empty(object : AssetsProvider {
            override fun loadAssetFd(path: String, accessMode: Int): AssetFileDescriptor? {
                val file = when (path.removePrefix("assets/")) {
                    "engines/semantic/manifest.json" -> badManifest
                    "engines/semantic/test-model.part" -> badModel
                    else -> return null // Real tokenizer and calibration stay in the application APK.
                }
                return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
            }
        })
        try {
            instrumentation.runOnMainSync {
                loader.addProvider(provider)
                privateResources.addLoaders(loader)
            }
            assertEquals("Application assets must remain untouched", MODEL_SHA256,
                JSONObject(context.assets.open("engines/semantic/manifest.json").bufferedReader().use { it.readText() })
                    .getString("modelSha256"))
            val isolatedModelRoot = File(ownedRoot, "deployment").apply { assertTrue(mkdirs()) }
            action(object : ContextWrapper(privateContext) {
                override fun getNoBackupFilesDir(): File = isolatedModelRoot
            })
        } finally {
            try {
                instrumentation.runOnMainSync {
                    privateResources.removeLoaders(loader)
                    loader.clearProviders()
                }
            } finally { provider.close() }
        }
    }

    private fun tokenizer(): OrtSession {
        val file = File(SemanticModelFiles.deploy(context), "tokenizer.onnx")
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            environment.createSession(file.absolutePath, options)
        }
    }

    private fun tokenize(session: OrtSession, text: String): IntArray =
        OnnxTensor.createTensor(environment, arrayOf(text)).use { input ->
            session.run(mapOf("inputs" to input)).use { result -> (result[0].value as IntArray).clone() }
        }

    private fun scores(engine: SemanticEngine, before: String, after: String, words: List<String>): FloatArray {
        val result = engine.score(before, after, words) { false }
        assertNotNull("Uncancelled E5 query must return a result", result)
        return result!!.also {
            assertEquals(words.size, it.size)
            assertTrue("Every semantic score must be finite", it.all { score -> score.isFinite() })
        }
    }

    private fun probe(id: String, values: FloatArray) = JSONObject().put("id", id).put("scores", JSONArray(values.toList()))
    private fun testAssetJson(name: String) = JSONObject(instrumentation.context.assets.open("semantic-extensions/$name").bufferedReader().use { it.readText() })
    private fun emit(key: String, value: JSONObject) {
        // Instrumentation output survives regression rollback; never read application-private user data.
        val encoded = Base64.encodeToString(value.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        instrumentation.sendStatus(2, Bundle().apply { putString(key, encoded) })
    }

    private fun assertBaselineIdentity(value: JSONObject) {
        assertEquals(1, value.getInt("formatVersion"))
        assertEquals(MODEL_SHA256, value.getString("modelSha256"))
        assertEquals(TOKENIZER_SHA256, value.getString("tokenizerSha256"))
    }

    companion object {
        private const val SCORE_TOLERANCE = 0.0001
        private const val MODEL_SHA256 = "dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88"
        private const val TOKENIZER_SHA256 = "4d28a2a61017a7b222164065d832e51103fbb3a4451c4e4938a2eeb8e83e44e8"
    }
}
