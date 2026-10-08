package com.example.ipa_board

import android.content.*
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MultilingualEngineTest {
    private fun queryBundle(type: Class<out EngineService>, raw: String, beforeCursor: String = "", options: Map<EngineFeature, Boolean> = emptyMap()): Bundle {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        var response: Bundle? = null
        val reply = Messenger(Handler(Looper.getMainLooper()) { response = it.data; latch.countDown(); true })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                Messenger(binder).send(Message.obtain(null, 1).apply {
                    replyTo = reply
                    data = Bundle().apply { putLong("revision", 71); putString("raw", raw); putString("beforeCursor", beforeCursor); options.forEach { (feature, value) -> putBoolean(feature.key, value) } }
                })
            }
            override fun onServiceDisconnected(name: ComponentName) { latch.countDown() }
        }
        assertTrue(context.bindService(Intent(context, type), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Engine timed out: $raw", latch.await(180, TimeUnit.SECONDS))
            assertNotNull("Engine crashed", response)
            assertFalse(response.toString(), response!!.containsKey("error"))
            assertEquals(71L, response!!.getLong("revision"))
            return response!!
        } finally { context.unbindService(connection) }
    }
    private fun query(type: Class<out EngineService>, raw: String, beforeCursor: String = ""): List<String> =
        queryBundle(type, raw, beforeCursor).getStringArrayList("text").orEmpty()

    private fun candidates(type: Class<out EngineService>, raw: String, beforeCursor: String = ""): List<Candidate> {
        val d = queryBundle(type, raw, beforeCursor)
        return d.getStringArrayList("text").orEmpty().mapIndexed { i, text ->
            Candidate(text, d.getStringArrayList("language")!![i], d.getIntArray("rank")!![i],
                CandidateKind.entries[d.getIntArray("kind")!![i]], d.getIntArray("score")!![i])
        }
    }

    @Test fun disabledConversionKeepsItsIndependentPrediction() {
        val chineseOff = mapOf(EngineFeature.CHINESE_CONVERSION to false)
        assertTrue(queryBundle(RimeService::class.java, "nihao", options = chineseOff).getStringArrayList("text").isNullOrEmpty())
        assertTrue(queryBundle(RimeService::class.java, "", "谢谢", chineseOff).getStringArrayList("text")!!.isNotEmpty())
        val japaneseOff = mapOf(EngineFeature.JAPANESE_CONVERSION to false)
        assertTrue(queryBundle(MozcService::class.java, "nihongo", options = japaneseOff).getStringArrayList("text").isNullOrEmpty())
        assertTrue(queryBundle(MozcService::class.java, "", "ありがとう", japaneseOff).getStringArrayList("text")!!.contains("ございます"))
    }
    @Test fun scriptConversionAndSegmentationHaveDefinedFallbacks() {
        val traditional = queryBundle(RimeService::class.java, "hanyu", options = mapOf(EngineFeature.SCRIPT_CONVERSION to false))
        assertTrue(traditional.getStringArrayList("text")!!.contains("漢語"))
        assertTrue(traditional.getStringArrayList("language")!!.all { it == "繁" })
        val noSegments = queryBundle(EnglishService::class.java, "中文123hel!", options = mapOf(EngineFeature.SEGMENTATION to false))
        assertTrue(noSegments.getStringArrayList("text").isNullOrEmpty())
        assertTrue(query(EnglishService::class.java, "hel").contains("hello"))
    }
    @Test fun englishFiltersCorrectionCompletionAndPredictionIndependently() {
        val corrections = queryBundle(EnglishService::class.java, "helo", options = mapOf(EngineFeature.ENGLISH_COMPLETION to false))
        assertTrue(corrections.getStringArrayList("text")!!.contains("hello"))
        assertTrue(corrections.getIntArray("kind")!!.all { it == CandidateKind.CORRECTION.ordinal })
        val completion = queryBundle(EnglishService::class.java, "hel", options = mapOf(EngineFeature.ENGLISH_CORRECTION to false))
        assertTrue(completion.getStringArrayList("text")!!.contains("hello"))
        assertTrue(completion.getIntArray("kind")!!.none { it == CandidateKind.CORRECTION.ordinal })
        val predictionOff = queryBundle(EnglishService::class.java, "", "thank ", mapOf(EngineFeature.ENGLISH_PREDICTION to false))
        assertTrue(predictionOff.getStringArrayList("text").isNullOrEmpty())
    }
    @Test fun englishRanksNativeScoresDescendingBeforeMultilingualMerge() {
        val english = candidates(EnglishService::class.java, "wha")
        val diagnostic = english.joinToString { "${it.text}:${it.nativeScore}(rank=${it.rank})" }
        assertEquals(diagnostic, "what", english.first().text)
        assertTrue(diagnostic, english.zipWithNext().all { (a, b) -> a.nativeScore >= b.nativeScore })
        val mixed = CandidateRanker.merge("wha", english + candidates(RimeService::class.java, "wha") +
            candidates(MozcService::class.java, "wha"))
        assertEquals(mixed.toString(), "what", mixed.first { it.language.split('/').contains("EN") }.text)
        assertTrue(mixed.toString(), mixed.indexOfFirst { it.text == "what" } < 3)
        for ((raw, context) in listOf("hel" to "", "tha" to "", "whe" to "", "helo" to "", "" to "thank ")) {
            val items = candidates(EnglishService::class.java, raw, context)
            assertTrue("$raw: $items", items.isNotEmpty())
            assertTrue("$raw: $items", items.zipWithNext().all { (a, b) -> a.nativeScore >= b.nativeScore })
            assertEquals(items.indices.toList(), items.map { it.rank })
        }
    }
    @Test fun rimeProvidesChineseFromRealDictionaries() {
        assertTrue(query(RimeService::class.java, "nihao").contains("你好"))
        val chinese = query(RimeService::class.java, "hanyu")
        assertTrue(chinese.toString(), chinese.contains("汉语"))
        assertTrue(chinese.toString(), chinese.contains("漢語"))
    }
    @Test fun latinImeCompletesCorrectsAndPredictsFromRealDictionary() {
        val completion = query(EnglishService::class.java, "hel")
        assertTrue(completion.toString(), completion.contains("hello"))
        val correction = query(EnglishService::class.java, "helo")
        assertTrue(correction.toString(), correction.contains("hello"))
        val prediction = query(EnglishService::class.java, "", "thank ")
        assertEquals(prediction.toString(), "you", prediction.first())
        assertTrue(query(EnglishService::class.java, "", "日本語").isNotEmpty())
        assertTrue(query(EnglishService::class.java, "", "").isEmpty())
        assertTrue(query(EnglishService::class.java, "100/4").isEmpty())
    }
    @Test fun mozcProvidesJapaneseFromRomaji() {
        val result = query(MozcService::class.java, "nihongo")
        assertTrue(result.toString(), result.contains("日本語"))
        assertTrue(query(MozcService::class.java, "konnichiha").contains("こんにちは"))
    }
    @Test fun chinesePhraseContinuationsUseTheBundledCorpus() {
        val next = candidates(RimeService::class.java, "", "谢谢")
        assertTrue(next.toString(), next.isNotEmpty())
        assertTrue(next.toString(), next.all { it.kind == CandidateKind.PREDICTION })
        assertTrue(next.toString(), next.any { it.text == "大家" || it.text == "你" })
        val weather = candidates(RimeService::class.java, "", "天气123，")
        assertTrue(weather.toString(), weather.any { it.text == "预报" })
        assertTrue(weather.toString(), weather.any { it.language == "繁" && it.text == "預報" })
        assertTrue(candidates(RimeService::class.java, "", "今天123，").isNotEmpty())
        assertTrue(query(RimeService::class.java, "", "").isEmpty())
    }
    @Test fun japaneseZeroQuerySuggestionsUseTransientContext() {
        val contexts = listOf("私は", "東京", "日本語", "ありがとう", "私は123!?", "ありがとう,")
        val results = contexts.map { it to candidates(MozcService::class.java, "", it) }
        val diagnostic = results.joinToString { (context, items) -> "$context: $items" }
        results.forEach { (context, items) ->
            android.util.Log.i("IPAPredictionTest", "$context -> ${items.take(6).map { it.text }}")
            assertTrue("Context must not be inserted again: $context -> $items", items.none { it.text == context })
        }
        assertTrue(diagnostic, results.any { it.second.isNotEmpty() })
        assertTrue(diagnostic, results.flatMap { it.second }.all { it.kind == CandidateKind.PREDICTION })
        assertTrue(query(MozcService::class.java, "", "").isEmpty())
        assertTrue(query(MozcService::class.java, "nihongo").contains("日本語"))
    }
    @Test fun mixedInputsPreserveLiteralCharactersAndStillProduceWords() {
        val chinese = query(RimeService::class.java, "nihao123,", "")
        assertTrue(chinese.toString(), chinese.contains("你好123,"))
        val english = query(EnglishService::class.java, "中文123hel!", "")
        assertTrue(english.toString(), english.contains("中文123hello!"))
        val japanese = query(MozcService::class.java, "nihongo123!", "")
        assertTrue(japanese.toString(), japanese.contains("日本語123!"))
        assertTrue(query(EnglishService::class.java, "", "你好，今天123。").isNotEmpty())
    }
    @Test fun nonPinyinOrMathQueriesDoNotCrashEngines() {
        val rimeRes = query(RimeService::class.java, "100/4")
        assertTrue(rimeRes.isEmpty())
        val mozcRes = query(MozcService::class.java, "12.5+3.5")
        assertTrue(mozcRes.isEmpty())
    }
}
