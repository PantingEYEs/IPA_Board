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

    fun withKeyText(row: Int, column: Int, text: String): KeyboardLayout = copy(
        rows = rows.mapIndexed { r, value ->
            if (r != row) value else value.copy(slots = value.slots.mapIndexed { c, slot ->
                if (c == column) slot.copy(text = text) else slot
            })
        }
    )

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
    val text: String = ""
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("widthWeight", widthWeight.toDouble())
        obj.put("text", text)
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): KeySlot {
            return KeySlot(obj.getDouble("widthWeight").toFloat(), obj.optString("text", ""))
        }
    }
}
