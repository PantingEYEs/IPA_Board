package com.example.ipa_board

import android.content.Context
import android.content.SharedPreferences
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.CompositionController
import com.example.ipa_board.ime.ImeChromeView
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class QuickPasteStatusTest {
    @Test fun exhaustedShortcutClearsClipboardCallbackAndCannotPasteAgain() = withFixture { fixture ->
        fixture.state(System.currentTimeMillis(), 0)
        var staleClicks = 0
        fixture.view.status.text = "Shortcut"
        fixture.view.onStatusClick = { staleClicks++ }
        fixture.update()
        fixture.assertInactive()
        fixture.view.status.performClick()
        assertEquals(0, staleClicks)
        // A subsequent refresh with no state must also remove any stale handler.
        fixture.view.onStatusClick = { staleClicks++ }
        fixture.update()
        fixture.assertInactive()
    }

    @Test fun expiredShortcutCannotCommitCompositionThroughAPreviouslyCapturedCallback() = withFixture { fixture ->
        fixture.prefs.edit().putString(SettingsConstants.KEY_QUICK_PASTE_RETENTION_TYPE, "CUSTOM")
            .putInt(SettingsConstants.KEY_QUICK_PASTE_RETENTION_SECONDS, 120).commit()
        val state = fixture.state(System.currentTimeMillis() - 61_000, 2)
        fixture.update()
        val staleCallback = requireNotNull(fixture.view.onStatusClick)
        fixture.composition.input("hel")
        val revision = fixture.composition.revision

        // Tightening retention expires the existing item without timing sleeps or clipboard access.
        fixture.prefs.edit().putInt(SettingsConstants.KEY_QUICK_PASTE_RETENTION_SECONDS, 60).commit()
        fixture.update()
        fixture.assertInactive()
        staleCallback()
        assertEquals("hel", fixture.composition.raw)
        assertEquals(revision, fixture.composition.revision)
        assertEquals(2, fixture.remainingUses(state))
    }

    @Test fun disablingQuickPasteInvalidatesCallbacksWithoutCommittingTheCurrentComposition() = withFixture { fixture ->
        fixture.prefs.edit().putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, true).commit()
        val state = fixture.state(System.currentTimeMillis(), 2)
        fixture.update()
        val staleCallback = requireNotNull(fixture.view.onStatusClick)
        fixture.composition.input("hel")
        val revision = fixture.composition.revision

        fixture.prefs.edit().putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, false).commit()
        fixture.update()
        fixture.assertInactive()
        staleCallback()
        assertEquals("hel", fixture.composition.raw)
        assertEquals(revision, fixture.composition.revision)
        assertEquals(2, fixture.remainingUses(state))
        fixture.prefs.edit().putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, true).commit()
        fixture.update()
        fixture.assertInactive()
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefName = "quick-paste-test-${UUID.randomUUID()}"
        try {
            instrumentation.runOnMainSync {
                test(Fixture(context, context.getSharedPreferences(prefName, Context.MODE_PRIVATE)))
            }
        } finally {
            context.deleteSharedPreferences(prefName)
        }
    }

    /** No service lifecycle, system clipboard access or actual focused editor is needed here. */
    private class Fixture(context: Context, val prefs: SharedPreferences) {
        private val service = IpaBoardService()
        val view = ImeChromeView(context).apply { showPanel(ImeChromeView.Panel.CLIPBOARD) }
        private val editor = EditText(context)
        private val connection = editor.onCreateInputConnection(EditorInfo())!!
        val composition = CompositionController({ connection }, { _, _ -> }, {})
        private val stateType = IpaBoardService::class.java.declaredClasses.single { it.simpleName == "QuickPasteState" }
        private val update = IpaBoardService::class.java.getDeclaredMethod("updateQuickPasteStatus").apply { isAccessible = true }

        init {
            field("chrome", view)
            field("prefs", prefs)
            field("composition", composition)
        }

        fun state(timestamp: Long, uses: Int): Any = stateType.getDeclaredConstructor(
            String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance("Shortcut", timestamp, uses).also {
                field("quickPasteState", it)
            }

        fun update() { update.invoke(service) }

        fun remainingUses(state: Any) = stateType.getDeclaredField("remainingUses")
            .apply { isAccessible = true }.getInt(state)

        fun assertInactive() {
            assertNull(view.onStatusClick)
            assertFalse(view.status.isClickable)
            assertEquals("Clipboard", view.status.text.toString())
        }

        private fun field(name: String, value: Any?) {
            IpaBoardService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
    }
}
