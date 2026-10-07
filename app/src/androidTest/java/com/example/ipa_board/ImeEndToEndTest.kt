package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.emoji.BundledEmojiCatalogSource
import com.example.ipa_board.emoji.EmojiCatalogRepository
import com.example.ipa_board.emoji.EmojiEntry
import com.example.ipa_board.emoji.InstalledEmojiCatalogSource
import com.example.ipa_board.ime.BuiltinLayouts
import org.junit.Assert.*
import org.junit.Test

class ImeEndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private val driver = ImeTestDriver()
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean) = driver.find(predicate)
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean) = driver.awaitNode(predicate)

    @Test fun widthSwapToolbarAndBoundGesturesTransformExactlyOnce() {
        val original = "aＡ1１,， 😀ɑ中"
        val swapped = "ａA１1，,　😀ɑ中"
        val held = "hＨｶﾞガ"
        val swappedHold = "ｈHガｶﾞ"
        val layout = KeyboardLayout("Width Swap E2E", listOf(
            RowLayout(1f, listOf(
                KeySlot(1f, original),
                KeySlot(1f, action = KeyAction.WIDTH_SWAP, longPressAction = KeyAction.WIDTH_SWAP),
                KeySlot(1f, "hold", longPressItems = listOf(LongPressItem(text = held))),
                KeySlot(1f, action = KeyAction.ENTER)
            )),
            RowLayout(1f, listOf(KeySlot(1f, action = KeyAction.CANDIDATES)))
        ))
        withWidthSwapKeyboard(listOf(layout, layout.copy(name = "Width Swap E2E alternate"))) { scenario, pages ->
            fun key(column: Int, action: Int = AccessibilityNodeInfo.ACTION_CLICK) {
                assertTrue(awaitNode { it.contentDescription?.toString()?.startsWith("Row 1, key $column:") == true }
                    .performAction(action))
            }
            fun state(enabled: Boolean) {
                val toggle = awaitNode {
                    it.contentDescription?.toString() == "全角 / 半角转换" &&
                        it.stateDescription?.toString() == if (enabled) "开启" else "关闭"
                }
                assertEquals(enabled, toggle.isSelected)
            }
            fun toolbar() {
                assertTrue(awaitNode { it.contentDescription?.toString() == "全角 / 半角转换" }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            state(false)
            key(1)
            key(4)
            awaitEditorText(scenario, original, composing = false)

            toolbar()
            state(true)
            key(1)
            awaitEditorText(scenario, original + swapped, composing = true)
            key(4)
            // The original raw buffer is transformed on commit, never the already transformed display.
            awaitEditorText(scenario, original + swapped, composing = false)

            key(3, AccessibilityNodeInfo.ACTION_LONG_CLICK)
            awaitEditorText(scenario, original + swapped + swappedHold, composing = true)
            key(4)
            awaitEditorText(scenario, original + swapped + swappedHold, composing = false)

            key(2)
            state(false)
            key(2, AccessibilityNodeInfo.ACTION_LONG_CLICK)
            state(true)
            assertTrue(awaitNode { it.contentDescription?.toString()?.startsWith("Row 2, key 1:") == true }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode { it.text?.toString()?.startsWith("Candidates") == true }
            state(true)
            assertTrue(awaitNode { it.contentDescription?.toString() == "Return to Keyboard" }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(awaitNode { it.contentDescription?.toString() == "Keyboard Overview" }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(awaitNode {
                it.contentDescription?.toString() == "${pages[1].removeSuffix(".json")}, Switch Page"
            }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            state(true)

            // A new input session on the same service retains the toggle and exercises direct output.
            scenario.onActivity {
                it.editor.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                it.getSystemService(android.view.inputmethod.InputMethodManager::class.java).restartInput(it.editor)
            }
            instrumentation.waitForIdleSync()
            driver.awaitKeyboard(scenario) { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
            state(true)
            key(1)
            val prefix = original + swapped + swappedHold
            awaitEditorText(scenario, prefix + swapped, composing = false)
            key(2)
            state(false)
            key(1)
            awaitEditorText(scenario, prefix + swapped + original, composing = false)
        }
    }

    @Test fun widthSwapPreservesNativePinyinEncodingAndCandidateCommit() {
        val layout = KeyboardLayout("Width Swap Candidate E2E", listOf(RowLayout(1f, listOf(
            KeySlot(1f, "nihao123", textBehavior = TextBehavior.AUTO),
            KeySlot(1f, action = KeyAction.WIDTH_SWAP),
            KeySlot(1f, action = KeyAction.ENTER)
        ))))
        withWidthSwapKeyboard(listOf(layout)) { scenario, _ ->
            assertTrue(awaitNode { it.contentDescription?.toString() == "全角 / 半角转换" }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode { it.contentDescription?.toString() == "全角 / 半角转换" && it.stateDescription?.toString() == "开启" }
            assertTrue(awaitNode { it.contentDescription?.toString() == "Row 1, key 1: nihao123" }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitEditorText(scenario, "ｎｉｈａｏ１２３", composing = true)
            // The engine still receives nihao123; selecting a Chinese candidate transforms its digits once.
            awaitNode { it.contentDescription?.toString() == "你好123" && it.isClickable }
            assertTrue(awaitNode { it.contentDescription?.toString() == "Expand" }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode { it.text?.toString()?.startsWith("Candidates (") == true }
            awaitNode { it.contentDescription?.toString() == "全角 / 半角转换" && it.stateDescription?.toString() == "开启" }
            assertTrue(awaitNode { it.contentDescription?.toString() == "Return to Keyboard" }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            driver.select(scenario, "你好123", "你好１２３")
            awaitEditorText(scenario, "你好１２３", composing = false)
        }
    }

    private fun awaitEditorText(scenario: ActivityScenario<ImeTestEditorActivity>, expected: String, composing: Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var actual = ""
        var composingStart = -1
        do {
            scenario.onActivity {
                actual = it.editor.text.toString()
                composingStart = BaseInputConnection.getComposingSpanStart(it.editor.text)
            }
            if (actual == expected && (composingStart >= 0) == composing) return
            Thread.sleep(40)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        assertEquals("Actual IME output must match the requested width exactly once", expected, actual)
        assertEquals("The editor's composing state must match the input session", composing, composingStart >= 0)
    }

    private fun withWidthSwapKeyboard(
        layouts: List<KeyboardLayout>,
        body: (ActivityScenario<ImeTestEditorActivity>, List<String>) -> Unit
    ) {
        val context = instrumentation.targetContext
        val groupSnapshot = PageGroupTestState(context)
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val alreadyEnabled = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        val pages = mutableListOf<String>()
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        try {
            ui.serviceInfo = ui.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            layouts.forEach { layout -> pages += LayoutFileManager.createLayout(context, layout.name, layout) }
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, pages)
            PageGroupManager.selectPage(context, pages.first())
            shell("ime enable $ime"); shell("ime set $ime")
            val activeScenario = ActivityScenario.launch(ImeTestEditorActivity::class.java)
            scenario = activeScenario
            driver.awaitKeyboard(activeScenario) { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
            assertEquals("The keyboard width conversion starts disabled", "关闭",
                awaitNode { it.contentDescription?.toString() == "全角 / 半角转换" }.stateDescription?.toString())
            body(activeScenario, pages)
        } finally {
            try {
                // This toggle is service-local rather than a preference; turn it off before the editor closes.
                find {
                    it.contentDescription?.toString() == "全角 / 半角转换" && it.stateDescription?.toString() == "开启"
                }?.let { check(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            } finally {
                scenario?.close()
                shell("ime set $oldIme")
                if (!alreadyEnabled) shell("ime disable $ime")
                pages.forEach { LayoutFileManager.deleteLayout(context, it) }
                ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
                groupSnapshot.close()
            }
        }
    }

    @Test fun emojiKeyOpensPanelAndInsertsWithoutClosingIt() {
        val context = instrumentation.targetContext
        val catalog = BundledEmojiCatalogSource(context).load()
        val first = catalog.entries.first { !it.isComponent }
        val newest = catalog.entries.filter { !it.isComponent && it.emojiVersion == catalog.version }
        assertTrue("The bundled release must include its newest emoji cohort", newest.isNotEmpty())
        val latest = newest.first()
        val installedFile = java.io.File(context.filesDir, InstalledEmojiCatalogSource.INSTALLED_FILE_NAME)
        require(!installedFile.exists() || installedFile.isFile) { "Installed emoji catalog is not a regular file" }
        val installedBefore = if (installedFile.isFile) installedFile.readBytes() else null
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val groupSnapshot = PageGroupTestState(context)
        val oldPage = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val alreadyEnabled = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var page: String? = null
        fun showAllEmoji() {
            assertTrue(awaitNode { it.text?.toString()?.startsWith("Emoji · ") == true && it.isClickable }
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var item = awaitNode { it.text?.toString() == context.getString(R.string.emoji_all) }
            while (!item.isClickable) item = requireNotNull(item.parent)
            assertTrue(item.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode { it.text?.toString() == "Emoji · ${context.getString(R.string.emoji_all)} ▾" }
        }
        fun findEmoji(entry: EmojiEntry): AccessibilityNodeInfo {
            val description = "${entry.name}, Emoji ${entry.emojiVersion}"
            val deadline = android.os.SystemClock.uptimeMillis() + 30_000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                find { it.contentDescription?.toString() == description && it.isClickable }?.let { return it }
                val grid = awaitNode { it.contentDescription?.toString() == context.getString(R.string.emoji_grid_description) }
                assertTrue("Bundled emoji is missing from the live picker: $description",
                    grid.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                Thread.sleep(80)
            }
            error("Timed out scrolling to bundled emoji: $description")
        }
        try {
            // Exercise the bundled release even if the user has an independently installed catalog.
            if (installedFile.exists()) check(installedFile.delete())
            EmojiCatalogRepository.getInstance(context).invalidate()
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            page = LayoutFileManager.createLayout(context, "Emoji E2E", KeyboardLayout("Emoji E2E", listOf(
                RowLayout(1f, listOf(KeySlot(1f, action = KeyAction.EMOJI), KeySlot(1f, "n", textBehavior = TextBehavior.AUTO)))
            )))
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(page))
            PageGroupManager.selectPage(context, page)
            shell("ime enable $ime"); shell("ime set $ime")
            ActivityScenario.launch(ImeTestEditorActivity::class.java).use { scenario ->
                android.os.SystemClock.sleep(700)
                scenario.onActivity {
                    it.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(it.editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                awaitNode { it.contentDescription?.toString() == "Row 1, key 2: n" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                awaitNode { it.contentDescription?.toString() == "Row 1, key 1: Emoji" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                showAllEmoji()
                assertNull(find { it.contentDescription?.toString()?.startsWith("Row ") == true })
                repeat(2) {
                    assertTrue(findEmoji(first).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    instrumentation.waitForIdleSync()
                }
                val repeated = "n" + first.text.repeat(2)
                scenario.onActivity { assertEquals(repeated, it.editor.text.toString()) }
                // Newest Unicode entries remain inputtable even if the device renders a name fallback.
                assertTrue(findEmoji(latest).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals(repeated + latest.text, it.editor.text.toString()) }
                awaitNode { it.contentDescription?.toString() == context.getString(R.string.emoji_grid_description) }
                awaitNode { it.contentDescription?.toString() == "Return to Keyboard" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                awaitNode { it.contentDescription?.toString() == "Row 1, key 1: Emoji" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                showAllEmoji()
                findEmoji(first)
                shell("input keyevent 4")
                awaitNode { it.contentDescription?.toString() == "Row 1, key 1: Emoji" }
            }
        } finally {
            try {
                shell("ime set $oldIme")
                if (!alreadyEnabled) shell("ime disable $ime")
                prefs.edit().apply { if (oldPage == null) remove(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE) else putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, oldPage) }.commit()
                page?.let { LayoutFileManager.deleteLayout(context, it) }
                ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
                groupSnapshot.close()
            } finally {
                if (installedBefore == null) installedFile.delete() else installedFile.writeBytes(installedBefore)
                EmojiCatalogRepository.getInstance(context).invalidate()
            }
        }
    }

    @Test fun actualTouchHoldCommitsOnlyOnRelease() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val groupSnapshot = PageGroupTestState(context)
        val oldPage = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val alreadyEnabled = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var page: String? = null
        try {
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            page = LayoutFileManager.createLayout(context, "Preview E2E", KeyboardLayout("Preview", listOf(
                RowLayout(1f, listOf(KeySlot(1f, "a", longPressText = "ɑ😀"), KeySlot(1f, "b")))
            )))
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(page))
            PageGroupManager.selectPage(context, page)
            shell("ime enable $ime"); shell("ime set $ime")
            ActivityScenario.launch(ImeTestEditorActivity::class.java).use { scenario ->
                driver.awaitKeyboard(scenario) { it.contentDescription?.toString() == "Row 1, key 1: a, long press: ɑ😀" }
                // Wait for the IME entrance animation before obtaining screen touch coordinates.
                android.os.SystemClock.sleep(700)
                val key = awaitNode { it.contentDescription?.toString() == "Row 1, key 1: a, long press: ɑ😀" }
                val bounds = android.graphics.Rect()
                key.getBoundsInScreen(bounds)
                val down = android.os.SystemClock.uptimeMillis()
                fun touch(action: Int) {
                    val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(ui.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                touch(android.view.MotionEvent.ACTION_DOWN)
                android.os.SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150)
                scenario.onActivity { assertEquals("", it.editor.text.toString()) }
                touch(android.view.MotionEvent.ACTION_UP)
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals("ɑ😀", it.editor.text.toString()) }
                touch(android.view.MotionEvent.ACTION_DOWN)
                touch(android.view.MotionEvent.ACTION_UP)
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals("ɑ😀a", it.editor.text.toString()) }
            }
        } finally {
            shell("ime set $oldIme")
            if (!alreadyEnabled) shell("ime disable $ime")
            prefs.edit().apply { if (oldPage == null) remove(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE) else putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, oldPage) }.commit()
            page?.let { LayoutFileManager.deleteLayout(context, it) }
            ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
            groupSnapshot.close()
        }
    }

    @Test fun actualKeyboardConvertsAndExpandedPanelReturnsToKeys() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val groupSnapshot = PageGroupTestState(context)
        val oldPage = prefs.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        val ime = "${context.packageName}/.IpaBoardService"
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val alreadyEnabled = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var page: String? = null
        var alternatePage: String? = null
        try {
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            page = LayoutFileManager.createLayout(context, "IME E2E", BuiltinLayouts.mixed.copy(name = "IME E2E"))
            alternatePage = LayoutFileManager.createLayout(context, "IME E2E 2", BuiltinLayouts.mixed.copy(name = "IME E2E 2")
                .withKeyMapping(0, 0, "q", KeyAction.TEXT, longPressText = "ni")
                .withKeyMapping(2, 8, "", KeyAction.BACKSPACE, longPressText = "ɑ😀"))
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(page))
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(alternatePage))
            PageGroupManager.selectPage(context, page)
            shell("ime enable $ime"); shell("ime set $ime")
            ActivityScenario.launch(ImeTestEditorActivity::class.java).use { scenario ->
                driver.awaitKeyboard(scenario) { it.contentDescription?.toString() == "Keyboard Overview" }
                for (letter in "nihao") {
                    val key = awaitNode { it.contentDescription?.toString()?.let { d -> d.startsWith("Row ") && d.endsWith(": $letter") } == true }
                    assertTrue(key.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                awaitNode { it.contentDescription?.toString() == "你好" && it.isClickable }
                assertTrue(awaitNode { it.contentDescription?.toString() == "Keyboard Overview" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(awaitNode { it.contentDescription?.toString() == "IME E2E 2, Switch Page" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals("nihao", it.editor.text.toString()) }
                awaitNode { it.contentDescription?.toString() == "你好" && it.isClickable }
                assertTrue(awaitNode { it.contentDescription?.toString() == "Expand" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitNode { it.text?.toString()?.startsWith("Candidates (") == true }
                assertNull(find { it.contentDescription?.toString()?.startsWith("Row ") == true })
                shell("input keyevent 4")
                awaitNode { it.contentDescription?.toString()?.startsWith("Row ") == true }
                driver.select(scenario, "你好", "你好")
                scenario.onActivity { assertEquals("你好", it.editor.text.toString()) }
                var committed = "你好"
                for ((raw, word) in listOf("hello" to "hello", "hanyu" to "汉语", "hanyu" to "漢語", "nihongo" to "日本語")) {
                    for (letter in raw) {
                        assertTrue(awaitNode { it.contentDescription?.toString()?.let { d -> d.startsWith("Row ") && d.endsWith(": $letter") } == true }
                            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    }
                    committed += word
                    driver.select(scenario, word, committed)
                }
                scenario.onActivity { assertEquals("你好hello汉语漢語日本語", it.editor.text.toString()) }
                // Long-press text joins mixed composition; a function key's hold must not delete text.
                for (text in listOf("ni", "ɑ😀")) {
                    assertTrue(awaitNode { it.contentDescription?.toString()?.endsWith("long press: $text") == true }
                        .performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                    instrumentation.waitForIdleSync()
                }
                val committedPrefix = "你好hello汉语漢語日本語"
                val heldText = committedPrefix + "niɑ😀"
                scenario.onActivity {
                    assertEquals(heldText, it.editor.text.toString())
                    assertEquals(committedPrefix.length, BaseInputConnection.getComposingSpanStart(it.editor.text))
                    assertEquals(heldText.length, BaseInputConnection.getComposingSpanEnd(it.editor.text))
                }
                // Enter commits raw mixed text once, without adding a newline or converting it.
                assertTrue(awaitNode { it.contentDescription?.toString()?.startsWith("Row 4, key 5:") == true }
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(heldText, it.editor.text.toString())
                    assertEquals(-1, BaseInputConnection.getComposingSpanStart(it.editor.text))
                }
                assertTrue(awaitNode { it.contentDescription?.toString() == "Clipboard" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitNode { it.contentDescription?.toString() == "Return to Keyboard" }
                assertNull(find { it.contentDescription?.toString()?.startsWith("Row ") == true })
                shell("input keyevent 4")
                awaitNode { it.contentDescription?.toString()?.startsWith("Row ") == true }
                shell("input keyevent 4")
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                while (find { it.contentDescription?.toString() == "Keyboard Overview" } != null && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(80)
                assertNull(find { it.contentDescription?.toString() == "Keyboard Overview" })
            }
        } finally {
            shell("ime set $oldIme")
            if (!alreadyEnabled) shell("ime disable $ime")
            prefs.edit().apply { if (oldPage == null) remove(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE) else putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, oldPage) }.commit()
            page?.let { LayoutFileManager.deleteLayout(context, it) }
            alternatePage?.let { LayoutFileManager.deleteLayout(context, it) }
            ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
            groupSnapshot.close()
        }
    }
}
