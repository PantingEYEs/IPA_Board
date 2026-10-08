package com.example.ipa_board

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.clipboard.ClipboardItem
import com.example.ipa_board.clipboard.ClipboardPanelView
import com.example.ipa_board.clipboard.ClipboardRepository
import com.example.ipa_board.clipboard.ClipboardStatusView
import com.example.ipa_board.ime.Candidate
import com.example.ipa_board.ime.ImeChromeView
import com.example.ipa_board.kaomoji.KaomojiManagerActivity
import com.example.ipa_board.kaomoji.KaomojiPanelView
import com.example.ipa_board.kaomoji.KaomojiRepository
import com.example.ipa_board.kaomoji.KaomojiStatusView
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

class PresentationContractTest {
    @Test fun longPressSliderPersistsUserChangesAcrossRecreationAndPageGroups() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val originalTimeout = preferences.all[SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS]
        val groupSnapshot = PageGroupTestState(context)
        try {
            assertTrue(preferences.edit().remove(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS).commit())
            val firstGroup = PageGroupManager.active(context).id
            val firstAppearance = PageGroupManager.active(context).appearance
            val secondGroup = PageGroupManager.create(context, "Timing test ${UUID.randomUUID()}")
            PageGroupManager.select(context, firstGroup)
            ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val slider = activity.findViewById<SeekBar>(R.id.sb_long_press_time)
                    val defaultTimeout = ViewConfiguration.getLongPressTimeout().coerceIn(200, 2000)
                    assertEquals(activity.getString(R.string.keyboard_long_press_time_value, defaultTimeout),
                        activity.findViewById<TextView>(R.id.tv_long_press_time_value).text.toString())
                    assertFalse("Opening management must preserve the unset system-default preference",
                        preferences.contains(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS))
                    slider.progress = 0
                    assertFalse("A programmatic refresh must not persist a user choice",
                        preferences.contains(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS))
                    assertTrue("The actual SeekBar keyboard action must accept a user adjustment",
                        slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
                    assertEquals(250, KeyboardGestureSettings.longPressTimeoutMs(context))
                    assertEquals(250, preferences.getInt(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS, -1))
                    assertEquals("250 ms", activity.findViewById<TextView>(R.id.tv_long_press_time_value).text.toString())
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(1, activity.findViewById<SeekBar>(R.id.sb_long_press_time).progress)
                    assertEquals("250 ms", activity.findViewById<TextView>(R.id.tv_long_press_time_value).text.toString())
                    activity.findViewById<Spinner>(R.id.sp_page_groups).setSelection(
                        PageGroupManager.state(context).groups.indexOfFirst { it.id == secondGroup })
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(secondGroup, PageGroupManager.active(context).id)
                scenario.onActivity { activity ->
                    val slider = activity.findViewById<SeekBar>(R.id.sb_long_press_time)
                    assertEquals("Changing page groups must retain the global gesture setting", 1, slider.progress)
                    assertEquals("250 ms", activity.findViewById<TextView>(R.id.tv_long_press_time_value).text.toString())
                    assertTrue(slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
                    assertEquals(300, KeyboardGestureSettings.longPressTimeoutMs(context))
                    activity.findViewById<Spinner>(R.id.sp_page_groups).setSelection(
                        PageGroupManager.state(context).groups.indexOfFirst { it.id == firstGroup })
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(firstGroup, PageGroupManager.active(context).id)
                assertEquals("Timing changes must leave the page group's appearance intact", firstAppearance,
                    PageGroupManager.active(context).appearance)
                scenario.onActivity { activity ->
                    assertEquals(2, activity.findViewById<SeekBar>(R.id.sb_long_press_time).progress)
                    assertEquals("300 ms", activity.findViewById<TextView>(R.id.tv_long_press_time_value).text.toString())
                }
            }
        } finally {
            groupSnapshot.close()
            restoreLongPressPreference(preferences, originalTimeout)
        }
    }

    @Test fun longPressPreferencesPreserveSystemDefaultAndSafelyHandleInvalidSavedData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val originalTimeout = preferences.all[SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS]
        try {
            assertTrue(preferences.edit().remove(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS).commit())
            val systemDefault = ViewConfiguration.getLongPressTimeout().coerceIn(200, 2000)
            assertEquals(systemDefault, KeyboardGestureSettings.longPressTimeoutMs(context))
            assertFalse("Reading a default must not replace the unset preference",
                preferences.contains(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS))
            assertTrue(preferences.edit().putString(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS, "invalid legacy value").commit())
            assertEquals("A malformed saved preference must fall back to the system duration", systemDefault,
                KeyboardGestureSettings.longPressTimeoutMs(context))
            assertEquals("A read must leave malformed data untouched", "invalid legacy value",
                preferences.getString(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS, null))
            assertTrue(preferences.edit().putInt(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS, -10).commit())
            assertEquals(200, KeyboardGestureSettings.longPressTimeoutMs(context))
            assertTrue(preferences.edit().putInt(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS, 10_000).commit())
            assertEquals(2000, KeyboardGestureSettings.longPressTimeoutMs(context))
            KeyboardGestureSettings.setLongPressTimeoutMs(context, 650)
            assertEquals("Saving a valid custom duration must replace invalid data", 650,
                preferences.getInt(SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS, -1))
            assertEquals(650, KeyboardGestureSettings.longPressTimeoutMs(context))
        } finally {
            restoreLongPressPreference(preferences, originalTimeout)
        }
    }

    private fun restoreLongPressPreference(preferences: SharedPreferences, original: Any?) {
        val key = SettingsConstants.KEY_LONG_PRESS_TIMEOUT_MS
        val editor = preferences.edit()
        when (original) {
            is Int -> editor.putInt(key, original)
            is Long -> editor.putLong(key, original)
            is Float -> editor.putFloat(key, original)
            is Boolean -> editor.putBoolean(key, original)
            is String -> editor.putString(key, original)
            is Set<*> -> editor.putStringSet(key, original.map { it as String }.toSet())
            else -> editor.remove(key)
        }
        assertTrue("The gesture test must restore the exact original preference", editor.commit())
    }

    @Test fun clipboardCardsKeepUniformGeometryAndPasteUnabridgedText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "clipboard_presentation_${UUID.randomUUID()}"
        val repository = ClipboardRepository(prefsOverride = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
        val longText = (1..12).joinToString("\n") { "第 $it 行：漢字、かな、한글 😀 e\u0301 与完整粘贴内容。" }
        val sensitiveText = "Synthetic sensitive sample 🔒\n第二行完整内容"
        repository.addClip("短")
        val longItem = requireNotNull(repository.addClip(longText))
        val pinnedItem = requireNotNull(repository.addClip("Pinned Unicode 👩🏽‍💻"))
        repository.togglePin(pinnedItem.id)
        val sensitiveItem = requireNotNull(repository.addClip(sensitiveText, isSensitive = true))
        val items = repository.getItems()
        val settled = CountDownLatch(1)
        lateinit var panel: ClipboardPanelView
        lateinit var recycler: RecyclerView
        lateinit var editor: EditText
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        var pastedItem: ClipboardItem? = null
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    editor = EditText(activity).apply { showSoftInputOnFocus = false }
                    val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
                    panel = ClipboardPanelView(activity, repository) { item ->
                        pastedItem = item
                        assertTrue("The full clipboard item must be committed", connection.commitText(item.text, 1))
                    }
                    recycler = (0 until panel.childCount).map { panel.getChildAt(it) }.filterIsInstance<RecyclerView>().single()
                    // An even available width gives both grid spans exactly equal pixel space.
                    val width = activity.resources.displayMetrics.widthPixels.coerceAtMost(1080) / 2 * 2
                    val editorHeight = (48 * activity.resources.displayMetrics.density).toInt()
                    activity.setContentView(FrameLayout(activity).apply {
                        isFocusableInTouchMode = true
                        addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            bottomMargin = editorHeight
                        })
                        addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, editorHeight).apply {
                            gravity = Gravity.BOTTOM
                        })
                        requestFocus()
                    })
                    listener = ViewTreeObserver.OnPreDrawListener {
                        val cards = items.indices.mapNotNull { recycler.findViewHolderForAdapterPosition(it) }
                        if (panel.isAttachedToWindow && !recycler.isLayoutRequested && cards.size == items.size &&
                            cards.all { it.itemView.width > 0 && it.itemView.height > 0 && !it.itemView.isLayoutRequested }) {
                            settled.countDown()
                        }
                        true
                    }
                    recycler.viewTreeObserver.addOnPreDrawListener(listener)
                }
                val completed = settled.await(5, TimeUnit.SECONDS)
                scenario.onActivity {
                    recycler.viewTreeObserver.removeOnPreDrawListener(listener)
                    assertTrue("Clipboard grid did not finish laying out all four test cards", completed)
                    val holders = items.indices.map { index ->
                        requireNotNull(recycler.findViewHolderForAdapterPosition(index)) as ClipboardPanelView.ClipboardAdapter.ViewHolder
                    }
                    val cards = holders.map { it.itemView }
                    val containers = holders.map { it.container }
                    assertEquals("All grid cards must have the same measured dimensions", 1, cards.map { it.width to it.height }.distinct().size)
                    assertEquals("Card backgrounds must also have the same measured dimensions", 1,
                        containers.map { it.width to it.height }.distinct().size)
                    assertEquals("First row cards should align", cards[0].top, cards[1].top)
                    assertEquals("Second row cards should align", cards[2].top, cards[3].top)
                    assertTrue("Four cards must occupy two rows", cards[2].top >= cards[0].bottom)
                    assertTrue("Two cards must occupy separate columns", cards[1].left >= cards[0].right)
                    val gap = cards[1].left - cards[0].right
                    assertTrue("The column gap should be smaller than the content inset: gap=$gap, inset=${containers[0].paddingLeft}",
                        gap > 0 && gap < containers[0].paddingLeft)

                    val longHolder = holders[items.indexOfFirst { it.id == longItem.id }]
                    val textLayout = requireNotNull(longHolder.contentTextView.layout)
                    assertTrue("Long multiline text must visibly end in an ellipsis",
                        (0 until textLayout.lineCount).any { textLayout.getEllipsisCount(it) > 0 })
                    assertTrue("Tapping the preview should paste the original item", longHolder.container.performClick())
                    assertEquals(longItem, pastedItem)
                    assertEquals("Ellipsizing must never shorten the pasted Unicode text", longText, editor.text.toString())

                    val sensitiveHolder = holders[items.indexOfFirst { it.id == sensitiveItem.id }]
                    val sensitivePreview = sensitiveHolder.contentTextView.text.toString()
                    assertFalse("Sensitive data must remain hidden in the preview", sensitivePreview.contains("Synthetic sensitive sample"))
                    assertFalse("Other sensitive lines must also remain hidden", sensitivePreview.contains("第二行完整内容"))
                    assertTrue("Pinned item must retain its visible marker", holders[items.indexOfFirst { it.id == pinnedItem.id }].pinTextView.visibility == View.VISIBLE)
                    editor.text.clear()
                    assertTrue(sensitiveHolder.container.performClick())
                    assertEquals(sensitiveItem, pastedItem)
                    assertEquals("Sensitive previews must also retain complete paste contents", sensitiveText, editor.text.toString())
                    assertEquals("Preview rendering and clicking must preserve stored content", longText,
                        ClipboardRepository(prefsOverride = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)).getItemById(longItem.id)?.text)
                }
            }
        } finally {
            context.deleteSharedPreferences(preferenceName)
        }
    }

    @Test fun clipboardSortReversesTheWholeDisplayWithoutChangingStoredItems() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "clipboard_order_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val repository = ClipboardRepository(prefsOverride = preferences)
        val (pinnedOld, pinnedNew, longItem, sensitiveItem) = clipboardOrderItems()
        listOf(pinnedOld, pinnedNew, longItem, sensitiveItem).forEach(repository::restoreItem)
        val normalIds = listOf(pinnedNew.id, pinnedOld.id, longItem.id, sensitiveItem.id)
        val storedItems = repository.getItems()
        val savedPreferences = preferences.all.toMap()
        val kaomojiDirectory = File(context.cacheDir, "clipboard_sort_reference_${UUID.randomUUID()}")
        try {
            assertTrue(kaomojiDirectory.mkdirs())
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val panel = ClipboardPanelView(activity, repository) {}
                    val toolbar = ClipboardStatusView(activity, panel)
                    val recycler = clipboardRecycler(panel)
                    val adapter = recycler.adapter as ClipboardPanelView.ClipboardAdapter
                    val sort = descendants(toolbar).filterIsInstance<TextView>()
                        .single { it.contentDescription == "Clipboard reverse order" }
                    val referencePanel = KaomojiPanelView(activity,
                        KaomojiRepository(storageDirOverride = kaomojiDirectory)) {}
                    val reference = KaomojiStatusView(activity, referencePanel) {}
                    val referenceSort = descendants(reference).filterIsInstance<TextView>()
                        .single { it.text.toString() == "⇅" }
                    activity.setContentView(FrameLayout(activity).apply { addView(panel) })

                    fun checkStyle(reversed: Boolean) {
                        assertEquals(if (reversed) "⇅ Rev" else "⇅", sort.text.toString())
                        assertEquals(if (reversed) Color.WHITE else Color.LTGRAY, sort.currentTextColor)
                        assertEquals(reversed, sort.isSelected)
                        assertEquals(if (reversed) "Reverse order" else "Normal order", sort.stateDescription.toString())
                        assertEquals("Clipboard sort should use the kaomoji control's small text size",
                            referenceSort.textSize, sort.textSize, 0f)
                        assertEquals(referenceSort.gravity, sort.gravity)
                        assertEquals(listOf(referenceSort.paddingLeft, referenceSort.paddingTop,
                            referenceSort.paddingRight, referenceSort.paddingBottom),
                            listOf(sort.paddingLeft, sort.paddingTop, sort.paddingRight, sort.paddingBottom))
                        val referenceMargins = referenceSort.layoutParams as ViewGroup.MarginLayoutParams
                        val margins = sort.layoutParams as ViewGroup.MarginLayoutParams
                        assertEquals(listOf(referenceMargins.leftMargin, referenceMargins.topMargin,
                            referenceMargins.rightMargin, referenceMargins.bottomMargin),
                            listOf(margins.leftMargin, margins.topMargin, margins.rightMargin, margins.bottomMargin))
                        assertNull("Sort should have no colored button background", sort.background)
                    }

                    assertEquals("Clipboard", toolbar.tvTitle.text.toString())
                    assertSame(toolbar.tvTitle, toolbar.getChildAt(0))
                    assertSame(sort, toolbar.getChildAt(toolbar.childCount - 1))
                    assertFalse(panel.isReversed)
                    assertEquals(normalIds, adapter.displayItems.map { it.id })
                    checkStyle(false)
                    assertTrue(sort.performClick())
                    referencePanel.toggleReversed()
                    assertTrue(panel.isReversed)
                    assertEquals("Pinned entries must move with the entire displayed sequence",
                        normalIds.reversed(), adapter.displayItems.map { it.id })
                    assertEquals(referenceSort.text.toString(), sort.text.toString())
                    assertEquals(referenceSort.currentTextColor, sort.currentTextColor)
                    checkStyle(true)
                    assertTrue(sort.performClick())
                    assertFalse(panel.isReversed)
                    assertEquals("Two sort clicks must restore the exact original sequence",
                        normalIds, adapter.displayItems.map { it.id })
                    checkStyle(false)

                    panel.toggleReversed()
                    checkStyle(true)
                    val newPanel = ClipboardPanelView(activity, repository) {}
                    assertFalse("A newly opened panel starts in normal order", newPanel.isReversed)
                    assertEquals(normalIds,
                        (clipboardRecycler(newPanel).adapter as ClipboardPanelView.ClipboardAdapter).displayItems.map { it.id })
                    assertEquals("Display sorting must not mutate pin, timestamp, text or repository order",
                        storedItems, repository.getItems())
                    assertEquals("Display sorting must not rewrite persisted history", savedPreferences, preferences.all)
                    assertEquals(storedItems, ClipboardRepository(prefsOverride = preferences).getItems())
                }
            }
        } finally {
            kaomojiDirectory.deleteRecursively()
            context.deleteSharedPreferences(preferenceName)
        }
    }

    @Test fun reversedClipboardRefreshKeepsTwoColumnsAndPastesCompleteUnicodeAndSensitiveText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "clipboard_reverse_refresh_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        val repository = ClipboardRepository(prefsOverride = preferences)
        val (pinnedOld, pinnedNew, longItem, sensitiveItem) = clipboardOrderItems()
        listOf(pinnedOld, pinnedNew, longItem, sensitiveItem).forEach(repository::restoreItem)
        val newItem = ClipboardItem(UUID.randomUUID().toString(), "New synthetic copy 🧪", 5_000L)
        val expectedIds = listOf(sensitiveItem.id, longItem.id, newItem.id, pinnedOld.id, pinnedNew.id)
        val settled = CountDownLatch(1)
        lateinit var panel: ClipboardPanelView
        lateinit var recycler: RecyclerView
        lateinit var editor: EditText
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        lateinit var savedPreferences: Map<String, *>
        var pastedItem: ClipboardItem? = null
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    editor = EditText(activity).apply { showSoftInputOnFocus = false }
                    val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
                    panel = ClipboardPanelView(activity, repository) { item ->
                        pastedItem = item
                        assertTrue(connection.commitText(item.text, 1))
                    }
                    recycler = clipboardRecycler(panel)
                    val adapter = recycler.adapter as ClipboardPanelView.ClipboardAdapter
                    val toolbar = ClipboardStatusView(activity, panel)
                    assertTrue(descendants(toolbar).single { it.contentDescription == "Clipboard reverse order" }.performClick())
                    panel.refreshList()
                    assertTrue("An ordinary refresh must retain reversed order", panel.isReversed)
                    assertEquals(listOf(sensitiveItem.id, longItem.id, pinnedOld.id, pinnedNew.id),
                        adapter.displayItems.map { it.id })
                    repository.restoreItem(newItem)
                    savedPreferences = preferences.all.toMap()
                    panel.refreshList()
                    assertTrue("A new copy must not reset the active panel's order", panel.isReversed)
                    assertEquals(expectedIds, adapter.displayItems.map { it.id })

                    val width = activity.resources.displayMetrics.widthPixels.coerceAtMost(1080) / 2 * 2
                    val editorHeight = (48 * activity.resources.displayMetrics.density).toInt()
                    activity.setContentView(FrameLayout(activity).apply {
                        isFocusableInTouchMode = true
                        addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            bottomMargin = editorHeight
                        })
                        addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, editorHeight).apply {
                            gravity = Gravity.BOTTOM
                        })
                        requestFocus()
                    })
                    listener = ViewTreeObserver.OnPreDrawListener {
                        val cards = expectedIds.indices.mapNotNull { recycler.findViewHolderForAdapterPosition(it) }
                        if (panel.isAttachedToWindow && !recycler.isLayoutRequested && cards.size == expectedIds.size &&
                            cards.all { it.itemView.width > 0 && it.itemView.height > 0 && !it.itemView.isLayoutRequested }) {
                            settled.countDown()
                        }
                        true
                    }
                    recycler.viewTreeObserver.addOnPreDrawListener(listener)
                }
                val completed = settled.await(5, TimeUnit.SECONDS)
                scenario.onActivity {
                    recycler.viewTreeObserver.removeOnPreDrawListener(listener)
                    assertTrue("Reversed clipboard cards did not finish laying out", completed)
                    assertEquals(2, (recycler.layoutManager as GridLayoutManager).spanCount)
                    val holders = expectedIds.indices.map { index ->
                        requireNotNull(recycler.findViewHolderForAdapterPosition(index)) as ClipboardPanelView.ClipboardAdapter.ViewHolder
                    }
                    assertEquals(1, holders.map { it.itemView.width to it.itemView.height }.distinct().size)
                    assertEquals(holders[0].itemView.top, holders[1].itemView.top)
                    assertTrue(holders[1].itemView.left >= holders[0].itemView.right)
                    assertEquals("Reversing must retain both pinned markers", 2,
                        holders.count { it.pinTextView.visibility == View.VISIBLE })

                    val longHolder = holders[expectedIds.indexOf(longItem.id)]
                    val longLayout = requireNotNull(longHolder.contentTextView.layout)
                    assertTrue("Long Unicode preview should remain visually abbreviated",
                        (0 until longLayout.lineCount).any { longLayout.getEllipsisCount(it) > 0 })
                    assertTrue(longHolder.container.performClick())
                    assertEquals(longItem, pastedItem)
                    assertEquals("Reverse order must still paste full multiline Unicode", longItem.text, editor.text.toString())
                    val sensitiveHolder = holders[expectedIds.indexOf(sensitiveItem.id)]
                    assertFalse(sensitiveHolder.contentTextView.text.toString().contains("Synthetic sensitive sample"))
                    editor.text.clear()
                    assertTrue(sensitiveHolder.container.performClick())
                    assertEquals(sensitiveItem, pastedItem)
                    assertEquals("Sensitive previews must still paste the complete original", sensitiveItem.text, editor.text.toString())
                    assertEquals(expectedIds, (recycler.adapter as ClipboardPanelView.ClipboardAdapter).displayItems.map { it.id })
                    assertEquals("Refresh and paste must not rewrite stored items", savedPreferences, preferences.all)
                    assertEquals(listOf(pinnedNew, pinnedOld, newItem, longItem, sensitiveItem),
                        ClipboardRepository(prefsOverride = preferences).getItems())
                }
            }
        } finally {
            context.deleteSharedPreferences(preferenceName)
        }
    }

    @Test fun clipboardSortToolbarPreservesQuickPasteAndReattachesStatusAcrossPanels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferenceName = "clipboard_status_parent_${UUID.randomUUID()}"
        val repository = ClipboardRepository(prefsOverride = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val chrome = ImeChromeView(activity)
                    val panel = ClipboardPanelView(activity, repository) {}
                    activity.setContentView(chrome)
                    chrome.showPanel(ImeChromeView.Panel.CLIPBOARD)
                    var quickPasteClicks = 0
                    chrome.status.text = "Synthetic quick paste 🧪"
                    chrome.onStatusClick = { quickPasteClicks++ }
                    val toolbar = ClipboardStatusView(activity, panel, chrome.status)
                    chrome.setStatusCustomView(toolbar)
                    val sort = descendants(toolbar).single { it.contentDescription == "Clipboard reverse order" }
                    assertSame("Quick-paste status should remain in the clipboard toolbar", toolbar, chrome.status.parent)
                    assertSame(toolbar, chrome.statusContainer.getChildAt(0))
                    assertEquals("Synthetic quick paste 🧪", chrome.status.text.toString())
                    assertTrue(chrome.status.performClick())
                    assertEquals(1, quickPasteClicks)
                    assertTrue(sort.performClick())
                    assertTrue(panel.isReversed)
                    assertEquals("Sorting must not trigger the adjacent quick-paste action", 1, quickPasteClicks)

                    chrome.showPanel(ImeChromeView.Panel.CLIPBOARD)
                    assertSame("Refreshing the same clipboard panel must retain its custom toolbar",
                        toolbar, chrome.statusContainer.getChildAt(0))
                    assertSame(toolbar, chrome.status.parent)
                    assertTrue(panel.isReversed)
                    chrome.showPanel(ImeChromeView.Panel.KEYBOARD)
                    assertNull("Leaving clipboard should detach its sort toolbar", toolbar.parent)
                    assertSame("The original status must be reattached for keyboard mode", chrome.statusContainer, chrome.status.parent)
                    assertEquals(-1, toolbar.indexOfChild(chrome.status))
                    assertNull(chrome.onStatusClick)

                    chrome.showPanel(ImeChromeView.Panel.CLIPBOARD)
                    val nextToolbar = ClipboardStatusView(activity, panel, chrome.status)
                    chrome.setStatusCustomView(nextToolbar)
                    chrome.showPanel(ImeChromeView.Panel.KAOMOJI)
                    assertNull("Kaomoji mode must detach the previous clipboard toolbar", nextToolbar.parent)
                    assertSame(chrome.statusContainer, chrome.status.parent)
                    assertTrue("Kaomoji mode must not retain a clipboard sort control",
                        descendants(chrome.statusContainer).none { it.contentDescription == "Clipboard reverse order" })
                }
            }
        } finally {
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private fun clipboardOrderItems(): List<ClipboardItem> = listOf(
        ClipboardItem(UUID.randomUUID().toString(), "Older pinned 👩🏽‍💻", 1_000L, isPinned = true),
        ClipboardItem(UUID.randomUUID().toString(), "Newer pinned 漢字", 4_000L, isPinned = true),
        ClipboardItem(UUID.randomUUID().toString(),
            (1..12).joinToString("\n") { "第 $it 行：漢字、かな、한글 😀 e\u0301 👩🏽‍💻 与完整粘贴内容。" }, 3_000L),
        ClipboardItem(UUID.randomUUID().toString(), "Synthetic sensitive sample 🔒\n第二行完整内容 e\u0301", 2_000L, isSensitive = true)
    )

    private fun clipboardRecycler(panel: ClipboardPanelView): RecyclerView =
        (0 until panel.childCount).map { panel.getChildAt(it) }.filterIsInstance<RecyclerView>().single()

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    @Test fun candidateTouchTargetsFollowKeyboardGeometryAcrossPageAndWidthChanges() {
        fun button(root: View, description: String): Button? {
            if (root is Button && root.contentDescription == description) return root
            if (root is ViewGroup) for (index in 0 until root.childCount) {
                button(root.getChildAt(index), description)?.let { return it }
            }
            return null
        }
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            lateinit var chrome: ImeChromeView
            scenario.onActivity { activity ->
                chrome = ImeChromeView(activity).apply {
                    setKeyboardHeight(400)
                    render("x", listOf(Candidate("字", "繁"), Candidate("a considerably longer candidate", "EN")), "", 1)
                }
                activity.setContentView(FrameLayout(activity).apply {
                    addView(chrome, FrameLayout.LayoutParams(1080, ViewGroup.LayoutParams.WRAP_CONTENT))
                })
            }
            for ((width, keys) in listOf(1080 to 2, 640 to 10, 1080 to 4)) {
                val settled = CountDownLatch(1)
                lateinit var listener: ViewTreeObserver.OnPreDrawListener
                scenario.onActivity { activity ->
                    listener = ViewTreeObserver.OnPreDrawListener {
                        val short = button(chrome, "字")
                        val long = button(chrome, "a considerably longer candidate")
                        // An attached ViewRoot performs the follow-up measure requested when
                        // onLayout updates candidate minima from the newly measured keys.
                        if (chrome.isAttachedToWindow && chrome.width == width && !chrome.isLayoutRequested &&
                            short != null && long != null && short.width > 0 && long.width > 0 &&
                            !short.isLayoutRequested && !long.isLayoutRequested) {
                            settled.countDown()
                        }
                        true
                    }
                    chrome.viewTreeObserver.addOnPreDrawListener(listener)
                    chrome.layoutParams = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val layout = KeyboardLayout("Test", listOf(RowLayout(1f, List(keys) { KeySlot(1f, "x") })))
                    KeyboardRenderer.render(activity, chrome.keyboardHost, layout, 400, android.graphics.Color.WHITE)
                }
                val completed = settled.await(5, TimeUnit.SECONDS)
                scenario.onActivity { activity ->
                    chrome.viewTreeObserver.removeOnPreDrawListener(listener)
                    val row = chrome.keyboardHost.getChildAt(0) as ViewGroup
                    val minimumKey = (0 until row.childCount).minOf { row.getChildAt(it).width }
                    val short = requireNotNull(button(chrome, "字"))
                    val long = requireNotNull(button(chrome, "a considerably longer candidate"))
                    val minimumTouchTarget = (48 * activity.resources.displayMetrics.density).toInt()
                    val diagnostic = "after $width/$keys: chrome=${chrome.width}, key=$minimumKey, " +
                        "short=${short.width}, long=${long.width}, minimum=${short.minimumWidth}, touch=$minimumTouchTarget"
                    assertTrue("Attached layout did not settle $diagnostic", completed)
                    assertTrue("Candidate too narrow $diagnostic", short.width >= minimumKey)
                    assertTrue("Candidate touch target too narrow $diagnostic", short.width >= minimumTouchTarget)
                    assertTrue("Long candidate narrower than short candidate $diagnostic", long.width >= short.width)
                }
            }
        }
    }

    @Test fun kaomojiActionsRemainGrayAfterRepeatedSortAndManageToggles() {
        ActivityScenario.launch(KaomojiManagerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val expected = activity.getColor(R.color.management_control)
                val ids = listOf(R.id.btn_sort_za, R.id.btn_add_kaomoji, R.id.btn_batch_manage,
                    R.id.btn_import_config, R.id.btn_export_config, R.id.btn_select_all,
                    R.id.btn_batch_add_tag, R.id.btn_batch_delete)
                fun checkColors() = ids.forEach { id ->
                    val button = activity.findViewById<Button>(id)
                    val tint = requireNotNull(button.backgroundTintList)
                    for (state in listOf(intArrayOf(), intArrayOf(android.R.attr.state_pressed),
                        intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_enabled))) {
                        assertEquals("Unexpected action color for $id", expected, tint.getColorForState(state, 0))
                    }
                }
                checkColors()
                repeat(3) { activity.findViewById<Button>(R.id.btn_sort_za).performClick(); checkColors() }
                repeat(2) { activity.findViewById<Button>(R.id.btn_batch_manage).performClick(); checkColors() }
            }
        }
    }
}
