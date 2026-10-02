package com.example.ipa_board.ime

/** Integrated input functions and explicit plans, independent of native loading state. */
enum class EngineStatus { INTEGRATED, REPLACEMENT_PLANNED, PLANNED }
data class EngineInfo(
    val name: String,
    val version: String,
    val status: EngineStatus = EngineStatus.INTEGRATED,
    val detail: String = ""
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
            EngineInfo("Rime · Luna Pinyin (Chinese)", "$RIME_VERSION · schema 0.26"),
            EngineInfo("Mozc (Japanese)", MOZC_VERSION)
        )),
        EngineCategory("completion", "Completion Engines", listOf(
            EngineInfo("LatinIME · English suggestions", ENGLISH_VERSION,
                detail = "Offline word completion and contextual candidates; read-only dictionary.")
        )),
        EngineCategory("pinyin_correction", "Pinyin Correction Engines", listOf(
            EngineInfo("Rime · Pinyin spelling & key correction", "$RIME_VERSION · Luna Pinyin schema 0.26")
        )),
        EngineCategory("script_conversion", "Script Conversion Engines", listOf(
            EngineInfo("OpenCC (Traditional → Simplified Chinese)", "Source: Trime 3.3.12")
        )),
        EngineCategory("ranking", "Candidate Ranking Engines", listOf(
            EngineInfo("IPA Board · CandidateRanker", "Built-in · IPA Board $appVersion",
                detail = "Multilingual ranking using source rank, preceding English context and candidate type.")
        )),
        EngineCategory("calculation", "Calculation Engines", listOf(
            EngineInfo("IPA Board · CalculatorEvaluator", "Built-in · IPA Board $appVersion")
        )),
        EngineCategory("otp_detection", "SMS Code Detection Engines", listOf(
            EngineInfo("IPA Board · SmsOtpParser", "Built-in · IPA Board $appVersion")
        )),
        EngineCategory("prediction", "Next-word Prediction Engines", listOf(
            EngineInfo("LatinIME · Contextual next-word prediction", ENGLISH_VERSION,
                detail = "Dictionary bigrams from the English suffix before the cursor; works with no current word.")
        )),
        EngineCategory("english_correction", "English Spelling Correction Engines", listOf(
            EngineInfo("LatinIME · English spelling correction", ENGLISH_VERSION,
                detail = "Edit-distance correction candidates alongside Chinese and Japanese candidates.")
        )),
        EngineCategory("personalization", "Personalization Engines", listOf(
            planned("LatinIME · Personal and history dictionaries", "Later phase: local vocabulary and history learning; currently disabled.")
        )),
        EngineCategory("multilingual_segmentation", "Multilingual Segmentation Engines"),
        EngineCategory("touch_correction", "Touch Correction Engines")
    )

    private fun planned(name: String, detail: String) =
        EngineInfo(name, "Not selected", EngineStatus.PLANNED, detail)
}
