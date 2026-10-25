package com.example.ipa_board

import android.content.Context
import org.json.JSONObject
import androidx.core.content.edit

/** A toolbar position has one binding; keyboard-page gestures are deliberately independent. */
data class MultifunctionBarBinding(
    val action: KeyAction,
    val text: String = "",
    val textBehavior: TextBehavior = TextBehavior.LITERAL
) {
    init { require(text.length <= 1000) { "Maximum 1000 characters per toolbar entry" } }
    fun asKeySlot() = KeySlot(1f, text = if (action == KeyAction.TEXT) text else "",
        action = action, textBehavior = textBehavior)
}

object MultifunctionBarSettings {
    const val KEY_BINDINGS = "multifunction_bar_bindings_v1"

    fun load(context: Context): Map<MultifunctionBarSlot, MultifunctionBarBinding> {
        val preferences = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        return decode(preferences.all[KEY_BINDINGS] as? String)
    }

    fun defaults(): Map<MultifunctionBarSlot, MultifunctionBarBinding> =
        MultifunctionBarSlot.entries.associateWith { MultifunctionBarBinding(it.defaultAction) }

    /** Bad/removed actions fall back per position without discarding other valid assignments. */
    internal fun decode(value: String?): Map<MultifunctionBarSlot, MultifunctionBarBinding> {
        val root = try { value?.let(::JSONObject) } catch (_: Exception) { null }
        return MultifunctionBarSlot.entries.associateWith { slot ->
            try {
                val item = root?.getJSONObject(slot.wireValue) ?: return@associateWith MultifunctionBarBinding(slot.defaultAction)
                val action = KeyAction.fromWireValue(item.getString("action"))
                MultifunctionBarBinding(action,
                    if (action == KeyAction.TEXT) item.getString("text") else "",
                    if (action == KeyAction.TEXT) TextBehavior.fromWireValue(item.optString("textBehavior", "literal")) else TextBehavior.LITERAL)
            } catch (_: Exception) {
                MultifunctionBarBinding(slot.defaultAction)
            }
        }
    }

    /** Update only this position, preserving other assignments and future/unknown fields. */
    fun save(context: Context, slot: MultifunctionBarSlot, binding: MultifunctionBarBinding) {
        val preferences = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val root = try { JSONObject(preferences.all[KEY_BINDINGS] as? String ?: "{}") }
            catch (_: Exception) { JSONObject() }
        val item = root.optJSONObject(slot.wireValue) ?: JSONObject()
        item.put("action", binding.action.wireValue).put("text", binding.text)
            .put("textBehavior", binding.textBehavior.wireValue)
        root.put(slot.wireValue, item)
        preferences.edit { putString(KEY_BINDINGS, root.toString()) }
    }
}
