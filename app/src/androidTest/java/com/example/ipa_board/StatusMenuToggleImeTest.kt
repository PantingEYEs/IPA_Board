package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.GridView
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.emoji.EmojiPickerView
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.ImeChromeView
import com.example.ipa_board.ime.RemoteKeyboardMode
import com.example.ipa_board.kaomoji.KaomojiPanelView
import com.example.ipa_board.kaomoji.KaomojiStatusView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.io.File

/** Real window-dispatched taps: an accessibility click alone cannot exercise outside dismissal. */
@SdkSuppress(minSdkVersion = 35)
class StatusMenuToggleImeTest {
    @get:Rule val lifecycleTrace: TestRule = ImeLifecycleTrace()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val driver = ImeTestDriver()
    private val context get() = instrumentation.targetContext

    @Test fun emojiTitleTouchPreservesCategoryScrollAndEditorWhileToggling() = withKeyboard { fixture ->
        openPanel(KeyAction.EMOJI, ImeChromeView.Panel.EMOJI)
        await("Emoji catalog must load") { views(chrome()).filterIsInstance<EmojiPickerView>().any() }
        val picker = main { views(chrome()).filterIsInstance<EmojiPickerView>().single() }
        assertTrue("The installed catalog must expose multiple categories", main { picker.categories.size > 1 })
        val category = main { picker.categories[1] }
        val marker = category
        val editor = editorState(fixture)

        tap { chrome().status }
        awaitMenu(marker, true)
        assertEditorUnchanged(fixture, editor)
        tap { chrome().status }
        awaitMenu(marker, false)
        assertEditorUnchanged(fixture, editor)
        tap { chrome().status }
        awaitMenu(marker, true)
        tap { menuText(marker, category) }
        awaitMenu(marker, false)
        await("Choosing an Emoji category must navigate the same picker") { picker.currentCategoryName == category }
        main { picker.grid.scrollBy(0, (38 * context.resources.displayMetrics.density).toInt()) }
        instrumentation.waitForIdleSync()
        val panel = main { emojiState(picker) }
        assertTrue("The fixture must have a noninitial scroll position", panel.first > 0 || panel.offset != 0)

        repeat(2) {
            tap { chrome().status }
            awaitMenu(marker, true)
            tap { chrome().status }
            awaitMenu(marker, false)
            main {
                assertSame(picker, views(chrome()).filterIsInstance<EmojiPickerView>().single())
                assertEquals(panel, emojiState(picker))
            }
            assertEditorUnchanged(fixture, editor)
        }
        assertBackAndFunctionSwitch(fixture, KeyAction.EMOJI, ImeChromeView.Panel.EMOJI, marker)
    }

    @Test fun kaomojiTitleAndSharedCallbackToggleWithoutChangingFiltersAndSortStillWorks() = withKeyboard { fixture ->
        openPanel(KeyAction.KAOMOJI, ImeChromeView.Panel.KAOMOJI)
        val panel = main { views(chrome()).filterIsInstance<KaomojiPanelView>().single() }
        withKaomojiItems(panel) {
            val marker = "Reset Filters"
            val editor = editorState(fixture)
            tap { kaomojiTitle() }
            awaitMenu(marker, true)
            tap { kaomojiTitle() }
            awaitMenu(marker, false)
            tap { kaomojiTitle() }
            awaitMenu(marker, true)
            tap { menuText(marker, "Menu fixture") }
            tap { menuText(marker, "Reversed") }
            main { panel.toggleColorTag("Red") }
            assertTrue(main { panel.isReversed })
            assertEquals(setOf("Menu fixture"), main { panel.selectedTags.toSet() })
            main { requireNotNull(chrome().onStatusClick).invoke() }
            awaitMenu(marker, false)

            val grid = main { views(panel).filterIsInstance<GridView>().single() }
            main { grid.setSelection(12) }
            await("The Kaomoji fixture must scroll") { grid.firstVisiblePosition > 0 }
            val state = main { kaomojiState(panel, grid) }
            main { requireNotNull(chrome().onStatusClick).invoke() }
            awaitMenu(marker, true)
            tap { kaomojiTitle() }
            awaitMenu(marker, false)
            main {
                assertSame(panel, views(chrome()).filterIsInstance<KaomojiPanelView>().single())
                assertEquals(state, kaomojiState(panel, grid))
            }
            assertEditorUnchanged(fixture, editor)

            // The adjacent sort button is outside the toggle target and keeps ordinary outside dismissal.
            tap { kaomojiTitle() }
            awaitMenu(marker, true)
            tap {
                val toolbar = chrome().statusContainer.getChildAt(0) as KaomojiStatusView
                views(toolbar).filterIsInstance<TextView>().single { it !== toolbar.tvTitle }
            }
            awaitMenu(marker, false)
            main {
                assertEquals(!state.reversed, panel.isReversed)
                assertEquals(state.tags, panel.selectedTags.toSet())
                assertEquals(state.colors, panel.selectedColorTags.toSet())
            }
            assertEditorUnchanged(fixture, editor)
            tap { kaomojiTitle() }
            awaitMenu(marker, true)
            main { requireNotNull(chrome().onStatusClick).invoke() }
            awaitMenu(marker, false)
            assertBackAndFunctionSwitch(fixture, KeyAction.KAOMOJI, ImeChromeView.Panel.KAOMOJI, marker)
        }
    }

    @Test fun pageGroupTitleTouchPreservesCurrentPageAndNormalGroupSelection() = withKeyboard(pageCount = 8) { fixture ->
        openPanel(KeyAction.PAGES, ImeChromeView.Panel.PAGES)
        val marker = main { PageGroupManager.label(context, PageGroupManager.state(context).groups.single { it.id == fixture.secondGroup }) }
        val scroll = main { views(chrome()).filterIsInstance<ScrollView>().single() }
        await("Pages content must be laid out before scrolling") { scroll.height > 0 && scroll.getChildAt(0).height > scroll.height }
        main { scroll.scrollTo(0, 100) }
        await("The Pages fixture must scroll") { scroll.scrollY > 0 }
        val state = main { pageState(scroll) }
        val editor = editorState(fixture)

        tap { chrome().status }
        awaitMenu(marker, true)
        tap { chrome().status }
        awaitMenu(marker, false)
        main {
            assertSame(scroll, views(chrome()).filterIsInstance<ScrollView>().single())
            assertEquals(state, pageState(scroll))
        }
        assertEditorUnchanged(fixture, editor)
        tap { chrome().status }
        awaitMenu(marker, true)
        tap { menuText(marker, marker) }
        awaitMenu(marker, false)
        await("Normal menu choice must select the requested group") { PageGroupManager.active(context).id == fixture.secondGroup }
        main { assertEquals(fixture.pages.last(), PageGroupManager.activeFilename(context)) }
        assertEditorUnchanged(fixture, editor)
        val selected = main { pageState(views(chrome()).filterIsInstance<ScrollView>().single()) }
        tap { chrome().status }
        awaitMenu(marker, true)
        tap { chrome().status }
        awaitMenu(marker, false)
        main { assertEquals(selected, pageState(views(chrome()).filterIsInstance<ScrollView>().single())) }
        assertBackAndFunctionSwitch(fixture, KeyAction.PAGES, ImeChromeView.Panel.PAGES, marker)
    }

    private data class EmojiState(val category: Int, val first: Int, val offset: Int)
    private fun emojiState(picker: EmojiPickerView): EmojiState {
        val manager = picker.grid.layoutManager as GridLayoutManager
        val first = manager.findFirstVisibleItemPosition()
        return EmojiState(picker.currentCategoryIndex, first, (manager.findViewByPosition(first)?.top ?: 0) - picker.grid.paddingTop)
    }
    private data class KaomojiState(val tags: Set<String>, val colors: Set<String>, val reversed: Boolean, val first: Int, val top: Int)
    private fun kaomojiState(panel: KaomojiPanelView, grid: GridView) = KaomojiState(
        panel.selectedTags.toSet(), panel.selectedColorTags.toSet(), panel.isReversed,
        grid.firstVisiblePosition, grid.getChildAt(0)?.top ?: 0)
    private data class PageState(val group: String, val file: String?, val x: Int, val y: Int)
    private fun pageState(scroll: ScrollView) = PageState(PageGroupManager.active(context).id,
        PageGroupManager.activeFilename(context), scroll.scrollX, scroll.scrollY)

    private fun assertBackAndFunctionSwitch(fixture: Fixture, action: KeyAction, panel: ImeChromeView.Panel, marker: String) {
        val editor = editorState(fixture)
        tap { if (panel == ImeChromeView.Panel.KAOMOJI) kaomojiTitle() else chrome().status }
        awaitMenu(marker, true)
        back()
        awaitMenu(marker, false)
        main { assertEquals("First Back closes only the menu", panel, chrome().panel) }
        assertEditorUnchanged(fixture, editor)
        back()
        await("Second Back returns to the keyboard") { chrome().panel == ImeChromeView.Panel.KEYBOARD }
        openPanel(action, panel)
        awaitMenu(marker, false)
        tap { if (panel == ImeChromeView.Panel.KAOMOJI) kaomojiTitle() else chrome().status }
        awaitMenu(marker, true)
        val otherAction = if (action == KeyAction.PAGES) KeyAction.KAOMOJI else KeyAction.PAGES
        val otherPanel = if (otherAction == KeyAction.PAGES) ImeChromeView.Panel.PAGES else ImeChromeView.Panel.KAOMOJI
        tap { toolbarButton(otherAction) }
        await("Switching functions changes the panel") { chrome().panel == otherPanel }
        awaitMenu(marker, false)
        openPanel(action, panel)
        awaitMenu(marker, false)
        assertEditorUnchanged(fixture, editor)
    }

    private data class EditorState(val text: String, val composingStart: Int, val composingEnd: Int,
        val selectionStart: Int, val selectionEnd: Int, val focused: Boolean, val windowFocused: Boolean,
        val starts: List<String>)
    private fun editorState(fixture: Fixture): EditorState {
        var state: EditorState? = null
        fixture.editor.onActivity {
            val editor = it.editor
            val events = File(context.filesDir, "diagnostics.log").takeIf(File::isFile)?.readLines().orEmpty()
                .filter { line -> line == "IME/START_INPUT/READY" || line == "IME/START_INPUT_VIEW/READY" }
            state = EditorState(editor.text.toString(), BaseInputConnection.getComposingSpanStart(editor.text),
                BaseInputConnection.getComposingSpanEnd(editor.text), editor.selectionStart, editor.selectionEnd,
                editor.hasFocus(), editor.hasWindowFocus(), events)
        }
        return requireNotNull(state)
    }
    private fun assertEditorUnchanged(fixture: Fixture, before: EditorState) {
        assertTrue("The fixture must have pending composition", before.composingStart >= 0)
        assertTrue("The fixture must retain editor focus", before.focused && before.windowFocused)
        assertTrue("Lifecycle diagnostics must be active", before.starts.contains("IME/START_INPUT/READY"))
        assertEquals("Menu operations must preserve text, composition, caret, focus and input session", before, editorState(fixture))
    }
    private fun awaitEditor(fixture: Fixture, message: String, condition: (EditorState) -> Boolean): EditorState {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var state: EditorState
        do {
            state = editorState(fixture)
            if (condition(state)) return state
            SystemClock.sleep(40L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("$message; observed $state", condition(state))
        return state
    }

    private fun openPanel(action: KeyAction, panel: ImeChromeView.Panel) {
        driver.click { it.contentDescription?.toString() == MultifunctionBarView.description(action) }
        await("The requested IME panel must appear", timeout = 30_000L) { chrome().panel == panel && chrome().onStatusClick != null }
    }
    private fun back() {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int) = KeyEvent(down, SystemClock.uptimeMillis(), action,
            KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD)
        assertTrue("System must accept Back DOWN", ui.injectInputEvent(event(KeyEvent.ACTION_DOWN), true))
        assertTrue("System must accept Back UP", ui.injectInputEvent(event(KeyEvent.ACTION_UP), true))
        instrumentation.waitForIdleSync()
    }

    /** Each accepted gesture is injected once; in particular UP is never retried after dismissal. */
    private fun tap(target: () -> View) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var previous: Rect? = null
        var stableSince = 0L
        var bounds: Rect? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val next = main {
                val view = target()
                val visible = Rect()
                if (!view.isShown || !view.isAttachedToWindow || !view.getLocalVisibleRect(visible) || visible.isEmpty) null
                else {
                    // Clip through the ancestor hierarchy before converting the local intersection
                    // to screen coordinates; full bounds can point outside a scrollable menu.
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    visible.offset(location[0], location[1])
                    visible
                }
            }
            val now = SystemClock.uptimeMillis()
            if (next != null && next == previous && now - stableSince >= 200L) { bounds = next; break }
            if (next != previous) { previous = next; stableSince = now }
            SystemClock.sleep(40L)
        }
        val rect = requireNotNull(bounds) { "Owned menu target did not reach stable screen bounds: $previous" }
        val down = SystemClock.uptimeMillis()
        var ended = false
        fun inject(action: Int, required: Boolean = true) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                val accepted = ui.injectInputEvent(event, true)
                if (required) assertTrue("System must accept a single owned-target touch at $rect", accepted)
            } finally { event.recycle() }
        }
        try {
            inject(MotionEvent.ACTION_DOWN)
            inject(MotionEvent.ACTION_UP)
            ended = true
        } finally { if (!ended) runCatching { inject(MotionEvent.ACTION_CANCEL, false) } }
        instrumentation.waitForIdleSync()
    }

    // WindowInspector exposes only this process's windows; all view access is on the main thread.
    private fun chrome(): ImeChromeView = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::views).filterIsInstance<ImeChromeView>().single { it.isShown && it.isAttachedToWindow }
    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }
    private fun kaomojiTitle() = (chrome().statusContainer.getChildAt(0) as KaomojiStatusView).tvTitle
    private fun toolbarButton(action: KeyAction) = views(chrome().multifunctionBar)
        .single { it.isClickable && it.contentDescription?.toString() == MultifunctionBarView.description(action) }
    private fun menuRoots(marker: String) = WindowInspector.getGlobalWindowViews().filter { root ->
        root.isShown && root.isAttachedToWindow && views(root).none { it is ImeChromeView } &&
            views(root).any { it is TextView && it.isShown && it.text.toString() == marker }
    }
    private fun menuText(marker: String, text: String): View = views(menuRoots(marker).single())
        .filterIsInstance<TextView>().single { it.isShown && it.text.toString() == text }.also {
            // Scroll only the owned popup's ancestors, then tap() waits for stable visible bounds.
            it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true)
        }
    private fun awaitMenu(marker: String, shown: Boolean) = await("Menu '$marker' should be ${if (shown) "open exactly once" else "closed"}") {
        menuRoots(marker).size == if (shown) 1 else 0
    }
    private fun await(message: String, timeout: Long = 5_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            if (main(condition)) return
            SystemClock.sleep(40L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue(message, main(condition))
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun withKaomojiItems(panel: KaomojiPanelView, body: () -> Unit) {
        val file = File(context.filesDir, "kaomoji_config.json")
        val before = if (file.isFile) file.readBytes() else null
        val originalIds = main { panel.repository.getAllKaomojis().map { it.id }.toSet() }
        val texts = (0 until 36).map { "Menu fixture $it (・ω・)" }
        require(main { panel.repository.getAllKaomojis().none { it.text in texts } }) { "Synthetic Kaomoji fixture must not merge existing entries" }
        val items = JSONArray().apply {
            texts.forEach { put(JSONObject().put("text", it)
                .put("tags", JSONArray(listOf("Menu fixture", "Red"))).put("usageCount", 0)) }
        }
        try {
            main {
                val imported = panel.repository.importJson(JSONObject().put("version", 1).put("items", items).toString().byteInputStream())
                assertEquals(36, imported.addedCount)
                assertEquals(0, imported.mergedCount)
                panel.refreshList()
            }
            body()
        } finally {
            main {
                try {
                    panel.repository.batchDelete(panel.repository.getAllKaomojis().map { it.id }.toSet() - originalIds)
                    panel.refreshList()
                } finally { if (before == null) file.delete() else file.writeBytes(before) }
            }
        }
    }

    private data class Fixture(val editor: ActivityScenario<ImeTestEditorActivity>, val secondGroup: String, val pages: List<String>)
    private fun withKeyboard(pageCount: Int = 1, body: (Fixture) -> Unit) {
        val groups = PageGroupTestState(context)
        val prefs = EngineSettings.preferences(context)
        val keys = EngineFeature.entries.map { it.key } + listOf(RemoteKeyboardMode.KEY_SNAPSHOT, MultifunctionBarSettings.KEY_BINDINGS)
        val before = keys.associateWith { prefs.all[it] }
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val wasEnabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val flags = ui.serviceInfo.flags
        val pages = mutableListOf<String>()
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        try {
            main {
                assertTrue(prefs.edit().remove(MultifunctionBarSettings.KEY_BINDINGS).remove(RemoteKeyboardMode.KEY_SNAPSHOT)
                    .apply { EngineFeature.entries.forEach { putBoolean(it.key, false) } }.commit())
                MultifunctionBarSettings.save(context, MultifunctionBarSlot.LEFT_FIRST, MultifunctionBarBinding(KeyAction.EMOJI))
            }
            ui.serviceInfo = ui.serviceInfo.apply { this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            val first = PageGroupManager.create(context, "Status menu first")
            val second = PageGroupManager.create(context, "Status menu second")
            val layout = KeyboardLayout("Status menu fixture", listOf(RowLayout(1f, listOf(
                KeySlot(1f, "aβ", textBehavior = TextBehavior.AUTO), KeySlot(1f, action = KeyAction.LEFT), KeySlot(1f, action = KeyAction.ENTER)))))
            repeat(pageCount) { pages += LayoutFileManager.createLayout(context, "Status menu fixture $it", layout) }
            PageGroupManager.addPages(context, first, pages)
            PageGroupManager.addPages(context, second, listOf(pages.last()))
            PageGroupManager.setAppearance(context, first, GroupAppearance(heightDp = 200))
            PageGroupManager.setAppearance(context, second, GroupAppearance(heightDp = 200))
            PageGroupManager.select(context, first)
            PageGroupManager.selectPage(context, pages.first())
            shell("ime enable $ime")
            shell("ime set $ime")
            val editor = ActivityScenario.launch(ImeTestEditorActivity::class.java)
            scenario = editor
            driver.awaitKeyboard(editor) { it.contentDescription?.toString() == "Row 1, key 1: aβ" }
            val fixture = Fixture(editor, second, pages.toList())
            driver.click { it.contentDescription?.toString() == "Row 1, key 1: aβ" }
            awaitEditor(fixture, "The accepted text key must establish the composing range before moving its caret") {
                it.text == "aβ" && it.composingStart == 0 && it.composingEnd == 2 && it.selectionStart == 2 && it.selectionEnd == 2
            }
            driver.click { it.contentDescription?.toString()?.startsWith("Row 1, key 2:") == true }
            val state = awaitEditor(fixture, "The accepted left-arrow key must move the composing caret exactly once") {
                it.text == "aβ" && it.composingStart == 0 && it.composingEnd == 2 && it.selectionStart == 1 && it.selectionEnd == 1
            }
            assertEditorUnchanged(fixture, state)
            body(fixture)
        } finally {
            try { scenario?.close() } finally {
                try { shell("ime set $oldIme"); if (!wasEnabled) shell("ime disable $ime") } finally {
                    try { pages.forEach { LayoutFileManager.deleteLayout(context, it) } } finally {
                        try { groups.close() } finally {
                            try { assertTrue(prefs.edit().apply { before.forEach { (key, value) -> restoreValue(key, value) } }.commit()) }
                            finally { ui.serviceInfo = ui.serviceInfo.apply { this.flags = flags } }
                        }
                    }
                }
            }
        }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            else -> error("Unexpected status-menu fixture preference type: $key")
        }
    }
}
