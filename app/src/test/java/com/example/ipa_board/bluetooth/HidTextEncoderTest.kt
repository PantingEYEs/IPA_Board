package com.example.ipa_board.bluetooth

import com.example.ipa_board.bluetooth.HidTextEncoder.Stroke
import org.junit.Assert.*
import org.junit.Test

class HidTextEncoderTest {
    private val shift = HidKeyboardReport.LEFT_SHIFT

    @Test fun everyPrintableAsciiCharacterUsesItsUsPhysicalKeyAndRequiredShift() {
        // Independent physical-row reference from USB HUT 1.5 section 10.
        val rows = listOf(
            Triple("\u00601234567890-=", "~!@#\$%^&*()_+",
                listOf(0x35, 0x1e, 0x1f, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x2d, 0x2e)),
            Triple("qwertyuiop[]\\", "QWERTYUIOP{}|",
                listOf(0x14, 0x1a, 0x08, 0x15, 0x17, 0x1c, 0x18, 0x0c, 0x12, 0x13, 0x2f, 0x30, 0x31)),
            Triple("asdfghjkl;'", "ASDFGHJKL:\"",
                listOf(0x04, 0x16, 0x07, 0x09, 0x0a, 0x0b, 0x0d, 0x0e, 0x0f, 0x33, 0x34)),
            Triple("zxcvbnm,./", "ZXCVBNM<>?",
                listOf(0x1d, 0x1b, 0x06, 0x19, 0x05, 0x11, 0x10, 0x36, 0x37, 0x38)),
        )
        val expected = mutableMapOf(' ' to Stroke(0x2c))
        for ((plain, shifted, usages) in rows) {
            plain.forEachIndexed { index, character -> expected[character] = Stroke(usages[index]) }
            shifted.forEachIndexed { index, character -> expected[character] = Stroke(usages[index], shift) }
        }
        val ascii = (0x20..0x7e).map(Int::toChar).joinToString("")
        assertEquals(ascii.toSet(), expected.keys)
        assertEquals(ascii.map { expected.getValue(it) }, HidTextEncoder.strokes(ascii))
    }

    @Test fun capsLockInvertsShiftForLettersWithoutChangingTheirPhysicalKeys() {
        val lower = "abcdefghijklmnopqrstuvwxyz"
        val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val usages = (0x04..0x1d).toList()
        assertEquals(usages.map { Stroke(it, shift) }, HidTextEncoder.strokes(lower, capsLock = true))
        assertEquals(usages.map { Stroke(it) }, HidTextEncoder.strokes(upper, capsLock = true))
        assertEquals(listOf(Stroke(4, shift), Stroke(4), Stroke(0x1d, shift), Stroke(0x1d)),
            HidTextEncoder.strokes("aAzZ", capsLock = true))
        assertEquals(listOf(Stroke(4), Stroke(4, shift), Stroke(0x1d), Stroke(0x1d, shift)),
            HidTextEncoder.strokes("aAzZ"))
    }

    @Test fun capsLockDoesNotChangeDigitsPunctuationWhitespaceOrTheirShiftRequirements() {
        val nonLetters = (0x20..0x7e).map(Int::toChar)
            .filter { it !in 'a'..'z' && it !in 'A'..'Z' }.joinToString("") + "\t\r\n"
        assertEquals(HidTextEncoder.strokes(nonLetters), HidTextEncoder.strokes(nonLetters, capsLock = true))
        val expected = listOf(Stroke(0x1e), Stroke(0x1e, shift), Stroke(0x27), Stroke(0x27, shift),
            Stroke(0x2d), Stroke(0x2d, shift), Stroke(0x31), Stroke(0x31, shift))
        assertEquals(expected, HidTextEncoder.strokes("1!0)-_\\|", capsLock = true))
    }

    @Test fun tabsAndNewlinesPreserveOrderAndCrLfProducesOnlyOneEnter() {
        assertEquals(emptyList<Stroke>(), HidTextEncoder.strokes(""))
        assertEquals(emptyList<Stroke>(), HidTextEncoder.strokes("", capsLock = true))
        for ((text, enters) in listOf("\r\n" to 1, "\r" to 1, "\n" to 1, "\r\r\n" to 2, "\n\r" to 2)) {
            assertEquals(List(enters) { Stroke(0x28) }, HidTextEncoder.strokes(text))
        }
        assertEquals(listOf(Stroke(0x2b), Stroke(4, shift), Stroke(0x28), Stroke(5), Stroke(0x28),
            Stroke(6), Stroke(0x28), Stroke(7), Stroke(0x2b)),
            HidTextEncoder.strokes("\tA\r\nb\rc\nd\t"))
    }

    @Test fun unsupportedControlsRejectTheEntireTextRatherThanBecomingKeyboardActions() {
        for (code in (0..0x1f).filter { it !in listOf(0x09, 0x0a, 0x0d) } + (0x7f..0x9f)) {
            val control = code.toChar().toString()
            for (text in listOf(control + "after", "before" + control, "before" + control + "after")) {
                assertNull("Unsupported control code $code", HidTextEncoder.strokes(text))
            }
        }
    }

    @Test fun unicodeAndMalformedSurrogatesRejectTheWholeBatchWithoutReplacementOrTransliteration() {
        val unsupported = listOf("é", "e\u0301", "中文", "Ａ", "\u00a0", "😀", "👩‍💻",
            "\u200d", "\ufe0f", "\ud800", "\udfff", "\ud800a", "a\udfff")
        for (fragment in unsupported) for (caps in listOf(false, true)) {
            for (text in listOf(fragment, "ASCII " + fragment, fragment + " tail", "before " + fragment + " after")) {
                assertNull(HidTextEncoder.strokes(text, caps))
            }
        }
        assertEquals(listOf(Stroke(4), Stroke(5, shift)), HidTextEncoder.strokes("aB"))
    }

    @Test fun encodingHasNoUiLengthLimitAndEachCallReturnsItsOwnCompleteSequence() {
        val text = "aA1!\t\r\n".repeat(1024)
        val expectedChunk = listOf(Stroke(4), Stroke(4, shift), Stroke(0x1e), Stroke(0x1e, shift),
            Stroke(0x2b), Stroke(0x28))
        assertEquals(List(1024) { expectedChunk }.flatten(), HidTextEncoder.strokes(text))
        val first = requireNotNull(HidTextEncoder.strokes("aA"))
        val second = requireNotNull(HidTextEncoder.strokes("aA"))
        assertNotSame(first, second)
        assertEquals(first, second)
    }
}
