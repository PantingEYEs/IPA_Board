package com.example.ipa_board

import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.BluetoothRemoteEditorMode
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.RemoteKeyboardMode
import org.junit.Assert.*
import org.junit.Test

/** Dedicated preferences only; no Bluetooth, other IMEs or global editor settings are changed. */
class BluetoothRemoteEngineModeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun ownedRemoteEditorEntersOnceAndPreservesExactOriginalConfiguration() = withPreferences { prefs ->
        val feature = EngineFeature.entries.first()
        assertTrue(prefs.edit().putBoolean(feature.key, true).putString("unrelated", "keep").commit())
        val original = prefs.all.toMap()
        val mode = RemoteKeyboardMode(prefs)
        val policy = BluetoothRemoteEditorMode()
        val marker = BluetoothRemoteEditorMode.PREFIX + "screen-1"
        assertEquals(RemoteKeyboardMode.Result.ENABLED,
            policy.enter(context.packageName, context.packageName, marker, mode))
        assertTrue(EngineFeature.entries.all { prefs.getBoolean(it.key, true).not() })
        val snapshot = prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null)
        assertTrue(prefs.edit().putBoolean(feature.key, true).commit())
        assertNull(policy.enter(context.packageName, context.packageName, marker, mode))
        assertTrue(prefs.getBoolean(feature.key, false))
        assertEquals(snapshot, prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null))

        assertEquals(RemoteKeyboardMode.Result.RESTORED, mode.restore())
        assertEquals(original, prefs.all)
        // Connection recreation in the same screen cannot undo an explicit user restoration.
        assertNull(policy.enter(context.packageName, context.packageName, marker, mode))
        assertEquals(original, prefs.all)
        assertEquals(RemoteKeyboardMode.Result.ENABLED,
            policy.enter(context.packageName, context.packageName,
                BluetoothRemoteEditorMode.PREFIX + "screen-2", mode))
        assertEquals(RemoteKeyboardMode.Result.RESTORED, mode.restore())
        assertEquals(original, prefs.all)
        assertNull(policy.enter(context.packageName, context.packageName, marker, mode))
        assertEquals(original, prefs.all)
    }

    @Test fun otherEditorsCannotEnterAndAnExistingSnapshotIsNeverReplaced() = withPreferences { prefs ->
        val mode = RemoteKeyboardMode(prefs)
        val policy = BluetoothRemoteEditorMode()
        val marker = BluetoothRemoteEditorMode.PREFIX + "screen-1"
        for ((pkg, options) in listOf("another.app" to marker, context.packageName to null,
            context.packageName to "unrelated", context.packageName to BluetoothRemoteEditorMode.PREFIX)) {
            assertNull(policy.enter(pkg, context.packageName, options, mode))
            assertTrue(prefs.all.isEmpty())
        }
        assertEquals(RemoteKeyboardMode.Result.ENABLED, mode.toggle())
        val snapshot = prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null)
        val feature = EngineFeature.entries.first()
        assertTrue(prefs.edit().putBoolean(feature.key, true).commit())
        assertNull(policy.enter(context.packageName, context.packageName, marker, mode))
        assertEquals(snapshot, prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null))
        assertTrue(prefs.getBoolean(feature.key, false))
    }

    private fun withPreferences(body: (android.content.SharedPreferences) -> Unit) {
        val prefs = context.getSharedPreferences("bluetooth-engine-mode-test-owned", 0)
        assertTrue(prefs.edit().clear().commit())
        try { body(prefs) } finally { assertTrue(prefs.edit().clear().commit()) }
    }
}
