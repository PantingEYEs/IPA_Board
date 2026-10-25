package com.example.ipa_board.ime

import org.json.JSONObject

/** Optional page-owned configuration. null means unbound; false values are explicit disabling. */
data class PageEngineBinding(val states: Map<String, Boolean>) {
    init {
        require(states.size <= 128 && states.keys.all { it.isNotBlank() && it.length <= 128 }) { "Invalid engine binding" }
    }
    fun toJsonObject() = JSONObject().put("version", 1).put("states", JSONObject(states))

    companion object {
        fun fromJsonObject(value: JSONObject?): PageEngineBinding? = try {
            if (value == null || value.getInt("version") != 1) null else {
                val states = value.getJSONObject("states")
                val values = linkedMapOf<String, Boolean>()
                for (key in states.keys()) {
                    val enabled = states.get(key)
                    require(enabled is Boolean) { "Invalid engine state" }
                    values[key] = enabled
                }
                PageEngineBinding(values)
            }
        } catch (_: Exception) { null } // Invalid optional metadata must not make the keyboard unusable.
    }
}

/** Page entry, not redraw or editing, is the trigger. Track pages even while Remote is active. */
internal class PageEngineEntryPolicy {
    private var observed = false
    private var page: String? = null
    private var remote = false

    fun enter(nextPage: String?, remoteActive: Boolean): Boolean {
        val apply = !remoteActive && nextPage != null && (!observed || page != nextPage || remote)
        observed = true
        page = nextPage
        remote = remoteActive
        return apply
    }
}
