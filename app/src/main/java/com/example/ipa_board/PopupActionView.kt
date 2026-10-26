package com.example.ipa_board

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.hypot

/** Drawing only. The originating keyboard key owns and interprets the entire touch stream. */
// This overlay receives immutable runtime geometry and is constructed only in code.
@SuppressLint("ViewConstructor")
internal class PopupActionView(
    context: Context,
    private val configuration: PopupConfiguration,
    private val originX: Double,
    private val originY: Double,
    private val backgroundColor: Int,
    private val symbolColor: Int,
    private val shift: Boolean,
    private val ctrl: Boolean,
    pointerSource: View? = null
) : View(context) {
    internal val activeLabel: String? get() = activeIndex?.let { configuration.items.getOrNull(it)?.binding?.previewText(shift, ctrl) }
    var activeIndex: Int? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointerOverlay = KeyPreviewPointerOverlay(this, pointerSource)
    private data class Point(val x: Double, val y: Double)
    private data class DrawItem(
        val index: Int, val x: Float, val y: Float, val radius: Float,
        val coversWindow: Boolean, val label: String?, val textSize: Float, val baseline: Float
    )
    private val outline = Path()
    private var drawItems: List<DrawItem> = emptyList()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildDrawing()
    }

    /** Geometry is immutable for this window; pointer movement only changes its active colors. */
    private fun rebuildDrawing() {
        val density = resources.displayMetrics.density.toDouble()
        // Clip in Double before handing coordinates to Canvas. Valid imported Float values
        // can overflow after density conversion; neither the persisted shape nor its scale changes.
        var polygon = configuration.vertices.map { Point(originX + it.x * density, originY + it.y * density) }
        fun clip(inside: (Point) -> Boolean, intersection: (Point, Point) -> Point) {
            if (polygon.isEmpty()) return
            val result = mutableListOf<Point>()
            var previous = polygon.last()
            for (point in polygon) {
                if (inside(point) != inside(previous)) result += intersection(previous, point)
                if (inside(point)) result += point
                previous = point
            }
            polygon = result
        }
        fun atX(a: Point, b: Point, x: Double): Point = Point(x, a.y + (b.y - a.y) * ((x - a.x) / (b.x - a.x)))
        fun atY(a: Point, b: Point, y: Double): Point = Point(a.x + (b.x - a.x) * ((y - a.y) / (b.y - a.y)), y)
        clip({ it.x >= 0 }, { a, b -> atX(a, b, 0.0) })
        clip({ it.x <= width }, { a, b -> atX(a, b, width.toDouble()) })
        clip({ it.y >= 0 }, { a, b -> atY(a, b, 0.0) })
        clip({ it.y <= height }, { a, b -> atY(a, b, height.toDouble()) })
        outline.apply {
            reset()
            fillType = Path.FillType.EVEN_ODD
            polygon.forEachIndexed { index, p ->
                if (index == 0) moveTo(p.x.toFloat(), p.y.toFloat()) else lineTo(p.x.toFloat(), p.y.toFloat())
            }
            close()
        }
        paint.strokeWidth = density.toFloat()
        paint.textAlign = Paint.Align.CENTER
        val cached = mutableListOf<DrawItem>()
        val corners = listOf(Point(0.0, 0.0), Point(width.toDouble(), 0.0),
            Point(0.0, height.toDouble()), Point(width.toDouble(), height.toDouble()))
        configuration.items.forEachIndexed { index, item ->
            val cx = originX + item.center.x * density
            val cy = originY + item.center.y * density
            val radius = item.radius * density
            if (cx + radius < 0 || cy + radius < 0 || cx - radius > width || cy - radius > height) return@forEachIndexed
            val coversWindow = corners.all { hypot(cx - it.x, cy - it.y) <= radius }
            // Canvas cannot represent the very rare partial circle beyond Float range.
            // Omit that unrenderable object instead of introducing infinities or rewriting it.
            if (!coversWindow && (!cx.toFloat().isFinite() || !cy.toFloat().isFinite() || !radius.toFloat().isFinite())) return@forEachIndexed
            // A center outside this window has no visible centered icon.
            val hasIcon = cx in 0.0..width.toDouble() && cy in 0.0..height.toDouble()
            var label: String? = null
            var baseline = 0f
            if (hasIcon) {
                paint.textSize = (radius * .8).coerceAtMost(maxOf(width, height).toDouble()).toFloat()
                label = item.binding.previewText(shift, ctrl).ifEmpty { "∅" }
                val textWidth = paint.measureText(label)
                if (textWidth > radius * 1.7) paint.textSize *= (radius * 1.7 / textWidth).toFloat()
                baseline = cy.toFloat() - (paint.ascent() + paint.descent()) / 2
            }
            cached += DrawItem(index, cx.toFloat(), cy.toFloat(), radius.toFloat(), coversWindow,
                label, paint.textSize, baseline)
        }
        drawItems = cached
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        pointerOverlay.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = backgroundColor
        paint.style = Paint.Style.FILL
        canvas.drawPath(outline, paint)
        paint.style = Paint.Style.STROKE
        paint.color = symbolColor
        canvas.drawPath(outline, paint)
        var i = 0
        while (i < drawItems.size) {
            val item = drawItems[i++]
            paint.style = Paint.Style.FILL
            paint.color = if (item.index == activeIndex) symbolColor else backgroundColor
            if (item.coversWindow) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            } else {
                canvas.drawCircle(item.x, item.y, item.radius, paint)
            }
            item.label?.let { label ->
                paint.style = Paint.Style.FILL
                paint.color = if (item.index == activeIndex) backgroundColor else symbolColor
                paint.textSize = item.textSize
                canvas.drawText(label, item.x, item.baseline, paint)
            }
        }
    }
}
