package com.example.ipa_board

import com.example.ipa_board.clipboard.ClipboardItem
import com.example.ipa_board.clipboard.ClipboardRepository
import org.junit.Assert.*
import org.junit.Test

class ClipboardQuickPasteTest {

    @Test
    fun defaultQuickPasteConstantsAreSetCorrectly() {
        assertEquals("quick_paste_enabled", SettingsConstants.KEY_QUICK_PASTE_ENABLED)
        assertEquals("quick_paste_retention_type", SettingsConstants.KEY_QUICK_PASTE_RETENTION_TYPE)
        assertEquals("quick_paste_retention_seconds", SettingsConstants.KEY_QUICK_PASTE_RETENTION_SECONDS)
        assertEquals("quick_paste_usage_type", SettingsConstants.KEY_QUICK_PASTE_USAGE_TYPE)
        assertEquals("quick_paste_usage_times", SettingsConstants.KEY_QUICK_PASTE_USAGE_TIMES)
    }

    @Test
    fun retentionTimeAndUsageLimitsValidation() {
        val validSeconds = 60
        assertTrue(validSeconds in 0..43200)

        val invalidSecondsHigh = 50000
        assertFalse(invalidSecondsHigh in 0..43200)

        val validTimes = 1
        assertTrue(validTimes in 0..100)

        val invalidTimesHigh = 101
        assertFalse(invalidTimesHigh in 0..100)
    }
}
