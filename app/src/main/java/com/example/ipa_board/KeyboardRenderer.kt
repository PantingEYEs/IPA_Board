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
                val slotView = FrameLayoutWithBorder(context, symbolColor).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        slot.widthWeight
                    )
                }
                
                val textView = TextView(context).apply {
                    text = slot.text.ifEmpty { "∅" }
                    maxLines = 2
                    gravity = Gravity.CENTER
                    setTextColor(symbolColor)
                }
                
                slotView.contentDescription = "Row ${rowIndex + 1}, key ${columnIndex + 1}: ${slot.text.ifEmpty { "unassigned" }}"
                if (onKeyClick != null) {
                    slotView.isFocusable = true
                    slotView.setOnClickListener { onKeyClick(rowIndex, columnIndex, slot) }
                }
                slotView.addView(textView)
                rowView.addView(slotView)
            }
            
            container.addView(rowView)
        }
    }

    private class FrameLayoutWithBorder(context: Context, symbolColor: Int) : android.widget.FrameLayout(context) {
        init {
            val border = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
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
