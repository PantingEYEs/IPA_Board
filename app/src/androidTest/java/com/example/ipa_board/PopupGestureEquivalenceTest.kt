package com.example.ipa_board

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import com.example.ipa_board.ime.ImeChromeView
import java.util.concurrent.atomic.AtomicInteger
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

    @Test fun popupPointerOverlayUsesChromeCoordinatesColorsAndLiveVisibility() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var chrome: ImeChromeView
            lateinit var key: KeyPreviewFrameLayout
            val background = Color.rgb(32, 64, 96)
            val drawCount = AtomicInteger()
            val down = SystemClock.uptimeMillis()
            fun chromeTouch(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                try { assertTrue(chrome.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                chrome = ImeChromeView(activity).apply {
                    setKeyboardHeight(180)
                    showPointerLocation = true
                    setPointerColors(Color.GREEN, Color.BLUE, Color.RED)
                    // Wide test strokes avoid subpixel antialias ambiguity while retaining the actual shared paints.
                    pointerPaint.strokeWidth = 3f
                    axisPaint.strokeWidth = 3f
                    pointPaint.strokeWidth = 3f
                }
                host.addView(chrome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                activity.setContentView(host)
                val config = PopupConfiguration(
                    vertices = listOf(PopupPoint(-80f, -400f), PopupPoint(80f, -400f), PopupPoint(80f, -20f), PopupPoint(-80f, -20f)),
                    anchor = PopupPoint(0f, 0f))
                KeyboardRenderer.render(activity, chrome.keyboardHost,
                    KeyboardLayout("Pointer fixture", listOf(RowLayout(1f, listOf(KeySlot(1f, "tap",
                        multifunctionMode = MultifunctionMode.POPUP, popupConfiguration = config))))),
                    180, Color.WHITE, popupBgColor = background, popupSymbolColor = Color.WHITE,
                    showKeyPreview = false, onKeyClick = { _, _, _ -> fail("Pointer fixture must not tap") })
                key = (chrome.keyboardHost.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val keyLocation = IntArray(2).also(key::getLocationOnScreen)
                val chromeLocation = IntArray(2).also(chrome::getLocationOnScreen)
                chromeTouch(MotionEvent.ACTION_DOWN, keyLocation[0] - chromeLocation[0] + key.width / 2f,
                    keyLocation[1] - chromeLocation[1] + key.height / 2f)
            }
            SystemClock.sleep(KeyboardGestureSettings.longPressTimeoutMs(instrumentation.targetContext).toLong() + 100)
            instrumentation.waitForIdleSync()
            lateinit var content: View
            var pointerX = 0f
            var pointerY = 0f
            scenario.onActivity {
                content = requireNotNull(key.visiblePopupContent)
                content.viewTreeObserver.addOnDrawListener { drawCount.incrementAndGet() }
                val density = chrome.resources.displayMetrics.density
                pointerX = chrome.width / 2f
                pointerY = -30f * density
                chromeTouch(MotionEvent.ACTION_MOVE, pointerX, pointerY)
                val pointer = requireNotNull(chrome.currentPointers[0])
                assertEquals(pointerX, pointer.x, 0f)
                assertEquals(pointerY, pointer.y, 0f)
                assertPopupPointerPixels(content, chrome, background, Color.GREEN, Color.BLUE, Color.RED)
                chrome.setPointerColors(Color.MAGENTA, Color.CYAN, Color.YELLOW)
                assertPopupPointerPixels(content, chrome, background, Color.MAGENTA, Color.CYAN, Color.YELLOW)
                chrome.setPointerColors(Color.BLACK, Color.BLACK, Color.RED, differenceBlend = true)
                val inverse = Color.rgb(255 - Color.red(background), 255 - Color.green(background), 255 - Color.blue(background))
                assertPopupPointerPixels(content, chrome, background, inverse, inverse, Color.RED)
            }
            instrumentation.waitForIdleSync()
            val countBeforeDisable = drawCount.get()
            scenario.onActivity { chrome.showPointerLocation = false }
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (drawCount.get() <= countBeforeDisable && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue("Disabling Pointer Location refreshes the separate popup window without another MOVE", drawCount.get() > countBeforeDisable)
            scenario.onActivity {
                assertTrue(chrome.currentPointers.isEmpty())
                val bitmap = renderOwnedView(content)
                try {
                    val chromeLocation = IntArray(2).also(chrome::getLocationOnScreen)
                    val popupLocation = IntArray(2).also(content::getLocationOnScreen)
                    val x = (chromeLocation[0] - popupLocation[0] + pointerX).toInt()
                    val y = (chromeLocation[1] - popupLocation[1] + pointerY).toInt()
                    assertEquals(background, bitmap.getPixel(x, y))
                } finally { bitmap.recycle() }
                chromeTouch(MotionEvent.ACTION_UP, pointerX, pointerY)
                assertNull(key.visiblePopupContent)
            }
        }
    }

    private fun assertPopupPointerPixels(content: View, chrome: ImeChromeView, background: Int,
        pathColor: Int, axisColor: Int, crossColor: Int) {
        val bitmap = renderOwnedView(content)
        try {
            val source = IntArray(2).also(chrome::getLocationOnScreen)
            val popup = IntArray(2).also(content::getLocationOnScreen)
            val pointer = requireNotNull(chrome.currentPointers[0])
            val x = (source[0] - popup[0] + pointer.x).toInt()
            val y = (source[1] - popup[1] + pointer.y).toInt()
            val density = chrome.resources.displayMetrics.density
            fun near(expected: Int, px: Int, py: Int) {
                assertTrue("Sample ($px,$py) must be inside the test-owned Popup", px in 2 until bitmap.width - 2 && py in 2 until bitmap.height - 2)
                assertTrue("Expected overlay color $expected near ($px,$py)",
                    (px - 1..px + 1).any { sx -> (py - 1..py + 1).any { sy -> bitmap.getPixel(sx, sy) == expected } })
            }
            near(crossColor, x + (5 * density).toInt(), y)
            near(axisColor, x + (25 * density).toInt(), y)
            // The source-relative y is still negative here, above the source's vertical axis.
            near(pathColor, x, y + (15 * density).toInt())
            assertEquals(background, bitmap.getPixel(x + (25 * density).toInt(), y + (15 * density).toInt()))
        } finally { bitmap.recycle() }
    }

    @Test fun popupItemsHaveNoCircularOutlineButStillInvertWhenActive() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val background = Color.rgb(32, 64, 96)
            val config = PopupConfiguration(
                vertices = listOf(PopupPoint(0f, 0f), PopupPoint(100f, 0f), PopupPoint(100f, 100f), PopupPoint(0f, 100f)),
                anchor = PopupPoint(0f, 0f),
                items = listOf(PopupItem(PopupPoint(50f, 50f), 20f, LongPressItem(text = "x"))))
            val content = PopupActionView(context, config, 0.0, 0.0, background, Color.WHITE, false, false)
            val size = (100 * density).toInt()
            content.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, size, size)
            val inactive = renderOwnedView(content)
            try {
                val edge = (30 * density).toInt()
                val middleY = (50 * density).toInt()
                for (x in edge - 2..edge + 2) assertEquals("Inactive item boundary has no stroke", background, inactive.getPixel(x, middleY))
            } finally { inactive.recycle() }
            content.activeIndex = 0
            val active = renderOwnedView(content)
            try { assertEquals(Color.WHITE, active.getPixel((35 * density).toInt(), (50 * density).toInt())) }
            finally { active.recycle() }
        }
    }

    private fun renderOwnedView(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        // Only the test-owned popup is rendered; no screenshot or unrelated UI data is read.
        view.draw(Canvas(it))
    }

    private fun touch(key: KeyPreviewFrameLayout, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { key.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
