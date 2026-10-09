package com.example.ipa_board.ipa

import com.example.ipa_board.ime.CandidateKind
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class IpaGraphRuntimeTest {
    @Test fun publishedFieldsAndConfigurationAreMappedWithoutLexicalMetadataInOutput() {
        val api = IpaGraphApi(FakeGraph::class.java)
        val graph = api.open("test-owned.ipad") as FakeGraph
        api.configure(graph)
        assertEquals("test-owned.ipad", graph.path)
        assertEquals(listOf(true, true, true, true, true), graph.regions)
        assertEquals(listOf(30, false, 60, 2048), graph.options)
        assertEquals("[a-zɑ]+", api.segmentPattern(graph))
        assertTrue(api.acceptsInput(graph, "ɑ"))
        graph.result = FakeGraph.QueryResult("ok", listOf(
            FakeGraph.Candidate("汉字😀", "简", 0, 0, 150, 0.4f),
            FakeGraph.Candidate("words", "EN", 1, 2, 99, 0f)))
        val candidates = api.query(graph, "ɑ", "before", "after")
        assertEquals(listOf("汉字😀", "words"), candidates.map { it.text })
        assertEquals(listOf(CandidateKind.CONVERSION, CandidateKind.COMPLETION), candidates.map { it.kind })
        assertEquals(listOf("ɑ", "before", "after"), graph.request)
        assertEquals(150, candidates.first().nativeScore)
        assertEquals(0.4f, candidates.first().contextAffinity)
        api.close(graph)
        assertTrue(graph.closed)
    }

    @Test fun normalEmptyStatusesAreNotFailuresButUnknownOrMalformedResponsesAreRejected() {
        val api = IpaGraphApi(FakeGraph::class.java)
        val graph = api.open("test-owned.ipad") as FakeGraph
        for (status in listOf("ok", "timeout", "unsupported_input")) {
            graph.result = FakeGraph.QueryResult(status, emptyList())
            assertTrue(api.query(graph, "ɑ", "", "").isEmpty())
        }
        graph.result = FakeGraph.QueryResult("timeout", listOf(FakeGraph.Candidate("word")))
        rejected { api.query(graph, "ɑ", "", "") }
        graph.result = FakeGraph.QueryResult("untrusted status", emptyList())
        rejected { api.query(graph, "ɑ", "", "") }
        graph.result = FakeGraph.QueryResult("error", emptyList())
        try { api.query(graph, "ɑ", "", ""); fail("error status must fail") }
        catch (error: IllegalStateException) { assertEquals("IPA query failed", error.message) }
        graph.result = FakeGraph.QueryResult("ok", List(31) { FakeGraph.Candidate("word") })
        rejected { api.query(graph, "ɑ", "", "") }
    }

    @Test fun allSupportedLabelsAndUnicodeRemainIntactAndInvalidCommitTextIsRejected() {
        for (language in listOf("简", "繁", "日", "EN")) {
            val candidate = IpaCandidateContract.convert("ʃa\u0301😀", language, 0, 0, 10, 1f)
            assertEquals("ʃa\u0301😀", candidate.text)
            assertEquals(language, candidate.language)
        }
        assertEquals("EN/简", IpaCandidateContract.convert("same", "EN/简", 0, 0, 0, 0f).language)
        assertEquals("EN/简", IpaCandidateContract.convert("same", "EN/简/EN", 0, 0, 0, 0f).language)
        for (language in listOf("", "EN/", "/EN", "EN//简", "EN/unknown"))
            rejected { IpaCandidateContract.convert("word", language, 0, 0, 0, 0f) }
        for (text in listOf("", " ", "a".repeat(1001), "\uD800", "\uDC00", "a\uD800b"))
            rejected { IpaCandidateContract.convert(text, "EN", 0, 0, 0, 0f) }
        for (kind in listOf(-1, 1, 3, 4, 99))
            rejected { IpaCandidateContract.convert("word", "EN", 0, kind, 0, 0f) }
        rejected { IpaCandidateContract.convert("word", "unknown", 0, 0, 0, 0f) }
        rejected { IpaCandidateContract.convert("word", "EN", -1, 0, 0, 0f) }
        for (affinity in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f))
            rejected { IpaCandidateContract.convert("word", "EN", 0, 0, 0, affinity) }
    }

    @Test fun reflectiveCallsExposeTheOriginalFixedFailureCategoryInsteadOfInvocationWrappers() {
        val api = IpaGraphApi(FakeGraph::class.java)
        try { api.open("fail"); fail("expected dictionary failure") }
        catch (error: IOException) { assertEquals("test failure", error.message) }
    }

    private fun rejected(operation: () -> Unit) {
        try { operation(); fail("Invalid response must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    class FakeGraph(val path: String) {
        init { if (path == "fail") throw IOException("test failure") }
        var closed = false
        var regions = emptyList<Boolean>()
        var options = emptyList<Any>()
        var request = emptyList<String>()
        var result = QueryResult("ok", emptyList())
        class Policy(val enUS: Boolean, val enGB: Boolean, val zhCN: Boolean, val zhTW: Boolean, val jaJP: Boolean)
        class Options(val limit: Int, val prefix: Boolean, val deadline: Int, val budget: Int)
        class Candidate(@JvmField val text: String, @JvmField val language: String = "EN", @JvmField val rank: Int = 0,
            @JvmField val kind: Int = 0, @JvmField val nativeScore: Int = 0, @JvmField val contextAffinity: Float = 0f)
        class QueryResult(@JvmField val status: String, @JvmField val candidates: List<Candidate>)
        fun configure(policy: Policy, settings: Options) {
            regions = listOf(policy.enUS, policy.enGB, policy.zhCN, policy.zhTW, policy.jaJP)
            options = listOf(settings.limit, settings.prefix, settings.deadline, settings.budget)
        }
        fun acceptsInput(raw: String) = raw == "ɑ"
        fun getSegmentPattern() = "[a-zɑ]+"
        fun queryDetailed(raw: String, before: String, after: String): QueryResult { request = listOf(raw, before, after); return result }
        fun close() { closed = true }
    }
}
