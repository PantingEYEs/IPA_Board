package com.example.ipa_board.shortcuts

import android.content.Context
import android.content.SharedPreferences
import com.example.ipa_board.MultifunctionBarSettings
import com.example.ipa_board.SettingsConstants
import java.io.InputStream

/** Install the bundled Shortcut file once, only for a positively identified fresh configuration. */
object ShortcutDefaults {
    const val ASSET_PATH = "initialization/shortcuts/default.json"
    const val KEY_INITIALIZED = "shortcut_defaults_initialized_v1"
    internal const val KEY_PENDING = "shortcut_defaults_pending_v1"
    enum class Result { APPLIED, SKIPPED, FAILED, RESTORE_FAILED }

    private val shortcutKeys = setOf(SettingsConstants.KEY_SHIFT_SHORTCUTS, SettingsConstants.KEY_CTRL_SHORTCUTS,
        MultifunctionBarSettings.KEY_BINDINGS, ReplacementSettings.KEY)

    fun initialize(context: Context): Result = initialize(context) { context.assets.open(ASSET_PATH) }

    /** The injected source supports owned failure tests; production always uses the packaged file. */
    internal fun initialize(context: Context, openAsset: () -> InputStream): Result {
        return try {
            val preferences = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            // A normal Activity recreation must not wait for an unrelated confirmed import's lock.
            if (preferences.all[KEY_INITIALIZED] == true) return Result.SKIPPED
            synchronized(preferences) {
                val snapshot = preferences.all
                if (snapshot[KEY_INITIALIZED] == true) return@synchronized Result.SKIPPED
                // Presence is authoritative, including explicitly empty or malformed categories.
                val hasShortcuts = shortcutKeys.any(snapshot::containsKey)
                val pending = snapshot[KEY_PENDING] == true
                val oldMarkers = snapshot.containsKey(KEY_INITIALIZED) ||
                    snapshot.containsKey(KEY_PENDING) && !pending
                val existing = !pending && (snapshot.keys.any { it != KEY_PENDING } ||
                    hasExistingUserFiles(context))
                if (hasShortcuts || oldMarkers || existing) {
                    return@synchronized if (setMarkers(preferences, complete = true)) Result.SKIPPED else Result.FAILED
                }
                // Remember fresh eligibility before layout/kaomoji initialization can create old markers.
                // A failed import remains retryable, but any later saved Shortcut category wins.
                if (!pending && !setMarkers(preferences, complete = false)) return@synchronized Result.FAILED
                val plan = openAsset().use(ShortcutImport::read)
                when (ShortcutImport.apply(preferences, plan)) {
                    ShortcutImport.ApplyResult.APPLIED ->
                        if (setMarkers(preferences, complete = true)) Result.APPLIED else Result.FAILED
                    ShortcutImport.ApplyResult.FAILED -> Result.FAILED
                    ShortcutImport.ApplyResult.RESTORE_FAILED -> Result.RESTORE_FAILED
                }
            }
        } catch (_: Exception) {
            // Optional defaults must never prevent keyboard layouts or the settings page from opening.
            Result.FAILED
        }
    }

    private fun hasExistingUserFiles(context: Context): Boolean {
        val names = context.filesDir.list() ?: error("Cannot inspect initialization state")
        val dataFiles = setOf("kaomoji_config.json", "emoji_usage.json", "updated_emoji_test.txt")
        // Runtime artifacts such as ProfileInstaller's marker are not evidence of prior user setup.
        return names.any { name -> name == "layouts" || dataFiles.any { name == it || name.startsWith("$it.") } }
    }

    @android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
    private fun setMarkers(preferences: SharedPreferences, complete: Boolean): Boolean {
        val before = preferences.all
        val committed = try {
            preferences.edit().apply {
                if (complete) putBoolean(KEY_INITIALIZED, true).remove(KEY_PENDING)
                else putBoolean(KEY_PENDING, true)
            }.commit()
        } catch (_: Exception) { false }
        if (committed) return true
        // A failed disk commit may already have updated memory. Do not leave a false completion there.
        try {
            preferences.edit().apply {
                for (key in listOf(KEY_INITIALIZED, KEY_PENDING)) {
                    if (!before.containsKey(key)) remove(key) else when (val value = before[key]) {
                        is Boolean -> putBoolean(key, value)
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
                        else -> error("Unsupported initialization marker")
                    }
                }
            }.commit()
        } catch (_: Exception) { /* No configuration values were modified by this marker transaction. */ }
        return false
    }
}
