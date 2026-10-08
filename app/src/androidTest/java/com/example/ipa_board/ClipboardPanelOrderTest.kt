package com.example.ipa_board

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.clipboard.ClipboardItem
import com.example.ipa_board.clipboard.ClipboardPanelView
import com.example.ipa_board.clipboard.ClipboardRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClipboardPanelOrderTest {
    @Test fun capacityPruningCancelsUndoForTheSwipedOldestItemWithoutDeletingSurvivors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "clipboard-panel-capacity-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        var scenario: ActivityScenario<SettingsActivity>? = null
        try {
            val repository = ClipboardRepository(prefsOverride = preferences)
            val originalItems = (1..20).map { index ->
                ClipboardItem("capacity-$index", "Synthetic capacity item $index ɑ", index.toLong())
            }
            originalItems.forEach(repository::restoreItem)
            assertEquals(originalItems.reversed(), repository.getItems())

            val activeScenario = ActivityScenario.launch(SettingsActivity::class.java)
            scenario = activeScenario
            lateinit var panel: ClipboardPanelView
            lateinit var recycler: RecyclerView
            lateinit var adapter: ClipboardPanelView.ClipboardAdapter
            activeScenario.onActivity { activity ->
                panel = ClipboardPanelView(activity, repository) { fail("A left swipe must not paste") }
                activity.setContentView(panel)
                recycler = descendants(panel).filterIsInstance<RecyclerView>().single()
                adapter = recycler.adapter as ClipboardPanelView.ClipboardAdapter
                panel.toggleReversed()
                assertEquals(originalItems, adapter.displayItems)
            }
            awaitRendered(activeScenario, recycler, originalItems)

            val newest = ClipboardItem("capacity-21", "Synthetic incoming item 21 😀", 21L)
            val survivors = originalItems.drop(1) + newest
            swipeAndAwaitRemoval(activeScenario, recycler, adapter, position = 0) {
                val undo = descendants(panel).filterIsInstance<TextView>().single { it.text.toString() == "Undo" }
                assertTrue("The real swipe starts an undoable deletion", undo.isShown)
                assertEquals(originalItems.first(), repository.getItemById(originalItems.first().id))

                repository.restoreItem(newest)
                panel.refreshList()
                assertFalse("Capacity pruning must cancel the now-unavailable Undo", undo.isShown)
                assertNull(repository.getItemById(originalItems.first().id))
                assertEquals("Capacity remains 20 and only the oldest original item is pruned", survivors.reversed(), repository.getItems())
                assertTrue("Capacity refresh preserves the current reverse direction", panel.isReversed)
                assertEquals(survivors, adapter.displayItems)

                panel.undoPendingDelete()
                panel.commitPendingDelete()
                panel.refreshList()
                assertEquals("Expired Undo and commit cannot delete or resurrect another item", survivors.reversed(), repository.getItems())
                assertEquals(survivors, adapter.displayItems)
                assertFalse(undo.isShown)
            }
            awaitRendered(activeScenario, recycler, survivors)
        } finally {
            try {
                scenario?.close()
            } finally {
                preferences.edit().clear().commit()
                context.deleteSharedPreferences(preferenceName)
            }
        }
    }

    @Test fun swipedItemStaysHiddenAcrossOrderChangesAndUndoRestoresCurrentOrder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferenceName = "clipboard-panel-order-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        var scenario: ActivityScenario<SettingsActivity>? = null
        try {
            val repository = ClipboardRepository(prefsOverride = preferences)
            // Deliberately insert out of order, with a pinned timestamp older than two unpinned ones.
            val oldest = ClipboardItem("oldest", "合成旧条目 ɑ😀", 71L)
            val newest = ClipboardItem("newest", "Synthetic newest β", 419L)
            val pinned = ClipboardItem("pinned", "合成固定条目 ɨ", 143L, isPinned = true)
            val middle = ClipboardItem("middle", "Synthetic middle e\u0301", 263L)
            listOf(middle, oldest, pinned, newest).forEach(repository::restoreItem)
            val normalOrder = listOf(pinned, newest, middle, oldest)
            assertEquals(normalOrder, repository.getItems())

            val activeScenario = ActivityScenario.launch(SettingsActivity::class.java)
            scenario = activeScenario
            lateinit var panel: ClipboardPanelView
            lateinit var recycler: RecyclerView
            lateinit var adapter: ClipboardPanelView.ClipboardAdapter
            activeScenario.onActivity { activity ->
                panel = ClipboardPanelView(activity, repository) { fail("A left swipe must not paste") }
                activity.setContentView(panel)
                recycler = descendants(panel).filterIsInstance<RecyclerView>().single()
                adapter = recycler.adapter as ClipboardPanelView.ClipboardAdapter
                assertEquals(2, (recycler.layoutManager as GridLayoutManager).spanCount)
                panel.toggleReversed()
                assertTrue(panel.isReversed)
                assertEquals(normalOrder.reversed(), adapter.displayItems)
            }
            awaitRendered(activeScenario, recycler, normalOrder.reversed())

            swipeAndAwaitRemoval(activeScenario, recycler, adapter, position = 0) {
                assertEquals(normalOrder.reversed().filter { it.id != oldest.id }, adapter.displayItems)
                assertEquals("Deletion remains undoable in the repository", oldest, repository.getItemById(oldest.id))
                panel.toggleReversed()
                panel.refreshList()
                assertFalse(panel.isReversed)
                assertEquals(normalOrder.filter { it.id != oldest.id }, adapter.displayItems)
                assertEquals(normalOrder, repository.getItems())

                val undo = descendants(panel).filterIsInstance<TextView>().single { it.text.toString() == "Undo" }
                assertTrue("The actual Undo control must be visible", undo.isShown)
                assertTrue(undo.performClick())
                assertEquals("Undo follows the currently selected normal order", normalOrder, adapter.displayItems)
                assertEquals("Undo preserves original text, timestamps and pin state", normalOrder, repository.getItems())
                assertFalse(undo.isShown)
            }
            awaitRendered(activeScenario, recycler, normalOrder)

            // In normal order the first card is pinned; swipe the first unpinned card instead.
            swipeAndAwaitRemoval(activeScenario, recycler, adapter, position = 1) {
                assertEquals(newest, repository.getItemById(newest.id))
                panel.toggleReversed()
                panel.refreshList()
                val survivingOrder = normalOrder.filter { it.id != newest.id }
                assertTrue(panel.isReversed)
                assertEquals(survivingOrder.reversed(), adapter.displayItems)
                panel.commitPendingDelete()
                assertNull(repository.getItemById(newest.id))
                assertEquals("Only the swiped ID is committed", survivingOrder, repository.getItems())
                assertTrue("Committing keeps the selected reverse order", panel.isReversed)
                assertEquals(survivingOrder.reversed(), adapter.displayItems)
                panel.refreshList()
                panel.undoPendingDelete()
                assertEquals("A committed deletion cannot reappear on refresh or Undo", survivingOrder.reversed(), adapter.displayItems)
            }
            awaitRendered(activeScenario, recycler, normalOrder.filter { it.id != newest.id }.reversed())
        } finally {
            try {
                // Detachment commits only this fixture's pending deletion, if an assertion failed.
                scenario?.close()
            } finally {
                preferences.edit().clear().commit()
                context.deleteSharedPreferences(preferenceName)
            }
        }
    }

    private fun swipeAndAwaitRemoval(
        scenario: ActivityScenario<SettingsActivity>,
        recycler: RecyclerView,
        adapter: ClipboardPanelView.ClipboardAdapter,
        position: Int,
        afterRemoval: () -> Unit
    ) {
        val removed = CountDownLatch(1)
        lateinit var observer: RecyclerView.AdapterDataObserver
        scenario.onActivity {
            val remainingCount = adapter.itemCount - 1
            observer = object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    if (adapter.itemCount == remainingCount) removed.countDown()
                }
            }
            adapter.registerAdapterDataObserver(observer)
            val card = requireNotNull(recycler.findViewHolderForAdapterPosition(position)).itemView
            assertTrue(card.width > 0 && card.height > 0)
            val startX = card.left + card.width * 0.85f
            val y = card.top + card.height * 0.5f
            // Cross half one card, which is the real ItemTouchHelper swipe threshold in this grid.
            val distance = card.width * 0.72f
            val downTime = SystemClock.uptimeMillis()
            fun dispatch(action: Int, progress: Float, elapsed: Long) {
                val event = MotionEvent.obtain(downTime, downTime + elapsed, action, startX - distance * progress, y, 0)
                try {
                    recycler.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            dispatch(MotionEvent.ACTION_DOWN, 0f, 0L)
            for (step in 1..8) dispatch(MotionEvent.ACTION_MOVE, step / 8f, step * 16L)
            dispatch(MotionEvent.ACTION_UP, 1f, 144L)
        }
        try {
            assertTrue("The real left swipe must remove one displayed card", removed.await(2, TimeUnit.SECONDS))
            // Run immediately after the notification, before the two-second Undo deadline.
            scenario.onActivity { afterRemoval() }
        } finally {
            scenario.onActivity { adapter.unregisterAdapterDataObserver(observer) }
        }
    }

    private fun awaitRendered(
        scenario: ActivityScenario<SettingsActivity>,
        recycler: RecyclerView,
        expected: List<ClipboardItem>
    ) {
        val rendered = CountDownLatch(1)
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        scenario.onActivity {
            listener = ViewTreeObserver.OnPreDrawListener {
                // Only the first row is needed for the swipe; larger histories need not fit onscreen.
                val allVisible = expected.take(2).withIndex().all { (position, item) ->
                    val card = recycler.findViewHolderForAdapterPosition(position)?.itemView
                    card != null && card.width > 0 && card.height > 0 &&
                        descendants(card).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == item.text }
                }
                if (recycler.width > 0 && allVisible) rendered.countDown()
                true
            }
            recycler.viewTreeObserver.addOnPreDrawListener(listener)
            recycler.requestLayout()
            recycler.invalidate()
        }
        try {
            assertTrue("Test-owned grid must render the requested order", rendered.await(2, TimeUnit.SECONDS))
        } finally {
            scenario.onActivity { recycler.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
