package com.example.ipa_board.ime

import org.junit.Assert.*
import org.junit.Test

class EngineRuntimeStatesTest {
    @Test fun observersReadWithoutStartingAnythingAndCanDetach() {
        val states = EngineRuntimeStates()
        val before = states.snapshot()
        var calls = 0
        val stop = states.observe { calls++ }
        assertEquals(before, states.snapshot())
        assertTrue(before.values.all { it == EngineRuntimeState.UNKNOWN })
        assertEquals(1, calls)
        val epoch = states.connecting(EngineBackend.RIME)
        assertTrue(states.accept(EngineBackend.RIME, epoch, "process-a", 0, EngineRuntimeState.UNINITIALIZED))
        assertTrue(states.accept(EngineBackend.RIME, epoch, "process-a", 1, EngineRuntimeState.INITIALIZING))
        assertTrue(states.accept(EngineBackend.RIME, epoch, "process-a", 2, EngineRuntimeState.READY))
        assertEquals(EngineRuntimeState.READY, states.snapshot()[EngineBackend.RIME])
        stop()
        val delivered = calls
        states.disconnected(EngineBackend.RIME)
        assertEquals(delivered, calls)
    }

    @Test fun previousConnectionsOldSequencesAndForeignInstancesCannotReviveState() {
        val states = EngineRuntimeStates()
        val old = states.connecting(EngineBackend.IPA)
        assertTrue(states.accept(EngineBackend.IPA, old, "old-process", 2, EngineRuntimeState.READY))
        assertFalse(states.accept(EngineBackend.IPA, old, "old-process", 1, EngineRuntimeState.INITIALIZING))
        states.disconnected(EngineBackend.IPA)
        assertFalse(states.accept(EngineBackend.IPA, old, "old-process", 99, EngineRuntimeState.READY))
        val current = states.connecting(EngineBackend.IPA)
        assertTrue(states.accept(EngineBackend.IPA, current, "new-process", 0, EngineRuntimeState.UNINITIALIZED))
        assertFalse(states.accept(EngineBackend.IPA, current, "old-process", 100, EngineRuntimeState.READY))
        assertEquals(EngineRuntimeState.UNINITIALIZED, states.snapshot()[EngineBackend.IPA])
        assertTrue(states.accept(EngineBackend.IPA, current, "new-process", 1, EngineRuntimeState.READY))
    }

    @Test fun unavailableAndUnknownAreDistinctAndOtherBackendsStayIndependent() {
        val states = EngineRuntimeStates()
        val epoch = states.connecting(EngineBackend.SEMANTIC)
        assertTrue(states.accept(EngineBackend.SEMANTIC, epoch, "semantic", 0, EngineRuntimeState.UNAVAILABLE))
        assertEquals(EngineRuntimeState.UNKNOWN, states.snapshot()[EngineBackend.RIME])
        states.disconnected(EngineBackend.SEMANTIC)
        assertEquals(EngineRuntimeState.UNKNOWN, states.snapshot()[EngineBackend.SEMANTIC])
        states.disconnected(EngineBackend.SEMANTIC, unavailable = true)
        assertEquals(EngineRuntimeState.UNAVAILABLE, states.snapshot()[EngineBackend.SEMANTIC])
    }

    @Test fun malformedStateIdentityCannotReplaceAnObservation() {
        val states = EngineRuntimeStates()
        val epoch = states.connecting(EngineBackend.LATINIME)
        assertFalse(states.accept(EngineBackend.LATINIME, epoch, "", 0, EngineRuntimeState.READY))
        assertFalse(states.accept(EngineBackend.LATINIME, epoch, "x".repeat(129), 0, EngineRuntimeState.READY))
        assertFalse(states.accept(EngineBackend.LATINIME, epoch, "process", -1, EngineRuntimeState.READY))
        assertEquals(EngineRuntimeState.CONNECTING, states.snapshot()[EngineBackend.LATINIME])
    }
}
