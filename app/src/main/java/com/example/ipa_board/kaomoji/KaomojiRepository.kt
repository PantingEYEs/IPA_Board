package com.example.ipa_board.kaomoji

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class KaomojiRepository(
    private val context: Context? = null,
    private val storageDirOverride: File? = null
) {

    private val configFile: File
        get() = File(storageDirOverride ?: context!!.filesDir, "kaomoji_config.json")

    private val items = mutableListOf<KaomojiItem>()

    init {
        loadFromDisk()
    }

    /**
     * Get all current kaomojis.
     */
    fun getAllKaomojis(): List<KaomojiItem> {
        return items.toList()
    }

    /**
     * Search kaomojis by query string, sorted by usage count descending.
     * If isReversed is true, displays in reverse order.
     */
    fun search(query: String, isReversed: Boolean = false): List<KaomojiItem> {
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            items.toList()
        } else {
            items.filter { item ->
                item.text.contains(trimmed, ignoreCase = true) ||
                        item.tags.any { it.contains(trimmed, ignoreCase = true) }
            }
        }
        val sorted = filtered.sortedByDescending { it.usageCount }
        return if (isReversed) sorted.reversed() else sorted
    }

    /**
     * Add a new kaomoji.
     * Returns true if added successfully, false if duplicate text exists.
     */
    fun addKaomoji(text: String, initialTags: List<String> = emptyList()): Boolean {
        if (hasDuplicateText(text)) {
            return false
        }
        val cleanTags = initialTags.distinct()
        val newItem = KaomojiItem(text = text, tags = cleanTags, usageCount = 0)
        items.add(newItem)
        saveToDisk()
        return true
    }

    /**
     * Check if exact text already exists in repository.
     */
    fun hasDuplicateText(text: String): Boolean {
        return items.any { it.text == text }
    }

    /**
     * Add tags to a single kaomoji by ID.
     */
    fun addTagToKaomoji(id: String, newTag: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val cleanTag = newTag.trim()
        if (cleanTag.isEmpty()) return false

        val currentItem = items[index]
        if (currentItem.tags.contains(cleanTag)) {
            return false // Already has this tag
        }
        val updatedTags = (currentItem.tags + cleanTag).distinct()
        items[index] = currentItem.copy(tags = updatedTags)
        saveToDisk()
        return true
    }

    /**
     * Remove a tag from a kaomoji by ID.
     */
    fun removeTagFromKaomoji(id: String, tagToRemove: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val currentItem = items[index]
        if (!currentItem.tags.contains(tagToRemove)) return false

        val updatedTags = currentItem.tags.filter { it != tagToRemove }
        items[index] = currentItem.copy(tags = updatedTags)
        saveToDisk()
        return true
    }

    /**
     * Increment usage count for a kaomoji by ID.
     */
    fun incrementUsageCount(id: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val currentItem = items[index]
        items[index] = currentItem.copy(usageCount = currentItem.usageCount + 1)
        saveToDisk()
        return true
    }

    /**
     * Increment usage count for a kaomoji by text.
     */
    fun incrementUsageCountByText(text: String): Boolean {
        val index = items.indexOfFirst { it.text == text }
        if (index < 0) return false
        val currentItem = items[index]
        items[index] = currentItem.copy(usageCount = currentItem.usageCount + 1)
        saveToDisk()
        return true
    }

    /**
     * Add tag(s) to multiple kaomojis by list of IDs.
     */
    fun addTagToMultipleKaomojis(ids: Set<String>, newTags: List<String>): Int {
        val cleanTags = newTags.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanTags.isEmpty()) return 0

        var count = 0
        for (i in items.indices) {
            val item = items[i]
            if (ids.contains(item.id)) {
                val merged = (item.tags + cleanTags).distinct()
                if (merged.size != item.tags.size) {
                    items[i] = item.copy(tags = merged)
                    count++
                }
            }
        }
        if (count > 0) {
            saveToDisk()
        }
        return count
    }

    /**
     * Batch delete kaomojis by list of IDs.
     */
    fun batchDelete(ids: Set<String>): Int {
        val initialSize = items.size
        items.removeAll { ids.contains(it.id) }
        val deletedCount = initialSize - items.size
        if (deletedCount > 0) {
            saveToDisk()
        }
        return deletedCount
    }

    /**
     * Export configuration to output stream as JSON.
     */
    fun exportJson(outputStream: OutputStream) {
        val jsonString = serializeToJson()
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(jsonString)
        }
    }

    /**
     * Import configuration from input stream and merge (Union).
     * Returns ImportResult(addedCount, mergedCount).
     */
    data class ImportResult(val addedCount: Int, val mergedCount: Int)

    fun importJson(inputStream: InputStream): ImportResult {
        val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val importedItems = parseJsonString(content)

        var addedCount = 0
        var mergedCount = 0

        for (imp in importedItems) {
            val existingIndex = items.indexOfFirst { it.text == imp.text }
            if (existingIndex >= 0) {
                val existing = items[existingIndex]
                val mergedTags = (existing.tags + imp.tags).distinct()
                val mergedUsage = maxOf(existing.usageCount, imp.usageCount)
                if (mergedTags.size != existing.tags.size || mergedUsage != existing.usageCount) {
                    items[existingIndex] = existing.copy(tags = mergedTags, usageCount = mergedUsage)
                    mergedCount++
                }
            } else {
                items.add(imp)
                addedCount++
            }
        }

        saveToDisk()
        return ImportResult(addedCount, mergedCount)
    }

    private fun serializeToJson(): String {
        val sb = StringBuilder()
        sb.append("{\n  \"version\": 1,\n  \"items\": [\n")
        for (i in items.indices) {
            val item = items[i]
            sb.append("    {\n")
            sb.append("      \"text\": ").append(escapeJsonString(item.text)).append(",\n")
            sb.append("      \"tags\": [")
            sb.append(item.tags.joinToString(", ") { escapeJsonString(it) })
            sb.append("],\n")
            sb.append("      \"usageCount\": ").append(item.usageCount).append("\n")
            sb.append("    }")
            if (i < items.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n}")
        return sb.toString()
    }

    private fun escapeJsonString(str: String): String {
        val sb = StringBuilder("\"")
        for (ch in str) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch.code < 32) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun parseJsonString(jsonStr: String): List<KaomojiItem> {
        val result = mutableListOf<KaomojiItem>()
        val textRegex = Regex(""""text"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val tagsRegex = Regex(""""tags"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val usageRegex = Regex(""""usageCount"\s*:\s*(\d+)""")
        val tagItemRegex = Regex(""""((?:\\.|[^"\\])*)"""")

        val objectRegex = Regex("""\{[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)
        objectRegex.findAll(jsonStr).forEach { match ->
            val objStr = match.value
            val textMatch = textRegex.find(objStr)
            val tagsMatch = tagsRegex.find(objStr)
            val usageMatch = usageRegex.find(objStr)
            if (textMatch != null) {
                val text = unescapeJsonString(textMatch.groupValues[1])
                val tagsList = if (tagsMatch != null) {
                    tagItemRegex.findAll(tagsMatch.groupValues[1])
                        .map { unescapeJsonString(it.groupValues[1]).trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .toList()
                } else {
                    emptyList()
                }
                val usageCount = usageMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                if (text.isNotEmpty()) {
                    result.add(KaomojiItem(text = text, tags = tagsList, usageCount = usageCount))
                }
            }
        }
        return result
    }

    private fun unescapeJsonString(str: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '\\' && i + 1 < str.length) {
                val next = str[i + 1]
                when (next) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'u' -> {
                        if (i + 5 < str.length) {
                            val hex = str.substring(i + 2, i + 6)
                            try {
                                sb.append(hex.toInt(16).toChar())
                                i += 6
                            } catch (e: Exception) {
                                sb.append(c)
                                i++
                            }
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun loadFromDisk() {
        items.clear()
        val settings = if (storageDirOverride == null) context?.getSharedPreferences(
            com.example.ipa_board.SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE
        ) else null
        if (!configFile.exists()) {
            if (settings != null && !settings.getBoolean("kaomoji_assets_initialized", false)) {
                val assetDir = "initialization/kaomoji"
                val filenames = context!!.assets.list(assetDir).orEmpty()
                    .filter { it.endsWith(".json", ignoreCase = true) }.sorted()
                filenames.forEach { filename ->
                    context.assets.open("$assetDir/$filename").use { importJson(it) }
                }
            }
            saveToDisk()
            if (configFile.exists()) settings?.edit()?.putBoolean("kaomoji_assets_initialized", true)?.apply()
            return
        }

        try {
            val content = configFile.readText()
            val loaded = parseJsonString(content)
            items.addAll(loaded)
            settings?.edit()?.putBoolean("kaomoji_assets_initialized", true)?.apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveToDisk() {
        try {
            configFile.writeText(serializeToJson())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}
