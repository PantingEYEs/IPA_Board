package com.example.ipa_board

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ipa.IpaDictionaryLookup
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Offline real binary dictionary; no engine/native loading, user data or network queries. */
class IpaReverseLookupTest {
    @Test fun bundledDictionaryPreservesReadingsAcrossRegionsWithoutNativeEngine() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "reverse-contract-${UUID.randomUUID()}.ipad")
        try {
            context.assets.open("engines/ipa/bundled/lexicon.ipad").use { input -> file.outputStream().use { input.copyTo(it) } }
            IpaDictionaryLookup(file).use { reader ->
                for ((text, regions) in listOf("hello" to setOf("en-US", "en-GB"), "学校" to setOf("zh-CN", "ja-JP"), "學校" to setOf("zh-TW", "ja-JP"))) {
                    val start = SystemClock.elapsedRealtime()
                    val result = reader.lookup(text)
                    val duration = SystemClock.elapsedRealtime() - start
                    assertTrue("Stored regions for synthetic $text", result.readings.map { it.region }.containsAll(regions))
                    assertTrue(result.readings.size > 1)
                    if (text == "學校") assertTrue(result.readings.any { it.region == "zh-TW" && it.derived })
                    if (text == "学校") assertEquals(setOf("ɕy̯ɛ˧˥ ɕi̯ɑʊ̯˥˩", "ɕɥœ˧˥ ɕjɑʊ˥˩", "ɡakːoɯ", "ɡa̠k̚ko̞ː"),
                        result.readings.map { it.ipa }.toSet())
                    assertTrue(result.readings.all { it.ipa.isNotBlank() })
                    assertFalse(result.truncated)
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("ipa_reverse_timing", "sample=$text elapsed_ms=$duration readings=${result.readings.size}")
                    })
                }
                assertTrue(reader.lookup("__IPA_BOARD_MISSING_TEST_ENTRY__").readings.isEmpty())
            }
        } finally { file.delete() }
    }
}
