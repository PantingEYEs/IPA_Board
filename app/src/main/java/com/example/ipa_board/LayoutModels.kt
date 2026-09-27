package com.example.ipa_board

import org.json.JSONArray
import org.json.JSONObject

data class KeyboardLayout(
    val name: String,
    val rows: List<RowLayout>
) {
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
    val widthWeight: Float
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("widthWeight", widthWeight.toDouble())
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): KeySlot {
            return KeySlot(obj.getDouble("widthWeight").toFloat())
        }
    }
}
