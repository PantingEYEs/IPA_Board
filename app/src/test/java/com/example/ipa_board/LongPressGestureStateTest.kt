package com.example.ipa_board

import org.junit.Assert.*
import org.junit.Test

class LongPressGestureStateTest {
    private val original = LongPressItem(text = "ɑ")
    private val other = LongPressItem(text = "æ")

    @Test fun heldAndReleasedSelectionUsesOneLifecycle() {
        for (repeat in listOf(false, true)) {
            val state = LongPressGestureState()
            state.activate(original, repeat)
            assertTrue(state.activated)
            assertEquals(repeat, state.repeating)
            assertEquals(if (repeat) null else original, state.release())
            // Horizontal selection/inside-popup movement changes only the release target.
            state.select(other)
            assertEquals(repeat, state.repeating)
            assertEquals(if (repeat) null else other, state.release())
        }
    }

    @Test fun cancellationStopsHeldActionAndLatchesThroughReturnAndRelease() {
        for (repeat in listOf(false, true)) {
            val state = LongPressGestureState()
            state.activate(original, repeat)
            state.cancel()
            assertFalse(state.repeating)
            assertNull(state.release())
            // A returning pointer must not revive the gesture, even over another item.
            state.select(other)
            state.activate(other, repeat)
            assertTrue(state.canceled)
            assertFalse(state.repeating)
            assertNull(state.release())
        }
    }

    @Test fun noItemSelectedCannotCommitAStoredClassicBinding() {
        val state = LongPressGestureState()
        assertFalse(state.activated)
        assertNull(state.release())
    }
}
