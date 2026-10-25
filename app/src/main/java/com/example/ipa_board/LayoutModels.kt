package com.example.ipa_board

import com.example.ipa_board.ime.PageEngineBinding
import org.json.JSONArray
import org.json.JSONObject

data class KeyboardLayout(
    val name: String,
    val rows: List<RowLayout>,
    val engineBinding: PageEngineBinding? = null
) {
    init {
        require(rows.isNotEmpty() && rows.size <= 20) { "Layout must contain 1–20 rows" }
        require(rows.all { row ->
            row.heightWeight.isFinite() && row.heightWeight > 0 &&
                row.slots.isNotEmpty() && row.slots.size <= 40 &&
                row.slots.all { it.widthWeight.isFinite() && it.widthWeight > 0 && it.text.length <= 1000 && it.longPressText.length <= 1000 }
        }) { "Invalid key sizes or text (maximum 1000 characters per key)" }
    }

    fun withKeyText(row: Int, column: Int, text: String): KeyboardLayout =
        withKeyMapping(row, column, text, KeyAction.TEXT)

    fun withKeyMapping(
        row: Int, column: Int, text: String, action: KeyAction, behavior: TextBehavior? = null,
        longPressText: String? = null, longPressAction: KeyAction? = null,
        longPressItems: List<LongPressItem>? = null,
        swipeLeftText: String? = null, swipeLeftAction: KeyAction? = null,
        swipeRightText: String? = null, swipeRightAction: KeyAction? = null,
        multifunctionMode: MultifunctionMode? = null, popupConfiguration: PopupConfiguration? = null
    ): KeyboardLayout {
        require(row in rows.indices && column in rows[row].slots.indices) { "Unknown key position" }
        return copy(rows = rows.mapIndexed { r, value ->
            if (r != row) value else value.copy(slots = value.slots.mapIndexed { c, slot ->
                if (c == column) slot.copy(
                    text = text,
                    action = action,
                    textBehavior = behavior ?: slot.textBehavior,
                    longPressText = longPressText ?: slot.longPressText,
                    longPressAction = longPressAction ?: slot.longPressAction,
                    longPressItems = longPressItems ?: slot.longPressItems,
                    swipeLeftText = swipeLeftText ?: slot.swipeLeftText,
                    swipeLeftAction = swipeLeftAction ?: slot.swipeLeftAction,
                    swipeRightText = swipeRightText ?: slot.swipeRightText,
                    swipeRightAction = swipeRightAction ?: slot.swipeRightAction,
                    multifunctionMode = multifunctionMode ?: slot.multifunctionMode,
                    popupConfiguration = popupConfiguration ?: slot.popupConfiguration
                ) else slot
            })
        })
    }

    fun cleared(): KeyboardLayout = copy(rows = rows.map { row ->
        row.copy(slots = row.slots.map { slot -> slot.copy(text = "", action = KeyAction.TEXT, textBehavior = TextBehavior.LITERAL, longPressText = "", longPressAction = KeyAction.TEXT, longPressItems = emptyList(), swipeLeftText = "", swipeLeftAction = KeyAction.TEXT, swipeRightText = "", swipeRightAction = KeyAction.TEXT, multifunctionMode = MultifunctionMode.CLASSIC, popupConfiguration = null) })
    })

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("name", name)
        val rowsArr = JSONArray()
        for (row in rows) {
            rowsArr.put(row.toJsonObject())
        }
        obj.put("rows", rowsArr)
        engineBinding?.let { obj.put("engineBinding", it.toJsonObject()) }
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): KeyboardLayout {
            val obj = JSONObject(jsonStr)
            val name = obj.getString("name")
            val rowsArr = obj.getJSONArray("rows")
            val rows = mutableListOf<RowLayout>()
            for (i in 0 until rowsArr.length()) {
                rows.add(RowLayout.fromJsonObject(rowsArr.getJSONObject(i)))
            }
            return KeyboardLayout(name, rows, PageEngineBinding.fromJsonObject(obj.optJSONObject("engineBinding")))
        }
    }
}

data class RowLayout(
    val heightWeight: Float,
    val slots: List<KeySlot>
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("heightWeight", heightWeight.toDouble())
        val slotsArr = JSONArray()
        for (slot in slots) {
            slotsArr.put(slot.toJsonObject())
        }
        obj.put("slots", slotsArr)
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): RowLayout {
            val hWeight = obj.getDouble("heightWeight").toFloat()
            val slotsArr = obj.getJSONArray("slots")
            val slots = mutableListOf<KeySlot>()
            for (i in 0 until slotsArr.length()) {
                slots.add(KeySlot.fromJsonObject(slotsArr.getJSONObject(i)))
            }
            return RowLayout(hWeight, slots)
        }
    }
}

data class LongPressItem(
    val text: String = "",
    val action: KeyAction = KeyAction.TEXT
) {
    fun previewText(shiftEnabled: Boolean = false, ctrlEnabled: Boolean = false): String {
        val label = when {
            action != KeyAction.TEXT -> action.keyLabel
            text == " " -> "␣"
            text == "\n" -> "↵"
            text == "\t" -> "⇥"
            else -> text
        }.let { if (ctrlEnabled && action == KeyAction.TEXT && text.isNotEmpty()) "Ctrl+$it" else it }
        return label
    }

    fun description(): String {
        return if (action != KeyAction.TEXT) action.title else text
    }

    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("text", text)
        obj.put("action", action.wireValue)
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): LongPressItem {
            return LongPressItem(
                text = obj.optString("text", ""),
                action = if (obj.has("action")) KeyAction.fromWireValue(obj.getString("action")) else KeyAction.TEXT
            )
        }
    }
}

data class KeySlot(
    val widthWeight: Float,
    val text: String = "",
    val action: KeyAction = KeyAction.TEXT,
    val textBehavior: TextBehavior = TextBehavior.LITERAL,
    val longPressText: String = "",
    val longPressAction: KeyAction = KeyAction.TEXT,
    val longPressItems: List<LongPressItem> = emptyList(),
    val swipeLeftText: String = "",
    val swipeLeftAction: KeyAction = KeyAction.TEXT,
    val swipeRightText: String = "",
    val swipeRightAction: KeyAction = KeyAction.TEXT,
    val multifunctionMode: MultifunctionMode = MultifunctionMode.CLASSIC,
    val popupConfiguration: PopupConfiguration? = null
) {
    val effectiveLongPressItems: List<LongPressItem>
        get() {
            if (longPressItems.isNotEmpty()) return longPressItems
            if (longPressAction != KeyAction.TEXT) {
                return listOf(LongPressItem(action = longPressAction))
            }
            if (longPressText.isNotEmpty()) {
                return LongPressTextCodec.parse(longPressText).map { LongPressItem(text = it) }
            }
            // A repeat binding can be held directly unless the user assigned a separate hold action.
            if (action.repeatsWhileHeld) return listOf(LongPressItem(action = action))
            return emptyList()
        }

    val effectiveSwipeLeftItem: LongPressItem?
        get() = when {
            swipeLeftAction != KeyAction.TEXT -> LongPressItem(action = swipeLeftAction)
            swipeLeftText.isNotEmpty() -> LongPressItem(text = swipeLeftText)
            else -> null
        }

    val effectiveSwipeRightItem: LongPressItem?
        get() = when {
            swipeRightAction != KeyAction.TEXT -> LongPressItem(action = swipeRightAction)
            swipeRightText.isNotEmpty() -> LongPressItem(text = swipeRightText)
            else -> null
        }

    val hasSwipeLeft: Boolean get() = multifunctionMode == MultifunctionMode.CLASSIC && effectiveSwipeLeftItem != null
    val hasSwipeRight: Boolean get() = multifunctionMode == MultifunctionMode.CLASSIC && effectiveSwipeRightItem != null

    val hasLongPress: Boolean
        get() = when {
            multifunctionMode == MultifunctionMode.POPUP -> true
            action.repeatsWhileHeld -> true
            else -> effectiveLongPressItems.isNotEmpty()
        }

    fun displayText(shiftEnabled: Boolean = false): String = when (action) {
        KeyAction.TEXT -> (if (shiftEnabled) text.uppercase(java.util.Locale.ROOT) else text).ifEmpty { "∅" }
        else -> action.keyLabel
    }

    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("widthWeight", widthWeight.toDouble())
        obj.put("text", text)
        obj.put("action", action.wireValue)
        obj.put("textBehavior", textBehavior.wireValue)
        obj.put("longPressText", longPressText)
        obj.put("longPressAction", longPressAction.wireValue)
        if (longPressItems.isNotEmpty()) {
            val itemsArr = JSONArray()
            for (item in longPressItems) {
                itemsArr.put(item.toJsonObject())
            }
            obj.put("longPressItems", itemsArr)
        }
        obj.put("swipeLeftText", swipeLeftText)
        obj.put("swipeLeftAction", swipeLeftAction.wireValue)
        obj.put("swipeRightText", swipeRightText)
        obj.put("swipeRightAction", swipeRightAction.wireValue)
        obj.put("multifunctionMode", multifunctionMode.wireValue)
        popupConfiguration?.let { obj.put("popupConfiguration", it.toJsonObject()) }
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): KeySlot {
            val items = mutableListOf<LongPressItem>()
            if (obj.has("longPressItems")) {
                val arr = obj.getJSONArray("longPressItems")
                for (i in 0 until arr.length()) {
                    items.add(LongPressItem.fromJsonObject(arr.getJSONObject(i)))
                }
            }
            return KeySlot(
                obj.getDouble("widthWeight").toFloat(),
                obj.optString("text", ""),
                if (obj.has("action")) KeyAction.fromWireValue(obj.getString("action")) else KeyAction.TEXT,
                TextBehavior.fromWireValue(obj.optString("textBehavior", "literal")),
                obj.optString("longPressText", ""),
                if (obj.has("longPressAction")) KeyAction.fromWireValue(obj.getString("longPressAction")) else KeyAction.TEXT,
                items,
                obj.optString("swipeLeftText", ""),
                if (obj.has("swipeLeftAction")) KeyAction.fromWireValue(obj.getString("swipeLeftAction")) else KeyAction.TEXT,
                obj.optString("swipeRightText", ""),
                if (obj.has("swipeRightAction")) KeyAction.fromWireValue(obj.getString("swipeRightAction")) else KeyAction.TEXT,
                MultifunctionMode.fromWireValue(obj.optString("multifunctionMode", "classic")),
                if (obj.has("popupConfiguration") && !obj.isNull("popupConfiguration")) {
                    PopupConfiguration.fromJsonObject(obj.getJSONObject("popupConfiguration"))
                } else null
            )
        }
    }
}

enum class KeyAction(val wireValue: String, val title: String, val keyLabel: String, val help: String) {
    TEXT("text", "Text", "", "Enter symbols or text. Leave empty to unassign this key."),
    BACKSPACE("backspace", "Backspace", "⌫", "Delete the selection or the character before the cursor."),
    REPEAT_BACKSPACE("repeat_backspace", "Continuous Delete", "⌫…", "Repeatedly delete characters while long pressing until released."),
    LEFT("left", "Arrow Left", "←", "Move the cursor left. Shift extends the selection."),
    RIGHT("right", "Arrow Right", "→", "Move the cursor right. Shift extends the selection."),
    UP("up", "Arrow Up", "↑", "Move the cursor up. Shift extends the selection."),
    DOWN("down", "Arrow Down", "↓", "Move the cursor down. Shift extends the selection."),
    REPEAT_LEFT("repeat_left", "Continuous Cursor Left", "←…", "Repeatedly move the cursor left while held, using the Continuous Delete timing. Shift extends the selection."),
    REPEAT_RIGHT("repeat_right", "Continuous Cursor Right", "→…", "Repeatedly move the cursor right while held, using the Continuous Delete timing. Shift extends the selection."),
    REPEAT_UP("repeat_up", "Continuous Cursor Up", "↑…", "Repeatedly move the cursor up while held, using the Continuous Delete timing. Shift extends the selection."),
    REPEAT_DOWN("repeat_down", "Continuous Cursor Down", "↓…", "Repeatedly move the cursor down while held, using the Continuous Delete timing. Shift extends the selection."),
    SHIFT("shift", "Shift / Uppercase", "Shift", "Tap once for temporary Shift, again for Caps Lock, and once more to turn off."),
    CTRL("ctrl", "Ctrl", "Ctrl", "Apply Ctrl to the next key. Use single letters for shortcuts, such as Ctrl+A. Support depends on the receiving app."),
    ENTER("enter", "Enter", "↵", "Run the text field action, or insert a new line in a multiline field."),
    TAB("tab", "Tab", "Tab", "Send Tab. Focus movement depends on the receiving app."),
    HOME("home", "Home", "Home", "Move to the beginning of the line. Shift extends the selection."),
    END("end", "End", "End", "Move to the end of the line. Shift extends the selection."),
    EMOJI("emoji", "Emoji", "☺\uFE0E", "Open the scrollable emoji panel. Tap an emoji to insert it; Return closes the panel."),
    KAOMOJI("kaomoji", "顔文字", "顔", "Open the scrollable 顔文字 panel. Tap a 顔文字 to insert it; Return closes the panel."),
    WIDTH_SWAP("width_swap", "全角 / 半角转换", "◩", "Off by default. When enabled, swap full-width and half-width forms of typed characters that have a width counterpart. Tap again to turn off."),
    CALCULATOR("calculator", "计算器", "∑", "Toggle calculator mode. Evaluate math expressions and output results as candidates."),
    ENGINE_DASHBOARD("engine_dashboard", "Engine Dashboard", "◷", "Open the read-only engine dashboard. Return closes the panel."),
    REMOTE_KEYBOARD("remote_keyboard", "Remote Keyboard Mode", "⌁", "Save and disable all input engines. Press again to restore their original settings, including after an app restart. Manually enabled engines remain enabled until restoration. No built-in remote connection is required."),
    CANDIDATES("candidates", "Candidates", "⋯", "Open the candidate word list panel."),
    CLIPBOARD("clipboard", "Clipboard", "⧉", "Open the clipboard panel. Tap an item to insert it; Return closes the panel."),
    PAGES("pages", "Keyboard Pages", "⊞", "Open the keyboard page picker panel to switch active layouts."),
    PREV_PAGE("prev_page", "Previous Keyboard Page", "⊞‹", "Switch to the previous keyboard page."),
    NEXT_PAGE("next_page", "Next Keyboard Page", "⊞›", "Switch to the next keyboard page."),
    SELECT_ALL("select_all", "Select All", "全选", "Select all text in current input field."),
    COPY("copy", "Copy", "复制", "Copy current selected text."),
    CUT("cut", "Cut", "剪切", "Cut current selected text."),
    PASTE("paste", "Paste", "粘贴", "Paste clipboard text.");

    /** Repetition belongs to the gesture; each dispatch reuses its existing single-step operation. */
    val singleStepAction: KeyAction
        get() = when (this) {
            REPEAT_BACKSPACE -> BACKSPACE
            REPEAT_LEFT -> LEFT
            REPEAT_RIGHT -> RIGHT
            REPEAT_UP -> UP
            REPEAT_DOWN -> DOWN
            else -> this
        }

    val repeatsWhileHeld: Boolean get() = singleStepAction != this

    companion object {
        fun fromWireValue(value: String): KeyAction = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported key action: $value")
    }
}

/** Missing fields in old layouts deliberately retain direct IPA entry. */
enum class TextBehavior(val wireValue: String, val title: String) {
    LITERAL("literal", "Direct text / IPA"),
    AUTO("auto", "Mixed input (拼音 / 注音 / English / ローマ字)");

    companion object {
        fun fromWireValue(value: String): TextBehavior = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported text behavior: $value")
    }
}
