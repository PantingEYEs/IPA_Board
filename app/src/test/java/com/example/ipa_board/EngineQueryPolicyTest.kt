package com.example.ipa_board

import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test

class EngineQueryPolicyTest {
    @Test fun defaultsPreserveNativeFeaturesAndKeepSemanticOptIn() {
        val policy = EngineQueryPolicy()
        assertTrue(listOf("ZH", "JA", "EN").all { policy.needsRuntime(it) })
        assertFalse(policy.enabled(EngineFeature.SEMANTIC))
    }
    @Test fun conversionAndPredictionAreIndependentForEachLanguage() {
        for ((language, conversion, prediction) in listOf(
            Triple("ZH", EngineFeature.CHINESE_CONVERSION, EngineFeature.CHINESE_PREDICTION),
            Triple("JA", EngineFeature.JAPANESE_CONVERSION, EngineFeature.JAPANESE_PREDICTION))) {
            val policy = EngineQueryPolicy(mapOf(conversion to false))
            assertFalse(policy.wantsQuery(language, false))
            assertTrue(policy.wantsQuery(language, true))
            val off = EngineQueryPolicy(mapOf(conversion to false, prediction to false))
            assertFalse(off.needsRuntime(language))
        }
    }
    @Test fun englishCompletionAndCorrectionHaveSeparateFilters() {
        val correctionOnly = EngineQueryPolicy(mapOf(EngineFeature.ENGLISH_COMPLETION to false))
        assertTrue(correctionOnly.wantsQuery("EN", false))
        assertFalse(correctionOnly.permitsEnglish(CandidateKind.COMPLETION))
        assertFalse(correctionOnly.permitsEnglish(CandidateKind.EXACT))
        assertTrue(correctionOnly.permitsEnglish(CandidateKind.CORRECTION))
        val off = EngineQueryPolicy(mapOf(EngineFeature.ENGLISH_COMPLETION to false,
            EngineFeature.ENGLISH_CORRECTION to false, EngineFeature.ENGLISH_PREDICTION to false))
        assertFalse(off.needsRuntime("EN"))
    }
    @Test fun disablingScoringPreservesSourceOrderAndMergesDuplicateLanguages() {
        val candidates = listOf(Candidate("same", "简", 10), Candidate("first", "EN", 0),
            Candidate("same", "繁", 1), Candidate("", "EN"))
        val result = CandidateRanker.unranked(candidates)
        assertEquals(listOf("same", "first"), result.map { it.text })
        assertEquals("简/繁", result.first().language)
    }
    @Test fun everyAdjustableCatalogEntryExplainsItsFallback() {
        val entries = EngineCatalog.categories("test").flatMap { it.engines }
        assertEquals(EngineFeature.entries.toSet(), entries.mapNotNull { it.feature }.toSet())
        assertTrue(entries.filter { it.feature != null }.all { it.offBehavior.isNotBlank() })
        assertFalse(EngineCatalog.categories("test").any { it.id == "pinyin_correction" })
        assertTrue(entries.single { it.feature == EngineFeature.CHINESE_CONVERSION }.detail.contains("schema-defined"))
    }
}
