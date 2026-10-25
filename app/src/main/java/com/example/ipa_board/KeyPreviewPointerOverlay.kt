package com.example.ipa_board

import android.graphics.Canvas
import android.view.View
import android.view.ViewTreeObserver
import com.example.ipa_board.ime.ImeChromeView

/** The same live Pointer Location overlay for Classic and Popup preview windows. */
internal class KeyPreviewPointerOverlay(private val target: View, source: View?) : View.OnAttachStateChangeListener {
    private val chrome = run {
        var candidate = source
        while (candidate != null && candidate !is ImeChromeView) candidate = candidate.parent as? View
        candidate as? ImeChromeView
    }
    private val targetLocation = IntArray(2)
    private val chromeLocation = IntArray(2)
    private var observer: ViewTreeObserver? = null
    private val refresh = ViewTreeObserver.OnDrawListener {
        // Source redraws include pointer moves, color changes and disabling the overlay.
        if (target.isAttachedToWindow) target.invalidate()
    }

    init { target.addOnAttachStateChangeListener(this) }

    override fun onViewAttachedToWindow(view: View) {
        observer = chrome?.viewTreeObserver?.also { it.addOnDrawListener(refresh) }
    }

    override fun onViewDetachedFromWindow(view: View) {
        observer?.takeIf { it.isAlive }?.removeOnDrawListener(refresh)
        observer = null
    }

    fun draw(canvas: Canvas) {
        val source = chrome ?: return
        if (!source.showPointerLocation) return
        target.getLocationOnScreen(targetLocation)
        source.getLocationOnScreen(chromeLocation)
        val checkpoint = canvas.save()
        canvas.translate((chromeLocation[0] - targetLocation[0]).toFloat(),
            (chromeLocation[1] - targetLocation[1]).toFloat())
        for (path in source.pointerPaths.values) canvas.drawPath(path, source.pointerPaint)
        val crossSize = 10f * target.resources.displayMetrics.density
        for (point in source.currentPointers.values) {
            canvas.drawLine(0f, point.y, source.width.toFloat(), point.y, source.axisPaint)
            canvas.drawLine(point.x, 0f, point.x, source.height.toFloat(), source.axisPaint)
            canvas.drawLine(point.x - crossSize, point.y, point.x + crossSize, point.y, source.pointPaint)
            canvas.drawLine(point.x, point.y - crossSize, point.x, point.y + crossSize, source.pointPaint)
        }
        canvas.restoreToCount(checkpoint)
    }
}
