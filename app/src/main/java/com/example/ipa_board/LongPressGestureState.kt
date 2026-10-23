package com.example.ipa_board

/** Shared long-press lifecycle; surfaces supply selection geometry, never function semantics. */
internal class LongPressGestureState {
    var selected: LongPressItem? = null
        private set
    var canceled = false
        private set
    var activated = false
        private set
    var repeating = false
        private set

    fun activate(item: LongPressItem?, repeat: Boolean) {
        if (canceled) return
        selected = item
        activated = true
        repeating = repeat
    }

    // A horizontal selection changes the release target, without changing an active repeat.
    fun select(item: LongPressItem) {
        if (!canceled && activated) selected = item
    }

    fun cancel() {
        canceled = true
        repeating = false
    }

    fun release(): LongPressItem? = selected.takeIf { activated && !canceled && !repeating }
}
