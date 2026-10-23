package com.example.ipa_board

import org.junit.Assert.*
import org.junit.Test

class PopupGeometryTest {
    private fun point(x: Float, y: Float) = PopupPoint(x, y)
    private fun polygon(vararg vertices: PopupPoint) = PopupConfiguration(vertices.toList(), point(0f, 10f))
    private fun square() = polygon(point(0f, 0f), point(6f, 0f), point(6f, 6f), point(0f, 6f))
    private fun item(x: Float, y: Float, radius: Float = 1f, text: String = "a") =
        PopupItem(point(x, y), radius, LongPressItem(text))
    private fun assertPoint(x: Float, y: Float, actual: PopupPoint) {
        assertEquals(x, actual.x, 0.0001f)
        assertEquals(y, actual.y, 0.0001f)
    }

    @Test fun concaveOutlineUsesItsFilledRegionRatherThanBoundingBox() {
        val config = polygon(point(0f, 0f), point(6f, 0f), point(2f, 2f), point(0f, 6f))
        assertTrue(PopupGeometry.contains(config, point(1f, 1f)))
        assertFalse(PopupGeometry.contains(config, point(4f, 4f)))
        assertFalse(PopupGeometry.circleOverlaps(config, item(4f, 4f, 0.2f)))
        assertPoint(5f / 3f, 5f / 3f, PopupGeometry.centroid(config))
    }

    @Test fun selfIntersectionCountsBothFilledLobesPositively() {
        val bowtie = polygon(point(0f, 0f), point(6f, 6f), point(0f, 6f), point(2f, 0f))
        assertTrue(PopupGeometry.contains(bowtie, point(1f, 0.2f)))
        assertTrue(PopupGeometry.contains(bowtie, point(2f, 5f)))
        assertFalse(PopupGeometry.contains(bowtie, point(4f, 2f)))
        assertPoint(71f / 30f, 4.1f, PopupGeometry.centroid(bowtie))
        assertPoint(2f, 3f, PopupGeometry.rotationCenter(bowtie))
        assertFalse(PopupGeometry.circleOverlaps(bowtie, item(4f, 2f, 0.1f)))
        assertTrue(PopupGeometry.circleOverlaps(bowtie, item(1f, 0.2f, 0.1f)))
    }

    @Test fun zeroAreaCentroidFallsBackToVertexMeanWithoutRetainingUnfilledItems() {
        val line = polygon(point(0f, 0f), point(2f, 2f), point(4f, 4f), point(6f, 6f))
        assertPoint(3f, 3f, PopupGeometry.centroid(line))
        assertFalse(PopupGeometry.circleOverlaps(line, item(3f, 3f)))
        assertFalse(PopupGeometry.contains(line, point(3f, 3f)))
        assertNull(PopupGeometry.hitItem(line.copy(items = listOf(item(3f, 3f))), point(3f, 3f)))
        val point = polygon(point(3f, 4f), point(3f, 4f), point(3f, 4f), point(3f, 4f))
        assertPoint(3f, 4f, PopupGeometry.centroid(point))
    }

    @Test fun savingRetainsPartiallyOverlappingBoundItemsAndTheirStackingOrder() {
        val inside = item(2f, 2f)
        val protruding = item(6.5f, 2f)
        val cornerOverlap = item(6.5f, 6.5f)
        val unbound = item(2f, 2f, text = "")
        val outside = item(8f, 8f)
        val function = item(3f, 3f).copy(binding = LongPressItem(action = KeyAction.COPY))
        val config = square().copy(items = listOf(inside, protruding, unbound, outside, cornerOverlap, function))
        assertEquals(listOf(inside, protruding, cornerOverlap, function), PopupGeometry.filterForSave(config).items)
        assertEquals(6, config.items.size)
        assertEquals(config.vertices, PopupGeometry.filterForSave(config).vertices)
        assertEquals(config.anchor, PopupGeometry.filterForSave(config).anchor)
    }

    @Test fun targetingUsesTopmostCircleAndClipsItsProtrudingRegion() {
        val config = square().copy(items = listOf(item(3f, 3f, 2f), item(4f, 3f, 3f)))
        assertEquals(1, PopupGeometry.hitItem(config, point(3f, 3f)))
        assertEquals(0, PopupGeometry.hitItem(config.copy(items = config.items.reversed()), point(5.5f, 3f)))
        assertNull(PopupGeometry.hitItem(config, point(6.5f, 3f)))
        assertNull(PopupGeometry.hitItem(config, point(0f, 0f)))
        assertEquals(1, PopupGeometry.hitItem(config, point(6f, 3f)))
    }

    @Test fun orientationAndTranslationPreserveFilledCentroid() {
        val config = polygon(point(0f, 0f), point(6f, 6f), point(0f, 6f), point(2f, 0f))
        val reversed = config.copy(vertices = config.vertices.reversed())
        assertPoint(71f / 30f, 4.1f, PopupGeometry.centroid(reversed))
        val moved = config.copy(vertices = config.vertices.map { point(it.x + 10000f, it.y - 10000f) })
        assertPoint(10000f + 71f / 30f, -10000f + 4.1f, PopupGeometry.centroid(moved))
    }

    @Test fun defaultAndModeSwitchesPreserveIndependentBindingsAndGeometry() {
        val config = PopupConfiguration.default()
        val slot = KeySlot(1f, "t͡ʃ", longPressItems = listOf(LongPressItem(",")),
            swipeLeftText = "left", swipeRightAction = KeyAction.PASTE, popupConfiguration = config)
        val layout = KeyboardLayout("Modes", listOf(RowLayout(1f, listOf(slot))))
        val popup = layout.withKeyMapping(0, 0, slot.text, slot.action, multifunctionMode = MultifunctionMode.POPUP)
        val popupSlot = popup.rows.single().slots.single()
        assertTrue(popupSlot.hasLongPress)
        assertFalse(popupSlot.hasSwipeLeft)
        assertFalse(popupSlot.hasSwipeRight)
        assertEquals(slot.longPressItems, popupSlot.longPressItems)
        assertEquals(config, popupSlot.popupConfiguration)
        val back = popup.withKeyMapping(0, 0, slot.text, slot.action, multifunctionMode = MultifunctionMode.CLASSIC)
        assertEquals(layout, back)
        assertTrue(back.rows.single().slots.single().hasSwipeLeft)
        assertEquals("t͡ʃ", popupSlot.text)
        val cleared = popup.cleared().rows.single().slots.single()
        assertNull(cleared.popupConfiguration)
        assertEquals(MultifunctionMode.CLASSIC, cleared.multifunctionMode)
        assertEquals(emptyList<LongPressItem>(), cleared.longPressItems)
        assertTrue(config.items.isEmpty())
        assertTrue(KeySlot(1f, multifunctionMode = MultifunctionMode.POPUP).hasLongPress)
    }

    @Test fun invalidGeometryAndOversizedTextAreRejectedBeforeRendering() {
        assertThrows(IllegalArgumentException::class.java) { point(Float.NaN, 0f) }
        assertThrows(IllegalArgumentException::class.java) { point(0f, Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { item(0f, 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { item(0f, 0f, -1f) }
        assertThrows(IllegalArgumentException::class.java) { item(0f, 0f, Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { item(0f, 0f, text = "x".repeat(1001)) }
        assertThrows(IllegalArgumentException::class.java) { polygon(point(0f, 0f)) }
    }
}
