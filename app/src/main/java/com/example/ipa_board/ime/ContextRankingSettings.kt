package com.example.ipa_board.ime

import android.content.Context
import android.content.SharedPreferences
import com.example.ipa_board.SettingsConstants

/** Stable opt-in contract shared by the settings UI and the context-ranking pipeline. */
object ContextRankingSettings {
    const val KEY_ENABLED = SettingsConstants.KEY_SEMANTIC_CONTEXT_ENABLED
    const val DEFAULT_ENABLED = false
    const val MAX_CONTEXT_CHARACTERS = 256

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
