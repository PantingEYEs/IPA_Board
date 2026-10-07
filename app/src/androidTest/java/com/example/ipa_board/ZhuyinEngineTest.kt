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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.CandidateKind
import com.example.ipa_board.ime.CompositionController
import com.example.ipa_board.ime.EngineCoordinator
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.RimeService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the packaged Rime dictionaries through the same IPC path used by the keyboard. */
@RunWith(AndroidJUnit4::class)
class ZhuyinEngineTest {
    private fun query(
        raw: String,
        beforeCursor: String = "",
        options: Map<EngineFeature, Boolean> = emptyMap(),
    ): Bundle {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        var response: Bundle? = null
        val reply = Messenger(Handler(Looper.getMainLooper()) {
            response = it.data
            latch.countDown()
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                Messenger(binder).send(Message.obtain(null, 1).apply {
                    replyTo = reply
                    data = Bundle().apply {
                        putLong("revision", 83)
                        putLong("requestId", 19)
                        putString("raw", raw)
                        putString("beforeCursor", beforeCursor)
                        options.forEach { (feature, value) -> putBoolean(feature.key, value) }
                    }
                })
            }
            override fun onServiceDisconnected(name: ComponentName) { latch.countDown() }
        }
        assertTrue(context.bindService(Intent(context, RimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Rime timed out for $raw", latch.await(180, TimeUnit.SECONDS))
            val result = requireNotNull(response) { "Rime disconnected for $raw" }
            assertFalse(result.toString(), result.containsKey("error"))
            assertEquals(83L, result.getLong("revision"))
            assertEquals(19L, result.getLong("requestId"))
            return result
        } finally {
            context.unbindService(connection)
        }
    }

    private fun Bundle.texts(): List<String> = getStringArrayList("text").orEmpty()

    @Test fun tonedZhuyinConvertsWholePhrasesAndProvidesBothScripts() {
        val greeting = query("ㄋㄧˇㄏㄠˇ").texts()
        assertTrue(greeting.toString(), greeting.contains("你好"))
        val language = query("ㄏㄢˋㄩˇ")
        assertTrue(language.texts().toString(), language.texts().contains("漢語"))
        assertTrue(language.texts().toString(), language.texts().contains("汉语"))
        assertTrue(language.getIntArray("kind")!!.all { it == CandidateKind.CONVERSION.ordinal })
    }

    @Test fun mixedZhuyinPreservesLeadingAndTrailingLiteralText() {
        val result = query("中文😀ㄋㄧˇㄏㄠˇ123!").texts()
        assertTrue(result.toString(), result.contains("中文😀你好123!"))
        assertTrue(result.toString(), result.all { it.startsWith("中文😀") && it.endsWith("123!") })
    }

    @Test fun omittedTonesAndIncompleteInitialStillProvideCandidates() {
        val noTone = query("ㄋㄧ").texts()
        assertTrue(noTone.toString(), noTone.contains("你"))
        val initial = query("ㄋ").texts()
        assertTrue(initial.toString(), initial.isNotEmpty())
        assertTrue(initial.toString(), initial.none { it.contains('ㄋ') })
        val phrase = query("ㄋㄧㄏㄠ").texts()
        assertTrue(phrase.toString(), phrase.contains("你好"))
    }

    @Test fun toneMarksAreAppliedAndNeutralToneMayPrecedeItsSyllable() {
        val firstTone = query("ㄇㄚˉ").texts()
        assertTrue(firstTone.toString(), firstTone.contains("媽"))
        val fourthTone = query("ㄇㄚˋ").texts()
        assertTrue(fourthTone.toString(), fourthTone.contains("罵"))
        assertNotEquals(firstTone, fourthTone)
        val precedingNeutralTone = query("˙ㄉㄜ").texts()
        val followingNeutralTone = query("ㄉㄜ˙").texts()
        assertTrue(precedingNeutralTone.toString(), precedingNeutralTone.contains("的"))
        assertEquals(followingNeutralTone, precedingNeutralTone)
    }

    @Test fun chineseConversionSwitchAlsoControlsZhuyinWithoutDisablingPrediction() {
        val options = mapOf(EngineFeature.CHINESE_CONVERSION to false)
        assertTrue(query("ㄋㄧˇㄏㄠˇ", options = options).texts().isEmpty())
        val prediction = query("", beforeCursor = "谢谢", options = options)
        assertTrue(prediction.texts().toString(), prediction.texts().isNotEmpty())
        assertTrue(prediction.getIntArray("kind")!!.all { it == CandidateKind.PREDICTION.ordinal })
        assertTrue(query("ㄋㄧˇㄏㄠˇ").texts().contains("你好"))
    }

    @Test fun scriptConversionAndSegmentationSwitchesHaveConsistentZhuyinFallbacks() {
        val traditional = query("ㄏㄢˋㄩˇ", options = mapOf(EngineFeature.SCRIPT_CONVERSION to false))
        assertTrue(traditional.texts().toString(), traditional.texts().contains("漢語"))
        assertTrue(traditional.getStringArrayList("language")!!.all { it == "繁" })
        val unsegmented = mapOf(EngineFeature.SEGMENTATION to false)
        assertTrue(query("ㄋㄧˇㄏㄠˇ", options = unsegmented).texts().contains("你好"))
        assertTrue(query("ㄋㄧˇㄏㄠˇ123!", options = unsegmented).texts().isEmpty())
    }

    @Test fun changingInputAlphabetKeepsPinyinAndZhuyinSessionsIsolated() {
        for (raw in listOf("nihao", "ㄋㄧˇㄏㄠˇ", "nihao", "ㄋㄧˇㄏㄠˇ")) {
            val result = query(raw).texts()
            assertTrue("$raw: $result", result.contains("你好"))
            assertTrue("$raw: $result", result.none { it.contains('ㄋ') || it.contains('ㄏ') })
        }
    }

    @Test fun misplacedLeadingToneCannotBeSilentlyDiscardedByConversion() {
        // A tone typed before any syllable may be ignored by the native keyboard parser. Such a
        // query must stay literal rather than offer a replacement that silently removes the tone.
        assertTrue(query("ˊㄋㄧ").texts().isEmpty())
        assertTrue(query("ˇ").texts().isEmpty())
        assertTrue(query("ㄋㄧˇ").texts().contains("你"))
    }

    @Test fun incrementalZhuyinComposesDeletesRetypesAndCommitsChinese() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = EngineSettings.preferences(context)
        val before = EngineFeature.entries.associateWith {
            preferences.contains(it.key) to EngineSettings.enabled(preferences, it)
        }
        var coordinator: EngineCoordinator? = null
        lateinit var controller: CompositionController
        lateinit var editor: android.widget.EditText
        var phase = 1
        val initialResult = CountDownLatch(1)
        val retypedResult = CountDownLatch(1)
        val input = "ㄋㄧˇㄏㄠˇ"
        try {
            instrumentation.runOnMainSync {
                preferences.edit().apply {
                    val enabled = setOf(EngineFeature.CHINESE_CONVERSION, EngineFeature.SCRIPT_CONVERSION,
                        EngineFeature.SEGMENTATION, EngineFeature.RANKING)
                    EngineFeature.entries.forEach { putBoolean(it.key, it in enabled) }
                }.commit()
                editor = android.widget.EditText(context)
                val connection = editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
                controller = CompositionController({ connection }, { revision, raw ->
                    coordinator!!.query(revision, raw, controller.beforeCursor, controller.afterCursor)
                }, {})
                coordinator = EngineCoordinator(context) { revision, candidates, _ ->
                    controller.acceptResults(revision, candidates)
                    if (controller.raw == input && controller.candidates.any { it.text == "你好" }) {
                        if (phase == 1) initialResult.countDown() else retypedResult.countDown()
                    }
                }
                coordinator!!.start()
                input.forEach { controller.input(it.toString()) }
                assertEquals(input, controller.raw)
                assertEquals(input, editor.text.toString())
            }
            assertTrue("No candidate for incrementally composed Zhuyin", initialResult.await(90, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val oldRevision = controller.revision
                val stale = controller.candidates.first { it.text == "你好" }
                phase = 2
                assertTrue(controller.backspace())
                assertEquals("ㄋㄧˇㄏㄠ", controller.raw)
                assertFalse("A candidate from before deleting the tone must be rejected", controller.select(stale, oldRevision))
                controller.input("ˇ")
            }
            assertTrue("No candidate after deleting and restoring tone", retypedResult.await(90, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertTrue(controller.select(controller.candidates.first { it.text == "你好" }))
                assertEquals("你好", editor.text.toString())
                assertEquals("", controller.raw)
            }
        } finally {
            instrumentation.runOnMainSync {
                coordinator?.close()
                preferences.edit().apply {
                    before.forEach { (feature, original) ->
                        if (original.first) putBoolean(feature.key, original.second) else remove(feature.key)
                    }
                }.commit()
            }
        }
    }
}
