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
                row.slots.all { it.widthWeight.isFinite() && it.widthWeight > 0 && it.text.length <= 1000 }
        }) { "Invalid key sizes or text (maximum 1000 characters per key)" }
    }

    fun withKeyText(row: Int, column: Int, text: String): KeyboardLayout =
        withKeyMapping(row, column, text, KeyAction.TEXT)

    fun withKeyMapping(row: Int, column: Int, text: String, action: KeyAction): KeyboardLayout {
        require(row in rows.indices && column in rows[row].slots.indices) { "Unknown key position" }
        return copy(rows = rows.mapIndexed { r, value ->
            if (r != row) value else value.copy(slots = value.slots.mapIndexed { c, slot ->
                if (c == column) slot.copy(text = text, action = action) else slot
            })
        })
    }

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

data class KeySlot(
    val widthWeight: Float,
    val text: String = "",
    val action: KeyAction = KeyAction.TEXT
) {
    fun displayText(shiftEnabled: Boolean = false): String = when (action) {
        KeyAction.TEXT -> (if (shiftEnabled) text.uppercase(java.util.Locale.ROOT) else text).ifEmpty { "∅" }
        else -> action.keyLabel
    }

    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("widthWeight", widthWeight.toDouble())
        obj.put("text", text)
        obj.put("action", action.wireValue)
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): KeySlot {
            return KeySlot(
                obj.getDouble("widthWeight").toFloat(),
                obj.optString("text", ""),
                if (obj.has("action")) KeyAction.fromWireValue(obj.getString("action")) else KeyAction.TEXT
            )
        }
    }
}

enum class KeyAction(val wireValue: String, val title: String, val keyLabel: String, val help: String) {
    TEXT("text", "Text", "", "Enter symbols or text. Leave empty to unassign this key."),
    BACKSPACE("backspace", "Backspace", "⌫", "Delete the selection or the character before the cursor."),
    LEFT("left", "Arrow Left", "←", "Move the cursor left. Shift extends the selection."),
    RIGHT("right", "Arrow Right", "→", "Move the cursor right. Shift extends the selection."),
    UP("up", "Arrow Up", "↑", "Move the cursor up. Shift extends the selection."),
    DOWN("down", "Arrow Down", "↓", "Move the cursor down. Shift extends the selection."),
    SHIFT("shift", "Shift / Uppercase", "Shift", "Toggle uppercase text and Shift navigation. Tap again to turn off."),
    CTRL("ctrl", "Ctrl", "Ctrl", "Apply Ctrl to the next key. Use single letters for shortcuts, such as Ctrl+A. Support depends on the receiving app."),
    ENTER("enter", "Enter", "↵", "Run the text field action, or insert a new line in a multiline field."),
    TAB("tab", "Tab", "Tab", "Send Tab. Focus movement depends on the receiving app."),
    HOME("home", "Home", "Home", "Move to the beginning of the line. Shift extends the selection."),
    END("end", "End", "End", "Move to the end of the line. Shift extends the selection.");

    companion object {
        fun fromWireValue(value: String): KeyAction = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported key action: $value")
    }
}
