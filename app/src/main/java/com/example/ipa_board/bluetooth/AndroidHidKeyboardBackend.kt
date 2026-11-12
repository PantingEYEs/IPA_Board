package com.example.ipa_board.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Callback
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Connection
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Failure
import com.example.ipa_board.bluetooth.HidKeyboardBackend.Host
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executor

/** Android transport only. The session owns timeouts, host selection and screen lifecycle. */
@RequiresApi(Build.VERSION_CODES.P)
class AndroidHidKeyboardBackend(context: Context) : HidKeyboardBackend {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { main.post(it) }
    private var adapter: BluetoothAdapter? = null
    private var profile: BluetoothHidDevice? = null
    private var callback: Callback? = null
    private var receiver: BroadcastReceiver? = null
    private var generation = 0L
    private var started = false
    private var closed = false
    private var failed = false
    private var registered = false
    private var registrationRequested = false
    private var reportHost: String? = null
    private val closedProxies = Collections.newSetFromMap(IdentityHashMap<BluetoothProfile, Boolean>())

    private class Peer(val device: BluetoothDevice) {
        var connection: Connection? = null
        var input = HidKeyboardReport.input()
        var leds = 0
    }

    private val peers = linkedMapOf<String, Peer>()

    // Permission is checked before acquiring the profile; revocation races are caught by guarded().
    @SuppressLint("MissingPermission")
    override fun start(callback: Callback) {
        if (started && !closed) return
        started = true
        closed = false
        failed = false
        registered = false
        registrationRequested = false
        this.callback = callback
        val expected = ++generation
        if (!hasPermission()) {
            fail(Failure.PERMISSION)
            return
        }
        adapter = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        } catch (_: SecurityException) {
            fail(Failure.PERMISSION)
            return
        } catch (_: RuntimeException) {
            fail(Failure.SERVICE_UNAVAILABLE)
            return
        }
        val bluetooth = adapter
        if (bluetooth == null) {
            fail(Failure.UNSUPPORTED)
            return
        }
        guarded(false, Failure.SERVICE_UNAVAILABLE) {
            val stateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!active(expected) || intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF ->
                            fail(Failure.BLUETOOTH_OFF)
                    }
                }
            }
            // Bluetooth broadcasts may originate from its privileged process rather than system UID.
            ContextCompat.registerReceiver(context, stateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            receiver = stateReceiver
            val accepted = bluetooth.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(id: Int, proxy: BluetoothProfile) {
                    main.post {
                        if (!active(expected)) {
                            // An earlier request must not close a proxy currently owned by a newer one.
                            if (profile !== proxy) closeProxy(bluetooth, id, proxy)
                        } else if (id != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) {
                            closeProxy(bluetooth, id, proxy)
                            fail(Failure.UNSUPPORTED)
                        } else if (profile !== proxy) {
                            if (profile != null) {
                                closeProxy(bluetooth, id, proxy)
                            } else {
                                profile = proxy
                                register(proxy, expected)
                            }
                        }
                    }
                }

                override fun onServiceDisconnected(id: Int) {
                    main.post {
                        if (active(expected) && id == BluetoothProfile.HID_DEVICE) {
                            fail(Failure.SERVICE_UNAVAILABLE)
                        }
                    }
                }
            }, BluetoothProfile.HID_DEVICE)
            if (!accepted) fail(Failure.UNSUPPORTED)
            accepted
        }
    }

    // Only already bonded devices are read. No discovery, pairing or adapter state changes occur.
    @SuppressLint("MissingPermission")
    override fun bondedDevices(): List<Host> = guarded(emptyList(), Failure.SERVICE_UNAVAILABLE) {
        adapter!!.bondedDevices.map { host(it) }
            .sortedWith(compareBy<Host, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.address })
    }

    @SuppressLint("MissingPermission") // guarded() checks permission and catches revocation races.
    override fun connect(host: Host): Boolean = guarded(false, Failure.CONNECTION_FAILED) {
        val hid = profile
        val device = adapter!!.bondedDevices.firstOrNull { it.address.equals(host.address, true) }
        if (!registered || hid == null || device == null) {
            fail(Failure.CONNECTION_FAILED)
            false
        } else {
            peers.getOrPut(device.address) { Peer(device) }
            hid.connect(device).also { if (!it) fail(Failure.CONNECTION_FAILED) }
        }
    }

    @SuppressLint("MissingPermission") // guarded() checks permission and catches revocation races.
    override fun disconnect(host: Host): Boolean = guarded(false, Failure.CONNECTION_FAILED) {
        val peer = peers.values.firstOrNull { it.device.address.equals(host.address, true) }
        val hid = profile
        if (hid == null || peer == null) false else {
            // The actual connection state remains callback-driven even after an accepted request.
            hid.disconnect(peer.device).also { if (!it) fail(Failure.CONNECTION_FAILED) }
        }
    }

    @SuppressLint("MissingPermission") // guarded() checks permission and catches revocation races.
    override fun send(host: Host, report: ByteArray): Boolean = guarded(false, Failure.SEND_FAILED) {
        val hid = profile
        val peer = peers.values.firstOrNull { it.device.address.equals(host.address, true) }
        if (!registered || hid == null || peer?.connection != Connection.CONNECTED ||
            (reportHost != null && reportHost != peer.device.address) ||
            report.size != HidKeyboardReport.INPUT_BYTES) {
            fail(Failure.SEND_FAILED)
            false
        } else {
            val snapshot = report.copyOf()
            if (hid.sendReport(peer.device, HidKeyboardReport.REPORT_ID, snapshot)) {
                // Acceptance is not a delivery acknowledgement. GET_REPORT returns the accepted state.
                reportHost = peer.device.address
                peer.input = snapshot
                true
            } else {
                fail(Failure.SEND_FAILED)
                false
            }
        }
    }

    @SuppressLint("MissingPermission") // Cleanup individually catches permissions revoked mid-session.
    override fun close() {
        if (closed) return
        closed = true
        ++generation
        callback = null
        val hid = profile
        val bluetooth = adapter
        profile = null
        val unregister = registrationRequested
        registrationRequested = false
        registered = false
        receiver?.let {
            receiver = null
            try { context.unregisterReceiver(it) } catch (_: RuntimeException) { }
        }
        if (hid != null) {
            for (peer in peers.values.toList()) {
                if (peer.connection == Connection.CONNECTED || peer.connection == Connection.DISCONNECTING) {
                    cleanup { hid.sendReport(peer.device, HidKeyboardReport.REPORT_ID, HidKeyboardReport.input()) }
                }
                if (peer.connection != Connection.DISCONNECTED) cleanup { hid.disconnect(peer.device) }
            }
            if (unregister) cleanup { hid.unregisterApp() }
            if (bluetooth != null) closeProxy(bluetooth, BluetoothProfile.HID_DEVICE, hid)
        }
        peers.clear()
        reportHost = null
        adapter = null
    }

    @SuppressLint("MissingPermission") // guarded() covers registration and all report response calls.
    private fun register(hid: BluetoothHidDevice, expected: Long) {
        guarded(false, Failure.REGISTRATION_FAILED) {
            val accepted = hid.registerApp(
                BluetoothHidDeviceAppSdpSettings("IPA Board", "Bluetooth keyboard", "IPA Board",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD, HidKeyboardReport.descriptor),
                null, null, executor, object : BluetoothHidDevice.Callback() {
                    private fun current() = active(expected) && profile === hid

                    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
                        if (!current()) return
                        if (!isRegistered) {
                            fail(Failure.REGISTRATION_FAILED)
                        } else if (!registered) {
                            registered = true
                            callback?.onReady()
                            if (!current()) return
                            guarded(false, Failure.CONNECTION_FAILED) {
                                // Read actual profile state; a remembered plugged device alone is not proof.
                                for (device in hid.connectedDevices) {
                                    if (hid.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                                        peers.getOrPut(device.address) { Peer(device) }.connection = Connection.CONNECTED
                                    }
                                }
                                true
                            }
                            // Preserve real connection events if a vendor delivers them before readiness.
                            for (peer in peers.values.toList()) {
                                val state = peer.connection ?: continue
                                if (!current()) break
                                publishConnection(peer.device, state)
                            }
                        }
                    }

                    override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                        if (!current()) return
                        val connection = when (state) {
                            BluetoothProfile.STATE_CONNECTING -> Connection.CONNECTING
                            BluetoothProfile.STATE_CONNECTED -> Connection.CONNECTED
                            BluetoothProfile.STATE_DISCONNECTING -> Connection.DISCONNECTING
                            BluetoothProfile.STATE_DISCONNECTED -> Connection.DISCONNECTED
                            else -> return
                        }
                        val peer = peers.getOrPut(device.address) { Peer(device) }
                        peer.connection = connection
                        if (connection == Connection.DISCONNECTED) {
                            peer.input = HidKeyboardReport.input()
                            val wasSelected = reportHost == device.address
                            val hadLeds = wasSelected && peer.leds != 0
                            peer.leds = 0
                            if (wasSelected) reportHost = null
                            if (registered && hadLeds) callback?.onLedsChanged(0)
                        }
                        if (current() && registered) publishConnection(device, connection)
                    }

                    override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                        if (!current() || !registered) return
                        guarded(false, Failure.SEND_FAILED) {
                            when {
                                id.toInt() != HidKeyboardReport.REPORT_ID ->
                                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                                bufferSize < 0 -> hid.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM)
                                else -> {
                                    val peer = reportPeer(device)
                                    if (peer == null) {
                                        hid.reportError(device, BluetoothHidDevice.ERROR_RSP_NOT_READY)
                                    } else {
                                        val report = when (type) {
                                            BluetoothHidDevice.REPORT_TYPE_INPUT -> peer.input
                                            BluetoothHidDevice.REPORT_TYPE_OUTPUT -> byteArrayOf(peer.leds.toByte())
                                            else -> null
                                        }
                                        if (report == null) hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                                        else hid.replyReport(device, type, id,
                                            if (bufferSize == 0) report.copyOf() else report.copyOf(minOf(bufferSize, report.size)))
                                    }
                                }
                            }.also { if (!it) fail(Failure.SEND_FAILED) }
                        }
                    }

                    override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                        if (!current() || !registered) return
                        guarded(false, Failure.SEND_FAILED) {
                            val leds = HidKeyboardReport.ledState(data)
                            val error = when {
                                id.toInt() != HidKeyboardReport.REPORT_ID -> BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID
                                type != BluetoothHidDevice.REPORT_TYPE_OUTPUT -> BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ
                                leds == null -> BluetoothHidDevice.ERROR_RSP_INVALID_PARAM
                                reportPeer(device) == null -> BluetoothHidDevice.ERROR_RSP_NOT_READY
                                else -> BluetoothHidDevice.ERROR_RSP_SUCCESS
                            }
                            val accepted = hid.reportError(device, error)
                            if (!accepted) fail(Failure.SEND_FAILED)
                            else if (error == BluetoothHidDevice.ERROR_RSP_SUCCESS && current()) updateLeds(device, leds!!)
                            accepted
                        }
                    }

                    override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
                        if (!current() || !registered || reportId.toInt() != HidKeyboardReport.REPORT_ID) return
                        val leds = HidKeyboardReport.ledState(data) ?: return
                        updateLeds(device, leds)
                    }

                    override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
                        if (!current() || !registered) return
                        // The descriptor uses the same eight-byte input layout in boot and report modes.
                        if (protocol != BluetoothHidDevice.PROTOCOL_BOOT_MODE &&
                            protocol != BluetoothHidDevice.PROTOCOL_REPORT_MODE) {
                            guarded(false, Failure.SEND_FAILED) {
                                hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                                    .also { if (!it) fail(Failure.SEND_FAILED) }
                            }
                        }
                    }

                    override fun onVirtualCableUnplug(device: BluetoothDevice) {
                        if (!current()) return
                        val peer = peers[device.address]
                        peer?.connection = Connection.DISCONNECTED
                        peer?.input = HidKeyboardReport.input()
                        val wasSelected = reportHost == device.address
                        val hadLeds = wasSelected && peer != null && peer.leds != 0
                        peer?.leds = 0
                        if (wasSelected) reportHost = null
                        if (registered && hadLeds) callback?.onLedsChanged(0)
                        if (current() && registered) publishConnection(device, Connection.DISCONNECTED)
                    }
                })
            registrationRequested = accepted
            if (!accepted) fail(Failure.REGISTRATION_FAILED)
            accepted
        }
    }

    private fun updateLeds(device: BluetoothDevice, leds: Int) {
        val peer = reportPeer(device) ?: return
        if (peer.leds == leds) return
        peer.leds = leds
        callback?.onLedsChanged(leds)
    }

    private fun reportPeer(device: BluetoothDevice): Peer? =
        peers[device.address]?.takeIf {
            reportHost == device.address && it.connection == Connection.CONNECTED
        }

    private fun publishConnection(device: BluetoothDevice, connection: Connection) {
        guarded(false, Failure.CONNECTION_FAILED) {
            callback?.onConnectionChanged(host(device), connection)
            true
        }
    }

    @SuppressLint("MissingPermission") // Called only within guarded(), including callback publication.
    private fun host(device: BluetoothDevice) = Host(device.address, device.name?.takeIf { it.isNotBlank() } ?: device.address)

    private fun active(expected: Long = generation) = started && !closed && !failed && generation == expected

    private fun hasPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // Explicit check plus catch handles asynchronous permission revocation.
    private inline fun <T> guarded(default: T, failure: Failure, action: () -> T): T {
        if (!active()) return default
        if (!hasPermission()) {
            fail(Failure.PERMISSION)
            return default
        }
        return try {
            if (adapter?.isEnabled != true) {
                fail(Failure.BLUETOOTH_OFF)
                default
            } else action()
        } catch (_: SecurityException) {
            fail(Failure.PERMISSION)
            default
        } catch (_: RuntimeException) {
            fail(failure)
            default
        }
    }

    private fun fail(failure: Failure) {
        if (!active()) return
        failed = true
        callback?.onFailure(failure)
    }

    private fun closeProxy(bluetooth: BluetoothAdapter, id: Int, proxy: BluetoothProfile) {
        if (closedProxies.add(proxy)) cleanup { bluetooth.closeProfileProxy(id, proxy) }
    }

    private inline fun cleanup(action: () -> Unit) {
        try { action() } catch (_: RuntimeException) { }
    }
}
