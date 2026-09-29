package com.example.ipa_board

import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class KeyPressPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun tapAndHoldPreviewBeforeCommittingOnRelease() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var taps = 0
            var holds = 0
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("Preview", listOf(RowLayout(1f, listOf(KeySlot(1f, "a", longPressText = "ɑ😀"))))),
                    180, Color.WHITE, shiftEnabled = true, showKeyPreview = true,
                    onKeyLongClick = { _, _, _ -> holds++ }, onKeyClick = { _, _, _ -> taps++ })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                touch(key, MotionEvent.ACTION_DOWN)
                assertEquals("A", key.visiblePreview)
                assertEquals(0, taps)
                touch(key, MotionEvent.ACTION_UP)
                assertNull(key.visiblePreview)
                assertEquals(1, taps)
                touch(key, MotionEvent.ACTION_DOWN)
            }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            scenario.onActivity {
                assertEquals("ɑ😀", key.visiblePreview)
                assertEquals(0, holds)
                assertEquals(1, taps)
                touch(key, MotionEvent.ACTION_UP)
                assertNull(key.visiblePreview)
                assertEquals(1, holds)
                assertEquals(1, taps)
            }
        }
    }

    @Test fun movingOutCancellingAndDetachingNeverCommitOrLeavePopup() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            lateinit var host: LinearLayout
            var commits = 0
            scenario.onActivity { activity ->
                host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("Preview", listOf(RowLayout(1f, listOf(KeySlot(1f, action = KeyAction.BACKSPACE))))),
                    180, Color.WHITE, showKeyPreview = true, onKeyClick = { _, _, _ -> commits++ })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                touch(key, MotionEvent.ACTION_DOWN)
                assertEquals("⌫", key.visiblePreview)
            }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            scenario.onActivity {
                assertEquals("⌫", key.visiblePreview)
                touch(key, MotionEvent.ACTION_UP)
                assertEquals(1, commits) // No long mapping: holding still commits the ordinary key once.
                touch(key, MotionEvent.ACTION_DOWN)
                touch(key, MotionEvent.ACTION_MOVE, -10f)
                assertNull(key.visiblePreview)
                touch(key, MotionEvent.ACTION_UP)
                touch(key, MotionEvent.ACTION_DOWN)
                touch(key, MotionEvent.ACTION_CANCEL)
                assertNull(key.visiblePreview)
                touch(key, MotionEvent.ACTION_DOWN)
                host.removeAllViews()
                assertNull(key.visiblePreview)
                assertEquals(1, commits)
            }
        }
    }

    private fun touch(key: KeyPreviewFrameLayout, action: Int, x: Float = key.width / 2f) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, key.height / 2f, 0)
        try { key.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
