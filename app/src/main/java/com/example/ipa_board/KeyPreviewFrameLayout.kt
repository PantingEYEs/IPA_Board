package com.example.ipa_board

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Touch input is committed on release, after showing the selected short/long mapping. */
internal open class KeyPreviewFrameLayout(context: Context) : FrameLayout(context) {
    var previewEnabled = false
    var tapPreview = ""
    var holdPreview = ""
    var longPressItems: List<LongPressItem> = emptyList()
    var swipeLeftItem: LongPressItem? = null
    var swipeRightItem: LongPressItem? = null
    var onLongPressRepeat: (() -> Unit)? = null
    var onLongPressItemClick: ((LongPressItem) -> Unit)? = null
    var onSwipeLeftItemClick: ((LongPressItem) -> Unit)? = null
    var onSwipeRightItemClick: ((LongPressItem) -> Unit)? = null
    var shiftEnabled = false
    var ctrlEnabled = false

    private enum class QuickSwipe { NONE, LEFT, RIGHT }
    private var quickSwipe = QuickSwipe.NONE

    private var tracking = false
    private var held = false
    private var isRepeating = false
    private var isCanceled = false
    private var pointerId = -1
    private var startX = 0f
    private var startY = 0f
    private var currentTouchRawX = 0f
    private var currentTouchRawY = 0f
    private var currentSelectedIndex = 0
    private var popup: PopupWindow? = null

    internal val visiblePreview: String?
        get() = if (popup?.isShowing == true) (popup?.contentView as? TextView)?.text?.toString() else null

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (tracking && held && isRepeating && !isCanceled) {
                onLongPressRepeat?.invoke()
                postDelayed(this, 50L)
            }
        }
    }

    private val selectHold = Runnable {
        if (tracking && !isCanceled) {
            held = true
            quickSwipe = QuickSwipe.NONE
            currentSelectedIndex = 0
            startX = currentTouchRawX
            startY = currentTouchRawY

            val items = getEffectiveItems()
            val initialPreview = if (items.isNotEmpty()) {
                items[0].previewText(shiftEnabled, ctrlEnabled)
            } else holdPreview

            if (initialPreview.isNotEmpty()) {
                showPreview(initialPreview)
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (onLongPressRepeat != null) {
                isRepeating = true
                repeatRunnable.run()
            }
        }
    }

    private fun getEffectiveItems(): List<LongPressItem> {
        if (longPressItems.isNotEmpty()) return longPressItems
        if (holdPreview.isNotEmpty()) return listOf(LongPressItem(text = holdPreview))
        return emptyList()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!previewEnabled) return super.onTouchEvent(event)
        if (!isEnabled || !isClickable) return false

        val density = resources.displayMetrics.density
        val stepPx = (28 * density).toInt().coerceAtLeast(1)
        val cancelThresholdY = (32 * density).toInt()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPress()
                tracking = true
                isCanceled = false
                pointerId = event.getPointerId(0)
                startX = event.rawX
                startY = event.rawY
                currentTouchRawX = event.rawX
                currentTouchRawY = event.rawY
                quickSwipe = QuickSwipe.NONE
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                showPreview(tapPreview)

                val items = getEffectiveItems()
                val hasLong = (items.isNotEmpty() || holdPreview.isNotEmpty()) && isLongClickable
                if (hasLong) {
                    postDelayed(selectHold, KeyboardGestureSettings.longPressTimeoutMs(context).toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    currentTouchRawX = event.rawX
                    currentTouchRawY = event.rawY
                    val currentX = event.rawX
                    val currentY = event.rawY
                    val viewX = event.getX(index)
                    val viewY = event.getY(index)

                    if (!held) {
                        val deltaX = currentX - startX
                        val deltaY = currentY - startY
                        val swipeThreshold = 24 * density

                        if (Math.abs(deltaY) > cancelThresholdY && Math.abs(deltaY) > Math.abs(deltaX)) {
                            cancelPress()
                            isCanceled = true
                        } else if (Math.abs(deltaX) > swipeThreshold && Math.abs(deltaX) > Math.abs(deltaY) * 1.2) {
                            if (deltaX < 0 && swipeLeftItem != null) {
                                if (quickSwipe != QuickSwipe.LEFT) {
                                    quickSwipe = QuickSwipe.LEFT
                                    showPreview(swipeLeftItem!!.previewText(shiftEnabled, ctrlEnabled))
                                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            } else if (deltaX > 0 && swipeRightItem != null) {
                                if (quickSwipe != QuickSwipe.RIGHT) {
                                    quickSwipe = QuickSwipe.RIGHT
                                    showPreview(swipeRightItem!!.previewText(shiftEnabled, ctrlEnabled))
                                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            } else if (viewX < 0 || viewX >= width || viewY < 0 || viewY >= height) {
                                cancelPress()
                            }
                        } else if (Math.abs(deltaX) <= swipeThreshold / 2f) {
                            if (quickSwipe != QuickSwipe.NONE) {
                                quickSwipe = QuickSwipe.NONE
                                showPreview(tapPreview)
                            }
                            if (viewX < 0 || viewX >= width || viewY < 0 || viewY >= height) {
                                cancelPress()
                            }
                        }
                    } else {
                        if (currentY - startY > cancelThresholdY || viewY >= height + cancelThresholdY) {
                            cancelPress()
                            isCanceled = true
                        } else {
                            val items = getEffectiveItems()
                            if (items.isNotEmpty()) {
                                val deltaX = currentX - startX
                                val steps = Math.round(deltaX / stepPx)
                                val n = items.size
                                val newIndex = Math.floorMod(steps, n)
                                if (newIndex != currentSelectedIndex) {
                                    currentSelectedIndex = newIndex
                                    showPreview(items[newIndex].previewText(shiftEnabled, ctrlEnabled))
                                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            }
                        }
                    }
                } else {
                    cancelPress()
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                val pointerMatches = event.getPointerId(index) == pointerId
                val commit = tracking && pointerMatches && !isCanceled
                val longPress = held
                val wasRepeating = isRepeating
                val selectedIdx = currentSelectedIndex
                val swipeState = quickSwipe

                cancelPress()

                if (commit) {
                    if (longPress) {
                        if (!wasRepeating) {
                            val items = getEffectiveItems()
                            if (onLongPressItemClick != null && items.isNotEmpty() && selectedIdx in items.indices) {
                                onLongPressItemClick?.invoke(items[selectedIdx])
                            } else {
                                performLongClick()
                            }
                        }
                    } else {
                        when (swipeState) {
                            QuickSwipe.LEFT -> {
                                swipeLeftItem?.let { item -> onSwipeLeftItemClick?.invoke(item) } ?: performClick()
                            }
                            QuickSwipe.RIGHT -> {
                                swipeRightItem?.let { item -> onSwipeRightItemClick?.invoke(item) } ?: performClick()
                            }
                            QuickSwipe.NONE -> {
                                performClick()
                            }
                        }
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
            ellipsize = TextUtils.TruncateAt.END
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
        quickSwipe = QuickSwipe.NONE
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
