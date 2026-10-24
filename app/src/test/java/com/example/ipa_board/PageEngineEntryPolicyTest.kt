package com.example.ipa_board

import com.example.ipa_board.ime.PageEngineEntryPolicy
import org.junit.Assert.*
import org.junit.Test

class PageEngineEntryPolicyTest {
    @Test fun onlyEntryNotRedrawTriggersApplication() {
        val policy = PageEngineEntryPolicy()
        assertTrue(policy.enter("a",false))
        repeat(8) { assertFalse(policy.enter("a",false)) }
        assertTrue(policy.enter("b",false))
        assertTrue(policy.enter("a",false))
    }
    @Test fun remoteTracksDestinationAndExitAppliesOnceEvenWithoutAPageChange() {
        val policy = PageEngineEntryPolicy()
        assertTrue(policy.enter("a",false))
        assertFalse(policy.enter("a",true))
        assertFalse(policy.enter("b",true))
        assertFalse(policy.enter("c",true))
        assertTrue(policy.enter("c",false))
        assertFalse(policy.enter("c",false))
        assertFalse(policy.enter("c",true))
        assertTrue(policy.enter("c",false))
    }
    @Test fun emptyGroupsAndColdStartRemoteDoNotInventAPage() {
        val policy = PageEngineEntryPolicy()
        assertFalse(policy.enter(null,false))
        assertTrue(policy.enter("a",false))
        assertFalse(policy.enter(null,true))
        assertFalse(policy.enter(null,false))
        assertTrue(policy.enter("a",false))
        val remote = PageEngineEntryPolicy()
        assertFalse(remote.enter("a",true))
        assertTrue(remote.enter("a",false))
    }
}
