package com.example.ipa_board.bluetooth

/** Physical key reports, never local editor text or engine settings. Calls are serialized on the UI thread. */
interface HidKeyboardBackend {
    data class Host(val address: String, val name: String)
    enum class Connection { CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED }
    enum class Failure {
        UNSUPPORTED, PERMISSION, BLUETOOTH_OFF, SERVICE_UNAVAILABLE,
        REGISTRATION_FAILED, CONNECTION_FAILED, SEND_FAILED, TIMEOUT
    }

    interface Callback {
        fun onReady()
        fun onConnectionChanged(host: Host, connection: Connection)
        fun onLedsChanged(leds: Int)
        fun onFailure(failure: Failure)
    }

    fun start(callback: Callback)
    fun bondedDevices(): List<Host>
    fun connect(host: Host): Boolean
    fun disconnect(host: Host): Boolean
    fun send(host: Host, report: ByteArray): Boolean
    fun close()
}
