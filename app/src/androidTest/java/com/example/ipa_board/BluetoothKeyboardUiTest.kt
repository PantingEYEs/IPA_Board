package com.example.ipa_board

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.bluetooth.BluetoothKeyboardActivity
import com.example.ipa_board.bluetooth.BluetoothKeyboardRuntime
import com.example.ipa_board.bluetooth.BluetoothKeyboardSession
import com.example.ipa_board.bluetooth.BluetoothKeyboardSession.Phase
import com.example.ipa_board.bluetooth.HidKeyboardBackend
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Connection
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Host
import com.example.ipa_board.bluetooth.HidKeyboardReport
import com.example.ipa_board.bluetooth.RemoteKeyboardEditText
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.PinyinScheme
import com.example.ipa_board.ime.PinyinSettings
import com.example.ipa_board.ime.RemoteKeyboardMode
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real owned screen, fake radio, and synthetic drafts; never grants Bluetooth permissions or opens an IME. */
@SdkSuppress(minSdkVersion = 28)
class BluetoothKeyboardUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val host = Host("02:00:00:00:00:35", "Owned keyboard receiver")

    private inner class Backend : HidKeyboardBackend {
        lateinit var callback: HidKeyboardBackend.Callback
        val reports = mutableListOf<ByteArray>()
        val connections = mutableListOf<Host>()
        val disconnections = mutableListOf<Host>()
        var closeCount = 0
        var failNextKeyDown = false

        override fun start(callback: HidKeyboardBackend.Callback) { this.callback = callback }
        override fun bondedDevices() = listOf(host)
        override fun connect(host: Host): Boolean { connections += host; return true }
        override fun disconnect(host: Host): Boolean { disconnections += host; return true }
        override fun send(host: Host, report: ByteArray): Boolean {
            assertEquals(this@BluetoothKeyboardUiTest.host, host)
            reports += report.copyOf()
            if (failNextKeyDown && report[2] != 0.toByte()) {
                failNextKeyDown = false
                return false
            }
            return true
        }
        override fun close() { closeCount++ }
        fun keyDowns() = reports.filter { it[2] != 0.toByte() }
    }

    private inner class Fixture {
        val backends = mutableListOf<Backend>()
        val backend get() = backends.last()
        val session = BluetoothKeyboardSession(
            createBackend = { Backend().also { backends += it } },
            // No real startup timeout is necessary: every state transition is driven by the test.
            schedule = { _, _ -> {} },
            changed = BluetoothKeyboardRuntime::notifyState
        )
        lateinit var scenario: ActivityScenario<BluetoothKeyboardActivity>

        fun onUi(body: (BluetoothKeyboardActivity, RemoteKeyboardEditText) -> Unit) {
            scenario.onActivity { activity ->
                val editor = editor(activity)
                assertFalse("The owned editor must not acquire the real IME", editor.hasFocus())
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
                body(activity, editor)
            }
        }

        /** Called on the main thread after onResume, without invoking the permission-requesting Start button. */
        fun connectIncoming() {
            session.start()
            backend.callback.onReady()
            backend.callback.onConnectionChanged(host, Connection.CONNECTED)
            assertEquals(Phase.CONNECTED, session.state.phase)
        }
    }

    @Test fun acceptedCommandsDoNotEnableInputUntilTheConnectedCallback() = withFixture { f ->
        f.onUi { activity, editor ->
            assertStatus(activity, R.string.bluetooth_keyboard_idle)
            assertFalse(editor.inputAllowed)
            assertFalse(editor.isEnabled)
            assertFalse(button(activity, R.id.bluetooth_keyboard_send).isEnabled)
            assertFalse(button(activity, R.id.bluetooth_keyboard_control_keys).isEnabled)
            assertFalse(button(activity, R.id.bluetooth_keyboard_show_ime).isEnabled)
            assertNull(editor.onCreateInputConnection(EditorInfo()))
            assertEquals("", editor.text.toString())
            assertTrue(f.backends.isEmpty())

            f.session.start()
            assertStatus(activity, R.string.bluetooth_keyboard_starting)
            assertFalse(editor.inputAllowed)
            f.backend.callback.onReady()
            assertStatus(activity, R.string.bluetooth_keyboard_ready)
            assertFalse(editor.inputAllowed)
            assertTrue(button(activity, R.id.bluetooth_keyboard_connect).isEnabled)
            assertEquals(1, activity.findViewById<Spinner>(R.id.bluetooth_keyboard_hosts).count)
            assertTrue(button(activity, R.id.bluetooth_keyboard_connect).performClick())
            assertEquals(listOf(host), f.backend.connections)
            assertStatus(activity, R.string.bluetooth_keyboard_connecting, host.name)
            assertFalse(editor.inputAllowed)
            assertTrue(f.backend.reports.isEmpty())

            f.backend.callback.onConnectionChanged(host, Connection.CONNECTED)
            assertStatus(activity, R.string.bluetooth_keyboard_connected, host.name)
            assertTrue(editor.inputAllowed)
            assertTrue(editor.isEnabled)
            assertTrue(button(activity, R.id.bluetooth_keyboard_show_ime).isEnabled)
            assertTrue(button(activity, R.id.bluetooth_keyboard_control_keys).isEnabled)
            assertFalse(button(activity, R.id.bluetooth_keyboard_send).isEnabled)
            assertEquals(1, f.backend.reports.size)
            assertArrayEquals(ByteArray(8), f.backend.reports.single())
            f.backend.callback.onLedsChanged(2)
            val locks = activity.findViewById<TextView>(R.id.bluetooth_keyboard_leds)
            assertEquals(View.VISIBLE, locks.visibility)
            assertEquals(activity.getString(R.string.bluetooth_keyboard_lock_status,
                activity.getString(R.string.bluetooth_keyboard_lock_off),
                activity.getString(R.string.bluetooth_keyboard_lock_on),
                activity.getString(R.string.bluetooth_keyboard_lock_off)), locks.text.toString())

            assertTrue(button(activity, R.id.bluetooth_keyboard_disconnect).performClick())
            assertStatus(activity, R.string.bluetooth_keyboard_idle)
            assertFalse(editor.inputAllowed)
            assertEquals(1, f.backend.closeCount)
            f.backend.callback.onConnectionChanged(host, Connection.CONNECTED)
            assertStatus(activity, R.string.bluetooth_keyboard_idle)
            assertFalse(editor.inputAllowed)
        }
    }

    @Test fun switchingModeKeepsTheDraftAndUnsupportedTextRejectsTheWholeBatch() = withFixture { f ->
        f.onUi { activity, editor ->
            f.connectIncoming()
            f.backend.reports.clear()
            assertEquals(RemoteKeyboardEditText.Mode.MANUAL, editor.mode)
            assertTrue(connection(editor).commitText("local draft", 1))
            val mode = activity.findViewById<SwitchCompat>(R.id.bluetooth_keyboard_immediate)
            clickMode(mode)
            assertFalse(mode.isChecked)
            assertEquals(RemoteKeyboardEditText.Mode.MANUAL, editor.mode)
            assertEquals("local draft", editor.text.toString())
            assertMessage(activity, R.string.bluetooth_keyboard_switch_empty)

            editor.setText("ASCII 中 suffix")
            assertTrue(button(activity, R.id.bluetooth_keyboard_send).performClick())
            assertFalse(editor.isSending)
            assertEquals("ASCII 中 suffix", editor.text.toString())
            assertMessage(activity, R.string.bluetooth_keyboard_unsupported_text)
            assertTrue("No supported prefix may escape a rejected batch", f.backend.reports.isEmpty())

            editor.clearDraft()
            clickMode(mode)
            assertTrue(mode.isChecked)
            assertEquals(RemoteKeyboardEditText.Mode.IMMEDIATE, editor.mode)
            assertEquals(activity.getString(R.string.bluetooth_keyboard_immediate_help),
                activity.findViewById<TextView>(R.id.bluetooth_keyboard_mode_help).text.toString())
            assertTrue(connection(editor).commitText("A😀B", 1))
            assertFalse(editor.isSending)
            assertEquals("A😀B", editor.text.toString())
            assertMessage(activity, R.string.bluetooth_keyboard_unsupported_text)
            assertTrue(f.backend.reports.isEmpty())
            clickMode(mode)
            assertTrue(mode.isChecked)
            assertEquals(RemoteKeyboardEditText.Mode.IMMEDIATE, editor.mode)
            assertEquals("A😀B", editor.text.toString())
            assertMessage(activity, R.string.bluetooth_keyboard_switch_empty)
        }
    }

    @Test fun asynchronousSuccessClearsOnlyAfterSendingAndConsumesModifiersOnce() = withFixture { f ->
        f.onUi { activity, editor ->
            f.connectIncoming()
            f.backend.callback.onLedsChanged(2) // Caps Lock changes letter Shift, not punctuation.
            f.backend.reports.clear()
            activity.findViewById<CheckBox>(R.id.bluetooth_keyboard_ctrl).isChecked = true
            assertTrue(connection(editor).commitText("aA!", 1))
            assertTrue(button(activity, R.id.bluetooth_keyboard_send).performClick())
            assertTrue(editor.isSending)
            assertEquals("aA!", editor.text.toString())
            assertTrue("Transmission starts asynchronously", f.backend.reports.isEmpty())
            assertFalse(button(activity, R.id.bluetooth_keyboard_send).isEnabled)
            assertFalse(activity.findViewById<SwitchCompat>(R.id.bluetooth_keyboard_immediate).isEnabled)
            assertFalse(activity.findViewById<CheckBox>(R.id.bluetooth_keyboard_ctrl).isChecked)
            assertFalse(connection(editor).commitText("late edit", 1))
        }
        awaitSendFinished(f)
        f.onUi { activity, editor ->
            assertEquals("", editor.text.toString())
            assertMessage(activity, R.string.bluetooth_keyboard_sent)
            assertReports(f.backend.reports, listOf(0x04 to 3, 0x04 to 1, 0x1E to 3))
            f.backend.reports.clear()
            assertTrue(connection(editor).commitText("b", 1))
            assertTrue(button(activity, R.id.bluetooth_keyboard_send).performClick())
        }
        awaitSendFinished(f)
        f.onUi { _, editor ->
            assertEquals("", editor.text.toString())
            assertReports(f.backend.reports, listOf(0x05 to HidKeyboardReport.LEFT_SHIFT))
        }
    }

    @Test fun failureKeepsTheDraftAndStoppingCancelsWithoutReplayingAfterReconnect() = withFixture { f ->
        f.onUi { activity, editor ->
            f.connectIncoming()
            f.backend.reports.clear()
            f.backend.failNextKeyDown = true
            assertTrue(connection(editor).commitText("ab", 1))
            assertTrue(button(activity, R.id.bluetooth_keyboard_send).performClick())
        }
        awaitSendFinished(f)
        f.onUi { activity, editor ->
            assertEquals(Phase.ERROR, f.session.state.phase)
            assertEquals("ab", editor.text.toString())
            assertFalse(editor.inputAllowed)
            assertEquals(1, f.backend.keyDowns().size)
            assertEquals(1, f.backend.closeCount)
            assertMessage(activity, R.string.bluetooth_keyboard_send_interrupted)

            f.connectIncoming()
            assertTrue("Reconnect may release keys but must not retry the draft", f.backend.keyDowns().isEmpty())
            assertTrue(button(activity, R.id.bluetooth_keyboard_send).performClick())
            assertTrue(editor.isSending)
            // Both actions happen in one UI turn, before a delayed keystroke can run.
            assertTrue(button(activity, R.id.bluetooth_keyboard_disconnect).performClick())
            assertFalse(editor.isSending)
            assertEquals("ab", editor.text.toString())
            assertEquals(Phase.IDLE, f.session.state.phase)
            assertTrue(f.backend.keyDowns().isEmpty())
            assertMessage(activity, R.string.bluetooth_keyboard_send_cancelled)
        }
        allowQueuedStrokeToExpire()
        f.onUi { activity, editor ->
            assertTrue(f.backend.keyDowns().isEmpty())
            f.connectIncoming()
            assertEquals("ab", editor.text.toString())
            assertTrue(f.backend.keyDowns().isEmpty())
            f.backend.reports.clear()
            assertTrue(button(activity, R.id.bluetooth_keyboard_send).performClick())
        }
        awaitSendFinished(f)
        f.onUi { _, editor ->
            assertEquals("", editor.text.toString())
            assertReports(f.backend.reports, listOf(0x04 to 0, 0x05 to 0))
        }
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun pausingStopsSendingWithoutDisconnectingAndMissingPermissionStopsOnResume() {
        requirePermissionNotGranted()
        withFixture { f ->
            lateinit var ownedEditor: RemoteKeyboardEditText
            val draft = "a".repeat(200)
            f.onUi { activity, editor ->
                ownedEditor = editor
                f.connectIncoming()
                f.backend.reports.clear()
                editor.setText(draft)
                assertTrue(button(activity, R.id.bluetooth_keyboard_send).performClick())
                assertTrue(editor.isSending)
            }
            f.scenario.moveToState(Lifecycle.State.CREATED)
            var downCount = 0
            instrumentation.runOnMainSync {
                assertEquals(Phase.CONNECTED, f.session.state.phase)
                assertEquals(0, f.backend.closeCount)
                assertTrue(f.backend.disconnections.isEmpty())
                assertFalse(ownedEditor.inputAllowed)
                assertFalse(ownedEditor.isSending)
                assertEquals(draft, ownedEditor.text.toString())
                assertFalse(f.session.tap(0x04))
                assertArrayEquals("Pause releases physical keys", ByteArray(8), f.backend.reports.last())
                downCount = f.backend.keyDowns().size
            }
            allowQueuedStrokeToExpire()
            instrumentation.runOnMainSync { assertEquals(downCount, f.backend.keyDowns().size) }
            f.scenario.moveToState(Lifecycle.State.RESUMED)
            f.onUi { activity, editor ->
                assertEquals(Phase.IDLE, f.session.state.phase)
                assertEquals(1, f.backend.closeCount)
                assertFalse(editor.inputAllowed)
                assertFalse(button(activity, R.id.bluetooth_keyboard_send).isEnabled)
                assertEquals(draft, editor.text.toString())
                assertMessage(activity, R.string.bluetooth_keyboard_permission)
                assertEquals(downCount, f.backend.keyDowns().size)
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    fun recreationKeepsDraftAndModeWithoutInventingConnectionOrReplacingEngineSnapshot() {
        requirePermissionNotGranted()
        withEngineSnapshot { expected ->
            withFixture { f ->
                var marker: String? = null
                f.onUi { activity, editor ->
                    f.connectIncoming()
                    val mode = activity.findViewById<SwitchCompat>(R.id.bluetooth_keyboard_immediate)
                    clickMode(mode)
                    assertTrue(mode.isChecked)
                    assertEquals(RemoteKeyboardEditText.Mode.IMMEDIATE, editor.mode)
                    editor.setText("unsent local draft")
                    marker = editor.privateImeOptions
                    assertEquals(expected, engineValues())
                }
                f.scenario.recreate()
                f.onUi { activity, editor ->
                    assertEquals("unsent local draft", editor.text.toString())
                    assertEquals(RemoteKeyboardEditText.Mode.IMMEDIATE, editor.mode)
                    assertTrue(activity.findViewById<SwitchCompat>(R.id.bluetooth_keyboard_immediate).isChecked)
                    assertEquals(marker, editor.privateImeOptions)
                    assertEquals(Phase.IDLE, f.session.state.phase)
                    assertStatus(activity, R.string.bluetooth_keyboard_idle)
                    assertFalse(editor.inputAllowed)
                    assertFalse(button(activity, R.id.bluetooth_keyboard_send).isEnabled)
                    assertEquals(1, f.backend.closeCount)
                    assertTrue(f.backend.keyDowns().isEmpty())
                    f.backend.callback.onConnectionChanged(host, Connection.CONNECTED)
                    assertStatus(activity, R.string.bluetooth_keyboard_idle)
                    assertFalse(editor.inputAllowed)
                    assertEquals(expected, engineValues())
                }
            }
            assertEquals(expected, engineValues())
        }
    }

    private fun withFixture(body: (Fixture) -> Unit) {
        val app = context.applicationContext as Application
        // Set this before the window starts, including after recreate. Merely hiding the keyboard
        // after launch would let the real IME observe this screen's private mode marker first.
        val isolation = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity !is BluetoothKeyboardActivity) return
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            }
            override fun onActivityStarted(activity: Activity) {
                if (activity !is BluetoothKeyboardActivity) return
                // onActivityCreated runs inside super.onCreate, before this screen inflates its
                // layout. The window flag already excludes the IME; views are available now.
                editor(activity).apply {
                    showSoftInputOnFocus = false
                    isFocusable = false
                    clearFocus()
                }
            }
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        val fixture = Fixture()
        instrumentation.runOnMainSync {
            app.registerActivityLifecycleCallbacks(isolation)
            BluetoothKeyboardRuntime.replaceForTest(fixture.session)
        }
        try {
            ActivityScenario.launch(BluetoothKeyboardActivity::class.java).use { scenario ->
                fixture.scenario = scenario
                body(fixture)
            }
        } finally {
            instrumentation.runOnMainSync {
                try { BluetoothKeyboardRuntime.replaceForTest(null) }
                finally { app.unregisterActivityLifecycleCallbacks(isolation) }
            }
        }
    }

    private fun awaitSendFinished(f: Fixture) {
        val deadline = SystemClock.uptimeMillis() + 3_000L
        do {
            var finished = false
            f.onUi { _, editor -> finished = !editor.isSending }
            if (finished) return
            SystemClock.sleep(10)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("The short owned transmission did not finish")
    }

    private fun allowQueuedStrokeToExpire() {
        val barrier = CountDownLatch(1)
        instrumentation.runOnMainSync {
            Handler(Looper.getMainLooper()).postDelayed({ barrier.countDown() }, 100L)
        }
        assertTrue("The UI must process the cancellation barrier", barrier.await(3, TimeUnit.SECONDS))
    }

    private fun assertReports(actual: List<ByteArray>, strokes: List<Pair<Int, Int>>) {
        assertEquals(strokes.size * 2, actual.size)
        strokes.forEachIndexed { index, (usage, modifiers) ->
            assertArrayEquals(byteArrayOf(modifiers.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0), actual[index * 2])
            assertArrayEquals(ByteArray(8), actual[index * 2 + 1])
        }
    }

    private fun requirePermissionNotGranted() = assumeTrue(
        "This test needs an ungranted permission; it never grants or revokes device permissions",
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
    )

    private val engineKeys get() = EngineFeature.entries.map { it.key } + listOf(
        PinyinSettings.KEY_SCHEME, PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES, RemoteKeyboardMode.KEY_SNAPSHOT
    )
    private fun engineValues() = EngineSettings.preferences(context).all.filterKeys { it in engineKeys }

    private fun withEngineSnapshot(body: (Map<String, *>) -> Unit) {
        val prefs = EngineSettings.preferences(context)
        val before = engineValues()
        try {
            assertTrue(prefs.edit().apply {
                remove(RemoteKeyboardMode.KEY_SNAPSHOT)
                EngineFeature.entries.forEachIndexed { index, feature -> putBoolean(feature.key, index % 2 == 0) }
                putString(PinyinSettings.KEY_SCHEME, PinyinScheme.TERRA.schemaId)
                putBoolean(PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES, true)
            }.commit())
            assertEquals(RemoteKeyboardMode.Result.ENABLED, RemoteKeyboardMode(prefs).toggle())
            // An existing remote-mode snapshot must survive later user changes and connection actions.
            assertTrue(prefs.edit().putBoolean(EngineFeature.ENGLISH_COMPLETION.key, true).commit())
            assertNotNull(prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT, null))
            body(engineValues())
        } finally {
            assertTrue("Restore the exact engine preferences and previous recovery record", prefs.edit().apply {
                engineKeys.forEach { key -> restoreValue(key, before[key]) }
            }.commit())
        }
    }

    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                putStringSet(key, value as Set<String>)
            }
            else -> error("Unsupported stored preference type")
        }
    }

    private fun editor(activity: Activity): RemoteKeyboardEditText = activity.findViewById(R.id.bluetooth_keyboard_editor)
    private fun connection(editor: RemoteKeyboardEditText) = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
    private fun button(activity: Activity, id: Int): Button = activity.findViewById(id)
    private fun clickMode(mode: SwitchCompat) {
        assertTrue("The mode switch must be available", mode.isEnabled)
        // CompoundButton can toggle through its checked listener while performClick returns false.
        // Callers verify the resulting mode, draft and feedback instead of that listener receipt.
        mode.performClick()
    }
    private fun assertStatus(activity: Activity, resource: Int, vararg args: Any) = assertEquals(
        activity.getString(resource, *args), activity.findViewById<TextView>(R.id.bluetooth_keyboard_status).text.toString()
    )
    private fun assertMessage(activity: Activity, resource: Int) = assertEquals(
        activity.getString(resource), activity.findViewById<TextView>(R.id.bluetooth_keyboard_feedback).text.toString()
    )
}
