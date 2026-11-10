package com.example.ipa_board.shortcuts

import android.content.Context
import com.example.ipa_board.KeyAction
import com.example.ipa_board.MultifunctionBarSettings
import com.example.ipa_board.MultifunctionBarSlot
import com.example.ipa_board.SettingsConstants
import com.example.ipa_board.TextBehavior
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** A user-facing Shortcut export, deliberately limited to the four Shortcut categories. */
object ShortcutExport {
    const val FORMAT = "ipa-board-shortcuts"
    const val SCHEMA_VERSION = 1

    /** Capture all categories from one consistent preferences snapshot without changing settings. */
    fun capture(context: Context): String {
        val snapshot = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE).all
        return encode(snapshot)
    }

    /**
     * Preserve saved wire values and extension fields instead of using the forgiving runtime readers.
     * Invalid stored categories fail the whole export; absence alone supplies defaults.
     */
    fun encode(snapshot: Map<String, *>): String {
        val categories = JSONObject()
            .put("shift", shortcutMap(snapshot, SettingsConstants.KEY_SHIFT_SHORTCUTS, "shift"))
            .put("ctrl", shortcutMap(snapshot, SettingsConstants.KEY_CTRL_SHORTCUTS, "ctrl"))
            .put("multifunctionBar", multifunctionBar(snapshot))
            .put(ReplacementSettings.CATEGORY, replacementText(snapshot))
        val document = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("categories", categories)
        return escapeSurrogates(document.toString(2))
    }

    private fun shortcutMap(snapshot: Map<String, *>, key: String, category: String): JSONObject {
        val mapping = storedObject(snapshot, key, category) ?: return JSONObject()
        val keys = mapping.keys()
        while (keys.hasNext()) {
            requireString(mapping, keys.next(), category, allowEmpty = false)
        }
        return mapping
    }

    private fun multifunctionBar(snapshot: Map<String, *>): JSONObject {
        val category = "multifunctionBar"
        val bindings = storedObject(snapshot, MultifunctionBarSettings.KEY_BINDINGS, category) ?: JSONObject()
        for (slot in MultifunctionBarSlot.entries) {
            if (!bindings.has(slot.wireValue)) {
                bindings.put(slot.wireValue, JSONObject()
                    .put("action", slot.defaultAction.wireValue)
                    .put("text", "")
                    .put("textBehavior", TextBehavior.LITERAL.wireValue))
                continue
            }
            val binding = bindings.opt(slot.wireValue) as? JSONObject ?: invalid(category)
            val action = requireString(binding, "action", category, allowEmpty = false)
            if (binding.has("text")) {
                requireString(binding, "text", category)
            } else {
                if (action == KeyAction.TEXT.wireValue) invalid(category)
                binding.put("text", "")
            }
            if (binding.has("textBehavior")) {
                requireString(binding, "textBehavior", category, allowEmpty = false)
            } else {
                binding.put("textBehavior", TextBehavior.LITERAL.wireValue)
            }
        }
        return bindings
    }

    private fun replacementText(snapshot: Map<String, *>): JSONObject {
        val category = ReplacementSettings.CATEGORY
        val replacements = storedObject(snapshot, ReplacementSettings.KEY, category)
            ?: return JSONObject(ReplacementSettings.encode(emptyList()))
        // A future category schema may have different required fields and must not be relabeled as v1.
        val version = replacements.opt("schemaVersion")
        if (version !is Int || version != 1 || replacements.opt("category") != category) invalid(category)
        val rules = replacements.opt("rules") as? JSONArray ?: invalid(category)
        val ids = mutableSetOf<String>()
        for (index in 0 until rules.length()) {
            val rule = rules.opt(index) as? JSONObject ?: invalid(category)
            val id = requireString(rule, "id", category, allowEmpty = false)
            if (!ids.add(id)) invalid(category)
            requireString(rule, "source", category, allowEmpty = false)
            requireString(rule, "replacement", category, allowEmpty = false)
        }
        return replacements
    }

    private fun storedObject(snapshot: Map<String, *>, key: String, category: String): JSONObject? {
        if (!snapshot.containsKey(key)) return null
        val stored = snapshot[key] as? String ?: invalid(category)
        return try {
            val tokens = JSONTokener(stored)
            val value = tokens.nextValue() as? JSONObject ?: invalid(category)
            if (tokens.nextClean() != '\u0000') invalid(category)
            value
        } catch (_: JSONException) {
            // Parser messages can contain the private configuration; expose only the category name.
            invalid(category)
        }
    }

    private fun requireString(
        value: JSONObject,
        key: String,
        category: String,
        allowEmpty: Boolean = true,
    ): String {
        val text = value.opt(key) as? String ?: invalid(category)
        if (!allowEmpty && text.isEmpty()) invalid(category)
        return text
    }

    private fun invalid(category: String): Nothing =
        throw IllegalArgumentException("Invalid $category Shortcut configuration")

    private fun escapeSurrogates(json: String): String = buildString(json.length) {
        // Escapes preserve every UTF-16 unit, including isolated surrogates, when saved as UTF-8.
        for (character in json) {
            if (character in '\uD800'..'\uDFFF') {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}
