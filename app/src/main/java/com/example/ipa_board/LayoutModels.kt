package com.example.ipa_board

import org.json.JSONArray
import org.json.JSONObject

data class KeyboardLayout(
    val name: String,
    val rows: List<RowLayout>
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
        swipeRightText: String? = null, swipeRightAction: KeyAction? = null
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
                    swipeRightAction = swipeRightAction ?: slot.swipeRightAction
                ) else slot
            })
        })
    }

    fun cleared(): KeyboardLayout = copy(rows = rows.map { row ->
        row.copy(slots = row.slots.map { slot -> slot.copy(text = "", action = KeyAction.TEXT, textBehavior = TextBehavior.LITERAL, longPressText = "", longPressAction = KeyAction.TEXT, longPressItems = emptyList(), swipeLeftText = "", swipeLeftAction = KeyAction.TEXT, swipeRightText = "", swipeRightAction = KeyAction.TEXT) })
    })

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("name", name)
        val rowsArr = JSONArray()
        for (row in rows) {
            rowsArr.put(row.toJsonObject())
        }
        obj.put("rows", rowsArr)
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
            return KeyboardLayout(name, rows)
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
    val swipeRightAction: KeyAction = KeyAction.TEXT
) {
    val effectiveLongPressItems: List<LongPressItem>
        get() {
            if (longPressItems.isNotEmpty()) return longPressItems
            if (longPressAction != KeyAction.TEXT) {
                return listOf(LongPressItem(action = longPressAction))
            }
            if (longPressText.isNotEmpty()) {
                return if (longPressText.contains(",")) {
                    longPressText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { LongPressItem(text = it) }
                } else {
                    listOf(LongPressItem(text = longPressText))
                }
            }
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

    val hasSwipeLeft: Boolean get() = effectiveSwipeLeftItem != null
    val hasSwipeRight: Boolean get() = effectiveSwipeRightItem != null

    val hasLongPress: Boolean
        get() = when {
            action == KeyAction.REPEAT_BACKSPACE -> true
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
                if (obj.has("swipeRightAction")) KeyAction.fromWireValue(obj.getString("swipeRightAction")) else KeyAction.TEXT
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
    SHIFT("shift", "Shift / Uppercase", "Shift", "Toggle uppercase text and Shift navigation. Tap again to turn off."),
    CTRL("ctrl", "Ctrl", "Ctrl", "Apply Ctrl to the next key. Use single letters for shortcuts, such as Ctrl+A. Support depends on the receiving app."),
    ENTER("enter", "Enter", "↵", "Run the text field action, or insert a new line in a multiline field."),
    TAB("tab", "Tab", "Tab", "Send Tab. Focus movement depends on the receiving app."),
    HOME("home", "Home", "Home", "Move to the beginning of the line. Shift extends the selection."),
    END("end", "End", "End", "Move to the end of the line. Shift extends the selection."),
    EMOJI("emoji", "Emoji", "☺", "Open the scrollable emoji panel. Tap an emoji to insert it; Return closes the panel."),
    CANDIDATES("candidates", "Candidates", "⋯", "Open the candidate word list panel."),
    CLIPBOARD("clipboard", "Clipboard", "⧉", "Open the clipboard panel. Tap an item to insert it; Return closes the panel."),
    PAGES("pages", "Keyboard Pages", "⊞", "Open the keyboard page picker panel to switch active layouts."),
    PREV_PAGE("prev_page", "Previous Keyboard Page", "⊞‹", "Switch to the previous keyboard page."),
    NEXT_PAGE("next_page", "Next Keyboard Page", "⊞›", "Switch to the next keyboard page.");

    companion object {
        fun fromWireValue(value: String): KeyAction = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported key action: $value")
    }
}

/** Missing fields in old layouts deliberately retain direct IPA entry. */
enum class TextBehavior(val wireValue: String, val title: String) {
    LITERAL("literal", "Direct text / IPA"),
    AUTO("auto", "Mixed input (拼音 / English / ローマ字)");

    companion object {
        fun fromWireValue(value: String): TextBehavior = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported text behavior: $value")
    }
}
