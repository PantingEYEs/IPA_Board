package com.example.ipa_board.bluetooth

/** Text-to-key conversion for a receiver using a US keyboard layout; no transliteration. */
object HidTextEncoder {
    data class Stroke(val usage: Int, val modifiers: Int = 0)

    // USB HID Usage Tables 1.5, Keyboard/Keypad page (0x07), US key legends:
    // https://usb.org/sites/default/files/hut1_5.pdf (section 10)
    private val symbols = buildMap<Char, Stroke> {
        fun key(usage: Int, plain: Char, shifted: Char) {
            put(plain, Stroke(usage))
            put(shifted, Stroke(usage, HidKeyboardReport.LEFT_SHIFT))
        }
        val digits = "1234567890"
        val shiftedDigits = "!@#\$%^&*()"
        digits.forEachIndexed { index, digit -> key(0x1e + index, digit, shiftedDigits[index]) }
        put(' ', Stroke(0x2c))
        key(0x2d, '-', '_')
        key(0x2e, '=', '+')
        key(0x2f, '[', '{')
        key(0x30, ']', '}')
        key(0x31, '\\', '|')
        key(0x33, ';', ':')
        key(0x34, '\'', '"')
        key(0x35, '\u0060', '~')
        key(0x36, ',', '<')
        key(0x37, '.', '>')
        key(0x38, '/', '?')
    }

    /** Returns the complete sequence, or null if any character cannot be represented exactly. */
    fun strokes(text: String, capsLock: Boolean = false): List<Stroke>? {
        val result = ArrayList<Stroke>(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            val stroke = when (character) {
                in 'a'..'z' -> Stroke(0x04 + (character - 'a'),
                    if (capsLock) HidKeyboardReport.LEFT_SHIFT else 0)
                in 'A'..'Z' -> Stroke(0x04 + (character - 'A'),
                    if (capsLock) 0 else HidKeyboardReport.LEFT_SHIFT)
                '\t' -> Stroke(0x2b)
                '\n' -> Stroke(0x28)
                '\r' -> {
                    if (index < text.length && text[index] == '\n') index++
                    Stroke(0x28)
                }
                else -> symbols[character] ?: return null
            }
            result += stroke
        }
        return result
    }
}
