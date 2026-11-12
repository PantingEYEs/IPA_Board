package com.example.ipa_board.ime

/** Enter the existing engine mode once per owned input screen, independently of Bluetooth state. */
class BluetoothRemoteEditorMode {
    private val enteredSessions = mutableSetOf<String>()

    fun enter(editorPackage: String?, ownPackage: String, options: String?,
              mode: RemoteKeyboardMode): RemoteKeyboardMode.Result? {
        if (editorPackage != ownPackage || options?.startsWith(PREFIX) != true) return null
        val session = options.removePrefix(PREFIX)
        if (session.isBlank() || session.length > 128 || !enteredSessions.add(session)) return null
        // Reopening the input connection must not re-disable manually enabled engines or
        // re-enter the mode after the user explicitly restores it on this screen.
        return if (mode.active) null else mode.toggle()
    }

    companion object { const val PREFIX = "com.example.ipa_board.bluetooth_remote:" }
}
