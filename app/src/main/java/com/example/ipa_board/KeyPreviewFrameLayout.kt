package com.example.ipa_board

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow

/** One gesture owner for Classic previews and Popup selection; both share hold execution. */
internal open class KeyPreviewFrameLayout(context: Context) : FrameLayout(context) {
    var multifunctionMode = MultifunctionMode.CLASSIC
    var popupConfiguration: PopupConfiguration? = null
    var previewEnabled = false
    var tapPreview = ""
    var holdPreview = ""
    var longPressItems: List<LongPressItem> = emptyList()
    var longPressDefaultIndex: Int = 0
    var swipeLeftItem: LongPressItem? = null
    var swipeRightItem: LongPressItem? = null
    var onLongPressRepeat: (() -> Unit)? = null
    var repeatForItem: ((LongPressItem) -> (() -> Unit)?)? = null
    var onLongPressItemClick: ((LongPressItem) -> Unit)? = null
    var onSwipeLeftItemClick: ((LongPressItem) -> Unit)? = null
    var onSwipeRightItemClick: ((LongPressItem) -> Unit)? = null
    var shiftEnabled = false
    var ctrlEnabled = false
    var popupBgColor: Int = Color.WHITE
    var popupSymbolColor: Int = Color.BLACK

    private enum class QuickSwipe { NONE, LEFT, RIGHT }
    private var quickSwipe = QuickSwipe.NONE

    private var tracking = false
    private var held = false
    private var holdState = LongPressGestureState()
    private val isRepeating get() = holdState.repeating
    private var activeRepeat: (() -> Unit)? = null
    private var runtimeConfiguration: PopupConfiguration? = null
    private var popupOriginX = 0.0
    private var popupOriginY = 0.0
    private var actionView: PopupActionView? = null
    private var isCanceled = false
    private var pointerId = -1
    private var startX = 0f
    private var startY = 0f
    private var currentTouchRawX = 0f
    private var currentTouchRawY = 0f
    private var currentTouchLocalX = 0f
    private var currentTouchLocalY = 0f
    private var currentSelectedIndex = 0
    private var popup: PopupWindow? = null

    internal val repeatingHold: Boolean get() = tracking && held && isRepeating && !isCanceled
    internal val activePopupItem: Int? get() = actionView?.activeIndex
    internal val visiblePopupContent: android.view.View?
        get() = popup?.takeIf { it.isShowing }?.contentView

    internal val visiblePreview: String?
        get() = if (popup?.isShowing == true) actionView?.activeLabel else null

    internal val visiblePopupConfiguration: PopupConfiguration?
        get() = runtimeConfiguration.takeIf { popup?.isShowing == true }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (tracking && held && isRepeating && !isCanceled) {
                activeRepeat?.invoke()
                postDelayed(this, 50L)
            }
        }
    }

    private val selectHold = Runnable {
        if (tracking && !isCanceled) {
            held = true
            quickSwipe = QuickSwipe.NONE
            currentSelectedIndex = longPressDefaultIndex.takeIf { it in getEffectiveItems().indices } ?: 0
            startX = currentTouchRawX
            startY = currentTouchRawY

            if (multifunctionMode == MultifunctionMode.POPUP) {
                showActionPopup()
                updatePopupTouch(currentTouchLocalX, currentTouchLocalY)
            } else {
                val items = getEffectiveItems()
                val item = items.getOrNull(currentSelectedIndex)
                if (items.isNotEmpty()) {
                    showPopupConfiguration(KeyPreviewMetrics.classicQueue(context, height, items, currentSelectedIndex, shiftEnabled, ctrlEnabled))
                    actionView?.activeIndex = currentSelectedIndex
                }
                activateHold(item, item?.let { repeatForItem?.invoke(it) } ?: onLongPressRepeat)
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun getEffectiveItems(): List<LongPressItem> {
        if (longPressItems.isNotEmpty()) return longPressItems
        if (holdPreview.isNotEmpty()) return listOf(LongPressItem(text = holdPreview))
        return emptyList()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!previewEnabled && multifunctionMode != MultifunctionMode.POPUP) return super.onTouchEvent(event)
        if (!isEnabled || !isClickable) return false

        val density = resources.displayMetrics.density
        val cancelThresholdY = (32 * density).toInt()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPress()
                holdState = LongPressGestureState()
                tracking = true
                isCanceled = false
                pointerId = event.getPointerId(0)
                startX = event.rawX
                startY = event.rawY
                currentTouchRawX = event.rawX
                currentTouchRawY = event.rawY
                currentTouchLocalX = event.x
                currentTouchLocalY = event.y
                quickSwipe = QuickSwipe.NONE
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                if (previewEnabled) showPreview(tapPreview)

                val items = getEffectiveItems()
                val hasLong = multifunctionMode == MultifunctionMode.POPUP ||
                    ((items.isNotEmpty() || holdPreview.isNotEmpty()) && isLongClickable)
                if (hasLong) {
                    postDelayed(selectHold, KeyboardGestureSettings.longPressTimeoutMs(context).toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (multifunctionMode == MultifunctionMode.POPUP && !tracking) return true
                popup?.contentView?.invalidate()
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    currentTouchRawX = event.rawX
                    currentTouchRawY = event.rawY
                    currentTouchLocalX = event.getX(index)
                    currentTouchLocalY = event.getY(index)
                    val currentX = event.rawX
                    val currentY = event.rawY
                    val viewX = event.getX(index)
                    val viewY = event.getY(index)

                    if (held && multifunctionMode == MultifunctionMode.POPUP) {
                        updatePopupTouch(event.getX(index), event.getY(index))
                    } else if (!held) {
                        val deltaX = currentX - startX
                        val deltaY = currentY - startY
                        val swipeThreshold = 24 * density

                        if (Math.abs(deltaY) > cancelThresholdY && Math.abs(deltaY) > Math.abs(deltaX)) {
                            cancelPress()
                            isCanceled = true
                        } else if (multifunctionMode == MultifunctionMode.CLASSIC && Math.abs(deltaX) > swipeThreshold && Math.abs(deltaX) > Math.abs(deltaY) * 1.2) {
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
                        updateClassicTouch(currentX, currentY)
                    }
                } else {
                    cancelPress()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (held) {
                    if (multifunctionMode == MultifunctionMode.POPUP) updatePopupTouch(event.x, event.y)
                    else updateClassicTouch(event.rawX, event.rawY)
                }
                val index = event.actionIndex
                val pointerMatches = event.getPointerId(index) == pointerId
                val commit = tracking && pointerMatches && !isCanceled
                val longPress = held
                val wasRepeating = isRepeating
                val selectedItem = holdState.release()
                val activated = holdState.activated
                val swipeState = quickSwipe

                cancelPress()

                if (commit) {
                    if (longPress) {
                        if (!wasRepeating) {
                            if (onLongPressItemClick != null && selectedItem != null && activated) {
                                onLongPressItemClick?.invoke(selectedItem)
                            } else if (multifunctionMode == MultifunctionMode.CLASSIC) {
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

    private fun activateHold(item: LongPressItem?, repeat: (() -> Unit)?) {
        holdState.activate(item, repeat != null)
        activeRepeat = repeat
        if (repeat != null) repeatRunnable.run()
    }

    private fun showActionPopup() {
        showPopupConfiguration(popupConfiguration ?: KeyPreviewMetrics.initialPopup(context, height, holdPreview))
    }

    // Saved geometry uses absolute screen coordinates regardless of the current RTL locale.
    @android.annotation.SuppressLint("RtlHardcoded")
    private fun showPopupConfiguration(config: PopupConfiguration, renderShift: Boolean = shiftEnabled, renderCtrl: Boolean = ctrlEnabled) {
        runtimeConfiguration = config
        if (!isAttachedToWindow || windowToken == null) return
        popup?.dismiss()
        popup = null
        actionView = null
        val density = resources.displayMetrics.density
        val location = IntArray(2)
        getLocationOnScreen(location)
        popupOriginX = location[0] + width / 2.0 - config.anchor.x.toDouble() * density
        popupOriginY = location[1] + height / 2.0 - config.anchor.y.toDouble() * density
        // Double arithmetic avoids overflow when valid imported Float coordinates are large.
        val left = minOf(config.vertices.minOf { it.x.toDouble() }, config.items.minOfOrNull { it.center.x.toDouble() - it.radius } ?: Double.POSITIVE_INFINITY)
        val top = minOf(config.vertices.minOf { it.y.toDouble() }, config.items.minOfOrNull { it.center.y.toDouble() - it.radius } ?: Double.POSITIVE_INFINITY)
        val right = maxOf(config.vertices.maxOf { it.x.toDouble() }, config.items.maxOfOrNull { it.center.x.toDouble() + it.radius } ?: Double.NEGATIVE_INFINITY)
        val bottom = maxOf(config.vertices.maxOf { it.y.toDouble() }, config.items.maxOfOrNull { it.center.y.toDouble() + it.radius } ?: Double.NEGATIVE_INFINITY)
        val metrics = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
        val screen = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val usable = android.graphics.Rect(screen.left + insets.left, screen.top + insets.top,
            screen.right - insets.right, screen.bottom - insets.bottom)
        // Intersect the window with usable screen bounds; never translate or shrink the contents.
        val x = kotlin.math.floor(popupOriginX + left * density).coerceAtLeast(usable.left.toDouble())
        val y = kotlin.math.floor(popupOriginY + top * density).coerceAtLeast(usable.top.toDouble())
        val edgeX = kotlin.math.ceil(popupOriginX + right * density).coerceAtMost(usable.right.toDouble())
        val edgeY = kotlin.math.ceil(popupOriginY + bottom * density).coerceAtMost(usable.bottom.toDouble())
        if (edgeX <= x || edgeY <= y) return
        val w = (edgeX - x).toInt()
        val h = (edgeY - y).toInt()
        val drawing = PopupActionView(context, config, popupOriginX - x, popupOriginY - y,
            popupBgColor, popupSymbolColor, renderShift, renderCtrl, this)
        actionView = drawing
        popup = PopupWindow(drawing, w, h, false).apply {
            isTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isClippingEnabled = false
            setIsLaidOutInScreen(true)
            showAtLocation(this@KeyPreviewFrameLayout, Gravity.TOP or Gravity.LEFT, x.toInt(), y.toInt())
        }
    }

    private fun updatePopupTouch(localX: Float, localY: Float) {
        val config = runtimeConfiguration ?: return
        if (holdState.canceled) return
        val location = IntArray(2)
        getLocationOnScreen(location)
        val density = resources.displayMetrics.density
        val pointX = ((location[0].toDouble() + localX - popupOriginX) / density).toFloat()
        val pointY = ((location[1].toDouble() + localY - popupOriginY) / density).toFloat()
        if (!pointX.isFinite() || !pointY.isFinite()) {
            // Unrepresentable pointer mapping cannot execute an action; retain the saved configuration.
            cancelPress()
            isCanceled = true
            return
        }
        val point = PopupPoint(pointX, pointY)
        if (!PopupGeometry.contains(config, point)) {
            actionView?.activeIndex = null
            if (holdState.activated) {
                cancelPress()
                isCanceled = true
            }
            return
        }
        val target = PopupGeometry.hitItem(config, point)
        if (actionView?.activeIndex != target && target != null) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        actionView?.activeIndex = target
        if (target != null) {
            val item = config.items[target].binding
            if (!holdState.activated) {
                // The same hold session and repeat timer used by Classic keys owns execution.
                activateHold(item, repeatForItem?.invoke(item))
            } else holdState.select(item)
        }
    }

    private fun updateClassicTouch(rawX: Float, rawY: Float) {
        if (holdState.canceled || !tracking) return
        val density = resources.displayMetrics.density
        // Classic cancellation is relative to the activation touch, independent of key/window bounds.
        if (rawY - startY > (32 * density).toInt()) {
            cancelPress()
            isCanceled = true
            return
        }
        val config = runtimeConfiguration ?: return
        val initial = longPressDefaultIndex.takeIf { it in config.items.indices } ?: 0
        val target = ClassicPreviewGeometry.selectedIndex(config, initial, (rawX - startX) / density) ?: return
        if (target != currentSelectedIndex) {
            currentSelectedIndex = target
            getEffectiveItems().getOrNull(target)?.let(holdState::select)
            actionView?.activeIndex = target
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun showPreview(value: String) {
        // Tap/quick-swipe labels are already formatted; avoid applying Ctrl a second time.
        showPopupConfiguration(KeyPreviewMetrics.classicQueue(context, height,
            listOf(LongPressItem(text = value)), 0), renderShift = false, renderCtrl = false)
        actionView?.activeIndex = 0
    }

    private fun cancelPress() {
        removeCallbacks(selectHold)
        removeCallbacks(repeatRunnable)
        holdState.cancel()
        activeRepeat = null
        runtimeConfiguration = null
        actionView = null
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
