package com.example.ipa_board.bluetooth

import org.junit.Assert.*
import org.junit.Test

class HidKeyboardReportTest {
    @Test fun chordHasModifierReservedAndSixKeySlotsWithoutAnIdPrefix() {
        val report = HidKeyboardReport.input(
            HidKeyboardReport.LEFT_CTRL or HidKeyboardReport.LEFT_SHIFT, listOf(0x04, 0x1D))
        assertArrayEquals(bytes(0x03, 0, 0x04, 0x1D, 0, 0, 0, 0), report)
        assertArrayEquals(ByteArray(8), HidKeyboardReport.input())
    }

    @Test fun allModifierBitsAreEncodedUnsignedAndCanBeUsedWithoutRegularKeys() {
        for (mask in (0..7).map { 1 shl it } + 0xFF) {
            val report = HidKeyboardReport.input(mask)
            assertEquals(mask, report[0].toInt() and 0xFF)
            assertTrue(report.drop(1).all { it == 0.toByte() })
        }
        for (mask in listOf(-1, 0x100, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { HidKeyboardReport.input(mask) }
        }
    }

    @Test fun repeatedKeysUseOneSlotAndKeepFirstOccurrenceOrder() {
        assertArrayEquals(bytes(0, 0, 0x06, 0x04, 0x05, 0, 0, 0),
            HidKeyboardReport.input(keys = listOf(0x06, 0x04, 0x06, 0x05, 0x04)))
        val sixKeysRepeated = (0x04..0x09).flatMap { key -> List(4) { key } }
        assertArrayEquals(bytes(0, 0, 4, 5, 6, 7, 8, 9), HidKeyboardReport.input(keys = sixKeysRepeated))
        assertThrows(IllegalArgumentException::class.java) {
            HidKeyboardReport.input(keys = sixKeysRepeated + 0x0A)
        }
        assertArrayEquals(ByteArray(8), HidKeyboardReport.input())
    }

    @Test fun definedUsageBoundariesAreAcceptedButReservedErrorAndModifierUsagesAreRejected() {
        assertArrayEquals(bytes(0, 0, 0x04, 0x7F, 0x80, 0xA4, 0xB0, 0xDD),
            HidKeyboardReport.input(keys = listOf(0x04, 0x7F, 0x80, 0xA4, 0xB0, 0xDD)))
        val invalid = listOf(-1, 0, 1, 2, 3, 0xA5, 0xAF, 0xDE, 0xDF, 0xE0, 0xE7, 0xE8, 0xFF, 0x100,
            Int.MIN_VALUE, Int.MAX_VALUE)
        for (usage in invalid) {
            assertThrows("usage=$usage", IllegalArgumentException::class.java) {
                HidKeyboardReport.input(keys = listOf(0x04, usage))
            }
        }
    }

    @Test fun reportsAndDescriptorsDoNotShareMutableStorageWithCallers() {
        val keys = mutableListOf(0x04, 0x05)
        val report = HidKeyboardReport.input(keys = keys)
        keys[0] = 0x06
        assertArrayEquals(bytes(0, 0, 4, 5, 0, 0, 0, 0), report)
        report.fill(0x7F)
        assertArrayEquals(bytes(0, 0, 6, 5, 0, 0, 0, 0), HidKeyboardReport.input(keys = keys))

        val original = HidKeyboardReport.descriptor
        val other = HidKeyboardReport.descriptor
        assertNotSame(original, other)
        other.fill(0)
        assertArrayEquals(original, HidKeyboardReport.descriptor)
    }

    @Test fun ledReportsRequireExactlyOneByteAndIgnorePaddingBits() {
        for (value in 0..0xFF) {
            val report = bytes(value)
            assertEquals(value and 0x1F, HidKeyboardReport.ledState(report))
            assertArrayEquals(bytes(value), report)
        }
        for (length in listOf(0, 2, 8)) assertNull(HidKeyboardReport.ledState(ByteArray(length)))
    }

    @Test fun descriptorDeclaresExactlyTheReportsThatTheEncoderProduces() {
        val parsed = parseDescriptor(HidKeyboardReport.descriptor)
        assertEquals(listOf(Triple(0x01, 0x06, 0x01)), parsed.collections)
        assertEquals(0, parsed.openCollections)
        assertTrue("The descriptor must not declare a Report ID", parsed.reportIds.isEmpty())
        assertEquals(0, HidKeyboardReport.REPORT_ID)
        val inputs = parsed.fields.filter { it.type == 8 }
        val outputs = parsed.fields.filter { it.type == 9 }
        assertEquals(3, inputs.size)
        assertEquals(2, outputs.size)
        assertEquals(64, inputs.sumOf { it.size * it.count })
        assertEquals(8, outputs.sumOf { it.size * it.count })
        assertEquals(Field(8, 0x02, 0x07, 1, 8, 0, 1, 0xE0, 0xE7), inputs[0])
        assertEquals(0x01, inputs[1].flags)
        assertEquals(8, inputs[1].size * inputs[1].count)
        assertEquals(Field(8, 0x00, 0x07, 8, 6, 0, 0xDD, 0, 0xDD), inputs[2])
        assertEquals(Field(9, 0x02, 0x08, 1, 5, 0, 1, 1, 5), outputs[0])
        assertEquals(0x01, outputs[1].flags)
        assertEquals(3, outputs[1].size * outputs[1].count)
        assertEquals(HidKeyboardReport.input().size * 8, inputs.sumOf { it.size * it.count })
    }

    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()

    private data class Field(val type: Int, val flags: Int, val page: Int, val size: Int, val count: Int,
        val minimum: Int, val maximum: Int, val usageMinimum: Int?, val usageMaximum: Int?)
    private data class Descriptor(val fields: List<Field>, val collections: List<Triple<Int, Int, Int>>,
        val reportIds: List<Int>, val openCollections: Int)

    /** Decode short HID items to verify report semantics rather than comparing a copied byte blob. */
    private fun parseDescriptor(bytes: ByteArray): Descriptor {
        val fields = mutableListOf<Field>()
        val collections = mutableListOf<Triple<Int, Int, Int>>()
        val reportIds = mutableListOf<Int>()
        var page = 0
        var size = 0
        var count = 0
        var minimum = 0
        var maximum = 0
        var usage: Int? = null
        var usageMinimum: Int? = null
        var usageMaximum: Int? = null
        var depth = 0
        var offset = 0
        while (offset < bytes.size) {
            val prefix = bytes[offset++].toInt() and 0xFF
            require(prefix != 0xFE) { "Unexpected long HID item" }
            val length = (prefix and 3).let { if (it == 3) 4 else it }
            require(offset + length <= bytes.size) { "Truncated HID item" }
            var value = 0
            repeat(length) { index -> value = value or ((bytes[offset++].toInt() and 0xFF) shl (index * 8)) }
            val type = (prefix shr 2) and 3
            val tag = prefix shr 4
            when (type) {
                0 -> {
                    when (tag) {
                        8, 9 -> fields += Field(tag, value, page, size, count, minimum, maximum, usageMinimum, usageMaximum)
                        10 -> { collections += Triple(page, requireNotNull(usage), value); depth++ }
                        12 -> { depth--; require(depth >= 0) }
                        else -> error("Unexpected main item")
                    }
                    usage = null; usageMinimum = null; usageMaximum = null
                }
                1 -> when (tag) {
                    0 -> page = value
                    1 -> minimum = value
                    2 -> maximum = value
                    7 -> size = value
                    8 -> reportIds += value
                    9 -> count = value
                    else -> error("Unexpected global item")
                }
                2 -> when (tag) {
                    0 -> usage = value
                    1 -> usageMinimum = value
                    2 -> usageMaximum = value
                    else -> error("Unexpected local item")
                }
                else -> error("Reserved HID item type")
            }
        }
        return Descriptor(fields, collections, reportIds, depth)
    }
}
