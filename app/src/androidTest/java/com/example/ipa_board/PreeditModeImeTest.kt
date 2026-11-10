package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.ImeChromeView
import com.example.ipa_board.ime.RemoteKeyboardMode
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Real IME actions in a synthetic editor; device runs require regression.py's external snapshot. */
class PreeditModeImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val driver = ImeTestDriver()
    private val context get() = instrumentation.targetContext
    private val prefs get() = EngineSettings.preferences(context)

    private data class EditorState(val text: String, val start: Int, val end: Int,
        val composingStart: Int, val composingEnd: Int)
    private data class Geometry(val chrome: Rect, val keys: Rect, val editor: Rect, val editorWindow: Rect)
    private data class Bubble(val bounds: Rect, val caretX: Float)

    @Test fun boundModeKeyDefersPinyinUntilRealCandidateSelectionAndCanReturnToInline() = withKeyboard { scenario ->
        val geometry = stableGeometry(scenario)
        modeState(inline = true)
        key(2, 1)
        modeState(inline = false)
        noPreview()
        unchangedGeometry(scenario, geometry)

        typeDeferred("ling")
        bubble("ling")
        assertBubbleAppearance()
        main {
            val group = PageGroupManager.active(context)
            PageGroupManager.setAppearance(context, group.id, group.appearance.copy(
                backgroundColor = "#46324F", symbolColor = "#ABE3D0"))
        }
        awaitOwned("An already visible preedit must follow a keyboard palette change") {
            val label = previewRoots().singleOrNull()?.findViewWithTag<TextView>("ime_preedit_text")
            label?.currentTextColor == Color.parseColor(PageGroupManager.active(context).appearance.symbolColor)
        }
        bubble("ling")
        assertBubbleAppearance()
        publishOwnedBubblePreview()
        editor(scenario, SENTENCE, 0)
        unchangedGeometry(scenario, geometry)
        key(2, 3)
        // Select an explicit compatible Rime candidate from the expanded list, without a rank assumption.
        // The driver retries rejected stale nodes only; an accepted selection is never submitted twice.
        driver.click { it.contentDescription?.toString() == "零" && it.isClickable }
        editor(scenario, "零$SENTENCE", 1)
        noPreview()
        unchangedGeometry(scenario, geometry)

        key(2, 1)
        modeState(inline = true)
        unchangedGeometry(scenario, geometry)
        key(1, 5)
        editor(scenario, "零a$SENTENCE", 2, composingStart = 1, composingEnd = 2)
        noPreview()
        key(2, 2)
        editor(scenario, "零a$SENTENCE", 2)
    }

    @Test fun longPressAndToolbarSwitchesConfirmPendingTextOnceAndVirtualEditingDoesNotTouchTheHost() = withKeyboard { scenario ->
        val geometry = stableGeometry(scenario)
        key(2, 5, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        modeState(inline = false)
        typeDeferred("li")
        key(2, 6)
        preview("li", caret = 1)
        key(2, 7)
        preview("i", caret = 0)
        bubble("i", caret = 0)
        editor(scenario, SENTENCE, 0)
        unchangedGeometry(scenario, geometry)

        clickPreview("i")
        editor(scenario, "i$SENTENCE", 1)
        noPreview()
        unchangedGeometry(scenario, geometry)
        key(2, 5, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        modeState(inline = true)
        key(2, 5)
        editor(scenario, "im$SENTENCE", 2, composingStart = 1, composingEnd = 2)

        // Switching away from inline confirms the text already present, without reinserting it.
        key(2, 5, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        modeState(inline = false)
        editor(scenario, "im$SENTENCE", 2)
        typeDeferred("a")
        val short = bubble("a")
        key(3, 1)
        val longText = "a$LONG_TEXT"
        preview(longText)
        val capped = bubble(longText)
        assertTrue("A short preedit must wrap its content rather than occupy the maximum width",
            capped.bounds.width() > short.bounds.width())
        key(1, 5)
        val longerText = "${longText}a"
        preview(longerText)
        val longer = bubble(longerText)
        assertEquals("Additional text must scroll after the available width is reached",
            capped.bounds.width(), longer.bounds.width())
        repeat(longerText.length) { key(2, 6) }
        preview(longerText, caret = 0)
        bubble(longerText, caret = 0)
        editor(scenario, "im$SENTENCE", 2)
        unchangedGeometry(scenario, geometry)
        driver.click { it.contentDescription?.toString() == "预编辑模式" }
        modeState(inline = true)
        editor(scenario, "im$longerText$SENTENCE", 2 + longerText.length)
        noPreview()
        unchangedGeometry(scenario, geometry)
    }

    @Test fun shiftShortcutConsumesItsKeyAndExternalSelectionCancelsOnlyTheDeferredBuffer() = withKeyboard { scenario ->
        shiftToggle()
        modeState(inline = false)
        typeDeferred("li")
        editor(scenario, SENTENCE, 0)

        scenario.onActivity { it.editor.setSelection(it.editor.length()) }
        noPreview()
        editor(scenario, SENTENCE, SENTENCE.length)
        typeDeferred("g")
        editor(scenario, SENTENCE, SENTENCE.length)
        clickPreview("g")
        editor(scenario, "${SENTENCE}g", SENTENCE.length + 1)
        noPreview()

        shiftToggle()
        modeState(inline = true)
        key(1, 5)
        editor(scenario, "${SENTENCE}ga", SENTENCE.length + 2,
            composingStart = SENTENCE.length + 1, composingEnd = SENTENCE.length + 2)
        key(2, 2)
        editor(scenario, "${SENTENCE}ga", SENTENCE.length + 2)
        shiftToggle()
        modeState(inline = false)
        typeDeferred("l")
        bubble("l")
        scenario.onActivity {
            assertTrue(it.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(it.editor.windowToken, 0))
        }
        awaitOwned("Hiding the owned keyboard must dismiss its floating preedit") {
            shownChrome() == null && previewRoots().isEmpty()
        }
        editor(scenario, "${SENTENCE}ga", SENTENCE.length + 2)
    }

    private fun isKey(node: AccessibilityNodeInfo, row: Int, column: Int) =
        node.contentDescription?.toString()?.startsWith("Row $row, key $column:") == true

    private fun key(row: Int, column: Int, action: Int = AccessibilityNodeInfo.ACTION_CLICK) =
        driver.click(action) { isKey(it, row, column) }

    private fun shiftToggle() {
        key(2, 4)
        key(2, 5)
        assertFalse("The mode shortcut must consume temporary Shift",
            driver.awaitNode { isKey(it, 2, 4) && !it.isSelected }.isSelected)
    }

    private fun modeState(inline: Boolean) {
        val node = driver.awaitNode {
            it.contentDescription?.toString() == "预编辑模式" &&
                it.stateDescription?.toString() == if (inline) "内联" else "非内联"
        }
        assertEquals(!inline, node.isSelected)
        assertEquals("The chosen mode must be saved", inline,
            prefs.getBoolean(SettingsConstants.KEY_INLINE_PREEDIT_ENABLED, true))
    }

    private fun isPreview(node: AccessibilityNodeInfo) =
        node.contentDescription?.toString()?.endsWith("，点按原样确认") == true

    private fun preview(text: String, caret: Int = text.codePointCount(0, text.length)) {
        driver.awaitNode { isPreview(it) && it.text?.toString() == text &&
            it.stateDescription?.toString() == "光标在第 $caret 个字符后" && it.isVisibleToUser }
    }

    private fun noPreview() {
        driver.awaitAbsent(::isPreview)
        awaitOwned("Clearing preedit or switching inline must remove its popup window") { previewRoots().isEmpty() }
    }

    private fun clickPreview(text: String) {
        // Use a real touch on the owned popup, so window touchability/focus errors cannot pass
        // by invoking a view's accessibility callback directly.
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var previous: Rect? = null
        var stableSince = SystemClock.uptimeMillis()
        var target: Rect? = null
        do {
            val next = main {
                previewRoots().singleOrNull()?.let { root ->
                    root.findViewWithTag<TextView>("ime_preedit_text")?.takeIf { it.text.toString() == text }
                        ?.let { bounds(it).apply { intersect(bounds(root)) } }?.takeUnless { it.isEmpty }
                }
            }
            val now = SystemClock.uptimeMillis()
            if (next != null && next == previous && now - stableSince >= 200L) { target = next; break }
            if (next != previous) { previous = next; stableSince = now }
            SystemClock.sleep(40L)
        } while (SystemClock.uptimeMillis() < deadline)
        val rect = requireNotNull(target) { "The owned preedit popup did not reach stable touch bounds" }
        val down = SystemClock.uptimeMillis()
        var ended = false
        fun send(action: Int, required: Boolean = true) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                val accepted = ui.injectInputEvent(event, true)
                if (required) assertTrue("The system must accept a single touch on the owned popup", accepted)
            } finally { event.recycle() }
        }
        try { send(MotionEvent.ACTION_DOWN); send(MotionEvent.ACTION_UP); ended = true }
        finally { if (!ended) runCatching { send(MotionEvent.ACTION_CANCEL, false) } }
        instrumentation.waitForIdleSync()
    }

    // WindowInspector exposes only this process. All view reads run on the main thread.
    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private fun shownChrome(): ImeChromeView? = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::views).filterIsInstance<ImeChromeView>().singleOrNull {
            it.isShown && it.isAttachedToWindow && it.windowVisibility == View.VISIBLE
        }

    private fun previewRoots(): List<View> = WindowInspector.getGlobalWindowViews().filter {
        it.isShown && it.isAttachedToWindow && it.windowVisibility == View.VISIBLE &&
            it.findViewWithTag<TextView>("ime_preedit_text") != null
    }

    private fun bounds(view: View): Rect {
        val point = IntArray(2)
        view.getLocationOnScreen(point)
        return Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }

    private fun geometry(scenario: ActivityScenario<ImeTestEditorActivity>): Geometry {
        var result: Geometry? = null
        scenario.onActivity {
            val chrome = requireNotNull(shownChrome())
            assertTrue("The floating preedit must not take the editor's input focus", it.editor.hasFocus())
            assertTrue("The floating preedit must not take the editor window's focus", it.editor.hasWindowFocus())
            result = Geometry(bounds(chrome), bounds(chrome.keyboardHost), bounds(it.editor), bounds(it.window.decorView))
        }
        return requireNotNull(result)
    }

    private fun unchangedGeometry(scenario: ActivityScenario<ImeTestEditorActivity>, expected: Geometry) =
        assertEquals("A floating preedit must not resize or shift the editor, IME or key area", expected, geometry(scenario))

    private fun stableGeometry(scenario: ActivityScenario<ImeTestEditorActivity>): Geometry {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var previous: Geometry? = null
        var stableSince = SystemClock.uptimeMillis()
        do {
            val next = geometry(scenario)
            val now = SystemClock.uptimeMillis()
            if (next == previous && now - stableSince >= 200L) return next
            if (next != previous) { previous = next; stableSince = now }
            SystemClock.sleep(40L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("The owned keyboard and editor did not finish their initial layout")
    }

    private fun bubble(text: String, caret: Int = text.length): Bubble {
        var result: Bubble? = null
        awaitOwned("Preedit must touch the entire keyboard's top edge in its own window with its caret visible") {
            val chrome = shownChrome() ?: return@awaitOwned false
            val root = previewRoots().singleOrNull() ?: return@awaitOwned false
            val label = root.findViewWithTag<TextView>("ime_preedit_text") ?: return@awaitOwned false
            val layout = label.layout ?: return@awaitOwned false
            val chromeBounds = bounds(chrome)
            val popup = bounds(root)
            if (label.text.toString() != text || popup.width() <= 0 || popup.height() <= 0 ||
                root === chrome.rootView || views(root).any { it is ImeChromeView } ||
                popup.bottom != chromeBounds.top || popup.top < 0 || popup.width() > chromeBounds.width() ||
                popup.left < chromeBounds.left || popup.right > chromeBounds.right) return@awaitOwned false
            val caretX = bounds(label).left + label.compoundPaddingLeft + layout.getPrimaryHorizontal(caret) - label.scrollX
            if (caretX < popup.left || caretX > popup.right) return@awaitOwned false
            result = Bubble(popup, caretX)
            true
        }
        return requireNotNull(result)
    }

    private fun assertBubbleAppearance() = main {
        val chrome = requireNotNull(shownChrome())
        val popup = previewRoots().single()
        val label = popup.findViewWithTag<TextView>("ime_preedit_text")
        val appearance = PageGroupManager.active(context).appearance
        val background = Color.parseColor(appearance.backgroundColor)
        val foreground = Color.parseColor(appearance.symbolColor)
        val keyboardPixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            requireNotNull(chrome.keyboardHost.background).draw(Canvas(keyboardPixel))
            assertEquals("The real key area's drawn background must use the configured palette",
                background, keyboardPixel.getPixel(0, 0))
        } finally { keyboardPixel.recycle() }
        assertEquals("Floating preedit text must follow the current key symbol color", foreground, label.currentTextColor)
        assertTrue("A flat preedit must not cast an elevated window or child shadow", views(popup).all { it.elevation == 0f })

        val bitmap = Bitmap.createBitmap(popup.width, popup.height, Bitmap.Config.ARGB_8888)
        try {
            popup.draw(Canvas(bitmap))
            // Check the actual complete perimeter: filled corners rule out rounding/transparency,
            // and matching every edge pixel rules out an independently colored outline.
            for (x in 0 until bitmap.width) {
                assertEquals("The preedit top edge must share the keyboard background at x=$x", background, bitmap.getPixel(x, 0))
                assertEquals("The preedit bottom edge must share the keyboard background at x=$x", background, bitmap.getPixel(x, bitmap.height - 1))
            }
            for (y in 0 until bitmap.height) {
                assertEquals("The preedit left edge must be square and unoutlined at y=$y", background, bitmap.getPixel(0, y))
                assertEquals("The preedit right edge must be square and unoutlined at y=$y", background, bitmap.getPixel(bitmap.width - 1, y))
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("The rendered preedit must visibly use the configured foreground", foreground in pixels)
        } finally { bitmap.recycle() }
    }

    private fun publishOwnedBubblePreview() {
        val png = main {
            val chrome = requireNotNull(shownChrome())
            val popup = previewRoots().single()
            val chromeBounds = bounds(chrome)
            val popupBounds = bounds(popup)
            val combined = Rect(chromeBounds).apply { union(popupBounds) }
            var width = minOf(720, combined.width())
            var bytes: ByteArray
            do {
                val scale = width.toFloat() / combined.width()
                val bitmap = Bitmap.createBitmap(width, (combined.height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.rgb(48, 48, 48))
                    canvas.scale(scale, scale)
                    fun drawOwned(view: View, rect: Rect) {
                        canvas.save()
                        canvas.translate((rect.left - combined.left).toFloat(), (rect.top - combined.top).toFloat())
                        view.draw(canvas)
                        canvas.restore()
                    }
                    // Draw only our synthetic keyboard and popup, never capture the screen/editor window.
                    drawOwned(chrome, chromeBounds)
                    drawOwned(popup, popupBounds)
                    bytes = ByteArrayOutputStream().use { stream ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                        stream.toByteArray()
                    }
                } finally { bitmap.recycle() }
                width = (width * 0.75f).toInt().coerceAtLeast(1)
            } while (bytes.size > 50_000)
            bytes
        }
        // Status 0 is a completed test, so use the informational code for the render artifact.
        instrumentation.sendStatus(2, Bundle().apply {
            putString("preeditPopupPngBase64", Base64.encodeToString(png, Base64.NO_WRAP))
        })
    }

    private fun awaitOwned(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
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

    private fun typeDeferred(text: String) {
        var pending = ""
        for (character in text) {
            val column = "linga".indexOf(character) + 1
            require(column > 0)
            key(1, column)
            pending += character
            preview(pending)
        }
    }

    private fun editor(scenario: ActivityScenario<ImeTestEditorActivity>, text: String, caret: Int,
        composingStart: Int = -1, composingEnd: Int = -1) {
        val expected = EditorState(text, caret, caret, composingStart, composingEnd)
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var actual: EditorState? = null
        do {
            scenario.onActivity {
                actual = EditorState(it.editor.text.toString(), it.editor.selectionStart, it.editor.selectionEnd,
                    BaseInputConnection.getComposingSpanStart(it.editor.text), BaseInputConnection.getComposingSpanEnd(it.editor.text))
            }
            if (actual == expected) return
            SystemClock.sleep(40L)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("An accepted action must affect only the intended editor range", expected, actual)
    }

    private fun withKeyboard(body: (ActivityScenario<ImeTestEditorActivity>) -> Unit) {
        val groups = PageGroupTestState(context)
        val keys = EngineFeature.entries.map { it.key } + listOf(RemoteKeyboardMode.KEY_SNAPSHOT,
            MultifunctionBarSettings.KEY_BINDINGS, SettingsConstants.KEY_INLINE_PREEDIT_ENABLED,
            SettingsConstants.KEY_SHIFT_SHORTCUTS, SettingsConstants.KEY_CTRL_SHORTCUTS,
            SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, SettingsConstants.KEY_QUICK_PASTE_ENABLED)
        val before = keys.associateWith { prefs.all[it] }
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val wasEnabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val flags = ui.serviceInfo.flags
        var page: String? = null
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        try {
            instrumentation.runOnMainSync {
                assertTrue(prefs.edit().apply {
                    remove(RemoteKeyboardMode.KEY_SNAPSHOT)
                    remove(MultifunctionBarSettings.KEY_BINDINGS)
                    remove(SettingsConstants.KEY_INLINE_PREEDIT_ENABLED)
                    remove(SettingsConstants.KEY_CTRL_SHORTCUTS)
                    putString(SettingsConstants.KEY_SHIFT_SHORTCUTS,
                        SettingsConstants.serializeShortcutsJson(mapOf("m" to KeyAction.PREEDIT_MODE)))
                    putBoolean(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, false)
                    putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, false)
                    EngineFeature.entries.forEach { putBoolean(it.key, it == EngineFeature.CHINESE_CONVERSION) }
                }.commit())
                MultifunctionBarSettings.save(context, MultifunctionBarSlot.LEFT_FIRST, MultifunctionBarBinding(KeyAction.PREEDIT_MODE))
            }
            ui.serviceInfo = ui.serviceInfo.apply { this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            val group = PageGroupManager.create(context, "Preedit mode fixture")
            val layout = KeyboardLayout("Preedit mode fixture", listOf(
                RowLayout(1f, "linga".map { KeySlot(1f, it.toString(), textBehavior = TextBehavior.AUTO) }),
                RowLayout(1f, listOf(KeySlot(1f, action = KeyAction.PREEDIT_MODE),
                    KeySlot(1f, action = KeyAction.ENTER), KeySlot(1f, action = KeyAction.CANDIDATES),
                    KeySlot(1f, action = KeyAction.SHIFT),
                    KeySlot(1f, "m", textBehavior = TextBehavior.AUTO, longPressAction = KeyAction.PREEDIT_MODE),
                    KeySlot(1f, action = KeyAction.LEFT), KeySlot(1f, action = KeyAction.BACKSPACE))),
                RowLayout(1f, listOf(KeySlot(1f, LONG_TEXT, textBehavior = TextBehavior.AUTO)))))
            page = LayoutFileManager.createLayout(context, layout.name, layout)
            PageGroupManager.addPages(context, group, listOf(page))
            PageGroupManager.setAppearance(context, group,
                GroupAppearance(backgroundColor = "#213647", symbolColor = "#F4D98C", heightDp = 200))
            PageGroupManager.select(context, group)
            PageGroupManager.selectPage(context, page)
            shell("ime enable $ime")
            shell("ime set $ime")
            val owned = ActivityScenario.launch(ImeTestEditorActivity::class.java)
            scenario = owned
            driver.awaitKeyboard(owned) { isKey(it, 1, 1) }
            // Synthetic text only: this is not a clipboard operation or a claim about Gemini's trigger.
            owned.onActivity { it.editor.setText(SENTENCE); it.editor.setSelection(0) }
            instrumentation.waitForIdleSync()
            editor(owned, SENTENCE, 0)
            modeState(inline = true)
            body(owned)
        } finally {
            try { scenario?.close() } finally {
                try { shell("ime set $oldIme"); if (!wasEnabled) shell("ime disable $ime") } finally {
                    try { page?.let { LayoutFileManager.deleteLayout(context, it) } } finally {
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
            else -> error("Unexpected preedit fixture preference type: $key")
        }
    }

    private companion object {
        const val SENTENCE = "一二三四五六七八九十一二三"
        val LONG_TEXT = "Ｗ".repeat(59) + "😀"
    }
}
