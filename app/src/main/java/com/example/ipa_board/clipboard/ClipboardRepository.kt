package com.example.ipa_board.clipboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest

class ClipboardRepository(
    context: Context? = null,
    prefsOverride: SharedPreferences? = null
) {
    private val prefs: SharedPreferences = prefsOverride
        ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?: error("Either context or prefsOverride must be provided")

    private val items = mutableListOf<ClipboardItem>()

    init {
        loadFromPrefs()
    }

    @Synchronized
    fun getItems(): List<ClipboardItem> {
        val pinned = items.filter { it.isPinned }.sortedByDescending { it.timestamp }
        val unpinned = items.filter { !it.isPinned }.sortedByDescending { it.timestamp }
        return pinned + unpinned
    }

    @Synchronized
    fun getUnpinnedCount(): Int = items.count { !it.isPinned }

    @Synchronized
    fun getPinnedCount(): Int = items.count { it.isPinned }

    @Synchronized
    fun getItemById(id: String): ClipboardItem? = items.firstOrNull { it.id == id }

    /** A panel refresh is not a new copy event; retain this marker across service restarts. */
    @Synchronized
    fun syncSystemClip(text: String, copiedAt: Long, isSensitive: Boolean = false): ClipboardItem? {
        if (text.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val fingerprint = "$copiedAt:$digest"
        if (prefs.getString(KEY_SYNCED_SYSTEM_CLIP, null) == fingerprint) return null
        val item = addClip(text, isSensitive)
        prefs.edit().putString(KEY_SYNCED_SYSTEM_CLIP, fingerprint).apply()
        return item
    }

    @Synchronized
    fun addClip(text: String, isSensitive: Boolean = false): ClipboardItem? {
        if (text.isBlank()) return null
        val now = System.currentTimeMillis()
        val existingIndex = items.indexOfFirst { it.text == text }
        val item: ClipboardItem
        if (existingIndex != -1) {
            val existing = items[existingIndex]
            item = existing.copy(timestamp = now, isSensitive = isSensitive)
            items[existingIndex] = item
        } else {
            item = ClipboardItem(
                id = UUID.randomUUID().toString(),
                text = text,
                timestamp = now,
                isPinned = false,
                isSensitive = isSensitive
            )
            items.add(0, item)
        }
        pruneUnpinnedLocked()
        saveToPrefs()
        return item
    }

    /**
     * Toggles pin state.
     * When unpinning a pinned item:
     * Resets its timestamp to [currentTimeMs] (or System.currentTimeMillis()) BEFORE completing unpin,
     * then sets isPinned = false and prunes unpinned items if they exceed 20.
     */
    @Synchronized
    fun togglePin(id: String, currentTimeMs: Long = System.currentTimeMillis()): ClipboardItem? {
        val index = items.indexOfFirst { it.id == id }
        if (index == -1) return null
        val current = items[index]
        val updated: ClipboardItem
        if (current.isPinned) {
            // Reset timestamp to current time BEFORE completing unpin to prevent instant deletion
            updated = current.copy(isPinned = false, timestamp = currentTimeMs)
            items[index] = updated
            pruneUnpinnedLocked()
        } else {
            updated = current.copy(isPinned = true)
            items[index] = updated
        }
        saveToPrefs()
        return updated
    }

    @Synchronized
    fun deleteItem(id: String): Boolean {
        val removed = items.removeAll { it.id == id }
        if (removed) {
            saveToPrefs()
        }
        return removed
    }

    @Synchronized
    fun restoreItem(item: ClipboardItem) {
        if (items.none { it.id == item.id }) {
            items.add(item)
            if (!item.isPinned) {
                pruneUnpinnedLocked()
            }
            saveToPrefs()
        }
    }

    @Synchronized
    fun clearAllUnpinned() {
        items.removeAll { !it.isPinned }
        saveToPrefs()
    }

    @Synchronized
    private fun pruneUnpinnedLocked() {
        val unpinned = items.filter { !it.isPinned }
        if (unpinned.size > MAX_UNPINNED) {
            val sortedEarliest = unpinned.sortedBy { it.timestamp }
            val toRemoveCount = unpinned.size - MAX_UNPINNED
            val idsToRemove = sortedEarliest.take(toRemoveCount).map { it.id }.toSet()
            items.removeAll { it.id in idsToRemove }
        }
    }

    private fun loadFromPrefs() {
        items.clear()
        val jsonStr = prefs.getString(KEY_CLIPBOARD_ITEMS, null) ?: return
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val itemStr = jsonArray.get(i).toString()
                ClipboardItem.fromJsonString(itemStr)?.let { items.add(it) }
            }
        } catch (_: Throwable) {
            try {
                val trimmed = jsonStr.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    val body = trimmed.substring(1, trimmed.length - 1)
                    val objectRegex = """\{[^{}]*\}""".toRegex()
                    objectRegex.findAll(body).forEach { match ->
                        ClipboardItem.fromJsonString(match.value)?.let { items.add(it) }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun saveToPrefs() {
        val jsonStr = try {
            val jsonArray = JSONArray()
            items.forEach { jsonArray.put(JSONObject(it.toJsonString())) }
            jsonArray.toString()
        } catch (_: Throwable) {
            "[" + items.joinToString(",") { it.toJsonString() } + "]"
        }
        prefs.edit().putString(KEY_CLIPBOARD_ITEMS, jsonStr).apply()
    }

    companion object {
        const val PREFS_NAME = "ipa_board_prefs"
        const val KEY_CLIPBOARD_ITEMS = "clipboard_history_items"
        private const val KEY_SYNCED_SYSTEM_CLIP = "clipboard_synced_system_clip"
        const val MAX_UNPINNED = 20
    }
}
