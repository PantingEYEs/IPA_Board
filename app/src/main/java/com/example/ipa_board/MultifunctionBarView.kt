package com.example.ipa_board

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.text.TextUtils
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton

/** Stable positions, independent of whichever action the user assigns to them. */
enum class MultifunctionBarSlot(val wireValue: String, val defaultAction: KeyAction) {
    LEFT_FIRST("left_first", KeyAction.CLIPBOARD),
    LEFT_SECOND("left_second", KeyAction.PAGES),
    LEFT_THIRD("left_third", KeyAction.KAOMOJI),
    RIGHT_FIRST("right_first", KeyAction.REMOTE_KEYBOARD),
    RIGHT_SECOND("right_second", KeyAction.WIDTH_SWAP),
    RIGHT_THIRD("right_third", KeyAction.CALCULATOR)
}

/** One layout for the service and the settings preview; neither caller builds its own buttons. */
class MultifunctionBarView(context: Context) : LinearLayout(context) {
    val statusContainer = FrameLayout(context)
    val status = TextView(context)
    var onSlotClick: (MultifunctionBarSlot) -> Unit = {}
    var editing = false
        set(value) { field = value; render() }
    private var bindings = MultifunctionBarSettings.defaults()
    private var calculatorEnabled = false
    private var remoteKeyboardEnabled = false
    private var widthSwapEnabled = false
    private var shiftState = ShiftState.OFF
    private var ctrlEnabled = false
    private val buttons = MultifunctionBarSlot.entries.associateWith { slot ->
        RepeatingButton(context).apply {
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = null
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(4), 0, dp(4), 0)
            minHeight = dp(28)
            minimumHeight = dp(28)
            minWidth = dp(50)
            minimumWidth = dp(50)
            layoutParams = LayoutParams(dp(50), dp(28))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { onSlotClick(slot) }
        }
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.BLACK)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(28))
        MultifunctionBarSlot.entries.take(3).forEach { addView(buttons.getValue(it)) }
        status.setTextColor(Color.LTGRAY)
        status.textSize = 10f
        status.gravity = Gravity.CENTER_VERTICAL
        statusContainer.addView(status, FrameLayout.LayoutParams(-1, -1).apply {
            setMargins(dp(6), 0, dp(6), 0)
        })
        addView(statusContainer, LayoutParams(0, dp(28), 1f))
        MultifunctionBarSlot.entries.drop(3).forEach { addView(buttons.getValue(it)) }
        render()
    }

    fun buttonAt(slot: MultifunctionBarSlot): Button = buttons.getValue(slot)

    fun bindingAt(slot: MultifunctionBarSlot): MultifunctionBarBinding = bindings.getValue(slot)

    fun setBindings(value: Map<MultifunctionBarSlot, MultifunctionBarBinding>) {
        val next = MultifunctionBarSettings.defaults() + value
        buttons.forEach { (slot, button) ->
            if (bindings[slot] != next[slot]) button.cancelGesture()
        }
        bindings = next
        render()
    }

    fun setCalculatorEnabled(value: Boolean) { calculatorEnabled = value; render() }
    fun setRemoteKeyboardEnabled(value: Boolean) { remoteKeyboardEnabled = value; render() }
    fun setWidthSwapEnabled(value: Boolean) { widthSwapEnabled = value; render() }
    fun setModifiers(shift: ShiftState, ctrl: Boolean) { shiftState = shift; ctrlEnabled = ctrl; render() }

    private fun render() {
        buttons.forEach { (slot, button) ->
            val binding = bindings.getValue(slot)
            val action = binding.action
            val active = !editing && when (action) {
                KeyAction.REMOTE_KEYBOARD -> remoteKeyboardEnabled
                KeyAction.WIDTH_SWAP -> widthSwapEnabled
                KeyAction.CALCULATOR -> calculatorEnabled
                KeyAction.SHIFT -> shiftState != ShiftState.OFF
                KeyAction.CTRL -> ctrlEnabled
                else -> false
            }
            button.text = when {
                action == KeyAction.SHIFT && !editing && shiftState == ShiftState.CAPS_LOCK -> context.getString(R.string.caps_lock_label) + " •"
                action in setOf(KeyAction.SHIFT, KeyAction.CTRL) && active -> action.keyLabel + " •"
                action == KeyAction.TEXT -> binding.asKeySlot().displayText(!editing && shiftState != ShiftState.OFF).ifEmpty { "∅" }
                else -> action.keyLabel
            }
            button.setTypeface(null, if (!editing && action == KeyAction.SHIFT && shiftState == ShiftState.CAPS_LOCK) Typeface.BOLD else Typeface.NORMAL)
            button.contentDescription = if (action == KeyAction.TEXT) binding.text.ifEmpty { "unassigned" } else description(action)
            button.isSelected = active
            button.stateDescription = if (editing) null else when (action) {
                KeyAction.REMOTE_KEYBOARD -> context.getString(if (active) R.string.remote_keyboard_on else R.string.remote_keyboard_off)
                KeyAction.WIDTH_SWAP -> if (active) "开启" else "关闭"
                KeyAction.CALCULATOR, KeyAction.CTRL -> if (active) "On" else "Off"
                KeyAction.SHIFT -> context.getString(when (shiftState) {
                    ShiftState.OFF -> R.string.shift_state_off
                    ShiftState.SINGLE -> R.string.shift_state_once
                    ShiftState.CAPS_LOCK -> R.string.caps_lock_label
                })
                else -> null
            }
            button.alpha = if (!editing && action in setOf(KeyAction.REMOTE_KEYBOARD, KeyAction.WIDTH_SWAP, KeyAction.CALCULATOR) && !active) 0.5f else 1f
            button.repeatEnabled = !editing && action == KeyAction.REPEAT_BACKSPACE
        }
    }

    /** Repeats dispatch the same binding callback as a click; deletion stays in the IME controller. */
    private class RepeatingButton(context: Context) : AppCompatButton(context, null, android.R.attr.buttonStyle) {
        var repeatEnabled = false
            set(value) { if (field != value) cancelGesture(); field = value }
        private var tracking = false
        private var repeating = false
        private var canceled = false
        private val repeat = object : Runnable {
            override fun run() {
                if (!tracking || canceled || !repeatEnabled || !isEnabled) return
                repeating = true
                performClick()
                if (tracking && !canceled) postDelayed(this, 50L)
            }
        }

        fun cancelGesture() {
            if (tracking) canceled = true
            tracking = false
            removeCallbacks(repeat)
            isPressed = false
        }

        // Ordinary taps reach accessible performClick through Button's queued UP handling.
        // A completed repeat consumes UP so it cannot also enqueue an extra ordinary click.
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled) return super.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelGesture()
                    tracking = true
                    repeating = false
                    canceled = false
                    if (repeatEnabled) postDelayed(repeat, KeyboardGestureSettings.longPressTimeoutMs(context).toLong())
                }
                MotionEvent.ACTION_MOVE -> if (repeatEnabled && (event.x < 0 || event.x >= width || event.y < 0 || event.y >= height)) cancelGesture()
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelGesture()
                MotionEvent.ACTION_UP -> {
                    val consumed = repeating || canceled
                    tracking = false
                    removeCallbacks(repeat)
                    if (consumed) { isPressed = false; return true }
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow() }
        override fun performClick(): Boolean = super.performClick()
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            if (!hasWindowFocus) cancelGesture()
            super.onWindowFocusChanged(hasWindowFocus)
        }
        override fun onWindowVisibilityChanged(visibility: Int) {
            if (visibility != VISIBLE) cancelGesture()
            super.onWindowVisibilityChanged(visibility)
        }
    }

    companion object {
        // Preserve the existing toolbar labels while the key editor uses KeyAction's full titles.
        fun description(action: KeyAction): String = when (action) {
            KeyAction.PAGES -> "Keyboard Overview"
            KeyAction.CALCULATOR -> "Calculator"
            else -> action.title
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
