package com.example.ipa_board.ime

import android.content.Context
import android.content.SharedPreferences

/** App-level Roman input choices. Compatibility defaults live here, outside the Rime runtime. */
object PinyinSettings {
    const val KEY_SCHEME = "rime_pinyin_scheme"
    const val KEY_TERRA_ALTERNATIVE_TONES = "rime_terra_alternative_tones"
    val DEFAULT_SCHEME = PinyinScheme.LUNA

    enum class SaveResult { SAVED, UNCHANGED, FAILED, RESTORE_FAILED }

    fun isKey(key: String?) = key == KEY_SCHEME || key == KEY_TERRA_ALTERNATIVE_TONES
    fun scheme(context: Context): PinyinScheme = scheme(EngineSettings.preferences(context))
    fun scheme(preferences: SharedPreferences): PinyinScheme = decodeScheme(preferences.all[KEY_SCHEME])
    fun alternativeTones(preferences: SharedPreferences): Boolean = decodeAlternativeTones(preferences.all[KEY_TERRA_ALTERNATIVE_TONES])

    internal fun decodeScheme(value: Any?): PinyinScheme =
        PinyinScheme.entries.firstOrNull { it.schemaId == value } ?: DEFAULT_SCHEME
    internal fun decodeAlternativeTones(value: Any?): Boolean = value as? Boolean ?: false

    fun setScheme(context: Context, scheme: PinyinScheme): SaveResult =
        setScheme(EngineSettings.preferences(context), scheme)
    fun setScheme(preferences: SharedPreferences, scheme: PinyinScheme): SaveResult =
        save(preferences, KEY_SCHEME, scheme.schemaId)
    fun setAlternativeTones(context: Context, enabled: Boolean): SaveResult =
        setAlternativeTones(EngineSettings.preferences(context), enabled)
    fun setAlternativeTones(preferences: SharedPreferences, enabled: Boolean): SaveResult =
        save(preferences, KEY_TERRA_ALTERNATIVE_TONES, enabled)

    @android.annotation.SuppressLint("ApplySharedPref") // Return write/recovery status instead of claiming an async save succeeded.
    private fun save(preferences: SharedPreferences, key: String, value: Any): SaveResult = synchronized(preferences) {
        val before = try { preferences.all.toMap() } catch (_: RuntimeException) { return@synchronized SaveResult.FAILED }
        if (before.containsKey(key) && before[key] == value) return@synchronized SaveResult.UNCHANGED
        fun matches(expected: Any?, present: Boolean): Boolean = try {
            preferences.all.let { it.containsKey(key) == present && it[key] == expected }
        } catch (_: RuntimeException) { false }
        fun commit(replacement: Any?): Boolean = try {
            val editor = preferences.edit()
            when (replacement) {
                null -> editor.remove(key)
                is String -> editor.putString(key, replacement)
                is Boolean -> editor.putBoolean(key, replacement)
                is Int -> editor.putInt(key, replacement)
                is Long -> editor.putLong(key, replacement)
                is Float -> editor.putFloat(key, replacement)
                is Set<*> -> {
                    require(replacement.all { it is String })
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, replacement as Set<String>)
                }
                else -> error("Unsupported preference type")
            }
            editor.commit()
        } catch (_: RuntimeException) { false }

        if (commit(value) && matches(value, true)) return@synchronized SaveResult.SAVED
        // A failed commit can already have changed the in-memory value; preserve the exact old type or absence.
        val restored = commit(before[key]) && matches(before[key], before.containsKey(key))
        if (restored) SaveResult.FAILED else SaveResult.RESTORE_FAILED
    }
}
