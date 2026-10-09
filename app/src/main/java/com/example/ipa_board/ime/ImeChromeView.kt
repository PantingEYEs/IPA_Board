package com.example.ipa_board.ime

import android.R
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** IME-only chrome. Never passed to KeyboardRenderer or serialized with a layout. */
class ImeChromeView(context: Context) : LinearLayout(context) {
    enum class Panel { KEYBOARD, CANDIDATES, CLIPBOARD, PAGES, EMOJI, KAOMOJI, CALCULATOR }
    val keyboardHost = LinearLayout(context).apply { orientation = VERTICAL }
    private val body = FrameLayout(context)
    private val overlay = LinearLayout(context).apply { orientation = VERTICAL }
    private val candidateRow = LinearLayout(context)
    private var candidateMinimumWidth = dp(48)
    private val expand = button("⋯", "Expand") { showPanel(if (panel == Panel.KEYBOARD) Panel.CANDIDATES else Panel.KEYBOARD) }.apply {
        minWidth = dp(57)
        minimumWidth = dp(57)
        setPadding(dp(4), 0, dp(4), 0)
    }
    val statusContainer = FrameLayout(context)
    val status = TextView(context)
    private var customStatusView: View? = null
    var onStatusClick: (() -> Unit)? = null
        set(value) {
            field = value
            status.isClickable = value != null
            status.isFocusable = value != null
        }
    private var candidates = emptyList<Candidate>()
    private var engineMessage = ""
    private var panelTitle = ""
    var panel = Panel.KEYBOARD
        private set
    var onCandidate: (Candidate, Long) -> Unit = { _, _ -> }
    private var revision = -1L
    var onLiteral: () -> Unit = {}
    var onPanel: (Panel) -> Unit = {}
    var onCalculatorToggle: () -> Unit = {}
    var onWidthSwapToggle: (() -> Unit)? = null
    private val widthSwapBtn = toolbarButton("◩", "全角 / 半角转换") { onWidthSwapToggle?.invoke() }
    private val calcBtn = toolbarButton("∑", "Calculator") { onCalculatorToggle() }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(0, 0, 0))
        val toolbar = LinearLayout(context).apply {
            addView(toolbarButton("⧉", "Clipboard") { toggle(Panel.CLIPBOARD) })
            addView(toolbarButton("⊞", "Keyboard Overview") { toggle(Panel.PAGES) })
            addView(toolbarButton("顔", "顔文字") { toggle(Panel.KAOMOJI) })
            status.setTextColor(Color.LTGRAY)
            status.textSize = 10f
            status.gravity = Gravity.CENTER_VERTICAL
            status.setOnClickListener { onStatusClick?.invoke() }
            statusContainer.addView(status, FrameLayout.LayoutParams(-1, -1).apply {
                setMargins(dp(6), 0, dp(6), 0)
            })
            addView(statusContainer, LayoutParams(0, dp(28), 1f))
            addView(widthSwapBtn)
            addView(calcBtn)
        }
        addView(toolbar, LayoutParams(-1, dp(28)))
        val strip = LinearLayout(context)
        val horizontal = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(candidateRow) }
        strip.addView(horizontal, LayoutParams(0, dp(28), 1f))
        strip.addView(expand, LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)))//candidate drop down / return
        addView(strip)
        body.addView(keyboardHost, FrameLayout.LayoutParams(-1, -1))
        overlay.setBackgroundColor(Color.rgb(0, 0, 0))
        overlay.isClickable = true
        body.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        addView(body, LayoutParams(-1, dp(210)))
        setWidthSwapEnabled(false)
        setCalculatorEnabled(false)
        showPanel(Panel.KEYBOARD)
    }

    fun setKeyboardHeight(px: Int) {
        body.layoutParams = LayoutParams(-1, px)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Use the actual key geometry so custom layouts, page switches and rotation all follow.
        val keyWidth = (0 until keyboardHost.childCount).flatMap { rowIndex ->
            val row = keyboardHost.getChildAt(rowIndex) as? ViewGroup
            if (row == null) emptyList() else (0 until row.childCount).map { row.getChildAt(it).width }
        }.filter { it > 0 }.minOrNull() ?: dp(48)
        val minimum = maxOf(dp(48), keyWidth)
        if (minimum == candidateMinimumWidth) return
        candidateMinimumWidth = minimum
        for (index in 0 until candidateRow.childCount) {
            (candidateRow.getChildAt(index) as? Button)?.apply {
                minWidth = minimum
                minimumWidth = minimum
            }
        }
    }

    fun setCalculatorEnabled(enabled: Boolean) {
        calcBtn.alpha = if (enabled) 1.0f else 0.5f
    }

    fun setWidthSwapEnabled(enabled: Boolean) {
        widthSwapBtn.isSelected = enabled
        widthSwapBtn.stateDescription = if (enabled) "开启" else "关闭"
        widthSwapBtn.alpha = if (enabled) 1.0f else 0.5f
    }



    fun setStatusCustomView(view: View?) {
        customStatusView = view
        statusContainer.removeAllViews()
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            val lp = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(22)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(dp(2), dp(3), dp(6), dp(3))
            }
            statusContainer.addView(view, lp)
        } else {
            // Clipboard actions may share the original status view for quick-paste behavior.
            (status.parent as? ViewGroup)?.removeView(status)
            statusContainer.addView(status, FrameLayout.LayoutParams(-1, -1))
        }
    }

    fun render(raw: String, items: List<Candidate>, message: String, generation: Long) {
        engineMessage = message
        if (panel == Panel.KEYBOARD) status.text = engineMessage
        if (candidates != items || revision != generation) {
            revision = generation
            candidates = items
            candidateRow.removeAllViews()
            items.take(24).forEach { candidateRow.addView(candidateButton(it)) }
            if (panel == Panel.CANDIDATES) renderCandidates()
        }
        expand.isEnabled = if (panel == Panel.KEYBOARD) items.isNotEmpty() else true
    }

    fun showPanel(next: Panel) {
        onStatusClick = null
        val previous = panel
        panel = next
        if (next != previous || (next != Panel.KAOMOJI && next != Panel.CLIPBOARD)) {
            setStatusCustomView(null)
        }
        overlay.visibility = if (next == Panel.KEYBOARD) GONE else VISIBLE
        keyboardHost.importantForAccessibility = if (next == Panel.KEYBOARD) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Invisible keeps the geometry but prevents any underlying hit target or focus.
        keyboardHost.visibility = if (next == Panel.KEYBOARD) VISIBLE else INVISIBLE
        if (next == Panel.KEYBOARD) {
            onStatusClick = null
            expand.text = "⋯"
            expand.contentDescription = "Expand"
            expand.isEnabled = candidates.isNotEmpty()
            status.text = engineMessage
        } else {
            expand.text = "Return"
            expand.contentDescription = "Return to Keyboard"
            expand.isEnabled = true
            if (panelTitle.isNotEmpty() && customStatusView == null) status.text = panelTitle
        }
        if (next == Panel.CANDIDATES) renderCandidates()
        onPanel(next)
    }

    fun showContent(title: String, content: View) {
        panelTitle = title
        if (panel != Panel.KEYBOARD && customStatusView == null) status.text = title
        if (overlay.childCount == 0 || overlay.getChildAt(0) !== content) {
            overlay.removeAllViews()
            overlay.addView(content, LayoutParams(-1, -1))
        }
    }

    fun listContent(labels: List<String>, click: (Int) -> Unit): View = ListView(context).apply {
        adapter = object : ArrayAdapter<String>(context, R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(0,0,0)); minHeight = dp(20)
                }
        }
        setOnItemClickListener { _, _, position, _ -> click(position) }
    }

    fun candidateGridContent(snapshot: List<Candidate>, click: (Candidate) -> Unit): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val alphaColor = Color.argb(30, 255, 255, 255)
        val columnsCount = 6

        snapshot.chunked(columnsCount).forEach { rowCandidates ->
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(26))
            }
            for (i in 0 until columnsCount) {
                val candidate = rowCandidates.getOrNull(i)
                val border = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(1, alphaColor)
                }
                val slotView = FrameLayout(context).apply {
                    background = border
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                }
                if (candidate != null) {
                    val textView = TextView(context).apply {
                        text = candidate.text
                        textSize = 10f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        maxLines = 1
                    }
                    slotView.isFocusable = true
                    slotView.isClickable = true
                    slotView.contentDescription = candidate.text
                    slotView.setOnClickListener { click(candidate) }
                    slotView.addView(textView, FrameLayout.LayoutParams(-1, -1))
                }
                rowView.addView(slotView)
            }
            container.addView(rowView)
        }
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            addView(container)
        }
    }

    private fun renderCandidates() {
        val snapshot = candidates.toList()
        val generation = revision
        val title = if (snapshot.isEmpty()) "Candidates" else "Candidates (${snapshot.size})"
        showContent(title, candidateGridContent(snapshot) { candidate ->
            onCandidate(candidate, generation)
        })
    }
    private fun toggle(next: Panel) = showPanel(if (panel == next) Panel.KEYBOARD else next)
    private fun candidateButton(candidate: Candidate): View {
        val generation = revision
        return button("${candidate.text} ", candidate.text) { onCandidate(candidate, generation) }.apply {
            minWidth = candidateMinimumWidth
            minimumWidth = candidateMinimumWidth
            setPadding(paddingLeft / 2, paddingTop, paddingRight / 2, paddingBottom)
        }
    }
    private fun button(label: String, description: String, click: () -> Unit) = Button(context).apply {
        text = label; contentDescription = description; isAllCaps = false; textSize = 10f
        setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        minHeight = dp(20); minimumHeight = dp(20); minWidth = dp(48)
        setOnClickListener { click() }
    }
    private fun toolbarButton(label: String, description: String, click: () -> Unit) = Button(context).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(dp(4), 0, dp(4), 0)
        minHeight = dp(28)
        minimumHeight = dp(28)
        minWidth = dp(50)
        minimumWidth = dp(50)
        gravity = Gravity.CENTER
        setOnClickListener { click() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private val pointerPaths = mutableMapOf<Int, android.graphics.Path>()
    private val currentPointers = mutableMapOf<Int, android.graphics.PointF>()
    private val pointerPaint = android.graphics.Paint().apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 0f // 0 means a hairline (1 pixel regardless of density or scaling)
        style = android.graphics.Paint.Style.STROKE
        isAntiAlias = true
    }
    private val pointPaint = android.graphics.Paint().apply {
        color = 0xFFFF0000.toInt()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density // 1dp
        isAntiAlias = true
    }
    private val axisPaint = android.graphics.Paint().apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 0f
        style = android.graphics.Paint.Style.STROKE
        isAntiAlias = true
    }

    var showPointerLocation = false
        set(value) {
            field = value
            if (!value) {
                pointerPaths.clear()
                currentPointers.clear()
            }
            invalidate()
        }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (showPointerLocation) {
            val action = ev.actionMasked
            val pointerIndex = ev.actionIndex
            val pointerId = ev.getPointerId(pointerIndex)

            when (action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Start of a new touch sequence, clear previous retained paths
                    pointerPaths.clear()
                    currentPointers.clear()
                    val path = android.graphics.Path()
                    path.moveTo(ev.getX(pointerIndex), ev.getY(pointerIndex))
                    pointerPaths[pointerId] = path
                    currentPointers[pointerId] = android.graphics.PointF(ev.getX(pointerIndex), ev.getY(pointerIndex))
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    val path = android.graphics.Path()
                    path.moveTo(ev.getX(pointerIndex), ev.getY(pointerIndex))
                    pointerPaths[pointerId] = path
                    currentPointers[pointerId] = android.graphics.PointF(ev.getX(pointerIndex), ev.getY(pointerIndex))
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val historySize = ev.historySize
                    for (h in 0 until historySize) {
                        for (i in 0 until ev.pointerCount) {
                            val id = ev.getPointerId(i)
                            val hx = ev.getHistoricalX(i, h)
                            val hy = ev.getHistoricalY(i, h)
                            pointerPaths[id]?.lineTo(hx, hy)
                        }
                    }
                    for (i in 0 until ev.pointerCount) {
                        val id = ev.getPointerId(i)
                        val x = ev.getX(i)
                        val y = ev.getY(i)
                        pointerPaths[id]?.lineTo(x, y)
                        currentPointers[id]?.set(x, y)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_POINTER_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Only remove the current pointer so the crosshair disappears, but keep the path
                    currentPointers.remove(pointerId)
                }
            }
            invalidate()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (showPointerLocation) {
            for (path in pointerPaths.values) {
                canvas.drawPath(path, pointerPaint)
            }
            val crossSize = 10f * resources.displayMetrics.density
            val width = width.toFloat()
            val height = height.toFloat()

            for (p in currentPointers.values) {
                canvas.drawLine(0f, p.y, width, p.y, axisPaint)
                canvas.drawLine(p.x, 0f, p.x, height, axisPaint)
                canvas.drawLine(p.x - crossSize, p.y, p.x + crossSize, p.y, pointPaint)
                canvas.drawLine(p.x, p.y - crossSize, p.x, p.y + crossSize, pointPaint)
            }
        }
    }

}
