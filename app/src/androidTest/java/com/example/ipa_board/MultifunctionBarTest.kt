package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.ImeChromeView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MultifunctionBarTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test fun oldAndDamagedSettingsFallBackPerPositionWithoutWritingOnRead() {
        isolatedSettings { context ->
            val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            assertEquals(MultifunctionBarSettings.defaults(), MultifunctionBarSettings.load(context))
            assertTrue(prefs.all.isEmpty())
            val root = JSONObject().put("left_first", JSONObject().put("action", "engine_dashboard"))
                .put("left_second", JSONObject().put("action", "future_action"))
                .put("right_first", JSONObject().put("action", "text").put("text", "ɑ😀\nβ"))
            prefs.edit().putString(MultifunctionBarSettings.KEY_BINDINGS, root.toString()).commit()
            val before = prefs.all
            val restored = MultifunctionBarSettings.load(context)
            assertEquals(KeyAction.ENGINE_DASHBOARD, restored.getValue(MultifunctionBarSlot.LEFT_FIRST).action)
            assertEquals(KeyAction.PAGES, restored.getValue(MultifunctionBarSlot.LEFT_SECOND).action)
            assertEquals(MultifunctionBarBinding(KeyAction.TEXT, "ɑ😀\nβ"), restored.getValue(MultifunctionBarSlot.RIGHT_FIRST))
            assertEquals(before, prefs.all)
            prefs.edit().putString(MultifunctionBarSettings.KEY_BINDINGS, "broken json").commit()
            assertEquals(MultifunctionBarSettings.defaults(), MultifunctionBarSettings.load(context))
            prefs.edit().putInt(MultifunctionBarSettings.KEY_BINDINGS, 7).commit()
            assertEquals(MultifunctionBarSettings.defaults(), MultifunctionBarSettings.load(context))
        }
    }

    @Test fun savesOnlyTheChosenPositionAndPreservesUnicodeAndFutureSettings() {
        isolatedSettings { context ->
            val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val future = JSONObject().put("future_position", JSONObject().put("action", "future_action"))
                .put("left_first", JSONObject().put("action", "clipboard").put("future_field", 42))
                .put("left_third", JSONObject().put("action", "future_action").put("future_field", true))
            prefs.edit().putString(MultifunctionBarSettings.KEY_BINDINGS, future.toString()).putString("unrelated", "keep").commit()
            val binding = MultifunctionBarBinding(KeyAction.TEXT, " ɑ😀\nβ\t ", TextBehavior.AUTO)
            MultifunctionBarSettings.save(context, MultifunctionBarSlot.RIGHT_THIRD, binding)
            MultifunctionBarSettings.save(context, MultifunctionBarSlot.LEFT_FIRST, MultifunctionBarBinding(KeyAction.REPEAT_BACKSPACE))
            assertTrue(prefs.edit().commit()) // Wait for preceding apply() writes before reopening.
            val reopened = object : ContextWrapper(target) {
                override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(name, mode)
            }
            assertEquals(binding, MultifunctionBarSettings.load(reopened).getValue(MultifunctionBarSlot.RIGHT_THIRD))
            assertEquals(KeyAction.REPEAT_BACKSPACE, MultifunctionBarSettings.load(reopened).getValue(MultifunctionBarSlot.LEFT_FIRST).action)
            assertEquals("keep", prefs.getString("unrelated", null))
            val stored = JSONObject(prefs.getString(MultifunctionBarSettings.KEY_BINDINGS, "{}")!!)
            assertEquals("future_action", stored.getJSONObject("future_position").getString("action"))
            assertTrue(stored.getJSONObject("left_third").getBoolean("future_field"))
            assertEquals(42, stored.getJSONObject("left_first").getInt("future_field"))
        }
    }

    @Test fun previewAndRuntimeShareGeometryAndRebindingKeepsPanelStatusAndCandidateSnapshot() {
        instrumentation.runOnMainSync {
            val chrome = ImeChromeView(target)
            val preview = MultifunctionBarView(target).apply { editing = true }
            val longText = "ɑ😀".repeat(8)
            val assignments = MultifunctionBarSettings.defaults() + mapOf(
                MultifunctionBarSlot.LEFT_FIRST to MultifunctionBarBinding(KeyAction.ENGINE_DASHBOARD),
                MultifunctionBarSlot.LEFT_SECOND to MultifunctionBarBinding(KeyAction.TEXT, longText),
                MultifunctionBarSlot.RIGHT_FIRST to MultifunctionBarBinding(KeyAction.WIDTH_SWAP))
            chrome.setBarBindings(assignments)
            preview.setBindings(assignments)
            val runtime = chrome.multifunctionBar
            for (width in listOf(360, 600)) {
                val px = (width * target.resources.displayMetrics.density).toInt()
                for (view in listOf(preview, runtime)) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                }
                assertEquals(runtime.measuredHeight, preview.measuredHeight)
                for (slot in MultifunctionBarSlot.entries) {
                    val actual = runtime.buttonAt(slot)
                    val sample = preview.buttonAt(slot)
                    assertEquals(actual.left, sample.left)
                    assertEquals(actual.width, sample.width)
                    assertEquals(actual.height, sample.height)
                    assertEquals(actual.textSize, sample.textSize, 0f)
                    assertEquals(actual.text.toString(), sample.text.toString())
                }
                assertEquals(runtime.statusContainer.left, preview.statusContainer.left)
                assertEquals(runtime.statusContainer.width, preview.statusContainer.width)
                val textButton = runtime.buttonAt(MultifunctionBarSlot.LEFT_SECOND)
                assertTrue("Long labels must fit in the fixed entry width", textButton.layout.getEllipsisCount(0) > 0)
                assertEquals(longText, runtime.bindingAt(MultifunctionBarSlot.LEFT_SECOND).asKeySlot().text)
            }
            chrome.showPanel(ImeChromeView.Panel.CLIPBOARD)
            val custom = View(target)
            chrome.setStatusCustomView(custom)
            val candidate = com.example.ipa_board.ime.Candidate("好", "test")
            chrome.render("hao", listOf(candidate), "Test status", 123)
            val dispatched = mutableListOf<MultifunctionBarBinding>()
            chrome.onKeyBinding = { dispatched += it }
            // Every existing action uses the same dispatch callback, even duplicates in other slots.
            KeyAction.entries.forEach { action ->
                val binding = MultifunctionBarBinding(action, if (action == KeyAction.TEXT) "😀" else "")
                chrome.setBarBindings(assignments + (MultifunctionBarSlot.LEFT_FIRST to binding))
                runtime.buttonAt(MultifunctionBarSlot.LEFT_FIRST).performClick()
                assertEquals(binding, dispatched.last())
                assertEquals(ImeChromeView.Panel.CLIPBOARD, chrome.panel)
                assertSame(chrome.statusContainer, custom.parent)
            }
            var selected = false
            chrome.onCandidate = { result, revision ->
                assertEquals(candidate, result); assertEquals(123L, revision); selected = true
            }
            val strip = chrome.getChildAt(1) as ViewGroup
            val row = ((strip.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup)
            row.getChildAt(0).performClick()
            assertTrue(selected)
            chrome.setBarBindings(assignments)
            chrome.setWidthSwapEnabled(true)
            assertTrue(runtime.buttonAt(MultifunctionBarSlot.RIGHT_FIRST).isSelected)
            assertTrue(runtime.buttonAt(MultifunctionBarSlot.RIGHT_SECOND).isSelected)
            chrome.setBarBindings(assignments + mapOf(
                MultifunctionBarSlot.LEFT_FIRST to MultifunctionBarBinding(KeyAction.SHIFT),
                MultifunctionBarSlot.LEFT_SECOND to MultifunctionBarBinding(KeyAction.CTRL)))
            chrome.setModifiers(ShiftState.SINGLE, true)
            assertEquals("Shift •", runtime.buttonAt(MultifunctionBarSlot.LEFT_FIRST).text.toString())
            assertEquals("Ctrl •", runtime.buttonAt(MultifunctionBarSlot.LEFT_SECOND).text.toString())
            chrome.setModifiers(ShiftState.CAPS_LOCK, false)
            assertEquals("Caps Lock •", runtime.buttonAt(MultifunctionBarSlot.LEFT_FIRST).text.toString())
            assertTrue(runtime.buttonAt(MultifunctionBarSlot.LEFT_FIRST).typeface.isBold)
            chrome.setModifiers(ShiftState.OFF, false)
            assertEquals("Shift", runtime.buttonAt(MultifunctionBarSlot.LEFT_FIRST).text.toString())
            assertFalse(runtime.buttonAt(MultifunctionBarSlot.LEFT_FIRST).typeface?.isBold == true)
        }
    }

    @Test fun activitySelectsEditsCancelsAndPersistsBindingsWithoutExecutingThem() {
        withAppBindingState {
            val prefs = target.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val before = prefs.all
            ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bar = preview(activity)
                    assertTrue(bar.editing)
                    assertFalse(bar.status.isClickable)
                    assertEquals(before, prefs.all)
                    bar.buttonAt(MultifunctionBarSlot.RIGHT_FIRST).performClick()
                }
                // Opening a Remote Keyboard binding must only edit it, never toggle engine state.
                assertEquals(before, prefs.all)
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                scenario.onActivity { preview(it).buttonAt(MultifunctionBarSlot.LEFT_FIRST).performClick() }
                onView(withId(R.id.sp_bar_key_type)).inRoot(isDialog()).check { view, _ ->
                    val spinner = view as Spinner
                    assertEquals(KeyAction.entries.map { it.title }, (0 until spinner.count).map { spinner.getItemAtPosition(it) })
                    spinner.setSelection(KeyAction.entries.indexOf(KeyAction.TEXT))
                }
                instrumentation.waitForIdleSync()
                onView(withId(R.id.et_bar_key_text)).inRoot(isDialog()).perform(replaceText("ɑ中😀"), closeSoftKeyboard())
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                scenario.onActivity {
                    assertEquals("ɑ中😀", preview(it).buttonAt(MultifunctionBarSlot.LEFT_FIRST).text.toString())
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals("ɑ中😀", preview(activity).buttonAt(MultifunctionBarSlot.LEFT_FIRST).text.toString())
                    preview(activity).buttonAt(MultifunctionBarSlot.LEFT_FIRST).performClick()
                }
                onView(withId(R.id.et_bar_key_text)).inRoot(isDialog()).perform(replaceText("Discard"), closeSoftKeyboard())
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                assertEquals("ɑ中😀", MultifunctionBarSettings.load(target).getValue(MultifunctionBarSlot.LEFT_FIRST).text)
            }
            ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
                scenario.onActivity {
                    assertEquals("ɑ中😀", preview(it).buttonAt(MultifunctionBarSlot.LEFT_FIRST).text.toString())
                }
            }
        }
    }

    @Test fun continuousDeleteRepeatsUntilReleaseCancelRebindOrDetachWithNoExtraReleaseClick() {
        withAppBindingState {
            ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
                lateinit var bar: MultifunctionBarView
                lateinit var button: Button
                val clicks = AtomicInteger()
                var repeated = CountDownLatch(2)
                scenario.onActivity {
                    bar = preview(it)
                    bar.editing = false
                    bar.setBindings(MultifunctionBarSettings.defaults() +
                        (MultifunctionBarSlot.LEFT_FIRST to MultifunctionBarBinding(KeyAction.REPEAT_BACKSPACE)))
                    button = bar.buttonAt(MultifunctionBarSlot.LEFT_FIRST)
                    bar.onSlotClick = { clicks.incrementAndGet(); repeated.countDown() }
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { assertTrue(button.isLaidOut && button.width > 0) }
                fun send(action: Int, x: Float = 1f, y: Float = 1f, expectNoClick: Boolean = false) {
                    instrumentation.runOnMainSync {
                        val before = clicks.get()
                        val time = SystemClock.uptimeMillis()
                        val event = MotionEvent.obtain(time, time, action, x, y, 0)
                        try { button.dispatchTouchEvent(event) } finally { event.recycle() }
                        if (expectNoClick) assertEquals("Ending a hold must not add a tap", before, clicks.get())
                    }
                    // Platform Button posts an ordinary tap's click to the UI queue.
                    instrumentation.waitForIdleSync()
                }
                fun assertStopped() {
                    val atStop = clicks.get()
                    val checked = CountDownLatch(1)
                    Handler(Looper.getMainLooper()).postDelayed({ checked.countDown() }, 180)
                    assertTrue(checked.await(2, TimeUnit.SECONDS))
                    assertEquals(atStop, clicks.get())
                }
                val waitMs = KeyboardGestureSettings.longPressTimeoutMs(target).toLong() + 2000
                send(MotionEvent.ACTION_DOWN)
                send(MotionEvent.ACTION_UP)
                assertEquals(1, clicks.get())
                for (stop in listOf("release", "cancel", "leave", "rebind", "detach")) {
                    repeated = CountDownLatch(2)
                    send(MotionEvent.ACTION_DOWN)
                    assertTrue("Repeat must begin after the configured hold time: $stop", repeated.await(waitMs, TimeUnit.MILLISECONDS))
                    when (stop) {
                        "release" -> send(MotionEvent.ACTION_UP, expectNoClick = true)
                        "cancel" -> send(MotionEvent.ACTION_CANCEL)
                        "leave" -> { send(MotionEvent.ACTION_MOVE, -10f); send(MotionEvent.ACTION_UP, expectNoClick = true) }
                        "rebind" -> {
                            instrumentation.runOnMainSync { bar.setBindings(MultifunctionBarSettings.defaults()) }
                            send(MotionEvent.ACTION_UP, expectNoClick = true)
                        }
                        "detach" -> instrumentation.runOnMainSync { (bar.parent as ViewGroup).removeView(bar) }
                    }
                    assertStopped()
                    if (stop == "rebind") instrumentation.runOnMainSync {
                        bar.setBindings(MultifunctionBarSettings.defaults() +
                            (MultifunctionBarSlot.LEFT_FIRST to MultifunctionBarBinding(KeyAction.REPEAT_BACKSPACE)))
                    }
                }
            }
        }
    }

    private fun preview(activity: ShortcutManagerActivity) =
        activity.findViewById<ViewGroup>(R.id.multifunction_bar_preview).getChildAt(0) as MultifunctionBarView

    private fun isolatedSettings(body: (Context) -> Unit) {
        val name = "bar-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(ignored: String, mode: Int) = target.getSharedPreferences(name, mode)
        }
        try { body(context) } finally { target.deleteSharedPreferences(name) }
    }

    private fun withAppBindingState(body: () -> Unit) {
        val prefs = target.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val before = prefs.all[MultifunctionBarSettings.KEY_BINDINGS]
        try {
            assertTrue(prefs.edit().remove(MultifunctionBarSettings.KEY_BINDINGS).commit())
            body()
        } finally {
            assertTrue(prefs.edit().apply {
                if (before == null) remove(MultifunctionBarSettings.KEY_BINDINGS)
                else putString(MultifunctionBarSettings.KEY_BINDINGS, before as String)
            }.commit())
        }
    }
}
