package com.example.ipa_board

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.BlendMode
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Edits only a draft. The containing key dialog owns persistence and Save/Cancel recovery. */
// Programmatic-only: a key-specific draft is mandatory; XML inflation has no valid initial state.
@SuppressLint("ViewConstructor")
class PopupActionEditorView(context: Context, initial: PopupConfiguration) : LinearLayout(context) {
    var configuration: PopupConfiguration = initial
        private set
    var resetConfiguration: PopupConfiguration = PopupConfiguration.default()
    var onConfigurationChanged: ((PopupConfiguration) -> Unit)? = null

    private sealed class Selection {
        data object None : Selection()
        data object Popup : Selection()
        data object Anchor : Selection()
        data class Item(val index: Int) : Selection()
    }

    private var selection: Selection = Selection.None
    private val undoHistory = ArrayDeque<PopupConfiguration>()
    private val redoHistory = ArrayDeque<PopupConfiguration>()
    private var updatingShape = false
    private var itemLabels = initial.items.map { it.binding.previewText().ifEmpty { "∅" } }
    private val viewport = EditorViewport(context)
    private val shapeSelector = Spinner(context)
    private val undo = button(R.string.popup_editor_undo) { undoEdit() }
    private val redo = button(R.string.popup_editor_redo) { redoEdit() }


    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            setText(R.string.popup_editor_help)
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, dp(4f), 0, dp(6f))
        })
        viewport.contentDescription = context.getString(R.string.popup_editor_viewport)
        addView(viewport, LayoutParams(LayoutParams.MATCH_PARENT, dp(280f)))
        shapeSelector.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            listOf(R.string.popup_editor_uniform, R.string.popup_editor_freeform, R.string.popup_editor_distort, R.string.popup_editor_rotate).map(context::getString))
        shapeSelector.contentDescription = context.getString(R.string.popup_editor_shape_constraint)
        shapeSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingShape && selection == Selection.Popup) {
                    edit(configuration.copy(shapeMode = PopupShapeMode.entries[position]))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        addView(shapeSelector)
        addView(LinearLayout(context).apply {
            addView(button(R.string.popup_editor_add_item) {
                edit(configuration.copy(items = configuration.items + PopupItem(configuration.anchor, 24f)))
                select(Selection.Item(configuration.items.lastIndex))
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(undo, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(redo, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(LinearLayout(context).apply {
            addView(button(R.string.popup_editor_reset) {
                val snapshot = configuration
                edit(resetConfiguration)
                select(Selection.None)
                viewport.centerOnAnchor()
                publish()
                Snackbar.make(this@PopupActionEditorView, R.string.popup_editor_reset_notice, Snackbar.LENGTH_LONG)
                    .setAction(R.string.popup_editor_undo) { setConfiguration(snapshot) }.show()
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        refreshControls()
    }

    fun setConfiguration(value: PopupConfiguration) {
        viewport.cancelPendingTap()
        configuration = value
        undoHistory.clear()
        redoHistory.clear()
        select(Selection.None)
        publish()
    }

    fun setPopupColors(backgroundColor: Int, symbolColor: Int) {
        viewport.popupColor = backgroundColor
        viewport.symbolColor = symbolColor
        viewport.invalidate()
    }

    fun setPointerColors(axisColor: Int, crosshairColor: Int, differenceBlend: Boolean = false) {
        viewport.axisColor = axisColor
        viewport.crosshairColor = crosshairColor
        viewport.differenceBlend = differenceBlend
        viewport.invalidate()
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun button(label: Int, action: () -> Unit): Button = Button(context).apply {
        setText(label)
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = null
        background = GradientDrawable().apply {
            setColor(Color.rgb(60, 60, 60))
            cornerRadius = dp(4f).toFloat()
        }
        setOnClickListener { viewport.cancelPendingTap(); action() }
    }

    private fun select(value: Selection) {
        selection = value
        refreshControls()
        viewport.invalidate()
    }

    private fun refreshControls() {
        updatingShape = true
        shapeSelector.visibility = if (selection == Selection.Popup) VISIBLE else GONE
        shapeSelector.setSelection(configuration.shapeMode.ordinal)
        updatingShape = false
        undo.isEnabled = undoHistory.isNotEmpty()
        redo.isEnabled = redoHistory.isNotEmpty()
        undo.alpha = if (undo.isEnabled) 1f else .4f
        redo.alpha = if (redo.isEnabled) 1f else .4f
    }

    private fun publish() {
        itemLabels = configuration.items.map { it.binding.previewText().ifEmpty { "∅" } }
        refreshControls()
        viewport.invalidate()
        onConfigurationChanged?.invoke(configuration)
    }

    private fun edit(value: PopupConfiguration) {
        if (value == configuration) return
        undoHistory.addLast(configuration)
        redoHistory.clear()
        configuration = value
        publish()
    }

    private fun undoEdit() {
        if (undoHistory.isEmpty()) return
        redoHistory.addLast(configuration)
        configuration = undoHistory.removeLast()
        select(Selection.None)
        publish()
    }

    private fun redoEdit() {
        if (redoHistory.isEmpty()) return
        undoHistory.addLast(configuration)
        configuration = redoHistory.removeLast()
        select(Selection.None)
        publish()
    }

    private fun editItem(index: Int) {
        val original = configuration.items.getOrNull(index) ?: return
        val body = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12f), 0, dp(12f), 0) }
        val function = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, KeyAction.entries.map { it.title })
            setSelection(KeyAction.entries.indexOf(original.binding.action))
            contentDescription = context.getString(R.string.popup_editor_item_function)
        }
        val textInput = EditText(context).apply { setText(original.binding.text); setHint(R.string.popup_editor_text); contentDescription = context.getString(R.string.popup_editor_item_text) }
        body.addView(function)
        body.addView(textInput)
        function.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                textInput.visibility = if (KeyAction.entries[position] == KeyAction.TEXT) VISIBLE else GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        // Reordering happens as part of this dialog's Save, so Cancel discards it too.
        var destination = index
        val order = TextView(context).apply { setTextColor(Color.WHITE) }
        fun updateOrder() { order.text = context.getString(R.string.popup_editor_layer, destination + 1, configuration.items.size) }
        body.addView(order)
        body.addView(LinearLayout(context).apply {
            addView(button(R.string.popup_editor_bring_front) { destination = configuration.items.lastIndex; updateOrder() }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(button(R.string.popup_editor_move_up) { destination = (destination + 1).coerceAtMost(configuration.items.lastIndex); updateOrder() }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(button(R.string.popup_editor_move_down) { destination = (destination - 1).coerceAtLeast(0); updateOrder() }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        updateOrder()
        val dialog = AlertDialog.Builder(context).setTitle(R.string.popup_editor_item_title).setView(body)
            .setNegativeButton(R.string.popup_editor_cancel, null).setPositiveButton(R.string.popup_editor_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val action = KeyAction.entries[function.selectedItemPosition]
                val text = if (action == KeyAction.TEXT) textInput.text.toString() else ""
                if (text.length > 1000) { textInput.error = context.getString(R.string.popup_editor_text_limit); return@setOnClickListener }
                val items = configuration.items.toMutableList()
                if (index !in items.indices) { dialog.dismiss(); return@setOnClickListener }
                val item = items.removeAt(index).copy(binding = LongPressItem(text, action))
                items.add(destination.coerceIn(0, items.size), item)
                edit(configuration.copy(items = items))
                select(Selection.Item(destination))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private inner class EditorViewport(context: Context) : View(context) {
        var popupColor = Color.rgb(40, 40, 40)
        var symbolColor = Color.WHITE
        var axisColor = Color.GRAY
        var crosshairColor = Color.WHITE
        var differenceBlend = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val popupPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
        private val density get() = resources.displayMetrics.density
        private var offset = PopupPoint(0f, 0f)
        private var centered = false
        private var downPoint = PopupPoint(0f, 0f)
        private var downScreen = PopupPoint(0f, 0f)
        private var startOffset = offset
        private var downConfiguration = configuration
        private var downSelection: Selection = Selection.None
        private var downTarget: Selection = Selection.None
        private var handle = -1
        private var dragging = false
        private var pinching = false
        private var pinchDistance = 1f
        private var pinchRadius = 24f
        private var pendingTap: Runnable? = null
        private var tapTime = 0L
        private var tapPoint = PopupPoint(0f, 0f)
        private var tapTarget: Selection = Selection.None
        private var tapWasSelected = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop / density
        private val doubleSlop = ViewConfiguration.get(context).scaledDoubleTapSlop / density

        init { isClickable = true; setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (!centered) centerOnAnchor()
        }

        fun centerOnAnchor() {
            if (width == 0) { centered = false; return }
            offset = PopupPoint(width / density / 2f - configuration.anchor.x, height / density / 2f - configuration.anchor.y)
            centered = true
            invalidate()
        }

        fun cancelPendingTap() { pendingTap?.let(::removeCallbacks); pendingTap = null }

        override fun onDetachedFromWindow() { cancelPendingTap(); super.onDetachedFromWindow() }

        private fun finitePoint(x: Double, y: Double): PopupPoint {
            val limit = Float.MAX_VALUE.toDouble()
            return PopupPoint(x.coerceIn(-limit, limit).toFloat(), y.coerceIn(-limit, limit).toFloat())
        }
        private fun world(x: Float, y: Float) = finitePoint(x.toDouble() / density - offset.x, y.toDouble() / density - offset.y)
        private fun distance(a: PopupPoint, b: PopupPoint) = hypot(a.x.toDouble() - b.x, a.y.toDouble() - b.y)
        private fun target(point: PopupPoint): Selection {
            // Editor items are selectable even outside the popup; runtime has stricter hit testing.
            val item = configuration.items.indexOfLast { distance(it.center, point) <= it.radius }
            if (item >= 0) return Selection.Item(item)
            if (distance(configuration.anchor, point) <= 13f) return Selection.Anchor
            return if (PopupGeometry.contains(configuration, point)) Selection.Popup else Selection.None
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(15, 15, 15))
            canvas.save()
            canvas.scale(density, density)
            canvas.translate(offset.x, offset.y)
            val path = popupPath.apply {
                rewind()
                fillType = Path.FillType.EVEN_ODD
                configuration.vertices.forEachIndexed { index, p -> if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                close()
            }
            paint.style = Paint.Style.FILL; paint.color = popupColor
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = symbolColor
            canvas.drawPath(path, paint)
            val anchor = configuration.anchor
            if (selection == Selection.Anchor) {
                paint.color = axisColor
                if (differenceBlend) { paint.color = Color.WHITE; paint.blendMode = BlendMode.DIFFERENCE }
                canvas.drawLine(-offset.x, anchor.y, width / density - offset.x, anchor.y, paint)
                canvas.drawLine(anchor.x, -offset.y, anchor.x, height / density - offset.y, paint)
            }
            paint.color = crosshairColor
            paint.blendMode = null
            canvas.drawLine(anchor.x - 10f, anchor.y, anchor.x + 10f, anchor.y, paint)
            canvas.drawLine(anchor.x, anchor.y - 10f, anchor.x, anchor.y + 10f, paint)
            paint.blendMode = null
            configuration.items.forEachIndexed { index, item ->
                val selected = (selection as? Selection.Item)?.index == index
                paint.style = Paint.Style.FILL; paint.color = if (selected) symbolColor else popupColor
                canvas.drawCircle(item.center.x, item.center.y, item.radius, paint)
                paint.style = Paint.Style.STROKE; paint.color = symbolColor; paint.strokeWidth = 1.5f
                canvas.drawCircle(item.center.x, item.center.y, item.radius, paint)
                val label = itemLabels[index]
                paint.style = Paint.Style.FILL; paint.color = if (selected) popupColor else symbolColor
                paint.textAlign = Paint.Align.CENTER; paint.textSize = item.radius * .8f
                val maxWidth = item.radius * 1.7f
                if (paint.measureText(label) > maxWidth) paint.textSize *= maxWidth / paint.measureText(label)
                canvas.drawText(label, item.center.x, item.center.y - (paint.ascent() + paint.descent()) / 2f, paint)
            }
            if (selection == Selection.Popup) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = symbolColor
                configuration.vertices.forEachIndexed { index, p ->
                    if (configuration.shapeMode == PopupShapeMode.DISTORT || index == 2) {
                        paint.style = Paint.Style.FILL
                    } else paint.style = Paint.Style.STROKE
                    canvas.drawRect(p.x - 4f, p.y - 4f, p.x + 4f, p.y + 4f, paint)
                }
            }
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            val point = world(event.x, event.y)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downPoint = point; downScreen = PopupPoint(event.x / density, event.y / density)
                    startOffset = offset; downConfiguration = configuration
                    downSelection = selection; downTarget = target(point)
                    handle = if (selection == Selection.Popup && downTarget !is Selection.Item && downTarget != Selection.Anchor) {
                        configuration.vertices.indices.firstOrNull { index ->
                            (configuration.shapeMode == PopupShapeMode.DISTORT || index == 2) && distance(configuration.vertices[index], point) <= 12f
                        } ?: -1
                    } else -1
                    dragging = false; pinching = false
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val item = (selection as? Selection.Item)?.index?.let(configuration.items::getOrNull)
                    if (event.pointerCount == 2 && item != null) {
                        cancelPendingTap()
                        pinchDistance = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1)).coerceAtLeast(1f)
                        pinchRadius = item.radius; pinching = true; dragging = true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pinching && event.pointerCount >= 2) {
                        val index = (selection as? Selection.Item)?.index ?: return true
                        val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                        val radius = (pinchRadius.toDouble() * span / pinchDistance)
                            .coerceIn(.1, Float.MAX_VALUE.toDouble()).toFloat()
                        configuration = configuration.copy(items = configuration.items.mapIndexed { i, item -> if (i == index) item.copy(radius = radius) else item })
                        publish(); return true
                    }
                    if (pinching) return true
                    if (distance(point, downPoint) > slop || dragging) {
                        dragging = true; cancelPendingTap()
                        val dx = point.x.toDouble() - downPoint.x; val dy = point.y.toDouble() - downPoint.y
                        when {
                            handle >= 0 -> transformBoundary(point)
                            downSelection == Selection.Anchor && downTarget == downSelection -> configuration = downConfiguration.copy(anchor = finitePoint(downConfiguration.anchor.x + dx, downConfiguration.anchor.y + dy))
                            downSelection is Selection.Item && downTarget == downSelection -> {
                                val index = (downSelection as Selection.Item).index
                                configuration = downConfiguration.copy(items = downConfiguration.items.mapIndexed { i, item -> if (i == index) item.copy(center = finitePoint(item.center.x + dx, item.center.y + dy)) else item })
                            }
                            downSelection == Selection.None && downTarget == Selection.None -> {
                                offset = PopupPoint(startOffset.x + event.x / density - downScreen.x, startOffset.y + event.y / density - downScreen.y)
                            }
                        }
                        publish()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        if (configuration != downConfiguration) { undoHistory.addLast(downConfiguration); redoHistory.clear(); publish() }
                    } else handleTap(point, event.eventTime)
                    performClick()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelPendingTap()
                    configuration = downConfiguration
                    offset = startOffset
                    publish()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }

        private fun handleTap(point: PopupPoint, time: Long) {
            val hit = target(point)
            if (pendingTap != null && time - tapTime <= ViewConfiguration.getDoubleTapTimeout() && distance(point, tapPoint) <= doubleSlop && hit == tapTarget) {
                cancelPendingTap()
                when (hit) {
                    Selection.None -> centerOnAnchor()
                    Selection.Anchor -> { edit(configuration.copy(anchor = PopupGeometry.centroid(configuration))); select(Selection.Anchor) }
                    Selection.Popup -> {
                        val center = PopupGeometry.rotationCenter(configuration)
                        val minX = configuration.vertices.minOf { it.x }; val maxX = configuration.vertices.maxOf { it.x }
                        val minY = configuration.vertices.minOf { it.y }; val maxY = configuration.vertices.maxOf { it.y }
                        val half = max(maxX.toDouble() - minX, maxY.toDouble() - minY).coerceAtLeast(2.0) / 2.0
                        edit(configuration.copy(vertices = listOf(finitePoint(center.x - half, center.y - half), finitePoint(center.x + half, center.y - half), finitePoint(center.x + half, center.y + half), finitePoint(center.x - half, center.y + half))))
                        select(Selection.Popup)
                    }
                    is Selection.Item -> if (tapWasSelected) {
                        edit(configuration.copy(items = configuration.items.filterIndexed { index, _ -> index != hit.index }))
                        select(Selection.None)
                    } else select(hit)
                }
                return
            }
            // A pending first tap elsewhere should still be resolved, not silently lost.
            pendingTap?.let { removeCallbacks(it); pendingTap = null; it.run() }
            tapTime = time; tapPoint = point; tapTarget = hit; tapWasSelected = selection == hit
            val action = Runnable {
                pendingTap = null
                when {
                    hit is Selection.Item && selection == hit -> editItem(hit.index)
                    hit == Selection.Anchor && selection == hit -> select(Selection.None)
                    else -> select(hit)
                }
            }
            pendingTap = action
            postDelayed(action, ViewConfiguration.getDoubleTapTimeout().toLong())
        }

        private fun transformBoundary(point: PopupPoint) {
            val original = downConfiguration.vertices
            val vertices = when (downConfiguration.shapeMode) {
                PopupShapeMode.DISTORT -> original.mapIndexed { i, p -> if (i == handle) point else p }
                PopupShapeMode.ROTATE -> {
                    val center = PopupGeometry.rotationCenter(downConfiguration)
                    val start = original[2]
                    val angle = atan2(point.y.toDouble() - center.y, point.x.toDouble() - center.x) - atan2(start.y.toDouble() - center.y, start.x.toDouble() - center.x)
                    original.map { p ->
                        val x = p.x.toDouble() - center.x; val y = p.y.toDouble() - center.y
                        finitePoint(center.x + x * cos(angle) - y * sin(angle), center.y + x * sin(angle) + y * cos(angle))
                    }
                }
                PopupShapeMode.UNIFORM, PopupShapeMode.FREEFORM -> {
                    val fixed = original[0]; val corner = original[2]
                    val sx: Double; val sy: Double
                    if (downConfiguration.shapeMode == PopupShapeMode.UNIFORM) {
                        val vx = corner.x.toDouble() - fixed.x; val vy = corner.y.toDouble() - fixed.y
                        val denominator = vx * vx + vy * vy
                        val scale = if (denominator < .000001) 1.0 else ((point.x.toDouble() - fixed.x) * vx + (point.y.toDouble() - fixed.y) * vy) / denominator
                        sx = scale.coerceAtLeast(.001); sy = sx
                    } else {
                        sx = if (abs(corner.x.toDouble() - fixed.x) < .000001) 1.0 else (point.x.toDouble() - fixed.x) / (corner.x.toDouble() - fixed.x)
                        sy = if (abs(corner.y.toDouble() - fixed.y) < .000001) 1.0 else (point.y.toDouble() - fixed.y) / (corner.y.toDouble() - fixed.y)
                    }
                    original.map { p -> finitePoint(fixed.x + (p.x.toDouble() - fixed.x) * sx, fixed.y + (p.y.toDouble() - fixed.y) * sy) }
                }
            }
            configuration = downConfiguration.copy(vertices = vertices)
        }
    }
}
