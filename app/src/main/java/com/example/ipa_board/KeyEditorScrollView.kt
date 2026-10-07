package com.example.ipa_board

import android.content.Context
import android.util.AttributeSet
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ScrollView

/** Leave room for the dialog title, instructions and action buttons as fields appear. */
class KeyEditorScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val availableHeight = metrics.bounds.height() - insets.top - insets.bottom
        val maximum = (availableHeight * 0.6f).toInt().coerceAtLeast(1)
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val height = if (mode == MeasureSpec.UNSPECIFIED) maximum
            else minOf(MeasureSpec.getSize(heightMeasureSpec), maximum)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
    }
}
