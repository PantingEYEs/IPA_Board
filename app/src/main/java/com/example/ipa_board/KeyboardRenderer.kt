package com.example.ipa_board

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

object KeyboardRenderer {

    fun render(context: Context, container: ViewGroup, layout: KeyboardLayout, heightPx: Int, symbolColor: Int,
        shiftEnabled: Boolean = false,
        ctrlEnabled: Boolean = false,
        showUnassignedPlaceholders: Boolean = false,
        showKeyPreview: Boolean = false,
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
                    tapPreview = when {
                        slot.action != KeyAction.TEXT -> slot.action.keyLabel
                        slot.text == " " -> "␣"
                        slot.text == "\n" -> "↵"
                        slot.text == "\t" -> "⇥"
                        else -> slot.displayText(shiftEnabled)
                    }.let { if (ctrlEnabled && slot.action == KeyAction.TEXT) "Ctrl+$it" else it }
                    holdPreview = when {
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
                if (onKeyLongClick != null && slot.hasLongPress) {
                    slotView.setOnLongClickListener {
                        onKeyLongClick(rowIndex, columnIndex, slot)
                        true
                    }
                }
                if (slot.longPressAction == KeyAction.REPEAT_BACKSPACE || slot.action == KeyAction.REPEAT_BACKSPACE) {
                    slotView.onLongPressRepeat = {
                        onKeyLongClick?.invoke(rowIndex, columnIndex, slot)
                    }
                }
                slotView.addView(textView)
                if (slot.hasLongPress) {
                    val longPressLabel = if (slot.longPressAction != KeyAction.TEXT) slot.longPressAction.keyLabel else slot.longPressText
                    val longPressDesc = if (slot.longPressAction != KeyAction.TEXT) slot.longPressAction.title else slot.longPressText
                    slotView.contentDescription = "${slotView.contentDescription}, long press: $longPressDesc"
                    slotView.addView(TextView(context).apply {
                        text = longPressLabel
                        textSize = 10f
                        setTextColor(symbolColor)
                        maxLines = 1
                        gravity = Gravity.END
                        setPadding(2, 0, 4, 0)
                    }, android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
                    ))
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
                // Use a subtle version of the symbol color for the border
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
