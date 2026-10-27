package com.example.ipa_board

import com.example.ipa_board.clipboard.ClipboardObservation
import org.junit.Assert.*
import org.junit.Test

class ClipboardObservationTest {
    @Test fun enabledServiceCapturesBeforeFirstOpeningAndAcrossHiddenIntervals() {
        val source = Source()
        val observation = source.observation
        observation.start(true)
        source.copy("before first opening")
        observation.setInputViewShown(true)
        observation.setInputViewShown(false)
        source.copy("hidden A")
        source.copy("hidden B")
        observation.setInputViewShown(true)
        assertEquals(listOf("before first opening", "hidden A", "hidden B"), source.captured)
        assertEquals(1, source.subscriptions)
        assertEquals(0, source.unsubscriptions)
    }

    @Test fun disabledSyncRetainsVisibleRecordingButStopsWhenHidden() {
        val source = Source()
        source.observation.start(false)
        source.copy("hidden")
        source.observation.setInputViewShown(true)
        source.copy("visible")
        source.observation.setInputViewShown(false)
        source.copy("hidden again")
        assertEquals(listOf("visible"), source.captured)
        assertEquals(1, source.subscriptions)
        assertEquals(1, source.unsubscriptions)
    }

    @Test fun savedSettingChangesTakeEffectWithoutReopening() {
        val source = Source()
        source.observation.start(false)
        source.observation.setSyncEnabled(true)
        source.copy("enabled while hidden")
        source.observation.setSyncEnabled(false)
        source.copy("disabled while hidden")
        source.observation.setInputViewShown(true)
        source.observation.setSyncEnabled(true)
        source.observation.setSyncEnabled(false)
        source.copy("still visible")
        source.observation.setInputViewShown(false)
        source.copy("hidden")
        assertEquals(listOf("enabled while hidden", "still visible"), source.captured)
        assertEquals(2, source.subscriptions)
        assertEquals(2, source.unsubscriptions)
    }

    @Test fun repeatedViewCallbacksDoNotDuplicateListenersAndDestroyStopsAllRecording() {
        val source = Source()
        source.observation.start(true)
        repeat(3) {
            source.observation.setInputViewShown(true)
            source.observation.setInputViewShown(false)
        }
        source.observation.stop()
        source.observation.stop()
        source.observation.setSyncEnabled(true)
        source.observation.setInputViewShown(true)
        source.copy("after destroy")
        assertTrue(source.captured.isEmpty())
        assertFalse(source.observation.isListening)
        assertEquals(1, source.subscriptions)
        assertEquals(1, source.unsubscriptions)
    }

    private class Source {
        var subscriptions = 0
        var unsubscriptions = 0
        val captured = mutableListOf<String>()
        private var listener: ((String) -> Unit)? = null
        val observation = ClipboardObservation(
            subscribe = {
                check(listener == null)
                subscriptions++
                listener = { captured.add(it) }
            },
            unsubscribe = { unsubscriptions++; listener = null }
        )
        fun copy(text: String) { listener?.invoke(text) }
    }
}
