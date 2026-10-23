package com.example.ipa_board

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One even-odd geometry contract for drawing adapters, selection and Save filtering. */
object PopupGeometry {
    private const val EPSILON = 1e-9

    fun rotationCenter(config: PopupConfiguration): PopupPoint = PopupPoint(
        (config.vertices.sumOf { it.x.toDouble() } / 4.0).toFloat(),
        (config.vertices.sumOf { it.y.toDouble() } / 4.0).toFloat()
    )

    fun contains(config: PopupConfiguration, point: PopupPoint): Boolean =
        filledPieces(config).any { containsPolygon(it, point.doublePoint()) }

    /** Last item wins; an exposed portion outside the popup is never a target. */
    fun hitItem(config: PopupConfiguration, point: PopupPoint): Int? {
        if (!contains(config, point)) return null
        val p = point.doublePoint()
        return config.items.indices.reversed().firstOrNull { index ->
            val item = config.items[index]
            distanceSquared(p, item.center.doublePoint()) <= item.radius.toDouble() * item.radius
        }
    }

    /** Area centroid of filled lobes, rather than the signed centroid of an intersecting path. */
    fun centroid(config: PopupConfiguration): PopupPoint {
        var area = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        for (piece in filledPieces(config)) {
            // Each slab is a convex trapezoid (possibly a triangle). Local cross products
            // avoid cancellation when a small popup is translated far from the origin.
            for (i in 1 until piece.lastIndex) {
                val a = piece[0]
                val b = piece[i]
                val c = piece[i + 1]
                val triangleArea = abs(cross(b - a, c - a)) / 2.0
                area += triangleArea
                weightedX += triangleArea * (a.x / 3.0 + b.x / 3.0 + c.x / 3.0)
                weightedY += triangleArea * (a.y / 3.0 + b.y / 3.0 + c.y / 3.0)
            }
        }
        if (area == 0.0) return rotationCenter(config)
        return PopupPoint((weightedX / area).toFloat(), (weightedY / area).toFloat())
    }

    /** A configured circle may extend outside the popup; any filled-region overlap retains it. */
    fun circleOverlaps(config: PopupConfiguration, item: PopupItem): Boolean {
        val center = item.center.doublePoint()
        val radiusSquared = item.radius.toDouble() * item.radius
        return filledPieces(config).any { piece ->
            containsPolygon(piece, center) || piece.indices.any { i ->
                distanceToSegmentSquared(center, piece[i], piece[(i + 1) % piece.size]) <= radiusSquared
            }
        }
    }

    fun filterForSave(config: PopupConfiguration): PopupConfiguration = config.copy(
        items = config.items.filter { it.isConfigured && circleOverlaps(config, it) }
    )

    private data class Point(val x: Double, val y: Double) {
        operator fun minus(other: Point) = Point(x - other.x, y - other.y)
    }
    private data class Edge(val a: Point, val b: Point) {
        fun yAt(x: Double): Double = a.y + (x - a.x) / (b.x - a.x) * (b.y - a.y)
    }
    private fun PopupPoint.doublePoint() = Point(x.toDouble(), y.toDouble())
    private fun cross(a: Point, b: Point) = a.x * b.y - a.y * b.x
    private fun distanceSquared(a: Point, b: Point): Double =
        (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)

    private fun distanceToSegmentSquared(p: Point, a: Point, b: Point): Double {
        val delta = b - a
        val length = delta.x * delta.x + delta.y * delta.y
        if (length == 0.0) return distanceSquared(p, a)
        val t = (((p.x - a.x) * delta.x + (p.y - a.y) * delta.y) / length).coerceIn(0.0, 1.0)
        return distanceSquared(p, Point(a.x + t * delta.x, a.y + t * delta.y))
    }

    private fun containsPolygon(vertices: List<Point>, p: Point): Boolean {
        var inside = false
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            if (distanceToSegmentSquared(p, a, b) <= EPSILON * EPSILON) return true
            if ((a.y > p.y) != (b.y > p.y) &&
                p.x < a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x)) inside = !inside
        }
        return inside
    }

    /**
     * Split at every vertex and segment intersection. Between consecutive x values,
     * sorted edge crossings cannot change order: their even-odd pairs are filled
     * trapezoids. This also handles concave and self-intersecting quadrilaterals
     * without imposing a convex-only approximation or counting a lobe negatively.
     */
    private fun filledPieces(config: PopupConfiguration): List<List<Point>> {
        val vertices = config.vertices.map { it.doublePoint() }
        val edges = vertices.indices.map { Edge(vertices[it], vertices[(it + 1) % vertices.size]) }
        val cuts = vertices.mapTo(mutableListOf()) { it.x }
        for (i in edges.indices) for (j in i + 1 until edges.size) {
            val first = edges[i]
            val second = edges[j]
            val r = first.b - first.a
            val s = second.b - second.a
            val denominator = cross(r, s)
            if (denominator == 0.0) continue
            val delta = second.a - first.a
            val t = cross(delta, s) / denominator
            val u = cross(delta, r) / denominator
            if (t in 0.0..1.0 && u in 0.0..1.0) cuts.add(first.a.x + t * r.x)
        }
        val ordered = cuts.distinct().sorted()
        val result = mutableListOf<List<Point>>()
        for (i in 0 until ordered.lastIndex) {
            val left = ordered[i]
            val right = ordered[i + 1]
            val mid = left / 2.0 + right / 2.0
            if (right <= left || mid == left || mid == right) continue
            val crossing = edges.filter { mid > min(it.a.x, it.b.x) && mid < max(it.a.x, it.b.x) }
                .sortedBy { it.yAt(mid) }
            for (j in 0 until crossing.size - 1 step 2) {
                val lower = crossing[j]
                val upper = crossing[j + 1]
                if (upper.yAt(mid) <= lower.yAt(mid)) continue
                result.add(listOf(Point(left, lower.yAt(left)), Point(right, lower.yAt(right)),
                    Point(right, upper.yAt(right)), Point(left, upper.yAt(left))))
            }
        }
        return result
    }
}
