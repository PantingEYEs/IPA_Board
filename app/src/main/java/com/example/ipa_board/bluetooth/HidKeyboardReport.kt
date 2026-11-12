package com.example.ipa_board.bluetooth

/** Boot-compatible keyboard reports; no Android or transport state is retained here. */
object HidKeyboardReport {
    const val REPORT_ID = 0
    const val INPUT_BYTES = 8
    const val MAX_KEYS = 6
    const val LED_MASK = 0x1F

    const val LEFT_CTRL = 0x01
    const val LEFT_SHIFT = 0x02
    const val LEFT_ALT = 0x04
    const val LEFT_GUI = 0x08
    const val RIGHT_CTRL = 0x10
    const val RIGHT_SHIFT = 0x20
    const val RIGHT_ALT = 0x40
    const val RIGHT_GUI = 0x80

    // USB HID 1.11 Appendix B.1: https://usb.org/sites/default/files/documents/hid1_11.pdf
    // Keyboard usages: HUT 1.5 section 10, https://usb.org/sites/default/files/hut1_5.pdf
    private val reportDescriptor = intArrayOf(
        0x05, 0x01,       // Generic Desktop page
        0x09, 0x06,       // Keyboard
        0xA1, 0x01,       // Application collection
        0x05, 0x07,       // Keyboard/Keypad page
        0x19, 0xE0,       // Left Control
        0x29, 0xE7,       // Right GUI
        0x15, 0x00,       // Logical minimum 0
        0x25, 0x01,       // Logical maximum 1
        0x75, 0x01,       // One bit per modifier
        0x95, 0x08,       // Eight modifier bits
        0x81, 0x02,       // Input: data, variable, absolute
        0x95, 0x01,       // One reserved byte
        0x75, 0x08,
        0x81, 0x01,       // Input: constant
        0x05, 0x08,       // LED page
        0x19, 0x01,       // Num Lock
        0x29, 0x05,       // Kana
        0x95, 0x05,
        0x75, 0x01,
        0x91, 0x02,       // Output: five LED bits
        0x95, 0x01,
        0x75, 0x03,
        0x91, 0x01,       // Output: three constant padding bits
        0x05, 0x07,       // Keyboard/Keypad page
        0x19, 0x00,       // Zero denotes an unused key slot
        0x29, 0xDD,       // Highest non-modifier key usage supported here
        0x15, 0x00,
        0x26, 0xDD, 0x00, // Positive logical maximum 221
        0x75, 0x08,
        0x95, 0x06,
        0x81, 0x00,       // Input: six key usages as an array
        0xC0              // End collection; no Report ID item
    ).map(Int::toByte).toByteArray()

    /** Each caller owns its copy, so registration cannot corrupt future descriptors. */
    val descriptor: ByteArray get() = reportDescriptor.copyOf()

    /**
     * Encode pressed keys, preserving first-occurrence order. Modifiers belong only in their bitmask.
     * Reserved/error usages and more than six distinct keys are rejected rather than truncated.
     * With no arguments, the new zero-filled report releases every key and modifier.
     */
    fun input(modifiers: Int = 0, keys: List<Int> = emptyList()): ByteArray {
        require(modifiers in 0..0xFF) { "Modifier mask must fit in eight bits" }
        val pressed = linkedSetOf<Int>()
        for (key in keys) {
            require(key in 0x04..0xA4 || key in 0xB0..0xDD) { "Unsupported keyboard key usage" }
            pressed.add(key)
            require(pressed.size <= MAX_KEYS) { "At most six distinct keyboard keys may be pressed" }
        }
        return ByteArray(INPUT_BYTES).apply {
            this[0] = modifiers.toByte()
            pressed.forEachIndexed { index, key -> this[index + 2] = key.toByte() }
        }
    }

    /** Only the Num/Caps/Scroll Lock, Compose and Kana bits belong to this output report. */
    fun ledState(report: ByteArray): Int? =
        report.singleOrNull()?.toInt()?.and(LED_MASK)
}
