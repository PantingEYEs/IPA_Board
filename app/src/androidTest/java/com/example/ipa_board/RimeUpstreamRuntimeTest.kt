/* Copyright (c) 2026 PantingEYEs. All rights reserved.
 * Project-authored verification; no open-source license has been granted.
 */
package com.example.ipa_board

import android.os.Build
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.Candidate
import com.example.ipa_board.ime.CandidateConsumption
import com.example.ipa_board.ime.MixedCompositionCandidates
import com.example.ipa_board.ime.PinyinScheme
import com.example.ipa_board.ime.QueryEngine
import com.example.ipa_board.ime.RimeBuildIdentity
import com.example.ipa_board.ime.RimeBundle
import com.example.ipa_board.ime.RimeCandidateBatch
import com.example.ipa_board.ime.RimeNativeCandidate
import com.example.ipa_board.ime.RimePinyinInput
import com.example.ipa_board.ime.ZhuyinInput
import org.ipaboard.rime.RimeNative
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses only synthetic input and fresh files; never opens an installed user's Rime directory. */
class RimeUpstreamRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val schemaIds = listOf("luna_pinyin", "terra_pinyin", "bopomofo")
    private fun utf8(value: String) = value.toByteArray(Charsets.UTF_8)

    private fun evidence(value: JSONObject) {
        val payload = Base64.encodeToString(utf8(value.toString()), Base64.NO_WRAP)
        instrumentation.sendStatus(2, Bundle().apply {
            putString("chinese_rime_evidence_b64", payload)
        })
    }

    private inner class OwnedResources(
        excluded: Set<String>, registered: List<String>
    ) {
        private val cache = instrumentation.targetContext.cacheDir.apply {
            check(mkdirs() || isDirectory)
        }
        val root: File = Files.createTempDirectory(cache.toPath(), "rime-source-").toFile()
        val shared = File(root, "shared")
        val user = File(root, "user")
        var handle: Long = 0L
            private set

        init {
            try {
                check(shared.mkdirs() && user.mkdirs())
                fun copy(relative: String) {
                    if (relative in excluded) return
                    val asset = if (relative.isEmpty()) "engines/rime" else "engines/rime/$relative"
                    val children = instrumentation.targetContext.assets.list(asset).orEmpty()
                    val destination = if (relative.isEmpty()) shared else File(shared, relative)
                    if (children.isNotEmpty()) {
                        check(destination.mkdirs() || destination.isDirectory)
                        children.forEach { child -> copy(if (relative.isEmpty()) child else "$relative/$child") }
                    } else {
                        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
                        instrumentation.targetContext.assets.open(asset).use { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                copy("")
                // Rime deploys registered schemas. Excluding Luna must also remove it from this list.
                val registration = "patch:\n  schema_list:\n" +
                    registered.joinToString("") { "    - schema: $it\n" }
                File(shared, "default.custom.yaml").writeText(registration, Charsets.UTF_8)
            } catch (failure: Throwable) {
                removeOwnedFiles()
                throw failure
            }
        }

        fun start(t2s: File = File(shared, "opencc/t2s.json")) {
            check(handle == 0L)
            handle = RimeNative.open(utf8(shared.absolutePath), utf8(user.absolutePath),
                utf8(t2s.absolutePath), utf8(File(shared, "opencc/s2t.json").absolutePath))
            assertTrue("Open must return a positive opaque handle", handle > 0)
        }

        fun stop() {
            if (handle != 0L) {
                val closing = handle
                handle = 0L
                RimeNative.close(closing)
            }
        }

        fun close() {
            try { stop() } finally { removeOwnedFiles() }
        }

        private fun removeOwnedFiles() {
            check(root.canonicalFile.parentFile == cache.canonicalFile && root.name.startsWith("rime-source-"))
            check(!root.exists() || root.deleteRecursively()) { "Unable to remove synthetic Rime fixture" }
        }
    }

    private fun withResources(
        excluded: Set<String> = emptySet(),
        registered: List<String> = schemaIds,
        start: Boolean = true,
        test: (OwnedResources) -> Unit
    ) {
        val fixture = OwnedResources(excluded, registered)
        try {
            if (start) fixture.start()
            test(fixture)
        } finally { fixture.close() }
    }

    private data class Result(val encoded: String, val status: Int, val rows: List<RimeNativeCandidate>) {
        fun fullTexts(): Set<String> = rows.filter {
            it.startByte == it.prefixEndByte && it.endByte + it.committedInputBytes == encoded.length
        }.map { it.text }.toSet()
    }

    private fun query(fixture: OwnedResources, schema: String, encoded: String, limit: Int = 40): Result {
        val packet = RimeNative.query(fixture.handle, utf8(schema), utf8(encoded), limit)
        val header = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue("Packet must contain its header", packet.size >= 32)
        assertEquals(0x52494d45, header.getInt(0))
        assertEquals(3, header.getInt(4))
        val status = header.getInt(8)
        val rows = RimeCandidateBatch.decode(packet, utf8(encoded).size)
        assertTrue(rows.size <= limit)
        rows.forEachIndexed { index, row ->
            assertEquals(index, row.rank)
            assertTrue(row.startByte in 0..row.endByte)
            assertTrue(row.endByte + row.committedInputBytes <= encoded.length)
            assertTrue(row.prefixEndByte in 0..encoded.length)
        }
        evidence(JSONObject().put("kind", "query").put("schema", schema)
            .put("input", encoded).put("status", status).put("count", rows.size)
            .put("limit", limit).put("packet_bytes", packet.size)
            .put("sample_limit", 8).put("candidates", JSONArray().apply {
                rows.take(8).forEach { row -> put(JSONObject().put("text", row.text)
                    .put("rank", row.rank).put("start", row.startByte).put("end", row.endByte)
                    .put("prefix_end", row.prefixEndByte).put("committed", row.committedInputBytes)) }
            }))
        return Result(encoded, status, rows)
    }

    private fun pinyin(fixture: OwnedResources, scheme: PinyinScheme, raw: String, alternatives: Boolean = false): Result =
        query(fixture, scheme.schemaId, requireNotNull(RimePinyinInput(scheme, alternatives).encode(raw)))

    private fun zhuyin(fixture: OwnedResources, raw: String): Result =
        query(fixture, "bopomofo", requireNotNull(ZhuyinInput.encode(raw)))

    private fun expectArgument(block: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { block() }
        assertEquals("IPA_RIME_INVALID_ARGUMENT", error.message)
    }

    private fun expectState(code: String, block: () -> Unit) {
        val error = assertThrows(IllegalStateException::class.java) { block() }
        assertEquals(code, error.message)
    }

    @Test fun bundledIdentityAndRuntimeMatchRecordedBuildOnTheActualDevice() = withResources { fixture ->
        val identity = RimeNative.identity().toString(Charsets.UTF_8)
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        evidence(JSONObject().put("kind", "environment").put("identity", identity)
            .put("abi_version", RimeNative.abiVersion()).put("packet_version", RimeNative.PACKET_VERSION)
            .put("page_size", pageSize).put("sdk", Build.VERSION.SDK_INT)
            .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList())))
        assertEquals(RimeBundle.ABI_VERSION, RimeNative.abiVersion())
        assertEquals(RimeBundle.PACKET_VERSION, RimeNative.PACKET_VERSION)
        assertTrue(android.os.Process.is64Bit())
        assertTrue("Record actual page size; do not infer 16 KiB execution from ELF alignment",
            pageSize == 4096L || pageSize == 16384L)
        RimeBuildIdentity.validate(RimeNative.abiVersion(), RimeNative.identity())
        assertTrue("Native runtime must actually execute", "你好" in pinyin(fixture, PinyinScheme.LUNA, "nihao").fullTexts())
    }

    @Test fun lunaKeepsFourToneHomophonesAndUntonedSpellings() = withResources { fixture ->
        // Luna is intentionally tone-agnostic; its untoned spelling must retain all four readings.
        assertTrue(pinyin(fixture, PinyinScheme.LUNA, "ma").fullTexts().containsAll(listOf("媽", "麻", "馬", "罵")))
        for (raw in listOf("nihao", "ni'hao", "NIHAO"))
            assertTrue("Luna greeting for synthetic spelling", "你好" in pinyin(fixture, PinyinScheme.LUNA, raw).fullTexts())
    }

    @Test fun terraRespectsFourTonesAndAcceptsOmittedTonesAndAliases() = withResources { fixture ->
        val tones = linkedMapOf("ma-" to "媽", "ma/" to "麻", "ma<" to "馬", "ma\\" to "罵")
        tones.forEach { (raw, expected) ->
            val texts = pinyin(fixture, PinyinScheme.TERRA, raw).fullTexts()
            assertTrue("Terra compatible tone", expected in texts)
            assertTrue("Terra must exclude incompatible single-tone readings", (tones.values - expected).none { it in texts })
        }
        for (raw in listOf("nihao", "ni'hao", "ni<hao<", "ni<'hao<", "NI<HAO<", "ni<hao"))
            assertTrue("Terra greeting", "你好" in pinyin(fixture, PinyinScheme.TERRA, raw).fullTexts())
        for ((raw, expected) in mapOf("ma;" to "媽", "ma," to "馬", "ma." to "罵"))
            assertTrue(expected in pinyin(fixture, PinyinScheme.TERRA, raw, alternatives = true).fullTexts())
    }

    @Test fun bopomofoHandlesTonesNeutralOrderAndRejectsLeadingTone() = withResources { fixture ->
        val tones = linkedMapOf("ㄇㄚˉ" to "媽", "ㄇㄚˊ" to "麻", "ㄇㄚˇ" to "馬", "ㄇㄚˋ" to "罵")
        tones.forEach { (raw, expected) -> assertTrue(expected in zhuyin(fixture, raw).fullTexts()) }
        assertTrue("你好" in zhuyin(fixture, "ㄋㄧˇㄏㄠˇ").fullTexts())
        assertTrue("你好" in zhuyin(fixture, "ㄋㄧㄏㄠ").fullTexts())
        val leadingNeutral = zhuyin(fixture, "˙ㄇㄚ")
        val trailingNeutral = zhuyin(fixture, "ㄇㄚ˙")
        assertTrue(leadingNeutral.rows.isNotEmpty())
        assertEquals(leadingNeutral.rows, trailingNeutral.rows)
        assertNull(ZhuyinInput.encode("ˊㄋㄧ"))
        val bypassedEncoding = query(fixture, "bopomofo", "6" + requireNotNull(ZhuyinInput.encode("ㄋㄧ")))
        // The Unicode encoder rejects this input. If deliberately bypassed, this official
        // schema retains the encoded '6' as a literal prefix instead of silently dropping it.
        assertEquals(0, bypassedEncoding.status)
        assertTrue(bypassedEncoding.rows.isNotEmpty())
        assertTrue(bypassedEncoding.rows.all {
            it.text.startsWith("6") && it.startByte == 1 && it.prefixEndByte == 1 &&
                it.endByte == 3 && it.committedInputBytes == 0
        })
    }

    @Test fun openccPreservesUnicodeAndPunctuationInBothDirections() = withResources { fixture ->
        val traditional = "漢語，臺灣！😀 𠀀 IPA á / 123"
        val simplified = "汉语，台湾！😀 𠀀 IPA á / 123"
        assertEquals(simplified, RimeNative.convert(fixture.handle, utf8(traditional), RimeNative.TO_SIMPLIFIED).toString(Charsets.UTF_8))
        assertEquals(traditional, RimeNative.convert(fixture.handle, utf8(simplified), RimeNative.TO_TRADITIONAL).toString(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(), RimeNative.convert(fixture.handle, byteArrayOf(), RimeNative.TO_SIMPLIFIED))
    }

    @Test fun partialRangesPreserveTheExactSuffixAndExistingMixedTextBuilder() = withResources { fixture ->
        val raw = "ni<hao<shi\\jie\\"
        val response = pinyin(fixture, PinyinScheme.TERRA, raw)
        val partial = requireNotNull(response.rows.firstOrNull {
            it.text == "你好" && it.startByte == it.prefixEndByte && it.endByte + it.committedInputBytes < raw.length
        }) { "Expected a real partial greeting" }
        val consumed = partial.endByte + partial.committedInputBytes
        assertEquals("ni<hao<".length, consumed)
        assertEquals("shi\\jie\\", raw.drop(consumed))
        assertEquals(response.encoded.take(consumed), RimePinyinInput(PinyinScheme.TERRA).encode(raw.take(consumed)))
        assertTrue(response.fullTexts().isNotEmpty())

        val spelling = RimePinyinInput(PinyinScheme.TERRA)
        val adapter = object : QueryEngine {
            override val segmentPattern: Regex get() = spelling.segmentPattern
            override fun acceptsInput(raw: String) = spelling.accepts(raw)
            override fun query(raw: String): List<Candidate> {
                val encoded = spelling.encode(raw) ?: return emptyList()
                return this@RimeUpstreamRuntimeTest.query(fixture, "terra_pinyin", encoded).rows
                    .filter { it.startByte == it.prefixEndByte }.mapNotNull { row ->
                        val end = (1..raw.length).lastOrNull {
                            spelling.encode(raw.take(it)) == encoded.take(row.endByte + row.committedInputBytes)
                        } ?: return@mapNotNull null
                        Candidate(row.text, "繁", row.rank, consumption = if (end == raw.length) null else
                            CandidateConsumption(raw, end, "Rime", "terra_pinyin:${row.rank}"))
                    }
            }
        }
        val mixed = MixedCompositionCandidates.query(adapter, "前😀ni<hao<123!", "", "", false)
        assertTrue(mixed.any { it.text == "前😀你好123!" })
        assertTrue(mixed.all { it.text.startsWith("前😀") && it.text.endsWith("123!") })
    }

    @Test fun oneLeadingQuoteIsSharedAcrossEachBatchWithoutForcingItsDirection() = withResources { fixture ->
        repeat(2) {
            val response = pinyin(fixture, PinyinScheme.LUNA, "'nihao")
            val texts = response.fullTexts()
            assertTrue(texts.isNotEmpty())
            assertTrue(texts.all { it.first() in "‘’" })
            assertEquals(1, texts.map { it.first() }.distinct().size)
            assertTrue(texts.any { it.drop(1) == "你好" })
            assertTrue(response.rows.all { it.committedInputBytes + it.prefixEndByte == 1 })
        }
    }

    @Test fun boundedBatchesAndRepeatedQueriesLeaveNoPriorComposition() = withResources { fixture ->
        val baseline = query(fixture, "luna_pinyin", "nihao").rows
        assertTrue(baseline.isNotEmpty())
        assertEquals(1, query(fixture, "luna_pinyin", "nihao", 1).rows.size)
        query(fixture, "luna_pinyin", "nihaozzz")
        pinyin(fixture, PinyinScheme.TERRA, "ma<")
        zhuyin(fixture, "ㄋㄧˇㄏㄠˇ")
        repeat(3) { assertEquals(baseline, query(fixture, "luna_pinyin", "nihao").rows) }
        val unknown = query(fixture, "luna_pinyin", "?")
        assertTrue("Unknown punctuation must never turn into the previous greeting", unknown.rows.none { it.text.contains("你好") })
    }

    @Test fun malformedInputLimitsAndUnsupportedArgumentsFailWithoutPoisoningOwner() = withResources { fixture ->
        val schema = utf8("luna_pinyin")
        for (bad in listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), byteArrayOf(0), utf8("に"),
            utf8("a\n"), byteArrayOf(), ByteArray(257) { 'a'.code.toByte() })) {
            expectArgument { RimeNative.query(fixture.handle, schema, bad, 40) }
        }
        for (limit in listOf(-1, 0, 41))
            expectArgument { RimeNative.query(fixture.handle, schema, utf8("nihao"), limit) }
        expectArgument { RimeNative.query(fixture.handle, utf8("missing_schema"), utf8("nihao"), 40) }
        expectArgument { RimeNative.convert(fixture.handle, byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), 0) }
        expectArgument { RimeNative.convert(fixture.handle, byteArrayOf(0), 0) }
        expectArgument { RimeNative.convert(fixture.handle, utf8("test"), 2) }
        expectArgument { RimeNative.convert(fixture.handle, ByteArray(1024 * 1024 + 1) { 65 }, 0) }
        assertTrue("你好" in pinyin(fixture, PinyinScheme.LUNA, "nihao").fullTexts())
    }

    @Test fun opaqueHandlesRejectDoubleOpenAndRemainStaleAfterReopen() = withResources { fixture ->
        val old = fixture.handle
        expectState("IPA_RIME_INVALID_HANDLE") { RimeNative.query(0L, utf8("luna_pinyin"), utf8("nihao"), 40) }
        expectState("IPA_RIME_INVALID_HANDLE") { RimeNative.close(-1L) }
        expectState("IPA_RIME_ALREADY_OPEN") {
            RimeNative.open(utf8(fixture.shared.absolutePath), utf8(fixture.user.absolutePath),
                utf8(File(fixture.shared, "opencc/t2s.json").absolutePath), utf8(File(fixture.shared, "opencc/s2t.json").absolutePath))
        }
        fixture.stop()
        expectState("IPA_RIME_INVALID_HANDLE") { RimeNative.close(old) }
        fixture.start()
        assertTrue(fixture.handle != old)
        expectState("IPA_RIME_INVALID_HANDLE") { RimeNative.convert(old, utf8("漢語"), 0) }
        assertTrue("你好" in pinyin(fixture, PinyinScheme.TERRA, "ni<hao<").fullTexts())
    }

    @Test fun missingLunaResourcesDoNotPreventTerraOrBopomofo() = withResources(
        excluded = setOf("luna_pinyin.schema.yaml", "luna_pinyin.custom.yaml", "luna_pinyin.dict.yaml"),
        registered = listOf("terra_pinyin", "bopomofo")
    ) { fixture ->
        assertFalse(fixture.shared.walkTopDown().any { it.name.startsWith("luna_") })
        expectState("IPA_RIME_SCHEMA_UNAVAILABLE") { query(fixture, "luna_pinyin", "nihao") }
        assertTrue("你好" in pinyin(fixture, PinyinScheme.TERRA, "ni<hao<").fullTexts())
        assertTrue("你好" in zhuyin(fixture, "ㄋㄧˇㄏㄠˇ").fullTexts())
    }

    @Test fun converterOpenFailureReleasesGlobalOwnerForHealthyReopen() = withResources(start = false) { fixture ->
        expectState("IPA_RIME_CONVERSION_OPEN_FAILED") {
            fixture.start(File(fixture.shared, "opencc/synthetic-missing.json"))
        }
        assertEquals(0L, fixture.handle)
        fixture.start()
        assertTrue("你好" in pinyin(fixture, PinyinScheme.LUNA, "nihao").fullTexts())
        assertEquals("汉语", RimeNative.convert(fixture.handle, utf8("漢語"), 0).toString(Charsets.UTF_8))
    }

    @Test fun unavailableTerraSchemaDoesNotPoisonOtherSchemasSharingItsDictionary() = withResources(
        excluded = setOf("terra_pinyin.schema.yaml", "terra_pinyin.custom.yaml"),
        registered = listOf("luna_pinyin", "bopomofo")
    ) { fixture ->
        assertTrue(File(fixture.shared, "terra_pinyin.dict.yaml").isFile)
        expectState("IPA_RIME_SCHEMA_UNAVAILABLE") { query(fixture, "terra_pinyin", "ni<hao<") }
        assertTrue("你好" in pinyin(fixture, PinyinScheme.LUNA, "nihao").fullTexts())
        assertTrue("你好" in zhuyin(fixture, "ㄋㄧˇㄏㄠˇ").fullTexts())
        expectState("IPA_RIME_SCHEMA_UNAVAILABLE") { query(fixture, "terra_pinyin", "ma<") }
    }

    @Test fun schemaPoliciesPreventUserDictionaryCreationEvenAfterClose() = withResources { fixture ->
        repeat(3) {
            pinyin(fixture, PinyinScheme.LUNA, "nihao")
            query(fixture, "luna_pinyin", "nihao ") // Exercise a real native auto-commit path.
            pinyin(fixture, PinyinScheme.TERRA, "ni<hao<")
            query(fixture, "terra_pinyin", "ni<hao< ")
            zhuyin(fixture, "ㄋㄧˇㄏㄠˇ")
        }
        fixture.stop() // Also verify finalization did not flush learned data.
        val learningFiles = fixture.root.walkTopDown().count {
            it.name.contains("userdb", ignoreCase = true) || it.name.endsWith(".user.dict.yaml")
        }
        evidence(JSONObject().put("kind", "no_learning").put("user_dictionary_artifacts", learningFiles))
        assertEquals("Synthetic queries must not create a user dictionary", 0, learningFiles)
    }

    @Test fun concurrentQueriesAndConversionsShareOneOwnerWithoutCrossTalk() = withResources { fixture ->
        // Warm both sessions first so this exercises overlapping calls, not schema deployment.
        assertTrue("你好" in pinyin(fixture, PinyinScheme.LUNA, "nihao").fullTexts())
        assertTrue("馬" in pinyin(fixture, PinyinScheme.TERRA, "ma<").fullTexts())
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(3)
        try {
            val tasks = (0..2).map { worker ->
                workers.submit(Callable {
                    check(start.await(10, TimeUnit.SECONDS)) { "Concurrent fixture did not start" }
                    repeat(6) {
                        check(!Thread.currentThread().isInterrupted) { "Concurrent fixture was interrupted" }
                        when (worker) {
                            0 -> {
                                val texts = pinyin(fixture, PinyinScheme.LUNA, "nihao").fullTexts()
                                assertTrue("Luna query must retain its own input", "你好" in texts)
                                assertFalse("Terra input must not leak into Luna", "馬" in texts)
                            }
                            1 -> {
                                val texts = pinyin(fixture, PinyinScheme.TERRA, "ma<").fullTexts()
                                assertTrue("Terra query must retain its own input", "馬" in texts)
                                assertTrue("Tone filtering must survive concurrent calls", listOf("媽", "麻", "罵", "你好").none { it in texts })
                            }
                            2 -> {
                                assertEquals("汉语😀!", RimeNative.convert(fixture.handle,
                                    utf8("漢語😀!"), RimeNative.TO_SIMPLIFIED).toString(Charsets.UTF_8))
                                assertEquals("漢語😀!", RimeNative.convert(fixture.handle,
                                    utf8("汉语😀!"), RimeNative.TO_TRADITIONAL).toString(Charsets.UTF_8))
                            }
                        }
                    }
                })
            }
            start.countDown()
            tasks.forEach { task ->
                try { task.get(60, TimeUnit.SECONDS) }
                catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
            }
        } finally {
            start.countDown()
            workers.shutdownNow()
            // The owned runtime is closed by withResources only after every worker has joined.
            assertTrue("Concurrent fixture workers must stop before owner cleanup",
                workers.awaitTermination(60, TimeUnit.SECONDS))
        }
        assertTrue("你好" in pinyin(fixture, PinyinScheme.LUNA, "nihao").fullTexts())
        assertTrue("馬" in pinyin(fixture, PinyinScheme.TERRA, "ma<").fullTexts())
        evidence(JSONObject().put("kind", "concurrency").put("workers", 3)
            .put("iterations_per_worker", 6).put("native_calls", 24).put("all_workers_joined", true))
    }
}
