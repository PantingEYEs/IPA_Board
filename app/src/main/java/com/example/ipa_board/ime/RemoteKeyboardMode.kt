package com.example.ipa_board.ime

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Temporary engine override for Remote Keyboard. The snapshot shares the engine preferences so
 * entering/leaving the mode cannot persist an engine override without its matching recovery state.
 * Creating a controller only reads state; it never reapplies the override or replaces a snapshot.
 */
class RemoteKeyboardMode(private val preferences: SharedPreferences) {
    enum class Result { ENABLED, RESTORED, UNCHANGED, STORAGE_FAILED, INVALID_STATE }

    // SharedPreferences.commit() can fail after changing its in-memory map. Keep a fallback until
    // the original snapshot has been restored successfully, even if repairing that map also fails.
    private var recoverySnapshot: String? = null

    val active: Boolean
        get() = synchronized(preferences) {
            recoverySnapshot != null || preferences.contains(KEY_SNAPSHOT)
        }

    fun toggle(): Result = synchronized(preferences) {
        val current = readPreferences() ?: return@synchronized Result.STORAGE_FAILED
        if (recoverySnapshot != null || current.containsKey(KEY_SNAPSHOT)) {
            restoreLocked(current)
        } else {
            enableLocked(current)
        }
    }

    fun restore(): Result = synchronized(preferences) {
        val current = readPreferences() ?: return@synchronized Result.STORAGE_FAILED
        restoreLocked(current)
    }

    private fun enableLocked(current: Map<String, *>): Result {
        // A wrong preference type is not an engine state that can be restored safely.
        if (EngineFeature.entries.any { current.containsKey(it.key) && current[it.key] !is Boolean }) {
            return Result.INVALID_STATE
        }
        val snapshot = encodeSnapshot(current)
        recoverySnapshot = snapshot
        val saved = commit {
            putString(KEY_SNAPSHOT, snapshot)
            EngineFeature.entries.forEach { putBoolean(it.key, false) }
        }
        return if (saved) {
            recoverySnapshot = null
            Result.ENABLED
        } else {
            preserveRecovery(snapshot)
            Result.STORAGE_FAILED
        }
    }

    private fun restoreLocked(current: Map<String, *>): Result {
        if (recoverySnapshot == null && !current.containsKey(KEY_SNAPSHOT)) return Result.UNCHANGED
        val snapshot = recoverySnapshot ?: current[KEY_SNAPSHOT] as? String ?: return Result.INVALID_STATE
        val states = decodeSnapshot(snapshot) ?: return Result.INVALID_STATE
        recoverySnapshot = snapshot
        val saved = commit {
            // Unknown/removed features are deliberately untouched. Features added after the
            // snapshot was captured also keep their current settings rather than guessed values.
            EngineFeature.entries.forEach { feature ->
                if (states.containsKey(feature.key)) {
                    states[feature.key]?.let { putBoolean(feature.key, it) } ?: remove(feature.key)
                }
            }
            remove(KEY_SNAPSHOT)
        }
        return if (saved) {
            recoverySnapshot = null
            Result.RESTORED
        } else {
            preserveRecovery(snapshot)
            Result.STORAGE_FAILED
        }
    }

    private fun preserveRecovery(snapshot: String) {
        // In particular, a failed restore must not leave the in-memory map without an exit path.
        // This repair never changes engine values or replaces the original captured settings.
        commit { putString(KEY_SNAPSHOT, snapshot) }
        // Prefer shared state as soon as the map contains the recovery record. Another controller
        // may then complete restoration without leaving this instance's fallback falsely active.
        if (readPreferences()?.get(KEY_SNAPSHOT) == snapshot) recoverySnapshot = null
    }

    private fun readPreferences(): Map<String, *>? = try {
        preferences.all
    } catch (_: RuntimeException) {
        null
    }

    private fun commit(update: SharedPreferences.Editor.() -> Unit): Boolean = try {
        preferences.edit().apply(update).commit()
    } catch (_: RuntimeException) {
        false
    }

    private fun encodeSnapshot(current: Map<String, *>): String {
        val features = JSONArray()
        val values = JSONObject()
        EngineFeature.entries.forEach {
            features.put(it.key)
            values.put(it.key, if (current.containsKey(it.key)) current[it.key] else JSONObject.NULL)
        }
        return JSONObject().put("version", SNAPSHOT_VERSION).put("features", features)
            .put("values", values).toString()
    }

    private fun decodeSnapshot(snapshot: String): Map<String, Boolean?>? {
        return try {
            val root = JSONObject(snapshot)
            if (root.getInt("version") != SNAPSHOT_VERSION) return null
            val features = root.getJSONArray("features")
            val values = root.getJSONObject("values")
            if (features.length() == 0 || features.length() != values.length()) return null
            val states = linkedMapOf<String, Boolean?>()
            for (index in 0 until features.length()) {
                val key = features.get(index) as? String ?: return null
                if (key.isEmpty() || states.containsKey(key) || !values.has(key)) return null
                val value = values.get(key)
                states[key] = when (value) {
                    JSONObject.NULL -> null
                    is Boolean -> value
                    else -> return null
                }
            }
            if (EngineFeature.entries.none { states.containsKey(it.key) }) return null
            states
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val KEY_SNAPSHOT = "remote_keyboard_engine_snapshot"
        private const val SNAPSHOT_VERSION = 1
    }
}
