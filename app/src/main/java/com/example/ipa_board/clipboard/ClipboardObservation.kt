package com.example.ipa_board.clipboard

/** Owns one listener for the service lifetime, with optional recording while its view is hidden. */
internal class ClipboardObservation(
    private val subscribe: () -> Unit,
    private val unsubscribe: () -> Unit
) {
    private var running = false
    private var syncEnabled = true
    private var inputViewShown = false
    var isListening = false
        private set

    fun start(enabled: Boolean) {
        running = true
        syncEnabled = enabled
        reconcile()
    }

    fun setSyncEnabled(enabled: Boolean) {
        syncEnabled = enabled
        reconcile()
    }

    fun setInputViewShown(shown: Boolean) {
        inputViewShown = shown
        reconcile()
    }

    fun stop() {
        running = false
        reconcile()
    }

    private fun reconcile() {
        val needed = running && (syncEnabled || inputViewShown)
        if (needed == isListening) return
        if (needed) subscribe() else unsubscribe()
        isListening = needed
    }
}
