package com.example.ipa_board

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object KeyboardRenderer {

    fun render(context: Context, container: ViewGroup, layout: KeyboardLayout, heightPx: Int, symbolColor: Int,
        shiftEnabled: Boolean = false,
        ctrlEnabled: Boolean = false,
        showUnassignedPlaceholders: Boolean = false,
        showKeyPreview: Boolean = false,
        fontSizeSp: Int = SettingsConstants.DEFAULT_KEYBOARD_FONT_SIZE,
        onKeyQuickSwipeItemClick: ((Int, Int, KeySlot, LongPressItem) -> Unit)? = null,
        onKeyLongItemClick: ((Int, Int, KeySlot, LongPressItem) -> Unit)? = null,
        onKeyLongClick: ((Int, Int, KeySlot) -> Unit)? = null,
        onKeyClick: ((Int, Int, KeySlot) -> Unit)? = null
    ) {
        container.removeAllViews()
        
        val totalWeight = layout.rows.sumOf { it.heightWeight.toDouble() }.toFloat()

        for ((rowIndex, row) in layout.rows.withIndex()) {
            val rowHeightPx = if (totalWeight > 0) {
                ((row.heightWeight / totalWeight) * heightPx).toInt()
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }

            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    rowHeightPx
                )
            }

            for ((columnIndex, slot) in row.slots.withIndex()) {
                val active = (slot.action == KeyAction.SHIFT && shiftEnabled) ||
                    (slot.action == KeyAction.CTRL && ctrlEnabled)
                val slotView = FrameLayoutWithBorder(context, symbolColor, active).apply {
                    isSelected = active
                    previewEnabled = showKeyPreview
                    this.shiftEnabled = shiftEnabled
                    this.ctrlEnabled = ctrlEnabled
                    tapPreview = when {
                        slot.action != KeyAction.TEXT -> slot.action.keyLabel
                        slot.text == " " -> "␣"
                        slot.text == "\n" -> "↵"
                        slot.text == "\t" -> "⇥"
                        else -> slot.displayText(shiftEnabled)
                    }.let { if (ctrlEnabled && slot.action == KeyAction.TEXT) "Ctrl+$it" else it }
                    longPressItems = slot.effectiveLongPressItems
                    swipeLeftItem = slot.effectiveSwipeLeftItem
                    swipeRightItem = slot.effectiveSwipeRightItem
                    val firstItem = slot.effectiveLongPressItems.firstOrNull()
                    holdPreview = firstItem?.previewText(shiftEnabled, ctrlEnabled) ?: when {
                        slot.longPressAction != KeyAction.TEXT -> slot.longPressAction.keyLabel
                        slot.longPressText == " " -> "␣"
                        slot.longPressText == "\n" -> "↵"
                        slot.longPressText == "\t" -> "⇥"
                        else -> slot.longPressText
                    }.let { if (ctrlEnabled && slot.longPressAction == KeyAction.TEXT && slot.longPressText.isNotEmpty()) "Ctrl+$it" else it }
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        slot.widthWeight
                    )
                }
                
                val unassigned = slot.action == KeyAction.TEXT && slot.text.isEmpty()
                val label = if (unassigned && !showUnassignedPlaceholders) "" else slot.displayText(shiftEnabled)
                val textView = TextView(context).apply {
                    textSize = fontSizeSp.coerceIn(SettingsConstants.MIN_KEYBOARD_FONT_SIZE, SettingsConstants.MAX_KEYBOARD_FONT_SIZE).toFloat()
                    text = label + if (active) " •" else ""
                    maxLines = 2
                    gravity = Gravity.CENTER
                    setTextColor(symbolColor)
                }
                
                val description = when {
                    unassigned -> "unassigned"
                    slot.action == KeyAction.TEXT -> slot.displayText(shiftEnabled)
                    else -> slot.action.title
                }
                slotView.contentDescription = "Row ${rowIndex + 1}, key ${columnIndex + 1}: $description" +
                    if (active) ", active" else ""
                if (onKeyClick != null) {
                    slotView.isFocusable = true
                    slotView.setOnClickListener { onKeyClick(rowIndex, columnIndex, slot) }
                }
                if (onKeyQuickSwipeItemClick != null) {
                    slotView.onSwipeLeftItemClick = { item ->
                        onKeyQuickSwipeItemClick(rowIndex, columnIndex, slot, item)
                    }
                    slotView.onSwipeRightItemClick = { item ->
                        onKeyQuickSwipeItemClick(rowIndex, columnIndex, slot, item)
                    }
                }
                if ((onKeyLongItemClick != null || onKeyLongClick != null) && slot.hasLongPress) {
                    slotView.onLongPressItemClick = { item ->
                        if (onKeyLongItemClick != null) {
                            onKeyLongItemClick(rowIndex, columnIndex, slot, item)
                        } else {
                            onKeyLongClick?.invoke(rowIndex, columnIndex, slot)
                        }
                    }
                    slotView.setOnLongClickListener {
                        val firstItem = slot.effectiveLongPressItems.firstOrNull() ?: LongPressItem(text = slot.longPressText, action = slot.longPressAction)
                        if (onKeyLongItemClick != null) {
                            onKeyLongItemClick(rowIndex, columnIndex, slot, firstItem)
                        } else {
                            onKeyLongClick?.invoke(rowIndex, columnIndex, slot)
                        }
                        true
                    }
                }
                if (slot.longPressAction == KeyAction.REPEAT_BACKSPACE || slot.action == KeyAction.REPEAT_BACKSPACE) {
                    slotView.onLongPressRepeat = {
                        val firstItem = slot.effectiveLongPressItems.firstOrNull() ?: LongPressItem(text = slot.longPressText, action = slot.longPressAction)
                        if (onKeyLongItemClick != null) {
                            onKeyLongItemClick(rowIndex, columnIndex, slot, firstItem)
                        } else {
                            onKeyLongClick?.invoke(rowIndex, columnIndex, slot)
                        }
                    }
                }
                slotView.addView(textView, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ))
                if (slot.hasLongPress) {
                    val firstItem = slot.effectiveLongPressItems.firstOrNull()
                    // The persistent corner label shows the symbol, independently of Ctrl input mode.
                    val longPressLabel = firstItem?.previewText(shiftEnabled)
                        ?: if (slot.longPressAction != KeyAction.TEXT) slot.longPressAction.keyLabel else slot.longPressText
                    val longPressDesc = slot.effectiveLongPressItems.joinToString(", ") { it.description() }.ifEmpty {
                        if (slot.longPressAction != KeyAction.TEXT) slot.longPressAction.title else slot.longPressText
                    }
                    slotView.contentDescription = "${slotView.contentDescription}, long press: $longPressDesc"
                    slotView.addView(TextView(context).apply {
                        text = longPressLabel
                        textSize = fontSizeSp.coerceIn(SettingsConstants.MIN_KEYBOARD_FONT_SIZE, SettingsConstants.MAX_KEYBOARD_FONT_SIZE) * 9f / SettingsConstants.DEFAULT_KEYBOARD_FONT_SIZE
                        setTextColor(symbolColor)
                        alpha = 0.65f
                        maxLines = 1
                        gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    ).apply {
                        topMargin = (2 * context.resources.displayMetrics.density).toInt()
                    })
                }
                rowView.addView(slotView)
            }
            
            container.addView(rowView)
        }
    }

    private class FrameLayoutWithBorder(context: Context, symbolColor: Int, active: Boolean) : KeyPreviewFrameLayout(context) {
        init {
            val border = GradientDrawable().apply {
                setColor(if (active) Color.argb(65, Color.red(symbolColor), Color.green(symbolColor), Color.blue(symbolColor)) else Color.TRANSPARENT)
                val alphaColor = Color.argb(
                    30, 
                    Color.red(symbolColor), 
                    Color.green(symbolColor), 
                    Color.blue(symbolColor)
                )
                setStroke(1, alphaColor)
            }
            background = border
        }
    }
}