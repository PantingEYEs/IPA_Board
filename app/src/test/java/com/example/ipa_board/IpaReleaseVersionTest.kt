package com.example.ipa_board
import com.example.ipa_board.ipa.IpaReleaseVersion
import org.junit.Assert.*
import org.junit.Test
class IpaReleaseVersionTest {
    @Test fun versionsUseSemanticOrderingRatherThanTextOrPublishTime() {
        val values = listOf("v0.0.4-dev.2", "v0.0.4-dev.10", "v0.0.4-rc.1", "v0.0.4", "v0.0.5-dev.1")
        assertEquals(values, values.reversed().sortedBy { IpaReleaseVersion.parse(it)!! })
        assertFalse(IpaReleaseVersion.newer("v0.0.4", "v0.0.5-dev.1"))
        assertFalse(IpaReleaseVersion.newer("v0.0.4", "v0.0.4"))
        assertTrue(IpaReleaseVersion.newer("v0.0.4", "v0.0.4-dev"))
        assertEquals(0, IpaReleaseVersion.parse("1.0.0+first")!!.compareTo(IpaReleaseVersion.parse("1.0.0+second")!!))
        for (value in listOf("main", "v1.2", "v01.2.3", "1.0.0-01")) assertNull(IpaReleaseVersion.parse(value))
    }
}
