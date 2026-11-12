package com.example.ipa_board.bluetooth

import com.example.ipa_board.bluetooth.BluetoothKeyboardSession.Phase
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Connection
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Failure
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Host
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class BluetoothKeyboardSessionTest {
    private val host = Host("02:00:00:00:00:01", "Synthetic computer")
    private val otherHost = Host("02:00:00:00:00:02", "Other synthetic computer")

    private class Scheduler {
        data class Job(val at: Long, val action: () -> Unit, var cancelled: Boolean = false, var fired: Boolean = false)
        val jobs = mutableListOf<Job>()
        private var now = 0L
        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val job = Job(now + delay, action)
            jobs += job
            return { job.cancelled = true }
        }
        fun advanceBy(duration: Long) {
            val target = now + duration
            while (true) {
                val job = jobs.filter { !it.cancelled && !it.fired && it.at <= target }.minByOrNull { it.at }
                    ?: break
                now = job.at
                job.fired = true
                job.action()
            }
            now = target
        }
    }

    private class Backend : HidKeyboardBackend {
        data class Sent(val host: Host, val bytes: ByteArray, val accepted: Boolean)
        lateinit var callback: HidKeyboardBackend.Callback
        var starts = 0
        var closes = 0
        var connectAccepted = true
        var bonded = emptyList<Host>()
        val connections = mutableListOf<Host>()
        val disconnections = mutableListOf<Host>()
        val sent = mutableListOf<Sent>()
        val events = mutableListOf<String>()
        val sendResults = ArrayDeque<Boolean>()
        var duringSend: (() -> Unit)? = null
        override fun start(callback: HidKeyboardBackend.Callback) {
            starts++
            this.callback = callback
            events += "start"
        }
        override fun bondedDevices() = bonded
        override fun connect(host: Host): Boolean {
            connections += host
            events += "connect"
            return connectAccepted
        }
        override fun disconnect(host: Host): Boolean {
            disconnections += host
            events += "disconnect"
            return true
        }
        override fun send(host: Host, report: ByteArray): Boolean {
            val accepted = if (sendResults.isEmpty()) true else sendResults.removeFirst()
            sent += Sent(host, report.copyOf(), accepted)
            events += "send"
            duringSend?.invoke()
            return accepted
        }
        override fun close() { closes++; events += "close" }
    }

    private class Fixture {
        val scheduler = Scheduler()
        val backends = mutableListOf<Backend>()
        val changes = mutableListOf<BluetoothKeyboardSession.State>()
        val session = BluetoothKeyboardSession(
            { Backend().also { backends += it } }, scheduler::schedule, { changes += it })
        fun ready(): Backend {
            session.start()
            return backends.last().also { it.callback.onReady() }
        }
        fun connected(host: Host): Backend = ready().also {
            session.connect(host)
            it.callback.onConnectionChanged(host, Connection.CONNECTED)
            assertEquals(Phase.CONNECTED, session.state.phase)
        }
    }

    @Test fun startingAndAcceptedConnectRequestsWaitForCallbacksBeforeEnablingInput() {
        val f = Fixture()
        assertFalse(f.session.tap(0x04))
        f.session.start()
        val backend = f.backends.single()
        assertEquals(1, backend.starts)
        assertEquals(Phase.STARTING, f.session.state.phase)
        assertFalse(f.session.tap(0x04))
        f.session.connect(host)
        assertTrue(backend.connections.isEmpty())
        f.session.start()
        assertEquals(1, f.backends.size)

        backend.callback.onReady()
        assertEquals(Phase.READY, f.session.state.phase)
        assertFalse(f.session.tap(0x04))
        f.session.connect(host)
        assertEquals(listOf(host), backend.connections)
        assertEquals(Phase.CONNECTING, f.session.state.phase)
        assertFalse(f.session.tap(0x04))
        assertTrue(backend.sent.isEmpty())
        backend.callback.onConnectionChanged(host, Connection.CONNECTING)
        assertEquals(Phase.CONNECTING, f.session.state.phase)
        backend.callback.onConnectionChanged(host, Connection.CONNECTED)
        assertEquals(Phase.CONNECTED, f.session.state.phase)
        assertEquals(host, f.session.state.host)
        assertEquals(1, backend.sent.size)
        assertArrayEquals(ByteArray(8), backend.sent.single().bytes)
        assertTrue("Pre-connection taps must never be replayed", backend.sent.all { it.bytes.all { byte -> byte == 0.toByte() } })
    }

    @Test fun incomingConnectionsAreAcceptedOnlyAfterRegistrationAndClearReceiverKeys() {
        for (connectingFirst in listOf(false, true)) {
            val f = Fixture()
            f.session.start()
            val backend = f.backends.single()
            backend.callback.onConnectionChanged(host, Connection.CONNECTED)
            assertEquals(Phase.STARTING, f.session.state.phase)
            assertTrue(backend.sent.isEmpty())
            backend.callback.onReady()
            backend.bonded = listOf(host, otherHost)
            assertEquals(listOf(host, otherHost), f.session.bondedDevices())
            if (connectingFirst) {
                backend.callback.onConnectionChanged(host, Connection.CONNECTING)
                assertEquals(Phase.CONNECTING, f.session.state.phase)
                assertEquals(host, f.session.state.host)
            }
            backend.callback.onConnectionChanged(host, Connection.CONNECTED)
            assertEquals(Phase.CONNECTED, f.session.state.phase)
            assertEquals(host, backend.sent.single().host)
            assertArrayEquals(ByteArray(8), backend.sent.single().bytes)
            assertTrue("An incoming connection does not require an outgoing connect command", backend.connections.isEmpty())
        }
    }

    @Test fun anotherDeviceCannotReplaceTheSelectedHostOrDisconnectItsSession() {
        val f = Fixture()
        val backend = f.ready()
        f.session.connect(host)
        backend.callback.onConnectionChanged(otherHost, Connection.CONNECTING)
        backend.callback.onConnectionChanged(otherHost, Connection.CONNECTED)
        backend.callback.onConnectionChanged(otherHost, Connection.DISCONNECTED)
        assertEquals(listOf(otherHost, otherHost), backend.disconnections)
        assertEquals(Phase.CONNECTING, f.session.state.phase)
        assertEquals(host, f.session.state.host)
        assertTrue(backend.sent.isEmpty())
        backend.callback.onConnectionChanged(host.copy(name = "Updated synthetic name"), Connection.CONNECTED)
        assertEquals(Phase.CONNECTED, f.session.state.phase)
        backend.callback.onConnectionChanged(otherHost, Connection.CONNECTED)
        backend.callback.onConnectionChanged(otherHost, Connection.DISCONNECTING)
        assertEquals(Phase.CONNECTED, f.session.state.phase)
        assertEquals(host.address, f.session.state.host?.address)
        assertEquals(0, backend.closes)
        assertTrue(backend.sent.all { it.host.address == host.address })
    }

    @Test fun aTapSendsOneDownAndOneReleaseAndModifiersDoNotStickToTheNextTap() {
        val f = Fixture()
        val backend = f.connected(host)
        assertTrue(f.session.tap(0x04, HidKeyboardReport.LEFT_CTRL or HidKeyboardReport.RIGHT_SHIFT))
        assertTrue(f.session.tap(0x05))
        assertEquals(5, backend.sent.size)
        assertArrayEquals(bytes(0x21, 0, 0x04, 0, 0, 0, 0, 0), backend.sent[1].bytes)
        assertArrayEquals(ByteArray(8), backend.sent[2].bytes)
        assertArrayEquals(bytes(0, 0, 0x05, 0, 0, 0, 0, 0), backend.sent[3].bytes)
        assertArrayEquals(ByteArray(8), backend.sent[4].bytes)
        assertTrue(backend.sent.all { it.host == host && it.accepted })
        assertEquals(Phase.CONNECTED, f.session.state.phase)
    }

    @Test fun failedKeyDownClosesWithABestEffortReleaseAndNeverReplaysTheKey() {
        val f = Fixture()
        val backend = f.connected(host)
        backend.sendResults.add(false)
        assertFalse(f.session.tap(0x04, HidKeyboardReport.LEFT_CTRL))
        assertEquals(Failure.SEND_FAILED, f.session.state.failure)
        assertEquals(Phase.ERROR, f.session.state.phase)
        assertEquals(1, backend.closes)
        assertEquals(3, backend.sent.size)
        assertArrayEquals(bytes(1, 0, 4, 0, 0, 0, 0, 0), backend.sent[1].bytes)
        assertArrayEquals(ByteArray(8), backend.sent[2].bytes)
        assertEquals(listOf("send", "close"), backend.events.takeLast(2))
        assertFalse(f.session.tap(0x05))
        val next = f.connected(host)
        assertEquals(1, next.sent.size)
        assertArrayEquals(ByteArray(8), next.sent.single().bytes)
        assertEquals(3, backend.sent.size)
    }

    @Test fun failedKeyUpRetriesOnlyTheReleaseThenClosesInsteadOfSendingAnotherKeyDown() {
        val f = Fixture()
        val backend = f.connected(host)
        backend.sendResults.addAll(listOf(true, false, true))
        assertFalse(f.session.tap(0x06, HidKeyboardReport.LEFT_ALT))
        assertEquals(Phase.ERROR, f.session.state.phase)
        assertEquals(Failure.SEND_FAILED, f.session.state.failure)
        assertEquals(4, backend.sent.size)
        assertArrayEquals(bytes(4, 0, 6, 0, 0, 0, 0, 0), backend.sent[1].bytes)
        assertArrayEquals(ByteArray(8), backend.sent[2].bytes)
        assertFalse(backend.sent[2].accepted)
        assertArrayEquals(ByteArray(8), backend.sent[3].bytes)
        assertEquals(1, backend.closes)
        assertFalse(f.session.tap(0x07))
        assertEquals(4, backend.sent.size)
    }

    @Test fun explicitReleaseFailureStillClosesAndDoesNotKeepRetryingOrQueueInput() {
        val f = Fixture()
        val backend = f.connected(host)
        backend.sendResults.addAll(listOf(false, false))
        f.session.releaseKeys()
        assertEquals(Phase.ERROR, f.session.state.phase)
        assertEquals(Failure.SEND_FAILED, f.session.state.failure)
        assertEquals(3, backend.sent.size)
        assertTrue(backend.sent.all { it.bytes.contentEquals(ByteArray(8)) })
        assertEquals(1, backend.closes)
        f.session.releaseKeys()
        assertFalse(f.session.tap(0x04))
        assertEquals(3, backend.sent.size)
    }

    @Test fun failureToClearTheReceiverBeforeConnectingNeverEnablesTheKeyboard() {
        val f = Fixture()
        val backend = f.ready()
        f.session.connect(host)
        backend.sendResults.add(false)
        backend.callback.onConnectionChanged(host, Connection.CONNECTED)
        assertEquals(Phase.ERROR, f.session.state.phase)
        assertEquals(Failure.SEND_FAILED, f.session.state.failure)
        assertFalse(f.session.tap(0x04))
        assertEquals(1, backend.sent.size)
        assertEquals(1, backend.closes)
        assertFalse(f.changes.any { it.phase == Phase.CONNECTED })
    }

    @Test fun aRejectedConnectRequestFailsWithoutPretendingToBeConnected() {
        val f = Fixture()
        val backend = f.ready()
        backend.connectAccepted = false
        f.session.connect(host)
        assertEquals(Phase.ERROR, f.session.state.phase)
        assertEquals(Failure.CONNECTION_FAILED, f.session.state.failure)
        assertEquals(host, f.session.state.host)
        assertEquals(1, backend.closes)
        assertTrue(backend.sent.isEmpty())
        backend.callback.onConnectionChanged(host, Connection.CONNECTED)
        assertEquals(Phase.ERROR, f.session.state.phase)
        assertTrue(backend.sent.isEmpty())
    }

    @Test fun registrationAndConnectionTimeoutsFailOnlyThePendingOperation() {
        for (waitingForConnection in listOf(false, true)) {
            val f = Fixture()
            f.session.start()
            val backend = f.backends.single()
            if (waitingForConnection) {
                backend.callback.onReady()
                f.scheduler.advanceBy(10_000)
                f.session.connect(host)
            }
            f.scheduler.advanceBy(14_999)
            assertEquals(if (waitingForConnection) Phase.CONNECTING else Phase.STARTING, f.session.state.phase)
            f.scheduler.advanceBy(1)
            assertEquals(Phase.ERROR, f.session.state.phase)
            assertEquals(Failure.TIMEOUT, f.session.state.failure)
            assertEquals(1, backend.closes)
            assertTrue(backend.sent.isEmpty())
            backend.callback.onReady()
            backend.callback.onConnectionChanged(host, Connection.CONNECTED)
            assertEquals(Phase.ERROR, f.session.state.phase)
        }
    }

    @Test fun cancelledDeadlinesCannotExpireReadyOrConnectedSessions() {
        val f = Fixture()
        val backend = f.ready()
        val registration = f.scheduler.jobs.single()
        assertTrue(registration.cancelled)
        registration.action()
        assertEquals(Phase.READY, f.session.state.phase)
        f.session.connect(host)
        val connection = f.scheduler.jobs.last()
        backend.callback.onConnectionChanged(host, Connection.CONNECTED)
        assertTrue(connection.cancelled)
        connection.action()
        f.scheduler.advanceBy(60_000)
        assertEquals(Phase.CONNECTED, f.session.state.phase)
        assertEquals(0, backend.closes)
        assertTrue(f.session.tap(0x04))
    }

    @Test fun oldCallbacksAndAlreadyQueuedTimeoutsCannotPolluteANewSession() {
        val f = Fixture()
        val old = f.ready()
        f.session.connect(host)
        val staleDeadlines = f.scheduler.jobs.toList()
        f.session.stop()
        f.session.start()
        val current = f.backends.last()
        val expected = f.session.state
        val changeCount = f.changes.size
        old.callback.onReady()
        old.callback.onConnectionChanged(host, Connection.CONNECTED)
        old.callback.onConnectionChanged(host, Connection.DISCONNECTED)
        old.callback.onLedsChanged(0x1f)
        old.callback.onFailure(Failure.PERMISSION)
        staleDeadlines.forEach { it.action() }
        assertEquals(expected, f.session.state)
        assertEquals(changeCount, f.changes.size)
        assertTrue(current.sent.isEmpty())
        assertEquals(0, current.closes)
        current.callback.onReady()
        f.session.connect(otherHost)
        current.callback.onConnectionChanged(otherHost, Connection.CONNECTED)
        old.callback.onFailure(Failure.SERVICE_UNAVAILABLE)
        old.callback.onLedsChanged(0x1f)
        assertEquals(Phase.CONNECTED, f.session.state.phase)
        assertEquals(otherHost, f.session.state.host)
        assertEquals(0, f.session.state.leds)
        assertEquals(1, old.closes)
    }

    @Test fun permissionFailureClosesTheCurrentAttemptAndRequiresAnExplicitFreshStart() {
        for (wasConnected in listOf(false, true)) {
            val f = Fixture()
            val backend = if (wasConnected) f.connected(host) else {
                f.session.start()
                f.backends.single()
            }
            backend.callback.onFailure(Failure.PERMISSION)
            assertEquals(Phase.ERROR, f.session.state.phase)
            assertEquals(Failure.PERMISSION, f.session.state.failure)
            assertEquals(1, backend.closes)
            assertFalse(f.session.tap(0x04))
            f.scheduler.advanceBy(60_000)
            assertEquals(1, f.backends.size)
            val next = f.connected(host)
            assertNotSame(backend, next)
            assertNull(f.session.state.failure)
            assertEquals(1, next.sent.size)
            assertArrayEquals(ByteArray(8), next.sent.single().bytes)
        }
    }

    @Test fun losingTheSelectedConnectionClosesRegistrationSoLateReconnectsCannotResumeInput() {
        for (connection in listOf(Connection.DISCONNECTING, Connection.DISCONNECTED)) {
            val f = Fixture()
            val backend = f.connected(host)
            backend.callback.onConnectionChanged(host, connection)
            assertEquals(Phase.ERROR, f.session.state.phase)
            assertEquals(Failure.CONNECTION_FAILED, f.session.state.failure)
            assertEquals(1, backend.closes)
            assertEquals(listOf("send", "close"), backend.events.takeLast(2))
            backend.callback.onConnectionChanged(host, Connection.CONNECTED)
            assertFalse(f.session.tap(0x04))
            assertEquals(2, backend.sent.size)
        }
    }

    @Test fun stopReleasesKeysBeforeClosingAndLateCallbacksCannotRestartTheSession() {
        val f = Fixture()
        val backend = f.connected(host)
        assertTrue(f.session.tap(0x04, HidKeyboardReport.LEFT_GUI))
        f.session.stop()
        assertEquals(BluetoothKeyboardSession.State(), f.session.state)
        assertArrayEquals(ByteArray(8), backend.sent.last().bytes)
        assertEquals(listOf("send", "close"), backend.events.takeLast(2))
        assertEquals(1, backend.closes)
        val sends = backend.sent.size
        f.session.stop()
        backend.callback.onReady()
        backend.callback.onConnectionChanged(host, Connection.CONNECTED)
        backend.callback.onFailure(Failure.SEND_FAILED)
        assertEquals(Phase.IDLE, f.session.state.phase)
        assertFalse(f.session.tap(0x04))
        assertEquals(sends, backend.sent.size)
        assertEquals(1, backend.closes)
    }

    @Test fun ledUpdatesOnlyAffectTheCurrentConnectedHostAndDoNotSendKeys() {
        val f = Fixture()
        val backend = f.ready()
        backend.callback.onLedsChanged(0xff)
        assertEquals(0, f.session.state.leds)
        f.session.connect(host)
        backend.callback.onLedsChanged(0x1f)
        assertEquals(0, f.session.state.leds)
        backend.callback.onConnectionChanged(host, Connection.CONNECTED)
        backend.callback.onLedsChanged(0xe2)
        assertEquals(2, f.session.state.leds)
        backend.callback.onLedsChanged(0x1f)
        assertEquals(0x1f, f.session.state.leds)
        assertEquals(1, backend.sent.size)
        assertEquals(host, f.session.state.host)
        f.session.stop()
        backend.callback.onLedsChanged(3)
        assertEquals(0, f.session.state.leds)
    }

    @Test fun aSynchronousFailureDuringKeyDownCannotSendAnUpReportThroughAClosedBackend() {
        val f = Fixture()
        val backend = f.connected(host)
        backend.duringSend = {
            backend.duringSend = null
            backend.callback.onFailure(Failure.PERMISSION)
        }
        assertFalse(f.session.tap(0x04))
        assertEquals(Phase.ERROR, f.session.state.phase)
        assertEquals(Failure.PERMISSION, f.session.state.failure)
        assertEquals(3, backend.sent.size)
        assertArrayEquals(bytes(0, 0, 4, 0, 0, 0, 0, 0), backend.sent[1].bytes)
        assertArrayEquals(ByteArray(8), backend.sent[2].bytes)
        assertEquals("close", backend.events.last())
        assertEquals(1, backend.closes)
    }

    @Test fun disablingInputReleasesKeysWithoutDisconnectingKeepingAliveOrQueueingBackgroundTaps() {
        val f = Fixture()
        val backend = f.connected(host)
        backend.callback.onLedsChanged(2)
        f.session.setInputEnabled(false)
        assertEquals(Phase.CONNECTED, f.session.state.phase)
        assertEquals(2, f.session.state.leds)
        assertEquals(2, backend.sent.size)
        assertArrayEquals(ByteArray(8), backend.sent.last().bytes)
        assertFalse(f.session.tap(0x04, HidKeyboardReport.LEFT_CTRL))
        assertFalse(f.session.tap(0x05))
        f.scheduler.advanceBy(60_000)
        assertEquals("Background taps and elapsed time must not send queued or keepalive reports", 2, backend.sent.size)
        assertTrue(backend.disconnections.isEmpty())
        assertEquals(0, backend.closes)
        assertEquals(listOf(host), backend.connections)
        f.session.setInputEnabled(true)
        assertEquals(2, backend.sent.size)
        assertTrue(f.session.tap(0x06))
        assertEquals(4, backend.sent.size)
        assertArrayEquals(bytes(0, 0, 6, 0, 0, 0, 0, 0), backend.sent[2].bytes)
        assertArrayEquals(ByteArray(8), backend.sent[3].bytes)
        assertEquals(1, f.backends.size)
    }

    @Test fun aConnectionCallbackWhileInputIsDisabledDoesNotEnableSendingUntilForegroundReturns() {
        val f = Fixture()
        val backend = f.ready()
        f.session.connect(host)
        f.session.setInputEnabled(false)
        backend.callback.onConnectionChanged(host, Connection.CONNECTED)
        assertEquals(Phase.CONNECTED, f.session.state.phase)
        assertEquals(1, backend.sent.size)
        assertArrayEquals(ByteArray(8), backend.sent.single().bytes)
        assertFalse(f.session.tap(0x04))
        assertEquals(1, backend.sent.size)
        assertEquals(0, backend.closes)
        assertTrue(backend.disconnections.isEmpty())
        f.session.setInputEnabled(true)
        assertTrue(f.session.tap(0x05))
        assertEquals(3, backend.sent.size)
        assertArrayEquals(bytes(0, 0, 5, 0, 0, 0, 0, 0), backend.sent[1].bytes)
    }

    @Test fun returningToForegroundAfterSystemUnregistrationDoesNotRegisterOrReconnectAutomatically() {
        val f = Fixture()
        val backend = f.connected(host)
        f.session.setInputEnabled(false)
        backend.callback.onFailure(Failure.REGISTRATION_FAILED)
        assertEquals(Phase.ERROR, f.session.state.phase)
        assertEquals(Failure.REGISTRATION_FAILED, f.session.state.failure)
        assertEquals(1, backend.closes)
        val reportsAfterFailure = backend.sent.size
        f.session.setInputEnabled(true)
        f.scheduler.advanceBy(60_000)
        backend.callback.onReady()
        backend.callback.onConnectionChanged(host, Connection.CONNECTED)
        assertEquals(Phase.ERROR, f.session.state.phase)
        assertFalse(f.session.tap(0x04))
        assertEquals(reportsAfterFailure, backend.sent.size)
        assertEquals(1, f.backends.size)
        assertEquals(listOf(host), backend.connections)
        val next = f.connected(host)
        assertEquals(1, next.sent.size)
        assertArrayEquals(ByteArray(8), next.sent.single().bytes)
        assertTrue(f.session.tap(0x05))
        assertEquals(3, next.sent.size)
    }

    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
}
