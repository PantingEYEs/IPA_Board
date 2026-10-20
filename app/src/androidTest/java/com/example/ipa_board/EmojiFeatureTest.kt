package com.example.ipa_board

import android.content.Context
import android.view.ViewTreeObserver
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.AbsListView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.emoji.*
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EmojiFeatureTest {
    @Test fun rightEdgeTapAndVerticalSwipeStayDistinctAcrossKeyboardSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val catalog = BundledEmojiCatalogSource(context).load()
        val directory = java.io.File(context.cacheDir, "emoji-touch-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
        val usage = EmojiUsageRepository(storageDirOverride = directory)
        val selected = mutableListOf<EmojiEntry>()
        var clicked = CountDownLatch(1)
        lateinit var picker: EmojiPickerView
        var scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    picker = EmojiPickerView(activity, catalog, usage) { selected += it; clicked.countDown() }
                    picker.grid.setOnScrollListener(object : AbsListView.OnScrollListener {
                        override fun onScrollStateChanged(view: AbsListView, state: Int) { scrollState = state }
                        override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {}
                    })
                    activity.setContentView(FrameLayout(activity).apply {
                        addView(picker, FrameLayout.LayoutParams(-1, dp(224)))
                    })
                }
                for ((widthDp, heightDp) in listOf(280 to 168, 360 to 280)) {
                    awaitGridLayout(scenario, picker, "Keyboard size $widthDp x $heightDp", change = {
                        picker.layoutParams = picker.layoutParams.apply { width = dp(widthDp); height = dp(heightDp) }
                        picker.filterCategory(1)
                    }, ready = { picker.grid.firstVisiblePosition == 0 && picker.grid.canScrollVertically(1) })

                    fun tapRightEdge() {
                        lateinit var expected: EmojiEntry
                        var count = 0
                        var x = 0f
                        var y = 0f
                        var down = 0L
                        clicked = CountDownLatch(1)
                        scenario.onActivity {
                            val grid = picker.grid
                            val cells = (0 until grid.childCount).map { grid.getChildAt(it) }
                                .filter { it.top >= grid.paddingTop && it.bottom <= grid.height - grid.paddingBottom }
                            val cell = cells.maxBy { it.right }
                            val position = grid.firstVisiblePosition + grid.indexOfChild(cell)
                            expected = grid.adapter.getItem(position) as EmojiEntry
                            count = selected.size
                            x = (cell.right - dp(2)).toFloat()
                            y = (cell.top + cell.bottom) / 2f
                            down = SystemClock.uptimeMillis()
                            touch(grid, down, MotionEvent.ACTION_DOWN, x, y)
                            assertEquals("A press must not insert before release", count, selected.size)
                        }
                        SystemClock.sleep(60)
                        scenario.onActivity { touch(picker.grid, down, MotionEvent.ACTION_UP, x, y) }
                        assertTrue("Rightmost emoji must be selectable at width $widthDp", clicked.await(5, TimeUnit.SECONDS))
                        instrumentation.waitForIdleSync()
                        scenario.onActivity {
                            assertEquals("Exactly one callback per tap", count + 1, selected.size)
                            assertEquals(expected, selected.last())
                        }
                    }

                    tapRightEdge()
                    var beforePosition = 0
                    var beforeTop = 0
                    var beforeClicks = 0
                    var x = 0f
                    var start = 0f
                    var end = 0f
                    var down = 0L
                    scenario.onActivity {
                        val grid = picker.grid
                        beforePosition = grid.firstVisiblePosition
                        beforeTop = grid.getChildAt(0).top
                        beforeClicks = selected.size
                        x = (grid.width - dp(2)).toFloat()
                        start = (grid.height - grid.paddingBottom - dp(28)).toFloat()
                        end = (grid.paddingTop + dp(28)).toFloat()
                        down = SystemClock.uptimeMillis()
                        touch(grid, down, MotionEvent.ACTION_DOWN, x, start)
                    }
                    for (step in 1..12) {
                        SystemClock.sleep(25)
                        scenario.onActivity { touch(picker.grid, down, MotionEvent.ACTION_MOVE, x, start + (end - start) * step / 12f) }
                    }
                    // Keep a real stationary interval before release; do not invent future event times.
                    SystemClock.sleep(300)
                    scenario.onActivity { touch(picker.grid, down, MotionEvent.ACTION_UP, x, end) }
                    awaitGridLayout(scenario, picker, "Swipe from rightmost column", change = {}, ready = {
                        scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE &&
                            (picker.grid.firstVisiblePosition != beforePosition || picker.grid.getChildAt(0).top != beforeTop)
                    })
                    scenario.onActivity { assertEquals("Vertical dragging must not insert emoji", beforeClicks, selected.size) }
                    tapRightEdge() // The cell is recycled after the scroll; its current entry must be inserted.
                    scenario.onActivity {
                        val grid = picker.grid
                        val down = SystemClock.uptimeMillis()
                        touch(grid, down, MotionEvent.ACTION_DOWN, grid.width - dp(2).toFloat(), dp(40).toFloat())
                        touch(grid, down, MotionEvent.ACTION_CANCEL, grid.width - dp(2).toFloat(), dp(40).toFloat())
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { assertEquals("Cancelled touches must not insert emoji", beforeClicks + 1, selected.size) }
                }
                scenario.onActivity {
                    assertEquals(selected.size, usage.getUsageRecords().sumOf { it.count })
                    assertEquals(1, picker.currentCategoryIndex)
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private fun touch(view: View, down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun emojiActionCanBeAssignedAndRoundTripsWithoutLosingLongPress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val groupSnapshot = PageGroupTestState(context)
        val previous = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        val layout = KeyboardLayout("Emoji test", listOf(RowLayout(1f, listOf(KeySlot(1f, "a", longPressText = "ɑ")))))
        val filename = LayoutFileManager.createLayout(context, "Emoji test", layout)
        try {
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(filename))
            PageGroupManager.selectPage(context, filename)
            ActivityScenario.launch(KeyboardPageActivity::class.java).use {
                onView(withContentDescription(startsWith("Row 1, key 1:"))).perform(click())
                onView(withId(R.id.sp_key_type)).perform(click())
                onData(equalTo("Emoji")).inRoot(isPlatformPopup()).perform(click())
                onView(withText("Save")).perform(click())
                val saved = requireNotNull(LayoutFileManager.loadLayout(context, filename))
                assertEquals(KeyAction.EMOJI, saved.rows[0].slots[0].action)
                assertEquals("ɑ", saved.rows[0].slots[0].longPressText)
                assertEquals(saved, KeyboardLayout.fromJson(saved.toJson()))
            }
        } finally {
            prefs.edit().apply { if (previous == null) remove(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE) else putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, previous) }.commit()
            LayoutFileManager.deleteLayout(context, filename)
            groupSnapshot.close()
        }
    }

    @Test fun allEntriesAreReachableInRecycledGridAndCategoryFilteringWorks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val catalog = BundledEmojiCatalogSource(instrumentation.targetContext).load()
        lateinit var picker: EmojiPickerView
        var selected: EmojiEntry? = null
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                picker = EmojiPickerView(activity, catalog) { selected = it }
                activity.setContentView(picker)
            }
            awaitGridLayout(scenario, picker, "All emoji category", change = {
                assertEquals(0, picker.currentCategoryIndex)
                picker.filterCategory(1) // Category 1 is "All emoji"
            }, ready = { picker.grid.adapter.count == catalog.entries.size && picker.grid.firstVisiblePosition == 0 })
            scenario.onActivity {
                assertEquals(catalog.entries.size, picker.grid.adapter.count)
                assertTrue(gridState(picker), picker.grid.childCount in 1 until catalog.entries.size)
            }
            awaitGridLayout(scenario, picker, "Last bundled emoji", change = {
                picker.grid.setSelection(catalog.entries.lastIndex)
            }, ready = { picker.grid.lastVisiblePosition == catalog.entries.lastIndex })
            scenario.onActivity {
                assertEquals(gridState(picker), catalog.entries.lastIndex, picker.grid.lastVisiblePosition)
                val position = picker.grid.lastVisiblePosition
                val lastCell = requireNotNull(picker.grid.getChildAt(position - picker.grid.firstVisiblePosition)) {
                    "Last emoji has no attached cell: ${gridState(picker)}"
                }
                assertTrue(picker.grid.performItemClick(lastCell, position, position.toLong()))
                assertEquals(catalog.entries.last(), selected)
            }
            val componentGroup = catalog.groups.indexOf("Component")
            val components = catalog.entries.filter { it.isComponent }
            assertTrue("Bundled catalog must contain the Component group", componentGroup >= 0)
            assertTrue(components.isNotEmpty())
            awaitGridLayout(scenario, picker, "Component category", change = {
                picker.filterCategory(componentGroup + 2) // Most commonly (0), All (1), then groups
            }, ready = { picker.grid.adapter.count == components.size && picker.grid.firstVisiblePosition == 0 })
            scenario.onActivity {
                assertEquals(components, (0 until picker.grid.adapter.count).map {
                    picker.grid.adapter.getItem(it) as EmojiEntry
                })
            }
        }
    }

    /** Adapter notification and selection take effect during traversal, before pre-draw. */
    private fun awaitGridLayout(
        scenario: ActivityScenario<SettingsActivity>,
        picker: EmojiPickerView,
        phase: String,
        change: () -> Unit,
        ready: () -> Boolean
    ) {
        val laidOut = CountDownLatch(1)
        var observer: ViewTreeObserver? = null
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        try {
            scenario.onActivity {
                observer = picker.grid.viewTreeObserver
                listener = ViewTreeObserver.OnPreDrawListener {
                    if (picker.grid.isAttachedToWindow && picker.grid.width > 0 && picker.grid.height > 0 &&
                        picker.grid.childCount > 0 && ready()) {
                        laidOut.countDown()
                    }
                    true
                }
                observer!!.addOnPreDrawListener(listener!!)
                change()
                picker.grid.requestLayout()
            }
            val completed = laidOut.await(5, TimeUnit.SECONDS)
            scenario.onActivity {
                assertTrue("$phase did not finish a matching layout: ${gridState(picker)}", completed)
            }
        } finally {
            scenario.onActivity {
                listener?.let { callback ->
                    // An unattached observer is merged into the window's observer on attachment.
                    val current = observer?.takeIf { it.isAlive } ?: picker.grid.viewTreeObserver
                    if (current.isAlive) current.removeOnPreDrawListener(callback)
                }
            }
        }
    }

    private fun gridState(picker: EmojiPickerView): String = with(picker.grid) {
        "category=${picker.currentCategoryIndex}, adapter=${adapter.count}, attached=$isAttachedToWindow, " +
            "size=${width}x${height}, children=$childCount, visible=$firstVisiblePosition..$lastVisiblePosition"
    }
}
