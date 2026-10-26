package com.example.ipa_board

import org.junit.Assert.*
import org.junit.Test

class ClassicPreviewGeometryTest {
    private val items = listOf(LongPressItem("t͡ʃ,😀"), LongPressItem(action = KeyAction.COPY), LongPressItem("β"))
    private fun config() = ClassicPreviewGeometry.configuration(items, 1, 60f, 56f)

    @Test fun completeMixedQueueHasOneItemPerCellAndDefaultAlignedAnchor() {
        val config = config()
        assertEquals(items, config.items.map { it.binding })
        assertEquals(126f, config.vertices.maxOf { it.x }, .001f)
        assertEquals(56f, config.vertices.maxOf { it.y }, .001f)
        assertEquals(17.5f, config.items[1].radius, .001f)
        assertEquals(28f, config.items[1].center.y, .001f)
        assertEquals(10.5f, config.items.first().center.x - config.items.first().radius, .001f)
        assertEquals(10.5f, config.vertices.maxOf { it.x } - config.items.last().center.x - config.items.last().radius, .001f)
        assertEquals(config.items[1].center.x, config.anchor.x, .001f)
        for (i in items.indices) {
            assertEquals(i, ClassicPreviewGeometry.selectedIndex(config, 1, (i - 1) * 35f))
        }
    }

    @Test fun overrunAndPartialReturnRemainAtTheSameEndUntilReentering() {
        val config = config()
        for (x in listOf(10000f, 5000f, 100f, 63f, 18f)) assertEquals(2, ClassicPreviewGeometry.selectedIndex(config, 1, x))
        assertEquals(1, ClassicPreviewGeometry.selectedIndex(config, 1, 17f))
        for (x in listOf(-10000f, -5000f, -100f, -63f, -18f)) assertEquals(0, ClassicPreviewGeometry.selectedIndex(config, 1, x))
        assertEquals(1, ClassicPreviewGeometry.selectedIndex(config, 1, -17f))
        assertEquals(0, ClassicPreviewGeometry.selectedIndex(config, 1, Float.NEGATIVE_INFINITY))
        assertEquals(2, ClassicPreviewGeometry.selectedIndex(config, 1, Float.POSITIVE_INFINITY))
        assertNull(ClassicPreviewGeometry.selectedIndex(config, 1, Float.NaN))
    }

    @Test fun legacyDefaultIsFirstAndEditingQueueRetainsIndependentBindings() {
        val legacy = KeySlot(1f, "a", longPressText = "ɑ, \\,, β", swipeLeftAction = KeyAction.COPY,
            popupConfiguration = PopupConfiguration.default())
        assertEquals(LongPressItem("ɑ"), legacy.defaultLongPressItem)
        val edited = legacy.withLongPressQueue(items, 1)
        assertEquals(items[1], edited.defaultLongPressItem)
        assertEquals(legacy.text, edited.text)
        assertEquals(legacy.swipeLeftAction, edited.swipeLeftAction)
        assertEquals(legacy.popupConfiguration, edited.popupConfiguration)
        assertEquals(items[0], edited.copy(longPressDefaultIndex = -1).defaultLongPressItem)
        assertEquals(items[0], edited.copy(longPressDefaultIndex = 100).defaultLongPressItem)
    }

    @Test fun explicitEmptyQueueDisablesLegacyRepeatFallbackButNotItsTap() {
        val legacy = KeySlot(1f, action = KeyAction.REPEAT_LEFT)
        assertTrue(legacy.hasLongPress)
        assertEquals(KeyAction.REPEAT_LEFT, legacy.defaultLongPressItem?.action)
        val edited = legacy.withLongPressQueue(emptyList(), 0)
        assertFalse(edited.hasLongPress)
        assertTrue(edited.effectiveLongPressItems.isEmpty())
        assertNull(edited.defaultLongPressItem)
        assertEquals(legacy.action, edited.action)
    }

    @Test fun generatedPreviewCanShortenLongLegacyLabelsWithoutChangingTheirBinding() {
        val text = "β😀".repeat(500)
        val original = LongPressItem(text)
        val config = ClassicPreviewGeometry.configuration(listOf(original), 0, 60f, 56f)
        assertTrue(config.items.single().binding.text.length <= 1000)
        assertTrue(config.items.single().binding.text.endsWith("…"))
        assertEquals(text, original.text)
        assertEquals(0, ClassicPreviewGeometry.selectedIndex(config, 0, 10000f))
    }

    @Test fun longQueueMirrorDoesNotLoseActualBindingsOrExceedLegacyTextLimit() {
        val texts = listOf(LongPressItem("a".repeat(700)), LongPressItem("b".repeat(700)))
        val edited = KeySlot(1f, "tap").withLongPressQueue(texts, 1)
        assertEquals(texts, edited.effectiveLongPressItems)
        assertEquals(texts[1], edited.defaultLongPressItem)
        assertTrue(edited.longPressText.length <= 1000)
        KeyboardLayout("Long queue", listOf(RowLayout(1f, listOf(edited))))
    }
}
