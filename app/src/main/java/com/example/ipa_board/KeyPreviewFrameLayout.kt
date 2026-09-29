package com.example.ipa_board

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Touch input is committed on release, after showing the selected short/long mapping. */
internal open class KeyPreviewFrameLayout(context: Context) : FrameLayout(context) {
    var previewEnabled = false
    var tapPreview = ""
    var holdPreview = ""
    var onLongPressRepeat: (() -> Unit)? = null
    private var tracking = false
    private var held = false
    private var isRepeating = false
    private var pointerId = -1
    private var popup: PopupWindow? = null
    internal val visiblePreview: String?
        get() = if (popup?.isShowing == true) (popup?.contentView as? TextView)?.text?.toString() else null
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (tracking && held && isRepeating) {
                onLongPressRepeat?.invoke()
                postDelayed(this, 50L)
            }
        }
    }
    private val selectHold = Runnable {
        if (tracking) {
            held = true
            showPreview(holdPreview)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            if (onLongPressRepeat != null) {
                isRepeating = true
                repeatRunnable.run()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!previewEnabled) return super.onTouchEvent(event)
        if (!isEnabled || !isClickable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPress()
                tracking = true
                pointerId = event.getPointerId(0)
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                showPreview(tapPreview)
                if (holdPreview.isNotEmpty() && isLongClickable) {
                    postDelayed(selectHold, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0 || event.getX(index) < 0 || event.getX(index) >= width ||
                    event.getY(index) < 0 || event.getY(index) >= height) cancelPress()
            }
            MotionEvent.ACTION_UP -> {
                val commit = tracking && event.getPointerId(event.actionIndex) == pointerId &&
                    event.x >= 0 && event.x < width && event.y >= 0 && event.y < height
                val longPress = held
                val wasRepeating = isRepeating
                cancelPress()
                if (commit) {
                    if (longPress) {
                        if (!wasRepeating) performLongClick()
                    } else {
                        performClick()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelPress()
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) cancelPress()
        }
        return true
    }

    // Preserve accessibility and keyboard activation independently of the touch gesture.
    override fun performClick(): Boolean = super.performClick()

    private fun showPreview(value: String) {
        if (!isAttachedToWindow || windowToken == null) return
        popup?.dismiss()
        val density = resources.displayMetrics.density
        val label = TextView(context).apply {
            text = value
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            minWidth = (56 * density).toInt()
            minHeight = (56 * density).toInt()
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val maxWidth = minOf((180 * density).toInt(), resources.displayMetrics.widthPixels)
        label.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec((120 * density).toInt(), MeasureSpec.AT_MOST))
        val location = IntArray(2)
        getLocationOnScreen(location)
        val x = (location[0] + (width - label.measuredWidth) / 2)
            .coerceIn(0, (resources.displayMetrics.widthPixels - label.measuredWidth).coerceAtLeast(0))
        val y = (location[1] - label.measuredHeight - (4 * density).toInt()).coerceAtLeast(0)
        popup = PopupWindow(label, label.measuredWidth, label.measuredHeight, false).apply {
            isTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            elevation = 6 * density
            // The IME window itself may be only as tall as the keyboard.
            isClippingEnabled = false
            setIsLaidOutInScreen(true)
            showAtLocation(this@KeyPreviewFrameLayout, Gravity.TOP or Gravity.LEFT, x, y)
        }
    }

    private fun cancelPress() {
        removeCallbacks(selectHold)
        removeCallbacks(repeatRunnable)
        isRepeating = false
        tracking = false
        held = false
        pointerId = -1
        isPressed = false
        popup?.dismiss()
        popup = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onDetachedFromWindow() {
        cancelPress()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelPress()
    }
}
