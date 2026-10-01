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
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
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
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
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

    @Test fun longPressSwipeSelectsActionsInCycle() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var selectedItem: LongPressItem? = null
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                val slot = KeySlot(1f, "a", longPressItems = listOf(
                    LongPressItem(text = "ɑ"),
                    LongPressItem(text = "æ"),
                    LongPressItem(text = "ɒ")
                ))
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("PreviewCycle", listOf(RowLayout(1f, listOf(slot)))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyLongItemClick = { _, _, _, item -> selectedItem = item })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val startX = key.width / 2f
                val startY = key.height / 2f

                // Down & wait for long press
                touch(key, MotionEvent.ACTION_DOWN, startX, startY)
            }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            scenario.onActivity {
                val density = key.resources.displayMetrics.density
                val stepPx = 28 * density
                val startX = key.width / 2f
                val startY = key.height / 2f

                // Initial long press preview (index 0: ɑ)
                assertEquals("ɑ", key.visiblePreview)

                // Swipe right 1 step -> index 1: æ
                touch(key, MotionEvent.ACTION_MOVE, startX + stepPx * 1.2f, startY)
                assertEquals("æ", key.visiblePreview)

                // Swipe right 2 steps -> index 2: ɒ
                touch(key, MotionEvent.ACTION_MOVE, startX + stepPx * 2.2f, startY)
                assertEquals("ɒ", key.visiblePreview)

                // Swipe right 3 steps -> index 0: ɑ
                touch(key, MotionEvent.ACTION_MOVE, startX + stepPx * 3.2f, startY)
                assertEquals("ɑ", key.visiblePreview)

                // Swipe left 1 step -> index 2: ɒ
                touch(key, MotionEvent.ACTION_MOVE, startX - stepPx * 1.2f, startY)
                assertEquals("ɒ", key.visiblePreview)

                // Swipe left 2 steps -> index 1: æ
                touch(key, MotionEvent.ACTION_MOVE, startX - stepPx * 2.2f, startY)
                assertEquals("æ", key.visiblePreview)

                // Release on index 1
                touch(key, MotionEvent.ACTION_UP, startX - stepPx * 2.2f, startY)
                assertNull(key.visiblePreview)
                assertEquals("æ", selectedItem?.text)
            }
        }
    }

    @Test fun longPressMicroJitterStaysOnInitialAction() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var selectedItem: LongPressItem? = null
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                val slot = KeySlot(1f, "a", longPressText = "ɑ, æ, ɒ")
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("PreviewJitter", listOf(RowLayout(1f, listOf(slot)))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyLongItemClick = { _, _, _, item -> selectedItem = item })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val startX = key.width / 2f
                val startY = key.height / 2f

                touch(key, MotionEvent.ACTION_DOWN, startX, startY)
            }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            scenario.onActivity {
                val startX = key.width / 2f
                val startY = key.height / 2f

                assertEquals("ɑ", key.visiblePreview)

                // Micro jitter to the left (-5px) within deadzone
                touch(key, MotionEvent.ACTION_MOVE, startX - 5f, startY)
                assertEquals("ɑ", key.visiblePreview)

                // Micro jitter to the right (+5px) within deadzone
                touch(key, MotionEvent.ACTION_MOVE, startX + 5f, startY)
                assertEquals("ɑ", key.visiblePreview)

                touch(key, MotionEvent.ACTION_UP, startX, startY)
                assertEquals("ɑ", selectedItem?.text)
            }
        }
    }

    @Test fun longPressSwipeDownCancelsOperation() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var committed = false
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                val slot = KeySlot(1f, "a", longPressText = "ɑ, æ, ɒ")
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("PreviewCancel", listOf(RowLayout(1f, listOf(slot)))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyLongItemClick = { _, _, _, _ -> committed = true })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val startX = key.width / 2f
                val startY = key.height / 2f

                touch(key, MotionEvent.ACTION_DOWN, startX, startY)
            }
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            scenario.onActivity {
                val density = key.resources.displayMetrics.density
                val startX = key.width / 2f
                val startY = key.height / 2f

                assertEquals("ɑ", key.visiblePreview)

                // Swipe down by 40dp (> 32dp threshold)
                touch(key, MotionEvent.ACTION_MOVE, startX, startY + 40 * density)
                assertNull(key.visiblePreview)

                touch(key, MotionEvent.ACTION_UP, startX, startY + 40 * density)
                assertFalse(committed)
            }
        }
    }

    @Test fun quickSwipeLeftAndRightTriggerBeforeLongPress() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var swipedItem: LongPressItem? = null
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                val slot = KeySlot(
                    1f, "a",
                    longPressText = "ɑ",
                    swipeLeftText = "«",
                    swipeRightText = "»"
                )
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("PreviewQuickSwipe", listOf(RowLayout(1f, listOf(slot)))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyQuickSwipeItemClick = { _, _, _, item -> swipedItem = item })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val density = key.resources.displayMetrics.density
                val startX = key.width / 2f
                val startY = key.height / 2f

                // Down and quick swipe left before long press timeout
                touch(key, MotionEvent.ACTION_DOWN, startX, startY)
                touch(key, MotionEvent.ACTION_MOVE, startX - 30 * density, startY)
                assertEquals("«", key.visiblePreview)
                touch(key, MotionEvent.ACTION_UP, startX - 30 * density, startY)
                assertEquals("«", swipedItem?.text)

                // Down and quick swipe right before long press timeout
                swipedItem = null
                touch(key, MotionEvent.ACTION_DOWN, startX, startY)
                touch(key, MotionEvent.ACTION_MOVE, startX + 30 * density, startY)
                assertEquals("»", key.visiblePreview)
                touch(key, MotionEvent.ACTION_UP, startX + 30 * density, startY)
                assertEquals("»", swipedItem?.text)
            }
        }
    }

    @Test fun quickSwipeHeldDownEntersLongPress() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var longItem: LongPressItem? = null
            var quickItem: LongPressItem? = null
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                val slot = KeySlot(
                    1f, "a",
                    longPressText = "ɑ",
                    swipeLeftText = "«"
                )
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("PreviewHoldSwipe", listOf(RowLayout(1f, listOf(slot)))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyQuickSwipeItemClick = { _, _, _, item -> quickItem = item },
                    onKeyLongItemClick = { _, _, _, item -> longItem = item })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val density = key.resources.displayMetrics.density
                val startX = key.width / 2f
                val startY = key.height / 2f

                // Touch down & swipe left
                touch(key, MotionEvent.ACTION_DOWN, startX, startY)
                touch(key, MotionEvent.ACTION_MOVE, startX - 30 * density, startY)
                assertEquals("«", key.visiblePreview)
            }
            // Hold down and wait for long press timeout (500ms)
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            scenario.onActivity {
                val density = key.resources.displayMetrics.density
                val startX = key.width / 2f
                val startY = key.height / 2f

                // Long press should now be active, previewing long press item 'ɑ'
                assertEquals("ɑ", key.visiblePreview)

                touch(key, MotionEvent.ACTION_UP, startX - 30 * density, startY)
                assertNull(quickItem)
                assertEquals("ɑ", longItem?.text)
            }
        }
    }

    private fun touch(key: KeyPreviewFrameLayout, action: Int, x: Float = key.width / 2f, y: Float = key.height / 2f) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { key.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}