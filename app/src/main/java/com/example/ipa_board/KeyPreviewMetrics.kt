package com.example.ipa_board

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Keeps new Popup geometry derived from the existing Classic preview dimensions. */
internal object KeyPreviewMetrics {
    fun style(label: TextView) {
        val density = label.resources.displayMetrics.density
        label.textSize = 24f
        label.gravity = Gravity.CENTER
        label.setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
        label.minWidth = (56 * density).toInt()
        label.minHeight = (56 * density).toInt()
        label.maxLines = 3
        label.ellipsize = TextUtils.TruncateAt.END
        label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun measure(label: TextView) {
        val density = label.resources.displayMetrics.density
        label.measure(View.MeasureSpec.makeMeasureSpec(minOf((180 * density).toInt(), label.resources.displayMetrics.widthPixels), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((120 * density).toInt(), View.MeasureSpec.AT_MOST))
    }

    @android.annotation.SuppressLint("AppCompatCustomView")
    fun initialPopup(context: Context, keyHeightPx: Int, previewText: String): PopupConfiguration {
        val label = TextView(context).apply { text = previewText; style(this) }
        measure(label)
        val density = context.resources.displayMetrics.density
        return PopupConfiguration.default(keyHeightPx / density, label.measuredWidth / density, label.measuredHeight / density)
    }

    @android.annotation.SuppressLint("AppCompatCustomView")
    fun classicQueue(context: Context, keyHeightPx: Int, items: List<LongPressItem>, defaultIndex: Int,
        shift: Boolean = false, ctrl: Boolean = false): PopupConfiguration {
        var diameter = 56f
        val density = context.resources.displayMetrics.density
        for (item in items) {
            // A measured, unattached TextView cannot safely be rebound without layout params.
            val label = TextView(context).apply { text = item.previewText(shift, ctrl); style(this) }
            measure(label)
            diameter = maxOf(diameter, maxOf(label.measuredWidth, label.measuredHeight) / density)
        }
        return ClassicPreviewGeometry.configuration(items, defaultIndex, (keyHeightPx / density).coerceAtLeast(1f), diameter)
    }
}
