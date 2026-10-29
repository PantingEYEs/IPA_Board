package com.example.ipa_board.shortcuts

import android.content.Context
import com.example.ipa_board.SettingsConstants
import org.json.JSONArray
import org.json.JSONObject

/** Versioned ordered category for future Shortcut export (#33). Absence means no rules. */
object ReplacementSettings {
    const val KEY = "shortcut_replacement_text_v1"
    const val CATEGORY = "replacementText"
    private fun prefs(context: Context) = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
    fun load(context: Context): List<ReplacementRule> {
        val stored = prefs(context).all[KEY]
        require(stored == null || stored is String)
        return decode(stored as? String)
    }
    @android.annotation.SuppressLint("ApplySharedPref", "UseKtx") // Commit outcome controls UI failure feedback and rollback.
    fun save(context: Context, rules: List<ReplacementRule>): Boolean {
        val store = prefs(context)
        val before = store.getString(KEY, null)
        if (store.edit().putString(KEY, encode(rules)).commit()) return true
        store.edit().apply { if (before == null) remove(KEY) else putString(KEY, before) }.commit()
        return false
    }
    fun encode(rules: List<ReplacementRule>): String {
        require(rules.map { it.id }.distinct().size == rules.size)
        return JSONObject().put("schemaVersion", 1).put("category", CATEGORY).put("rules", JSONArray().apply {
            rules.forEach { put(JSONObject().put("id", it.id).put("source", it.source).put("replacement", it.replacement)) }
        }).toString()
    }
    fun decode(value: String?): List<ReplacementRule> {
        if (value == null) return emptyList()
        val root = JSONObject(value)
        require(root.getInt("schemaVersion") == 1 && root.getString("category") == CATEGORY)
        val rows = root.getJSONArray("rules")
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            ReplacementRule(row.getString("id"), row.getString("source"), row.getString("replacement"))
        }.also { require(it.map { row -> row.id }.distinct().size == it.size) }
    }
}
