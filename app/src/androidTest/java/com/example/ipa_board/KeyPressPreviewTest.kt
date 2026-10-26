package com.example.ipa_board

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class KeyPressPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var preferences: SharedPreferences
    private var originalTimeout: Any? = null

    @Before fun isolateLongPressPreference() {
        preferences = instrumentation.targetContext.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        originalTimeout = preferences.all[SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS]
        KeyboardGestureSettings.setLongPressTimeoutMs(instrumentation.targetContext, ViewConfiguration.getLongPressTimeout())
    }

    @After fun restoreLongPressPreference() {
        val editor = preferences.edit()
        val preferenceKey = SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS
        when (val value = originalTimeout) {
            null -> editor.remove(preferenceKey)
            is Int -> editor.putInt(preferenceKey, value)
            is String -> editor.putString(preferenceKey, value)
            is Boolean -> editor.putBoolean(preferenceKey, value)
            is Long -> editor.putLong(preferenceKey, value)
            is Float -> editor.putFloat(preferenceKey, value)
            is Set<*> -> {
                assertTrue("Unexpected non-string SharedPreferences set", value.all { it is String })
                editor.putStringSet(preferenceKey, value.filterIsInstance<String>().toSet())
            }
            else -> fail("Unexpected SharedPreferences type ${value.javaClass.name}")
        }
        assertTrue("Failed to restore the long-press test preference", editor.commit())
    }

    @Test fun updatedTimeoutAppliesToTheNextTouchWithoutRecreatingTheKey() {
        val systemTimeout = ViewConfiguration.getLongPressTimeout()
        // Both comparisons require an interval within the supported 200–2000 ms range.
        assumeTrue("System timeout leaves no interval for both gesture comparisons", systemTimeout in 400..1749)
        KeyboardGestureSettings.setLongPressTimeoutMs(instrumentation.targetContext, 2000)
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var taps = 0
            val heldItems = mutableListOf<String>()
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("CustomTimeout", listOf(RowLayout(1f, listOf(KeySlot(1f, "a", longPressText = "ɑ😀"))))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyClick = { _, _, _ -> taps++ },
                    onKeyLongItemClick = { _, _, _, item -> heldItems += item.text })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            var downTime = 0L
            scenario.onActivity {
                downTime = SystemClock.uptimeMillis()
                touch(key, MotionEvent.ACTION_DOWN)
            }
            SystemClock.sleep(systemTimeout.toLong() + 150)
            scenario.onActivity {
                val elapsed = SystemClock.uptimeMillis() - downTime
                assertTrue("Touch must exceed the previous system threshold: $elapsed ms", elapsed > systemTimeout)
                assertTrue("Fixture missed the custom short-press interval: $elapsed ms", elapsed < 2000)
                assertEquals("a", key.visiblePreview)
                assertEquals(0, taps)
                assertTrue(heldItems.isEmpty())
                touch(key, MotionEvent.ACTION_UP)
                assertEquals(1, taps)
                assertTrue(heldItems.isEmpty())
                assertNull(key.visiblePreview)

                // Reuse the attached key: the next down event must read the new setting.
                KeyboardGestureSettings.setLongPressTimeoutMs(it, 200)
                downTime = SystemClock.uptimeMillis()
                touch(key, MotionEvent.ACTION_DOWN)
            }
            SystemClock.sleep(300)
            scenario.onActivity {
                val elapsed = SystemClock.uptimeMillis() - downTime
                assertTrue("Hold must precede the previous system threshold: $elapsed ms", elapsed < systemTimeout)
                assertEquals("ɑ😀", key.visiblePreview)
                assertEquals(1, taps)
                assertTrue("Selecting a hold must not commit before release", heldItems.isEmpty())
                touch(key, MotionEvent.ACTION_UP)
                assertEquals(listOf("ɑ😀"), heldItems)
                assertEquals(1, taps)
                assertNull(key.visiblePreview)
            }
        }
    }

    @Test fun cancellingBeforeTheCustomTimeoutRemovesTheScheduledHold() {
        KeyboardGestureSettings.setLongPressTimeoutMs(instrumentation.targetContext, 200)
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var taps = 0
            var holds = 0
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("CustomCancel", listOf(RowLayout(1f, listOf(KeySlot(1f, "a", longPressText = "ɑ"))))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyClick = { _, _, _ -> taps++ }, onKeyLongClick = { _, _, _ -> holds++ })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                touch(key, MotionEvent.ACTION_DOWN)
                touch(key, MotionEvent.ACTION_CANCEL)
                assertNull(key.visiblePreview)
            }
            SystemClock.sleep(350)
            scenario.onActivity {
                assertNull(key.visiblePreview)
                touch(key, MotionEvent.ACTION_UP)
                assertEquals(0, taps)
                assertEquals(0, holds)
                touch(key, MotionEvent.ACTION_DOWN)
            }
            SystemClock.sleep(350)
            scenario.onActivity {
                assertEquals("ɑ", key.visiblePreview)
                assertEquals(0, taps)
                assertEquals(0, holds)
                touch(key, MotionEvent.ACTION_UP)
                assertEquals(0, taps)
                assertEquals(1, holds)
                assertNull(key.visiblePreview)
            }
        }
    }

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
                    180, Color.WHITE, shiftState = ShiftState.SINGLE, showKeyPreview = true,
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
            waitPastConfiguredTimeout()
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
            waitPastConfiguredTimeout()
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

    @Test fun longPressQueueUsesDefaultAndClampsWithoutCycling() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var selectedItem: LongPressItem? = null
            var taps = 0
            val items = listOf(LongPressItem("ɑ"), LongPressItem(action = KeyAction.COPY), LongPressItem("ɒ"))
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("Preview queue", listOf(RowLayout(1f, listOf(
                        KeySlot(1f, "a", longPressItems = items, longPressDefaultIndex = 1))))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyLongItemClick = { _, _, _, item -> selectedItem = item },
                    onKeyClick = { _, _, _ -> taps++ })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { touch(key, MotionEvent.ACTION_DOWN) }
            waitPastConfiguredTimeout()
            scenario.onActivity {
                assertTrue(key.visiblePopupContent is PopupActionView)
                val config = requireNotNull(key.visiblePopupConfiguration)
                assertEquals(items, config.items.map { it.binding })
                assertEquals(config.items[1].center.x, config.anchor.x, .001f)
                assertEquals(KeyAction.COPY.keyLabel, key.visiblePreview)
                val step = (config.items[1].center.x - config.items[0].center.x) * key.resources.displayMetrics.density
                val x = key.width / 2f
                val y = key.height / 2f
                touch(key, MotionEvent.ACTION_MOVE, x + step * 10f, y - 100f)
                assertEquals("ɒ", key.visiblePreview)
                touch(key, MotionEvent.ACTION_MOVE, x + step * 3f, y)
                assertEquals("ɒ", key.visiblePreview)
                touch(key, MotionEvent.ACTION_MOVE, x, y)
                assertEquals(KeyAction.COPY.keyLabel, key.visiblePreview)
                touch(key, MotionEvent.ACTION_MOVE, x - step * 10f, y)
                assertEquals("ɑ", key.visiblePreview)
                touch(key, MotionEvent.ACTION_UP, x - step, y)
                assertNull(key.visiblePreview)
                assertEquals(items[0], selectedItem)
                assertEquals(0, taps)
            }
        }
    }

    @Test fun cancellationIsRelativeToActivationTouchEvenBelowKeyBottom() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var commits = 0
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                key = KeyPreviewFrameLayout(activity).apply {
                    previewEnabled = true; tapPreview = "a"; longPressItems = listOf(LongPressItem("b"))
                    swipeRightItem = LongPressItem("right")
                    setOnClickListener { commits++ }; setOnLongClickListener { true }
                    onLongPressItemClick = { commits++ }
                }
                // A deliberately short key lets the two former cancellation conditions disagree.
                host.addView(key, LinearLayout.LayoutParams(200, (20 * activity.resources.displayMetrics.density).toInt()))
            }
            instrumentation.waitForIdleSync()
            val density = key.resources.displayMetrics.density
            scenario.onActivity {
                touch(key, MotionEvent.ACTION_DOWN, 100f, key.height - 1f)
                // Before activation horizontal dominance permits tracking outside the lower edge.
                touch(key, MotionEvent.ACTION_MOVE, 100f + 100 * density, key.height + 20 * density)
            }
            waitPastConfiguredTimeout()
            scenario.onActivity {
                assertEquals("b", key.visiblePreview)
                // Below the former key-bottom + 32dp boundary, but only 20dp from activation.
                touch(key, MotionEvent.ACTION_MOVE, 100f + 100 * density, key.height + 40 * density)
                assertEquals("b", key.visiblePreview)
                touch(key, MotionEvent.ACTION_UP, 100f + 100 * density, key.height + 40 * density)
                assertEquals(1, commits)
                touch(key, MotionEvent.ACTION_DOWN, 100f, 5f)
            }
            waitPastConfiguredTimeout()
            scenario.onActivity {
                touch(key, MotionEvent.ACTION_MOVE, 100f, 5f + 33 * density)
                assertNull(key.visiblePreview)
                touch(key, MotionEvent.ACTION_MOVE, 100f, 5f)
                touch(key, MotionEvent.ACTION_UP, 100f, 5f)
                assertEquals("Cancellation must latch through return", 1, commits)
            }
        }
    }

    @Test fun movingAcrossQueueRetainsTheInitiallyActivatedRepeatLifecycle() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            val effects = mutableListOf<LongPressItem>()
            val repeat = LongPressItem(action = KeyAction.REPEAT_LEFT)
            val ordinary = LongPressItem("text")
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                key = KeyPreviewFrameLayout(activity).apply {
                    previewEnabled = true; tapPreview = "tap"; longPressItems = listOf(repeat, ordinary)
                    setOnClickListener {}; setOnLongClickListener { true }
                    onLongPressItemClick = { effects += it }
                    repeatForItem = { item -> if (item.action.repeatsWhileHeld) ({ effects += item; Unit }) else null }
                }
                host.addView(key, LinearLayout.LayoutParams(400, 180))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { touch(key, MotionEvent.ACTION_DOWN) }
            waitPastConfiguredTimeout()
            scenario.onActivity {
                assertTrue(key.repeatingHold)
                assertTrue(effects.isNotEmpty())
                val count = effects.size
                val config = requireNotNull(key.visiblePopupConfiguration)
                val step = config.items[0].radius * 2 * key.resources.displayMetrics.density
                touch(key, MotionEvent.ACTION_MOVE, key.width / 2f + step, key.height / 2f)
                assertEquals("text", key.visiblePreview)
                assertTrue("Changing selection must not replace an active repeat", key.repeatingHold)
                touch(key, MotionEvent.ACTION_UP, key.width / 2f + step, key.height / 2f)
                assertEquals(count, effects.size)
                assertTrue(effects.all { it == repeat })
                assertFalse(key.repeatingHold)
                effects.clear()
                key.longPressDefaultIndex = 1
                touch(key, MotionEvent.ACTION_DOWN)
            }
            waitPastConfiguredTimeout()
            scenario.onActivity {
                assertFalse(key.repeatingHold)
                val step = requireNotNull(key.visiblePopupConfiguration).items[0].radius * 2 * key.resources.displayMetrics.density
                touch(key, MotionEvent.ACTION_MOVE, key.width / 2f - step, key.height / 2f)
                assertFalse("Moving onto repeat must not start a new hold lifecycle", key.repeatingHold)
                assertTrue(effects.isEmpty())
                touch(key, MotionEvent.ACTION_UP, key.width / 2f - step, key.height / 2f)
                assertEquals(listOf(repeat), effects)
            }
        }
    }

    @Test fun longPressMicroJitterStaysOnInitialAction() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var selectedItem: LongPressItem? = null
            var taps = 0
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                val slot = KeySlot(1f, "a", longPressText = "ɑ, æ, ɒ")
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("PreviewJitter", listOf(RowLayout(1f, listOf(slot)))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyLongItemClick = { _, _, _, item -> selectedItem = item },
                    onKeyClick = { _, _, _ -> taps++ })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val startX = key.width / 2f
                val startY = key.height / 2f

                touch(key, MotionEvent.ACTION_DOWN, startX, startY)
            }
            waitPastConfiguredTimeout()
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
                assertEquals(0, taps)
            }
        }
    }

    @Test fun longPressSwipeDownCancelsOperation() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var committed = false
            var taps = 0
            scenario.onActivity { activity ->
                val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM }
                activity.setContentView(host)
                val slot = KeySlot(1f, "a", longPressText = "ɑ, æ, ɒ")
                KeyboardRenderer.render(activity, host,
                    KeyboardLayout("PreviewCancel", listOf(RowLayout(1f, listOf(slot)))),
                    180, Color.WHITE, showKeyPreview = true,
                    onKeyLongItemClick = { _, _, _, _ -> committed = true },
                    onKeyClick = { _, _, _ -> taps++ })
                key = (host.getChildAt(0) as ViewGroup).getChildAt(0) as KeyPreviewFrameLayout
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val startX = key.width / 2f
                val startY = key.height / 2f

                touch(key, MotionEvent.ACTION_DOWN, startX, startY)
            }
            waitPastConfiguredTimeout()
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
                assertEquals(0, taps)
            }
        }
    }

    @Test fun quickSwipeLeftAndRightTriggerBeforeLongPress() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var swipedItem: LongPressItem? = null
            var taps = 0
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
                    onKeyQuickSwipeItemClick = { _, _, _, item -> swipedItem = item },
                    onKeyClick = { _, _, _ -> taps++ })
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
                assertEquals(0, taps)
            }
        }
    }

    @Test fun quickSwipeHeldDownEntersLongPress() {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            lateinit var key: KeyPreviewFrameLayout
            var longItem: LongPressItem? = null
            var quickItem: LongPressItem? = null
            var taps = 0
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
                    onKeyLongItemClick = { _, _, _, item -> longItem = item },
                    onKeyClick = { _, _, _ -> taps++ })
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
            // Wait past the active timeout, then verify the long-press mapping.
            waitPastConfiguredTimeout()
            scenario.onActivity {
                val density = key.resources.displayMetrics.density
                val startX = key.width / 2f
                val startY = key.height / 2f

                // Long press should now be active, previewing long press item 'ɑ'
                assertEquals("ɑ", key.visiblePreview)

                touch(key, MotionEvent.ACTION_UP, startX - 30 * density, startY)
                assertNull(quickItem)
                assertEquals("ɑ", longItem?.text)
                assertEquals(0, taps)
            }
        }
    }

    private fun waitPastConfiguredTimeout() {
        SystemClock.sleep(KeyboardGestureSettings.longPressTimeoutMs(instrumentation.targetContext).toLong() + 150)
    }

    private fun touch(key: KeyPreviewFrameLayout, action: Int, x: Float = key.width / 2f, y: Float = key.height / 2f) {
        // Match the real IME's clickable, attached and measured gesture surface.
        if (action == MotionEvent.ACTION_DOWN) {
            assertTrue("Preview fixture key is not attached", key.isAttachedToWindow)
            assertNotNull("Preview fixture key has no window token", key.windowToken)
            assertTrue("Preview fixture key has not been laid out", key.width > 0 && key.height > 0)
            assertTrue("Preview fixture needs the ordinary tap listener used by the IME", key.isClickable)
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try { key.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
