package com.example.ipa_board.ime

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

enum class EngineFeature(val key: String, val defaultEnabled: Boolean = true) {
    IPA_CONVERSION("engine_ipa_conversion"),
    CHINESE_CONVERSION("engine_chinese_conversion"), JAPANESE_CONVERSION("engine_japanese_conversion"),
    ENGLISH_COMPLETION("engine_english_completion"), ENGLISH_CORRECTION("engine_english_correction"),
    CHINESE_PREDICTION("engine_chinese_prediction"), JAPANESE_PREDICTION("engine_japanese_prediction"),
    ENGLISH_PREDICTION("engine_english_prediction"), SCRIPT_CONVERSION("engine_script_conversion"),
    RANKING("engine_candidate_ranking"), SEGMENTATION("engine_mixed_segmentation"),
    CALCULATOR("engine_calculator"), SMS_OTP("engine_sms_otp"),
    SEMANTIC(ContextRankingSettings.KEY_ENABLED, false)
}

object EngineSettings {
    fun preferences(context: Context) = ContextRankingSettings.preferences(context)
    fun enabled(context: Context, feature: EngineFeature) = enabled(preferences(context), feature)
    fun enabled(prefs: SharedPreferences, feature: EngineFeature) = prefs.getBoolean(feature.key, feature.defaultEnabled)
    fun setEnabled(context: Context, feature: EngineFeature, value: Boolean) {
        preferences(context).edit().putBoolean(feature.key, value).apply()
    }
    /** One preference transaction means listeners see a complete policy, never per-switch intermediates. */
    @android.annotation.SuppressLint("ApplySharedPref") // commit's result is required for failure rollback.
    internal fun applyStates(prefs: SharedPreferences, states: Map<EngineFeature, Boolean>): Boolean = synchronized(prefs) {
        val before = try { prefs.all } catch (_: RuntimeException) { return false }
        if (states.keys.any { before.containsKey(it.key) && before[it.key] !is Boolean }) return false
        if (states.all { (feature, value) -> before[feature.key] == value }) return true
        fun commit(update: SharedPreferences.Editor.() -> Unit) = try {
            prefs.edit().apply(update).commit()
        } catch (_: RuntimeException) { false }
        if (commit { states.forEach { (feature, value) -> putBoolean(feature.key, value) } }) return true
        // A failed commit can already have changed memory; best-effort rollback preserves unset defaults.
        commit { states.keys.forEach { feature ->
            val old = before[feature.key]
            if (old is Boolean) putBoolean(feature.key, old) else remove(feature.key)
        } }
        false
    }
    fun isKey(key: String?) = EngineFeature.entries.any { it.key == key }
    internal fun snapshot(prefs: SharedPreferences) = EngineQueryPolicy(EngineFeature.entries.associateWith { enabled(prefs, it) })
}

/** Passed explicitly to isolated engine processes; SharedPreferences are not read across processes. */
internal data class EngineQueryPolicy(private val values: Map<EngineFeature, Boolean> = emptyMap()) {
    fun enabled(feature: EngineFeature) = values[feature] ?: feature.defaultEnabled
    fun wantsQuery(language: String, prediction: Boolean): Boolean = when (language) {
        "IPA" -> !prediction && enabled(EngineFeature.IPA_CONVERSION)
        "ZH" -> enabled(if (prediction) EngineFeature.CHINESE_PREDICTION else EngineFeature.CHINESE_CONVERSION)
        "JA" -> enabled(if (prediction) EngineFeature.JAPANESE_PREDICTION else EngineFeature.JAPANESE_CONVERSION)
        "EN" -> if (prediction) enabled(EngineFeature.ENGLISH_PREDICTION) else
            enabled(EngineFeature.ENGLISH_COMPLETION) || enabled(EngineFeature.ENGLISH_CORRECTION)
        else -> false
    }
    fun needsRuntime(language: String) = wantsQuery(language, true) || wantsQuery(language, false)
    fun permitsEnglish(kind: CandidateKind) = when (kind) {
        CandidateKind.PREDICTION -> enabled(EngineFeature.ENGLISH_PREDICTION)
        CandidateKind.CORRECTION -> enabled(EngineFeature.ENGLISH_CORRECTION)
        else -> enabled(EngineFeature.ENGLISH_COMPLETION)
    }
    fun writeTo(bundle: Bundle) {
        EngineFeature.entries.forEach { bundle.putBoolean(it.key, enabled(it)) }
    }
    companion object {
        fun from(bundle: Bundle) = EngineQueryPolicy(EngineFeature.entries.associateWith {
            bundle.getBoolean(it.key, it.defaultEnabled)
        })
    }
}
