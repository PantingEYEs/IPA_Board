package com.example.ipa_board.clipboard

import org.json.JSONObject

/**
 * Data model for clipboard entries.
 */
data class ClipboardItem(
    val id: String,
    val text: String,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val isSensitive: Boolean = false
) {
    fun toJsonString(): String {
        return try {
            JSONObject().apply {
                put("id", id)
                put("text", text)
                put("timestamp", timestamp)
                put("isPinned", isPinned)
                put("isSensitive", isSensitive)
            }.toString()
        } catch (_: Throwable) {
            val escapedText = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            """{"id":"$id","text":"$escapedText","timestamp":$timestamp,"isPinned":$isPinned,"isSensitive":$isSensitive}"""
        }
    }

    companion object {
        fun fromJsonString(jsonStr: String): ClipboardItem? {
            return try {
                val json = JSONObject(jsonStr)
                ClipboardItem(
                    id = json.getString("id"),
                    text = json.getString("text"),
                    timestamp = json.getLong("timestamp"),
                    isPinned = json.optBoolean("isPinned", false),
                    isSensitive = json.optBoolean("isSensitive", false)
                )
            } catch (_: Throwable) {
                try {
                    fun parseField(key: String): String? {
                        val pattern = """"$key"\s*:\s*("(?:[^"\\]|\\.)*"|true|false|\d+)""".toRegex()
                        val match = pattern.find(jsonStr) ?: return null
                        val raw = match.groupValues[1]
                        return if (raw.startsWith("\"") && raw.endsWith("\"")) {
                            raw.substring(1, raw.length - 1)
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\")
                                .replace("\\n", "\n")
                                .replace("\\r", "\r")
                                .replace("\\t", "\t")
                        } else raw
                    }
                    val id = parseField("id") ?: return null
                    val text = parseField("text") ?: return null
                    val timestamp = parseField("timestamp")?.toLongOrNull() ?: return null
                    val isPinned = parseField("isPinned")?.toBooleanStrictOrNull() ?: false
                    val isSensitive = parseField("isSensitive")?.toBooleanStrictOrNull() ?: false
                    ClipboardItem(id, text, timestamp, isPinned, isSensitive)
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }
}
