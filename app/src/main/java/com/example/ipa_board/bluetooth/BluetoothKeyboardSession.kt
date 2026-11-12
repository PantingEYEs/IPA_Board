package com.example.ipa_board.bluetooth

import com.example.ipa_board.bluetooth.HidKeyboardBackend.Connection
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Failure
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Host

/** Owns one explicitly started connection. Nothing is queued for a later connection. */
class BluetoothKeyboardSession(
    private val createBackend: () -> HidKeyboardBackend,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val changed: (State) -> Unit
) {
    enum class Phase { IDLE, STARTING, READY, CONNECTING, CONNECTED, ERROR }
    data class State(
        val phase: Phase = Phase.IDLE,
        val host: Host? = null,
        val leds: Int = 0,
        val failure: Failure? = null
    )

    var state = State()
        private set
    private var backend: HidKeyboardBackend? = null
    private var generation = 0L
    private var cancelDeadline: (() -> Unit)? = null
    private var inputEnabled = true

    fun start() {
        if (state.phase != Phase.IDLE && state.phase != Phase.ERROR) return
        dispose()
        val expected = generation
        val link = createBackend()
        backend = link
        publish(State(Phase.STARTING))
        deadline(Phase.STARTING)
        link.start(object : HidKeyboardBackend.Callback {
            private fun current() = expected == generation && backend === link

            override fun onReady() {
                if (!current() || state.phase != Phase.STARTING) return
                cancelTimer()
                publish(State(Phase.READY))
            }

            override fun onConnectionChanged(host: Host, connection: Connection) {
                if (!current()) return
                if (state.host != null && state.host?.address != host.address) {
                    if (connection == Connection.CONNECTED || connection == Connection.CONNECTING) {
                        link.disconnect(host)
                    }
                    return
                }
                when (connection) {
                    Connection.CONNECTING -> {
                        if (state.phase == Phase.READY) {
                            publish(State(Phase.CONNECTING, host))
                            deadline(Phase.CONNECTING)
                        }
                    }
                    Connection.CONNECTED -> {
                        if (state.phase != Phase.READY && state.phase != Phase.CONNECTING) return
                        cancelTimer()
                        // Clear any state the receiver retained before enabling the keyboard.
                        if (!link.send(host, HidKeyboardReport.input())) {
                            if (current()) fail(Failure.SEND_FAILED)
                        } else if (current()) {
                            publish(State(Phase.CONNECTED, host))
                        }
                    }
                    Connection.DISCONNECTING, Connection.DISCONNECTED -> {
                        if (state.host?.address != host.address) return
                        // Close the registration too: a late reconnect must not resume input.
                        fail(Failure.CONNECTION_FAILED)
                    }
                }
            }

            override fun onLedsChanged(leds: Int) {
                if (current() && state.phase == Phase.CONNECTED) {
                    publish(state.copy(leds = leds and 0x1f))
                }
            }

            override fun onFailure(failure: Failure) {
                if (current()) fail(failure)
            }
        })
    }

    fun bondedDevices(): List<Host> = backend?.bondedDevices().orEmpty()

    fun connect(host: Host) {
        if (state.phase != Phase.READY) return
        val link = backend ?: return
        val expected = generation
        publish(State(Phase.CONNECTING, host))
        deadline(Phase.CONNECTING)
        if (!link.connect(host) && expected == generation) fail(Failure.CONNECTION_FAILED)
    }

    /** A tap sends down and up immediately. Modifiers are applied only to this report pair. */
    fun tap(usage: Int, modifiers: Int = 0): Boolean {
        val report = HidKeyboardReport.input(modifiers, listOf(usage))
        if (!inputEnabled || state.phase != Phase.CONNECTED) return false
        val link = backend ?: return false
        val host = state.host ?: return false
        val expected = generation
        if (!link.send(host, report)) {
            if (expected == generation) fail(Failure.SEND_FAILED)
            return false
        }
        if (expected != generation) return false
        if (!link.send(host, HidKeyboardReport.input())) {
            if (expected == generation) fail(Failure.SEND_FAILED)
            return false
        }
        return expected == generation && state.phase == Phase.CONNECTED
    }

    fun releaseKeys() {
        val host = state.host ?: return
        val link = backend ?: return
        val expected = generation
        if (state.phase == Phase.CONNECTED && !link.send(host, HidKeyboardReport.input()) &&
            expected == generation) fail(Failure.SEND_FAILED)
    }

    /** Backgrounding releases keys without requesting disconnection or keeping the link alive. */
    fun setInputEnabled(enabled: Boolean) {
        inputEnabled = enabled
        if (!enabled) releaseKeys()
    }

    /** Explicit disconnect. Does not unpair or touch local input configuration. */
    fun stop() {
        dispose()
        publish(State())
    }

    private fun fail(failure: Failure) {
        val host = state.host
        dispose()
        publish(State(Phase.ERROR, host, failure = failure))
    }

    private fun dispose() {
        ++generation
        cancelTimer()
        val old = backend
        backend = null
        val host = state.host
        if (old != null) {
            if (host != null && state.phase == Phase.CONNECTED) old.send(host, HidKeyboardReport.input())
            old.close()
        }
    }

    private fun deadline(phase: Phase) {
        cancelTimer()
        val expected = generation
        cancelDeadline = schedule(15_000) {
            if (expected == generation && state.phase == phase) fail(Failure.TIMEOUT)
        }
    }

    private fun cancelTimer() {
        cancelDeadline?.invoke()
        cancelDeadline = null
    }

    private fun publish(next: State) {
        state = next
        changed(next)
    }
}
