package com.example.ipa_board.ime

import android.R
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.example.ipa_board.MultifunctionBarView
import com.example.ipa_board.MultifunctionBarSlot
import com.example.ipa_board.MultifunctionBarBinding
import com.example.ipa_board.KeyAction
import com.example.ipa_board.ShiftState

/** IME chrome, with a toolbar layout shared by Shortcut Management. Outside keyboard-page JSON. */
class ImeChromeView(context: Context) : LinearLayout(context) {
    enum class Panel { KEYBOARD, CANDIDATES, CLIPBOARD, PAGES, EMOJI, KAOMOJI, CALCULATOR, ENGINE_DASHBOARD }
    val keyboardHost = LinearLayout(context).apply { orientation = VERTICAL }
    private val body = FrameLayout(context)
    private val overlay = LinearLayout(context).apply { orientation = VERTICAL }
    private val candidateRow = LinearLayout(context)
    private var inlinePreeditEnabled = true
    private var inputViewActive = false
    private var hasPreedit = false
    private val preeditText = PreeditTextView(context).apply {
        setOnClickListener { confirmPreedit() }
    }
    private val preeditScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(preeditText)
    }
    private val preeditBackground = ColorDrawable(Color.BLACK)
    private val preeditPopup = PopupWindow(preeditScroll, LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT, false).apply {
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        isOutsideTouchable = false
        isClippingEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setIsLaidOutInScreen(true)
        animationStyle = 0
        elevation = 0f
        setBackgroundDrawable(preeditBackground)
    }
    private val preeditBounds = Rect()
    private val revealPreeditCaret = Runnable {
        if (canShowPreedit() && preeditPopup.isShowing) preeditText.revealCaret()
    }
    private var candidateMinimumWidth = dp(48)
    private val expand = button("⋯", "Expand") { showPanel(if (panel == Panel.KEYBOARD) Panel.CANDIDATES else Panel.KEYBOARD) }.apply {
        minWidth = dp(57)
        minimumWidth = dp(57)
        setPadding(dp(4), 0, dp(4), 0)
    }
    val multifunctionBar = MultifunctionBarView(context)
    val statusContainer = multifunctionBar.statusContainer
    val status = multifunctionBar.status
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
    var onRemoteKeyboardToggle: () -> Unit = {}
    var onWidthSwapToggle: (() -> Unit)? = null
    var onPreeditModeToggle: (() -> Unit)? = null
    var onKeyBinding: ((MultifunctionBarBinding) -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(0, 0, 0))
        status.setOnClickListener { onStatusClick?.invoke() }
        multifunctionBar.onSlotClick = { slot ->
            val binding = multifunctionBar.bindingAt(slot)
            val callback = onKeyBinding
            if (callback != null) callback(binding) else when (binding.action) {
                KeyAction.CLIPBOARD -> toggle(Panel.CLIPBOARD)
                KeyAction.PAGES -> toggle(Panel.PAGES)
                KeyAction.KAOMOJI -> toggle(Panel.KAOMOJI)
                KeyAction.REMOTE_KEYBOARD -> onRemoteKeyboardToggle()
                KeyAction.WIDTH_SWAP -> onWidthSwapToggle?.invoke()
                KeyAction.PREEDIT_MODE -> onPreeditModeToggle?.invoke()
                KeyAction.CALCULATOR -> onCalculatorToggle()
                else -> Unit
            }
        }
        addView(multifunctionBar)
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
        setRemoteKeyboardEnabled(false)
        setWidthSwapEnabled(false)
        setCalculatorEnabled(false)
        showPanel(Panel.KEYBOARD)
    }

    fun setKeyboardHeight(px: Int) {
        body.layoutParams = LayoutParams(-1, px)
    }

    fun setKeyboardColors(backgroundColor: Int, symbolColor: Int) {
        keyboardHost.setBackgroundColor(backgroundColor)
        preeditBackground.color = backgroundColor
        preeditText.setTextColor(symbolColor)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updatePreeditVisibility()
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
        multifunctionBar.setCalculatorEnabled(enabled)
    }

    fun setRemoteKeyboardEnabled(enabled: Boolean) {
        multifunctionBar.setRemoteKeyboardEnabled(enabled)
    }

    fun setWidthSwapEnabled(enabled: Boolean) {
        multifunctionBar.setWidthSwapEnabled(enabled)
    }

    fun setInlinePreeditEnabled(enabled: Boolean) {
        if (inlinePreeditEnabled == enabled) return
        inlinePreeditEnabled = enabled
        multifunctionBar.setInlinePreeditEnabled(enabled)
        updatePreeditVisibility()
    }

    /** InputMethodService can retain this view after hiding its window. */
    fun setInputViewActive(active: Boolean) {
        inputViewActive = active
        updatePreeditVisibility()
    }

    override fun onDetachedFromWindow() {
        dismissPreedit()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) updatePreeditVisibility() else dismissPreedit()
    }

    fun setBarBindings(bindings: Map<MultifunctionBarSlot, MultifunctionBarBinding>) = multifunctionBar.setBindings(bindings)
    fun setModifiers(shift: ShiftState, ctrl: Boolean) = multifunctionBar.setModifiers(shift, ctrl)



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

    fun render(raw: String, items: List<Candidate>, message: String, generation: Long,
        preeditDisplay: String = raw, preeditCaret: Int = raw.length) {
        hasPreedit = raw.isNotEmpty()
        preeditText.setPreedit(preeditDisplay, preeditCaret)
        updatePreeditVisibility()
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

    private fun updatePreeditVisibility() {
        preeditText.isEnabled = hasPreedit
        preeditText.isClickable = hasPreedit
        preeditText.isFocusable = hasPreedit
        if (!canShowPreedit()) {
            dismissPreedit()
            return
        }
        val frame = Rect().also(::getWindowVisibleDisplayFrame)
        val margin = dp(8)
        val maximumWidth = minOf(width, frame.width()) - margin * 2
        if (maximumWidth <= 0) { dismissPreedit(); return }
        preeditScroll.measure(MeasureSpec.makeMeasureSpec(maximumWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val popupWidth = preeditScroll.measuredWidth
        val popupHeight = preeditScroll.measuredHeight
        val screen = IntArray(2).also(::getLocationOnScreen)
        val x = (if (layoutDirection == LAYOUT_DIRECTION_RTL) screen[0] + width - popupWidth - margin
            else screen[0] + margin).coerceIn(frame.left + margin, frame.right - popupWidth - margin)
        val y = screen[1] - popupHeight
        if (y < frame.top) { dismissPreedit(); return }
        // A separate, non-focusable child window floats above the entire chrome. It never
        // participates in IME measurement, insets or ancestor scrolling (unlike a dropdown).
        val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intArrayOf(x, y) else {
            val inWindow = IntArray(2).also(::getLocationInWindow)
            intArrayOf(x - screen[0] + inWindow[0], y - screen[1] + inWindow[1])
        }
        val bounds = Rect(location[0], location[1], location[0] + popupWidth, location[1] + popupHeight)
        try {
            if (!preeditPopup.isShowing) {
                preeditPopup.width = popupWidth
                preeditPopup.height = popupHeight
                preeditPopup.showAtLocation(this, Gravity.TOP or Gravity.LEFT, location[0], location[1])
            } else if (preeditBounds != bounds) {
                preeditPopup.update(location[0], location[1], popupWidth, popupHeight)
            }
        } catch (_: WindowManager.BadTokenException) {
            // The IME token may expire during a host/window transition. Do not retry stale UI.
            dismissPreedit()
            return
        }
        preeditBounds.set(bounds)
        preeditText.removeCallbacks(revealPreeditCaret)
        preeditText.post(revealPreeditCaret)
    }

    private fun canShowPreedit() = inputViewActive && !inlinePreeditEnabled && hasPreedit &&
        isAttachedToWindow && windowToken != null && windowVisibility == VISIBLE && isShown && width > 0 && height > 0

    private fun confirmPreedit() {
        if (canShowPreedit() && preeditPopup.isShowing) onLiteral()
    }

    private fun dismissPreedit() {
        preeditText.removeCallbacks(revealPreeditCaret)
        preeditPopup.dismiss()
        preeditBounds.setEmpty()
    }

    /** Draw the caret without inserting a marker into text or accessibility output. */
    private class PreeditTextView(context: Context) : androidx.appcompat.widget.AppCompatTextView(context) {
        private var caret = 0
        private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 2f * resources.displayMetrics.density
        }

        init {
            tag = "ime_preedit_text"
            setTextColor(Color.WHITE)
            hint = "预编辑"
            setHintTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            setSingleLine(true)
            includeFontPadding = false
            val density = resources.displayMetrics.density
            setPadding((8 * density).toInt(), (4 * density).toInt(),
                (8 * density).toInt(), (4 * density).toInt())
            minHeight = (28 * density).toInt()
            isFocusable = true
        }

        fun setPreedit(value: String, cursor: Int) {
            if (text.toString() != value) text = value
            caret = cursor.coerceIn(0, value.length)
            if (caret in 1 until value.length && Character.isSurrogatePair(value[caret - 1], value[caret])) caret--
            contentDescription = if (value.isEmpty()) "预编辑" else "$value，点按原样确认"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stateDescription = if (value.isEmpty()) null else "光标在第 ${value.codePointCount(0, caret)} 个字符后"
            }
            invalidate()
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            revealCaret()
        }

        fun revealCaret() {
            val lines = layout ?: return
            val position = caret.coerceIn(0, lines.text.length)
            val line = lines.getLineForOffset(position)
            val x = compoundPaddingLeft + lines.getPrimaryHorizontal(position).toInt()
            requestRectangleOnScreen(android.graphics.Rect(x - paddingLeft,
                extendedPaddingTop + lines.getLineTop(line), x + paddingRight,
                extendedPaddingTop + lines.getLineBottom(line)), true)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (text.isEmpty()) return
            val lines = layout ?: return
            val position = caret.coerceIn(0, lines.text.length)
            val line = lines.getLineForOffset(position)
            val x = compoundPaddingLeft + lines.getPrimaryHorizontal(position) - scrollX
            caretPaint.color = currentTextColor
            canvas.drawLine(x, (extendedPaddingTop + lines.getLineTop(line) - scrollY).toFloat(),
                x, (extendedPaddingTop + lines.getLineBottom(line) - scrollY).toFloat(), caretPaint)
        }
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    internal val pointerPaths = mutableMapOf<Int, android.graphics.Path>()
    internal val currentPointers = mutableMapOf<Int, android.graphics.PointF>()
    internal val pointerPaint = android.graphics.Paint().apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 0f // 0 means a hairline (1 pixel regardless of density or scaling)
        style = android.graphics.Paint.Style.STROKE
        isAntiAlias = true
    }
    internal val pointPaint = android.graphics.Paint().apply {
        color = 0xFFFF0000.toInt()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density // 1dp
        isAntiAlias = true
    }
    internal val axisPaint = android.graphics.Paint().apply {
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

    fun setPointerColors(pathColor: Int, axisColor: Int, crosshairColor: Int, differenceBlend: Boolean = false) {
        if (differenceBlend) {
            pointerPaint.color = android.graphics.Color.WHITE
            pointerPaint.blendMode = android.graphics.BlendMode.DIFFERENCE
            axisPaint.color = android.graphics.Color.WHITE
            axisPaint.blendMode = android.graphics.BlendMode.DIFFERENCE
        } else {
            pointerPaint.color = pathColor
            pointerPaint.blendMode = null
            axisPaint.color = axisColor
            axisPaint.blendMode = null
        }
        pointPaint.color = crosshairColor
        pointPaint.blendMode = null
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
