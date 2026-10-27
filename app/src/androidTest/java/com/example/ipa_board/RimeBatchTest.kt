package com.example.ipa_board

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** One runtime per instrumentation process; all input is synthetic, no highlighting/preview path. */
class RimeBatchTest {
    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        private val engine by lazy { RimeEngine(context) }
        private val batch by lazy { RimeBatchReader(context) }
        private val reference by lazy { ReplayReference(context) }
    }

    @Test fun batchMatchesLegacyForPinyinZhuyinAndPartialInputWithoutMutatingSelection() {
        var partialCandidates = 0
        val differences = mutableListOf<String>()
        val cases = listOf("yes", "shide", "soudesu", "nihao", "hanyu", "zhongguo", "nihaozhongguo",
            "nihaozzz", "xian", "xi'an", "ni'hao", "NIHAO", "a", "zzz", "nihao'", "'nihao", "ni''hao",
            "ㄋㄧˇㄏㄠˇ", "ㄏㄢˋㄩˇ", "ㄓㄨㄥㄨㄣˊ", "ㄋㄏ", "ㄋㄧㄏㄠ", "ㄋㄧˇ ㄏㄠˇ", "˙ㄇㄚ", "ㄇㄚ˙",
            "ˊㄋㄧ", "ˇ", "ㄋㄧˇ", "ㄋㄧˇ'ㄏㄠˇ")
        try {
            for (conversion in listOf(false, true)) {
                engine.configure(EngineQueryPolicy(mapOf(EngineFeature.SCRIPT_CONVERSION to conversion)))
                for (raw in cases) {
                    val old = engine.queryWithReader(raw) { id, input -> reference.readForRaw(raw, id, input) }
                    val actual = engine.queryWithReader(raw) { session, input ->
                        val before = reference.state(session)
                        val rows = batch.read(session, input)
                        assertEquals("Batch enumeration must preserve input/caret/highlight", before, reference.state(session))
                        partialCandidates += rows.count { it.startByte != it.prefixEndByte || it.endByte + it.committedInputBytes != input.toByteArray().size }
                        rows
                    }
                    if (raw == "'nihao") {
                        // Approved behavior: one real input produces one quote direction for the whole list.
                        assertTrue("Preserve the quote and words", actual.isNotEmpty())
                        assertTrue(actual.all { it.text.first() in "‘’" })
                        assertEquals(1, actual.map { it.text.first() }.distinct().size)
                        assertEquals(old.map { it.copy(text = it.text.drop(1)) }, actual.map { it.copy(text = it.text.drop(1)) })
                    } else if (old != actual) differences.add("$raw; conversion=$conversion: legacy=$old; batch=$actual")
                }
            }
            assertTrue("Legacy/new mismatches:\n${differences.joinToString("\n")}", differences.isEmpty())
            assertTrue("Exercise real partial candidates instead of only full matches", partialCandidates > 0)
        } finally { engine.configure(EngineQueryPolicy()) }
    }

    @Test fun invalidSessionAndChangedInputFailSafelyAndDoNotPoisonTheNextQuery() {
        assertThrows(IllegalStateException::class.java) { batch.read(0, "nihao") }
        val actual = engine.queryWithReader("nihao") { session, input ->
            assertTrue(batch.read(session, "unexpected").isEmpty())
            batch.read(session, input)
        }
        assertTrue(actual.any { it.text == "你好" })
        assertEquals(actual, engine.query("nihao"))
        assertTrue(engine.query("ˊㄋㄧ").isEmpty())
    }

    @Test fun compareLegacyAndBatchQueryDurationsOnTheSameRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val timings = mutableListOf<JSONObject>()
        try {
            engine.configure(EngineQueryPolicy())
            for (raw in listOf("yes", "shide", "soudesu")) {
                // Both paths get a warmup. Alternate ordering to reduce cache/order bias.
                engine.queryWithReader(raw, reference::read); engine.query(raw)
                repeat(20) { round ->
                    val results = mutableMapOf<String, List<Candidate>>()
                    val modes = if (round % 2 == 0) listOf("legacy", "batch") else listOf("batch", "legacy")
                    for (mode in modes) {
                        val start = android.os.SystemClock.elapsedRealtimeNanos()
                        val candidates = if (mode == "legacy") engine.queryWithReader(raw, reference::read) else engine.query(raw)
                        val duration = android.os.SystemClock.elapsedRealtimeNanos() - start
                        results[mode] = candidates
                        timings.add(JSONObject().apply {
                            put("schema", 1); put("case", raw); put("mode", mode); put("round", round)
                            put("duration_ns", duration); put("count", candidates.size)
                        })
                    }
                    assertEquals("Benchmark must compare equivalent results: $raw round=$round", results["legacy"], results["batch"])
                }
            }
        } finally {
            // Keep completed timing samples even if an equivalence assertion fails.
            timings.forEach { row -> instrumentation.sendStatus(2, Bundle().apply { putString("rime_batch_timing", row.toString()) }) }
        }
    }

    /** Exact pre-optimization algorithm retained only as a differential/benchmark reference. */
    private class ReplayReference(context: android.content.Context) {
        private val library = NativeLibrary.getInstance(File(context.applicationInfo.nativeLibraryDir, "librime_jni.so").absolutePath)
        private val api = library.getFunction("rime_get_api").invokePointer(emptyArray())
        private fun fn(name: String): Function = Function.getFunction(api.getPointer(8L + rimeApiFunctions.indexOf(name) * 8))
        private fun int(name: String, vararg args: Any?) = fn(name).invokeInt(args)
        private fun void(name: String, vararg args: Any?) = fn(name).invokeVoid(args)
        private fun input(id: Long) = fn("get_input").invokePointer(arrayOf(id))?.getString(0, "UTF-8").orEmpty()
        private fun commit(id: Long): String? = Memory(16).use { data ->
            data.clear(); data.setInt(0, 12)
            if (int("get_commit", id, data) == 0) null
            else try { data.getPointer(8)?.getString(0, "UTF-8") } finally { int("free_commit", data) }
        }
        private fun replay(id: Long, input: String) {
            void("clear_composition", id); commit(id)
            input.forEach { int("process_key", id, it.code, 0) }
        }
        fun readForRaw(raw: String, id: Long, expected: String): List<RimeNativeCandidate> {
            if (ZhuyinInput.containsSymbols(raw) && input(id) != expected) return emptyList()
            return read(id, expected)
        }
        fun read(id: Long, expected: String): List<RimeNativeCandidate> {
            val indices = mutableListOf<Int>()
            Memory(40).use { iterator ->
                iterator.clear()
                if (int("candidate_list_begin", id, iterator) != 0) {
                    try {
                        while (indices.size < 40 && int("candidate_list_next", iterator) != 0) indices.add(iterator.getInt(8))
                    } finally { void("candidate_list_end", iterator) }
                }
            }
            return indices.mapNotNull { index ->
                replay(id, expected)
                if (int("select_candidate", id, index.toLong()) == 0) return@mapNotNull null
                val text = commit(id) ?: return@mapNotNull null
                if (input(id).isNotEmpty()) null else RimeNativeCandidate(text, index, 0, expected.toByteArray().size)
            }
        }
        data class State(val input: String, val caret: Long, val page: Int, val highlight: Int)
        fun state(id: Long): State = Memory(88).use { data ->
            data.clear(); data.setInt(0, 84)
            check(int("get_context", id, data) != 0)
            try { State(input(id), fn("get_caret_pos").invokeLong(arrayOf(id)), data.getInt(36), data.getInt(44)) }
            finally { int("free_context", data) }
        }
    }
}
