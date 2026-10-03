package com.example.ipa_board

import android.content.Context

/** Restore group metadata after a test that uses the real management activity or IME. */
internal class PageGroupTestState(context: Context) : AutoCloseable {
    private val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
    private val values = listOf(SettingsConstants.KEY_PAGE_GROUP_STATE, SettingsConstants.KEY_ACTIVE_LAYOUT_FILE,
        SettingsConstants.KEY_BG_COLOR_HEX, SettingsConstants.KEY_SYMBOL_COLOR_HEX,
        SettingsConstants.KEY_KEYBOARD_HEIGHT, SettingsConstants.KEY_KEYBOARD_FONT_SIZE).associateWith { prefs.all[it] }
    override fun close() {
        prefs.edit().apply { values.forEach { (key, value) ->
            when (value) { is String -> putString(key, value); is Int -> putInt(key, value); else -> remove(key) }
        } }.commit()
    }
}
