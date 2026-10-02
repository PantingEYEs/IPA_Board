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
    private fun queryBundle(type: Class<out EngineService>, raw: String, beforeCursor: String = ""): Bundle {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        var response: Bundle? = null
        val reply = Messenger(Handler(Looper.getMainLooper()) { response = it.data; latch.countDown(); true })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                Messenger(binder).send(Message.obtain(null, 1).apply {
                    replyTo = reply
                    data = Bundle().apply { putLong("revision", 71); putString("raw", raw); putString("beforeCursor", beforeCursor) }
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
        assertTrue(query(EnglishService::class.java, "", "日本語").isEmpty())
        assertTrue(query(EnglishService::class.java, "", "").isEmpty())
        assertTrue(query(EnglishService::class.java, "100/4").isEmpty())
    }
    @Test fun mozcProvidesJapaneseFromRomaji() {
        val result = query(MozcService::class.java, "nihongo")
        assertTrue(result.toString(), result.contains("日本語"))
        assertTrue(query(MozcService::class.java, "konnichiha").contains("こんにちは"))
    }
    @Test fun nonPinyinOrMathQueriesDoNotCrashEngines() {
        val rimeRes = query(RimeService::class.java, "100/4")
        assertTrue(rimeRes.isEmpty())
        val mozcRes = query(MozcService::class.java, "12.5+3.5")
        assertTrue(mozcRes.isEmpty())
    }
}
