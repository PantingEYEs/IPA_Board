package com.example.ipa_board.emoji

import android.content.Context
import java.io.File

data class EmojiUsageRecord(
    val text: String,
    val count: Int,
    val lastUsed: Long
)

class EmojiUsageRepository(
    private val context: Context? = null,
    private val storageDirOverride: File? = null
) {
    private val usageFile: File
        get() = File(storageDirOverride ?: context!!.filesDir, FILE_NAME)

    private val usageMap = mutableMapOf<String, EmojiUsageRecord>()

    init {
        loadFromFile()
    }

    @Synchronized
    fun recordUsage(text: String, timestamp: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        val existing = usageMap[text]
        val newCount = (existing?.count ?: 0) + 1
        usageMap[text] = EmojiUsageRecord(text, newCount, timestamp)
        saveToFile()
    }

    @Synchronized
    fun getUsageRecords(): List<EmojiUsageRecord> {
        return usageMap.values.sortedWith(
            compareByDescending<EmojiUsageRecord> { it.count }
                .thenByDescending { it.lastUsed }
        )
    }

    @Synchronized
    fun getMostCommonlyUsed(catalog: EmojiCatalog, limit: Int = 20): List<EmojiEntry> {
        val records = getUsageRecords().take(limit)
        val catalogMap = catalog.entries.associateBy { it.text }
        return records.mapNotNull { record ->
            catalogMap[record.text] ?: EmojiEntry(
                text = record.text,
                name = record.text,
                group = "Custom",
                subgroup = "Custom",
                emojiVersion = "1.0",
                isComponent = false
            )
        }
    }

    @Synchronized
    fun clearUsages() {
        usageMap.clear()
        saveToFile()
    }

    @Synchronized
    private fun loadFromFile() {
        usageMap.clear()
        val file = usageFile
        if (!file.exists()) return
        try {
            val content = file.readText()
            if (content.isBlank()) return
            val entryRegex = Regex("\"((?:\\\\\"|[^\"])+)\"\\s*:\\s*\\{\\s*\"count\"\\s*:\\s*(\\d+)\\s*,\\s*\"lastUsed\"\\s*:\\s*(\\d+)\\s*\\}")
            entryRegex.findAll(content).forEach { match ->
                val text = unescapeJson(match.groupValues[1])
                val count = match.groupValues[2].toIntOrNull() ?: 0
                val lastUsed = match.groupValues[3].toLongOrNull() ?: 0L
                if (text.isNotBlank() && count > 0) {
                    usageMap[text] = EmojiUsageRecord(text, count, lastUsed)
                }
            }
        } catch (_: Exception) {
            // Safe fallback if file is missing or corrupted
        }
    }

    @Synchronized
    private fun saveToFile() {
        try {
            val file = usageFile
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            val sb = StringBuilder()
            sb.append("{\n  \"version\": 1,\n  \"usages\": {\n")
            val entries = usageMap.entries.toList()
            entries.forEachIndexed { index, entry ->
                sb.append("    \"").append(escapeJson(entry.key)).append("\": { \"count\": ")
                    .append(entry.value.count).append(", \"lastUsed\": ").append(entry.value.lastUsed).append(" }")
                if (index < entries.size - 1) {
                    sb.append(",")
                }
                sb.append("\n")
            }
            sb.append("  }\n}\n")
            file.writeText(sb.toString())
        } catch (_: Exception) {
            // Fail gracefully if writing fails
        }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }

    private fun unescapeJson(str: String): String {
        return str.replace("\\r", "\r").replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    companion object {
        const val FILE_NAME = "emoji_usage.json"
    }
}
