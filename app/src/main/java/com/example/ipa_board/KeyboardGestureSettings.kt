package com.example.ipa_board

import android.content.Context
import android.view.ViewConfiguration

/** Gesture timing is shared by all keyboard pages and read when a key is pressed. */
object KeyboardGestureSettings {
    fun longPressTimeoutMs(context: Context): Int {
        val default = ViewConfiguration.getLongPressTimeout()
        val preferences = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val timeout = try {
            preferences.getInt(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS, default)
        } catch (_: ClassCastException) {
            default
        }
        return timeout.coerceIn(SettingsConstants.MIN_LONG_PRESS_TIMEOUT_MS, SettingsConstants.MAX_LONG_PRESS_TIMEOUT_MS)
    }

    fun setLongPressTimeoutMs(context: Context, value: Int) {
        context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS,
                value.coerceIn(SettingsConstants.MIN_LONG_PRESS_TIMEOUT_MS, SettingsConstants.MAX_LONG_PRESS_TIMEOUT_MS))
            .apply()
    }
}
