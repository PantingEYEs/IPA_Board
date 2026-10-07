package com.example.ipa_board.ime

/** Integrated input functions and explicit plans, independent of native loading state. */
enum class EngineStatus { INTEGRATED, REPLACEMENT_PLANNED, PLANNED }
data class EngineInfo(
    val name: String,
    val version: String,
    val status: EngineStatus = EngineStatus.INTEGRATED,
    val detail: String = "",
    val feature: EngineFeature? = null,
    val offBehavior: String = "",
    val fixedReason: String = "",
    val versionSources: List<EngineVersionSource> = emptyList()
)
data class EngineCategory(val id: String, val title: String, val engines: List<EngineInfo> = emptyList())

/**
 * Inventory and roadmap for the query/conversion pipeline; this does not load or configure engines.
 * Native source revisions are pinned in docs/engine-integration.md and tools/engine-assets.lock.json.
 * Package versions are explicitly labelled as sources, not claimed as native library versions.
 */
object EngineCatalog {
    private const val RIME_VERSION = "librime 1.17.0 · source: Trime 3.3.12 · rev 33e781402501"
    private const val MOZC_VERSION = "Source: Libre Japanese Input 0.1.2 · Mozc rev ddd9730b0683"
    private const val ENGLISH_VERSION = "Source: HeliBoard 4.1 · rev 9f5bb635c2e8 · en_US dictionary 54"

    fun categories(appVersion: String): List<EngineCategory> = listOf(
        EngineCategory("syllable", "Syllable Engines", listOf(
            EngineInfo("Rime · Pinyin & Zhuyin (Chinese)", "$RIME_VERSION · Luna Pinyin 0.26 · Bopomofo 3.1", detail = "Chinese conversion from Pinyin or Zhuyin symbols, with five-tone filtering and optional omitted tones. Pinyin includes schema-defined spelling and key correction.", feature = EngineFeature.CHINESE_CONVERSION, offBehavior = "Off: omit Pinyin and Zhuyin conversion candidates; literal input and Chinese prediction stay available.", versionSources = listOf(EngineVersionSource.RIME, EngineVersionSource.TRIME)),
            EngineInfo("Mozc (Japanese)", MOZC_VERSION, feature = EngineFeature.JAPANESE_CONVERSION, offBehavior = "Off: Japanese conversion candidates are omitted; literal input and prediction stay available.", versionSources = listOf(EngineVersionSource.LIBRE_JAPANESE))
        )),
        EngineCategory("completion", "Completion Engines", listOf(
            EngineInfo("LatinIME · English suggestions", ENGLISH_VERSION,
                detail = "Offline word completion and contextual candidates; read-only dictionary.", feature = EngineFeature.ENGLISH_COMPLETION, offBehavior = "Off: English exact/completion candidates are omitted; correction and prediction have separate switches.", versionSources = listOf(EngineVersionSource.HELIBOARD))
        )),
        EngineCategory("script_conversion", "Script Conversion Engines", listOf(
            EngineInfo("OpenCC (Traditional → Simplified Chinese)", "Source: Trime 3.3.12", feature = EngineFeature.SCRIPT_CONVERSION, offBehavior = "Off: keep traditional Chinese candidates only. Context normalization remains part of Chinese phrase lookup.", versionSources = listOf(EngineVersionSource.TRIME))
        )),
        EngineCategory("ranking", "Candidate Ranking Engines", listOf(
            EngineInfo("IPA Board · CandidateRanker", "Built-in · IPA Board $appVersion",
                detail = "Input-compatible multilingual ranking with dictionary associations; no surrounding-language bonus.", feature = EngineFeature.RANKING, offBehavior = "Off: preserve engine source order and remove duplicates. Semantic ranking is controlled separately."),
            EngineInfo("Multilingual E5 · Semantic context ranking", "e5-small · int8 · rev 614241f622f5 · ONNX Runtime 1.23.2",
                detail = "Experimental, opt-in offline reranking with both sides of the cursor; preserves language diversity.", feature = EngineFeature.SEMANTIC, offBehavior = "Off: use ordinary candidates without semantic inference.", versionSources = listOf(EngineVersionSource.E5, EngineVersionSource.ONNX_RUNTIME))
        )),
        EngineCategory("calculation", "Calculation Engines", listOf(
            EngineInfo("IPA Board · CalculatorEvaluator", "Built-in · IPA Board $appVersion", feature = EngineFeature.CALCULATOR, offBehavior = "Off: omit calculation results; expressions can still be entered literally.")
        )),
        EngineCategory("otp_detection", "SMS Code Detection Engines", listOf(
            EngineInfo("IPA Board · SmsOtpParser", "Built-in · IPA Board $appVersion", feature = EngineFeature.SMS_OTP, offBehavior = "Off: skip local SMS code parsing/copying. Clipboard and Android autofill are unaffected.")
        )),
        EngineCategory("prediction", "Next-word Prediction Engines", listOf(
            EngineInfo("LatinIME · Contextual next-word prediction", ENGLISH_VERSION,
                detail = "English dictionary bigrams, with sentence-start/common-word fallback for mixed or non-English context.", feature = EngineFeature.ENGLISH_PREDICTION, offBehavior = "Off: no English next-word candidates.", versionSources = listOf(EngineVersionSource.HELIBOARD)),
            EngineInfo("Rime corpus · Chinese phrase continuation", "$RIME_VERSION · essay corpus",
                detail = "Read-only phrase suffixes and frequent-word fallback; not an arbitrary next-word language model.", feature = EngineFeature.CHINESE_PREDICTION, offBehavior = "Off: no Chinese phrase continuations; Chinese conversion is controlled separately.", versionSources = listOf(EngineVersionSource.TRIME)),
            EngineInfo("Mozc · Japanese next-word suggestions", MOZC_VERSION,
                detail = "Native zero-query suggestions from transient Japanese context; no saved history.", feature = EngineFeature.JAPANESE_PREDICTION, offBehavior = "Off: no Japanese next-word candidates; Japanese conversion is controlled separately.", versionSources = listOf(EngineVersionSource.LIBRE_JAPANESE))
        )),
        EngineCategory("english_correction", "English Spelling Correction Engines", listOf(
            EngineInfo("LatinIME · English spelling correction", ENGLISH_VERSION,
                detail = "Edit-distance correction candidates alongside Chinese and Japanese candidates.", feature = EngineFeature.ENGLISH_CORRECTION, offBehavior = "Off: English spelling corrections are omitted; completion and prediction stay independent.", versionSources = listOf(EngineVersionSource.HELIBOARD))
        )),
        EngineCategory("personalization", "Personalization Engines", listOf(
            planned("LatinIME · Personal and history dictionaries", "Later phase: local vocabulary and history learning; currently disabled.")
        )),
        EngineCategory("multilingual_segmentation", "Multilingual Segmentation Engines", listOf(
            EngineInfo("IPA Board · Mixed composition segmentation", "Built-in · IPA Board $appVersion",
                detail = "Converts letter runs independently while preserving numbers, punctuation and existing scripts.", feature = EngineFeature.SEGMENTATION, offBehavior = "Off: query complete letter/apostrophe input only; mixed symbols and numbers remain literal.")
        )),
        EngineCategory("touch_correction", "Touch Correction Engines")
    )

    private fun planned(name: String, detail: String) =
        EngineInfo(name, "Not selected", EngineStatus.PLANNED, detail)
}
