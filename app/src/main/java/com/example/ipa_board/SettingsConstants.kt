package com.example.ipa_board

import org.json.JSONObject

object SettingsConstants {
    const val PREFS_NAME = "ipa_board_prefs"
    
    const val KEY_BG_COLOR_HEX = "bg_color_hex"
    const val DEFAULT_BG_COLOR_HEX = "#000000"
    
    const val KEY_SYMBOL_COLOR_HEX = "symbol_color_hex"
    const val DEFAULT_SYMBOL_COLOR_HEX = "#FFFFFF"
    
    const val KEY_LAYOUT_REVISION = "layout_revision"

    const val KEY_ACTIVE_LAYOUT_FILE = "active_layout_file"
    const val DEFAULT_LAYOUT_FILENAME = "default.json"
    const val KEY_LAYOUT_ORDER = "layout_order"
    
    const val KEY_KEYBOARD_HEIGHT = "keyboard_height_dp"
    const val DEFAULT_KEYBOARD_HEIGHT = 210

    const val KEY_SHIFT_SHORTCUTS = "shift_shortcuts_json"
    const val KEY_CTRL_SHORTCUTS = "ctrl_shortcuts_json"

    const val KEY_QUICK_PASTE_ENABLED = "quick_paste_enabled"
    const val KEY_QUICK_PASTE_RETENTION_TYPE = "quick_paste_retention_type"
    const val KEY_QUICK_PASTE_RETENTION_SECONDS = "quick_paste_retention_seconds"
    const val KEY_QUICK_PASTE_USAGE_TYPE = "quick_paste_usage_type"
    const val KEY_QUICK_PASTE_USAGE_TIMES = "quick_paste_usage_times"

    const val KEY_SMS_OTP_AUTO_COPY_ENABLED = "sms_otp_auto_copy_enabled"
    const val KEY_SMS_OTP_MODE = "sms_otp_mode"

    fun parseShortcutsJson(jsonStr: String?): Map<String, KeyAction> {
        if (jsonStr.isNullOrEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(jsonStr)
            val result = mutableMapOf<String, KeyAction>()
            for (key in obj.keys()) {
                val wireValue = obj.getString(key)
                runCatching { KeyAction.fromWireValue(wireValue) }.getOrNull()?.let {
                    result[key] = it
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun serializeShortcutsJson(shortcuts: Map<String, KeyAction>): String {
        val obj = JSONObject()
        for ((key, action) in shortcuts) {
            obj.put(key, action.wireValue)
        }
        return obj.toString()
    }

    val DEFAULT_LAYOUT = KeyboardLayout(
        name = "Default",
        rows = listOf(
            RowLayout(1f, List(11) { KeySlot(1f) }),
            RowLayout(1f, List(11) { KeySlot(1f) }),
            RowLayout(1f, List(11) { KeySlot(1f) }),
            RowLayout(1f, List(11) { KeySlot(1f) }),
            RowLayout(1f, listOf(
                KeySlot(1f), KeySlot(1f), KeySlot(1f), 
                KeySlot(5f, " ", textBehavior = TextBehavior.AUTO, swipeLeftAction = KeyAction.PREV_PAGE, swipeRightAction = KeyAction.NEXT_PAGE), 
                KeySlot(1f), KeySlot(1f)
            )),
            RowLayout(1f, List(4) { KeySlot(1f) })
        )
    )
}
