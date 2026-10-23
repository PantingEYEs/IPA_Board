package com.example.ipa_board

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Owned-view checks compare the two actual gesture surfaces, including held states. */
class PopupGestureEquivalenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private data class Observation(val heldEffects: List<String>, val repeating: Boolean,
        val finalEffects: List<String>, val finalRepeating: Boolean, val taps: Int)

    @Test fun allEightOrdinaryBindingCasesMatchClassicLongPress() {
        for (case in 0..7) {
            assertEquals("Gesture equivalence ${case / 2 + 1}${if (case % 2 == 0) 'a' else 'b'}",
                exercise(MultifunctionMode.CLASSIC, LongPressItem(text = "ɑ"), case),
                exercise(MultifunctionMode.POPUP, LongPressItem(text = "ɑ"), case))
        }
    }

    @Test fun allEightRepeatingBindingCasesMatchClassicWithoutASecondRepeatLoop() {
        for (case in 0..7) {
            assertEquals("Repeating equivalence case $case",
                exercise(MultifunctionMode.CLASSIC, LongPressItem(action = KeyAction.REPEAT_BACKSPACE), case),
                exercise(MultifunctionMode.POPUP, LongPressItem(action = KeyAction.REPEAT_BACKSPACE), case))
        }
    }

    private fun exercise(mode: MultifunctionMode, binding: LongPressItem, case: Int): Observation {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            val effects = mutableListOf<String>()
            var taps = 0
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                val configuration = PopupConfiguration(
                    vertices = listOf(PopupPoint(-60f, -100f), PopupPoint(60f, -100f), PopupPoint(60f, -20f), PopupPoint(-60f, -20f)),
                    anchor = PopupPoint(0f, 0f),
                    items = listOf(PopupItem(PopupPoint(0f, -60f), 15f, binding)))
                val slot = KeySlot(1f, "a", longPressAction = binding.action, longPressItems = listOf(binding),
                    multifunctionMode = mode, popupConfiguration = configuration)
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("Equivalence", listOf(RowLayout(1f, listOf(slot)))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyLongItemClick = { _, _, _, item -> effects += item.previewText() },
                    onKeyClick = { _, _, _ -> taps++ })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { touch(key, MotionEvent.ACTION_DOWN, key.width / 2f, key.height / 2f) }
            SystemClock.sleep(KeyboardGestureSettings.longPressTimeoutMs(instrumentation.targetContext).toLong() + 100)
            var result: Observation? = null
            scenario.onActivity {
                val density = key.resources.displayMetrics.density
                val centerX = key.width / 2f
                val centerY = key.height / 2f
                val onY = if (mode == MultifunctionMode.POPUP) centerY - 60f * density else centerY
                // Popup starts outside its boundary; acquiring an item must not be mistaken for cancellation.
                if (mode == MultifunctionMode.POPUP) {
                    touch(key, MotionEvent.ACTION_MOVE, centerX, onY)
                    assertEquals(0, key.activePopupItem)
                }
                val heldEffects = effects.distinct()
                var x = centerX
                var y = onY
                if (case >= 2 && case <= 3) {
                    x += 35f * density // Blank space, still inside the popup / Classic horizontal slide.
                    touch(key, MotionEvent.ACTION_MOVE, x, y)
                    if (mode == MultifunctionMode.POPUP) assertNull(key.activePopupItem)
                } else if (case >= 4) {
                    y = centerY + 45f * density
                    touch(key, MotionEvent.ACTION_MOVE, x, y)
                    assertFalse("Cancellation must stop repeating before release", key.repeatingHold)
                    if (case >= 6) {
                        y = centerY
                        touch(key, MotionEvent.ACTION_MOVE, x, y)
                    }
                }
                val repeating = key.repeatingHold
                if (case % 2 == 1) touch(key, MotionEvent.ACTION_UP, x, y)
                result = Observation(heldEffects, repeating, effects.distinct(), key.repeatingHold, taps)
                touch(key, MotionEvent.ACTION_CANCEL, x, y)
            }
            return requireNotNull(result)
        }
    }

    @Test fun popupSuppressesStoredQuickSwipesAndWorksWithClassicPreviewDisabled() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var taps = 0
            var swipes = 0
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                activity.setContentView(host)
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("Popup", listOf(RowLayout(1f, listOf(KeySlot(1f, "a",
                        swipeRightText = "classic", multifunctionMode = MultifunctionMode.POPUP))))),
                    180, Color.WHITE, showKeyPreview = false,
                    onKeyQuickSwipeItemClick = { _, _, _, _ -> swipes++ }, onKeyClick = { _, _, _ -> taps++ })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val x = key.width / 2f
                val y = key.height / 2f
                touch(key, MotionEvent.ACTION_DOWN, x, y)
                touch(key, MotionEvent.ACTION_MOVE, x + 30 * key.resources.displayMetrics.density, y)
                touch(key, MotionEvent.ACTION_UP, x + 30 * key.resources.displayMetrics.density, y)
                assertEquals(0, swipes)
                assertEquals(1, taps)
            }
        }
    }

    @Test fun runtimeClipsAtUsableScreenEdgesWithoutMovingSavedItems() {
        val configuration = PopupConfiguration(
            vertices = listOf(PopupPoint(-10_000f, -10_000f), PopupPoint(100f, -10_000f),
                PopupPoint(100f, -20f), PopupPoint(-10_000f, -20f)),
            anchor = PopupPoint(0f, 0f),
            items = listOf(PopupItem(PopupPoint(0f, -60f), 15f, LongPressItem(text = "clipped"))))
        verifyClippedPopup(configuration, expected = "clipped", inspectBounds = true)
    }

    @Test fun extremeFiniteGeometryCanBeDrawnAndSelectedWithoutChangingItsConfiguration() {
        val limit = Float.MAX_VALUE
        val configuration = PopupConfiguration(
            vertices = listOf(PopupPoint(-limit, -limit), PopupPoint(limit, -limit),
                PopupPoint(limit, limit), PopupPoint(-limit, limit)),
            anchor = PopupPoint(0f, 0f),
            items = listOf(PopupItem(PopupPoint(0f, -60f), 15f, LongPressItem(text = "finite"))))
        verifyClippedPopup(configuration, expected = "finite", inspectBounds = false)
    }

    private fun verifyClippedPopup(configuration: PopupConfiguration, expected: String, inspectBounds: Boolean) {
        val before = configuration.toJsonObject().toString()
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var selected: LongPressItem? = null
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                val slot = KeySlot(1f, "tap", multifunctionMode = MultifunctionMode.POPUP,
                    popupConfiguration = configuration)
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("Clip fixture", listOf(RowLayout(1f, listOf(slot)))),
                    180, Color.WHITE, showKeyPreview = false,
                    onKeyLongItemClick = { _, _, _, item -> selected = item }, onKeyClick = { _, _, _ -> fail("Popup must not produce a tap") })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { touch(key, MotionEvent.ACTION_DOWN, key.width / 2f, key.height / 2f) }
            SystemClock.sleep(KeyboardGestureSettings.longPressTimeoutMs(instrumentation.targetContext).toLong() + 100)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val content = requireNotNull(key.visiblePopupContent)
                assertTrue(content.width > 0 && content.height > 0)
                val location = IntArray(2).also(content::getLocationOnScreen)
                val actual = Rect(location[0], location[1], location[0] + content.width, location[1] + content.height)
                val metrics = activity.getSystemService(WindowManager::class.java).maximumWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val usable = Rect(metrics.bounds.left + insets.left, metrics.bounds.top + insets.top,
                    metrics.bounds.right - insets.right, metrics.bounds.bottom - insets.bottom)
                assertTrue("Actual popup content must remain within usable screen: $actual / $usable", usable.contains(actual))
                if (inspectBounds) {
                    assertEquals("Offscreen left edge is clipped, not repositioned", usable.left, actual.left)
                    assertEquals("Offscreen top edge is clipped, not repositioned", usable.top, actual.top)
                }
                // Draw only this test-owned popup, never capture the device or another app.
                val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                try { content.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
                val x = key.width / 2f
                val y = key.height / 2f - 60f * key.resources.displayMetrics.density
                touch(key, MotionEvent.ACTION_MOVE, x, y)
                assertEquals("The item stays at its original coordinates after clipping", 0, key.activePopupItem)
                touch(key, MotionEvent.ACTION_UP, x, y)
                assertEquals(expected, selected?.text)
                assertEquals(before, key.popupConfiguration?.toJsonObject().toString())
            }
        }
    }

    private fun touch(key: KeyPreviewFrameLayout, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { key.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
