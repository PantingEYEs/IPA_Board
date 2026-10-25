package com.example.ipa_board.ime

import android.content.SharedPreferences

/** Changes configured switches only; the existing coordinator owns runtime changes and stale replies. */
internal class PageEngineBindingController(private val preferences: SharedPreferences) {
    enum class Result { UNCHANGED, APPLIED, STORAGE_FAILED }
    private val entries = PageEngineEntryPolicy()

    fun onPage(page: String?, remoteActive: Boolean, readBinding: () -> PageEngineBinding?): Result = synchronized(preferences) {
        // A persisted snapshot also protects callers while Remote is recovering from a failed write.
        if (!entries.enter(page, remoteActive || preferences.contains(RemoteKeyboardMode.KEY_SNAPSHOT))) return Result.UNCHANGED
        val binding = readBinding() ?: return Result.UNCHANGED
        val values = EngineFeature.entries.mapNotNull { feature -> binding.states[feature.key]?.let { feature to it } }.toMap()
        if (values.isEmpty()) return Result.UNCHANGED // New/unknown feature IDs are retained but never guessed.
        if (EngineSettings.applyStates(preferences, values)) Result.APPLIED else Result.STORAGE_FAILED
    }
}
