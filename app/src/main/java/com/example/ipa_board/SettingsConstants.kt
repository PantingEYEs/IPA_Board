package com.example.ipa_board

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
