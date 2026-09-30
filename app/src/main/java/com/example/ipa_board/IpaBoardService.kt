package com.example.ipa_board

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.example.ipa_board.ime.*
import com.example.ipa_board.emoji.*
import com.example.ipa_board.SettingsConstants.PREFS_NAME
import com.example.ipa_board.SettingsConstants.KEY_LAYOUT_REVISION
import com.example.ipa_board.SettingsConstants.KEY_BG_COLOR_HEX
import com.example.ipa_board.SettingsConstants.KEY_ACTIVE_LAYOUT_FILE
import com.example.ipa_board.SettingsConstants.KEY_KEYBOARD_HEIGHT
import com.example.ipa_board.SettingsConstants.KEY_SYMBOL_COLOR_HEX
import com.example.ipa_board.SettingsConstants.DEFAULT_KEYBOARD_HEIGHT
import com.example.ipa_board.SettingsConstants.DEFAULT_BG_COLOR_HEX
import com.example.ipa_board.SettingsConstants.DEFAULT_SYMBOL_COLOR_HEX
import com.example.ipa_board.SettingsConstants.DEFAULT_LAYOUT_FILENAME
import com.example.ipa_board.SettingsConstants.DEFAULT_LAYOUT
import java.util.Locale

class IpaBoardService : InputMethodService() {
    private var chrome: ImeChromeView? = null
    private lateinit var prefs: SharedPreferences
    private val inputController = KeyboardInputController()
    private lateinit var engines: EngineCoordinator
    private lateinit var composition: CompositionController
    private val emojiRepository by lazy { EmojiCatalogRepository(BundledEmojiCatalogSource(this)) }
    private val emojiExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val emojiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var emojiRequest = 0L
    private var emojiClosed = false
    private var engineStatus = "加载词库"
    private var directOnly = false
    private var consumedPanelBack = false
    private var panelBackRegistered = false
    private val panelBackCallback = android.window.OnBackInvokedCallback { chrome?.showPanel(ImeChromeView.Panel.KEYBOARD) }
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (chrome?.panel == ImeChromeView.Panel.CLIPBOARD) showClipboard()
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf(KEY_LAYOUT_REVISION, KEY_BG_COLOR_HEX, KEY_ACTIVE_LAYOUT_FILE, KEY_KEYBOARD_HEIGHT, KEY_SYMBOL_COLOR_HEX)) {
            if (key == KEY_ACTIVE_LAYOUT_FILE || key == KEY_LAYOUT_REVISION) inputController.reset()
            applySettings()
            if (key == KEY_LAYOUT_REVISION && chrome?.panel == ImeChromeView.Panel.PAGES) showPages()
        }
    }
    override fun onCreate() {
        super.onCreate()
        LayoutFileManager.initDefaultLayout(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        composition = CompositionController({ currentInputConnection }, { id, raw -> engines.query(id, raw) }, { refreshCandidates() })
        engines = EngineCoordinator(this) { id, candidates, state ->
            engineStatus = state
            composition.acceptResults(id, candidates)
            refreshCandidates()
        }
    }
    override fun onCreateInputView(): View {
        return ImeChromeView(this).also { view ->
            chrome = view
            view.onCandidate = { candidate, generation -> if (composition.select(candidate, generation)) view.showPanel(ImeChromeView.Panel.KEYBOARD) }
            view.onLiteral = { if (composition.literal()) view.showPanel(ImeChromeView.Panel.KEYBOARD) }
            view.onPanel = { panel ->
                updatePanelBack(panel != ImeChromeView.Panel.KEYBOARD)
                when (panel) {
                    ImeChromeView.Panel.CLIPBOARD -> showClipboard()
                    ImeChromeView.Panel.PAGES -> showPages()
                    ImeChromeView.Panel.EMOJI -> showEmoji()
                    else -> Unit
                }
            }
            applySettings(); refreshCandidates()
            engines.start()
        }
    }
    private fun refreshCandidates() {
        chrome?.render(composition.raw, composition.candidates, if (directOnly) "直接输入" else engineStatus, composition.revision)
    }
    private fun applySettings() {
        val view = chrome ?: return
        val density = resources.displayMetrics.density
        val availableDp = (resources.displayMetrics.heightPixels / density - 180).toInt().coerceAtLeast(100)
        val height = (prefs.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT).coerceIn(100, availableDp) * density).toInt()
        view.setKeyboardHeight(height)
        fun color(key: String, fallback: String) = try { Color.parseColor(prefs.getString(key, fallback)) } catch (_: IllegalArgumentException) { Color.parseColor(fallback) }
        view.keyboardHost.setBackgroundColor(color(KEY_BG_COLOR_HEX, DEFAULT_BG_COLOR_HEX))
        val filename = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME
        val layout = LayoutFileManager.loadLayout(this, filename) ?: DEFAULT_LAYOUT
        KeyboardRenderer.render(this, view.keyboardHost, layout, height, color(KEY_SYMBOL_COLOR_HEX, DEFAULT_SYMBOL_COLOR_HEX),
            inputController.shiftEnabled, inputController.ctrlEnabled,
            showKeyPreview = true,
            onKeyQuickSwipeItemClick = { _, _, slot, item -> handleLongPressItem(slot, item) },
            onKeyLongItemClick = { _, _, slot, item -> handleLongPressItem(slot, item) },
            onKeyLongClick = { _, _, slot -> handleLongPress(slot) }) { _, _, slot -> handleKey(slot) }
    }

    private fun handleLongPressItem(slot: KeySlot, item: LongPressItem) {
        if (item.action == KeyAction.TEXT && item.text.isNotEmpty()) {
            val ic = currentInputConnection ?: return
            if (!composition.literal()) return
            if (ic.commitText(item.text, 1)) {
                if (inputController.consumeSingleShift()) applySettings()
            }
        } else if (item.action != KeyAction.TEXT) {
            val longPressSlot = KeySlot(
                widthWeight = slot.widthWeight,
                text = "",
                action = item.action,
                textBehavior = slot.textBehavior
            )
            handleKey(longPressSlot)
        }
    }

    private fun handleLongPress(slot: KeySlot) {
        if (!slot.hasLongPress) return
        val item = slot.effectiveLongPressItems.firstOrNull() ?: LongPressItem(text = slot.longPressText, action = slot.longPressAction)
        handleLongPressItem(slot, item)
    }

    private fun handleKey(slot: KeySlot) {
        when (slot.action) {
            KeyAction.EMOJI -> {
                chrome?.showPanel(ImeChromeView.Panel.EMOJI)
                return
            }
            KeyAction.CANDIDATES -> {
                chrome?.showPanel(if (chrome?.panel == ImeChromeView.Panel.CANDIDATES) ImeChromeView.Panel.KEYBOARD else ImeChromeView.Panel.CANDIDATES)
                return
            }
            KeyAction.CLIPBOARD -> {
                chrome?.showPanel(if (chrome?.panel == ImeChromeView.Panel.CLIPBOARD) ImeChromeView.Panel.KEYBOARD else ImeChromeView.Panel.CLIPBOARD)
                return
            }
            KeyAction.PAGES -> {
                chrome?.showPanel(if (chrome?.panel == ImeChromeView.Panel.PAGES) ImeChromeView.Panel.KEYBOARD else ImeChromeView.Panel.PAGES)
                return
            }
            KeyAction.PREV_PAGE -> {
                val newPage = LayoutFileManager.switchPage(this, forward = false)
                val name = LayoutFileManager.loadLayout(this, newPage)?.name ?: newPage
                Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
                return
            }
            KeyAction.NEXT_PAGE -> {
                val newPage = LayoutFileManager.switchPage(this, forward = true)
                val name = LayoutFileManager.loadLayout(this, newPage)?.name ?: newPage
                Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
                return
            }
            else -> {}
        }
        val ic = currentInputConnection ?: return
        val beforeShiftState = inputController.shiftState
        val beforeShift = inputController.shiftEnabled
        val beforeCtrl = inputController.ctrlEnabled
        val isModifier = slot.action == KeyAction.SHIFT || slot.action == KeyAction.CTRL
        val text = if (beforeShift) slot.text.uppercase(Locale.ROOT) else slot.text
        if (!beforeCtrl && slot.action == KeyAction.TEXT && text.isEmpty()) return
        if (!directOnly && !beforeCtrl && !isModifier) {
            when {
                (slot.action == KeyAction.BACKSPACE || slot.action == KeyAction.REPEAT_BACKSPACE) && composition.backspace() -> {
                    if (inputController.consumeSingleShift()) applySettings()
                    return
                }
                slot.action == KeyAction.ENTER && composition.raw.isNotEmpty() -> {
                    composition.literal()
                    if (inputController.consumeSingleShift()) applySettings()
                    return
                }
                slot.action == KeyAction.TEXT && text == " " && composition.space() -> {
                    if (inputController.consumeSingleShift()) applySettings()
                    return
                }
                slot.action == KeyAction.TEXT && slot.textBehavior == TextBehavior.AUTO && text.isNotEmpty() &&
                    text.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' } -> {
                    composition.input(text)
                    if (inputController.consumeSingleShift()) applySettings()
                    return
                }
            }
        }
        if (!isModifier && !composition.literal()) return
        if (!inputController.handle(slot, ic, currentInputEditorInfo)) Toast.makeText(this, R.string.unsupported_shortcut, Toast.LENGTH_SHORT).show()
        if (beforeShiftState != inputController.shiftState || beforeCtrl != inputController.ctrlEnabled) applySettings()
    }
    private fun showEmoji() {
        val view = chrome ?: return
        val request = ++emojiRequest
        view.onStatusClick = null
        view.showContent("Emoji", TextView(this).apply {
            setText(R.string.emoji_loading)
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        })
        emojiExecutor.execute {
            val result = runCatching { emojiRepository.load() }
            emojiHandler.post {
                if (emojiClosed || request != emojiRequest || chrome !== view || view.panel != ImeChromeView.Panel.EMOJI) return@post
                result.fold(onSuccess = { catalog ->
                    val picker = EmojiPickerView(this, catalog) { entry ->
                        // Commit any pending composition first. Emoji bypass Shift/Ctrl and language conversion.
                        if (composition.literal()) currentInputConnection?.commitText(entry.text, 1)
                    }
                    fun updateStatus() {
                        view.showContent("Emoji · ${picker.currentCategoryName} ▾", picker)
                    }
                    picker.onCategorySelected = { _, _ -> updateStatus() }
                    view.onStatusClick = { showEmojiCategoryMenu(picker) }
                    updateStatus()
                }, onFailure = {
                    view.onStatusClick = null
                    view.showContent("Emoji", Button(this).apply {
                        setText(R.string.emoji_retry)
                        setOnClickListener { showEmoji() }
                    })
                })
            }
        }
    }

    private fun showEmojiCategoryMenu(picker: EmojiPickerView) {
        val view = chrome ?: return
        val popup = PopupMenu(this, view.status)
        picker.categories.forEachIndexed { index, category ->
            popup.menu.add(0, index, index, category)
        }
        popup.setOnMenuItemClickListener { item ->
            picker.filterCategory(item.itemId)
            true
        }
        popup.show()
    }

    private fun showClipboard() {
        val view = chrome ?: return
        val clip = try { clipboard.primaryClip } catch (_: SecurityException) { null }
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        val sensitive = clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
        val label = when {
            text.isNullOrEmpty() -> "剪贴板中没有可粘贴的文本"
            sensitive || directOnly -> "敏感内容 · 点击粘贴"
            else -> text.take(240) + if (text.length > 240) "…" else ""
        }
        view.showContent("剪贴板", view.listContent(listOf(label)) {
            if (!text.isNullOrEmpty() && composition.literal() && currentInputConnection?.commitText(text, 1) == true) view.showPanel(ImeChromeView.Panel.KEYBOARD)
        })
    }
    private fun showPages() {
        val view = chrome ?: return
        val active = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME)
        val layouts = LayoutFileManager.getLayoutOrder(this).mapNotNull { file -> LayoutFileManager.loadLayout(this, file)?.let { file to it } }
        val grid = GridLayout(this).apply { columnCount = 2 }
        layouts.forEach { (file, layout) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 8, 8, 8)
                isFocusable = true
                contentDescription = layout.name + if (file == active) "，当前键盘页" else "，切换键盘页"
                setBackgroundColor(if (file == active) Color.rgb(55,65,88) else Color.rgb(35,38,46))
                addView(TextView(this@IpaBoardService).apply { text = (if (file == active) "✓ " else "") + layout.name; setTextColor(Color.WHITE); textSize = 15f })
                val preview = LinearLayout(this@IpaBoardService).apply { orientation = LinearLayout.VERTICAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
                KeyboardRenderer.render(this@IpaBoardService, preview, layout, (80 * resources.displayMetrics.density).toInt(), Color.LTGRAY)
                addView(preview)
                setOnClickListener {
                    prefs.edit().putString(KEY_ACTIVE_LAYOUT_FILE, file).apply()
                    view.showPanel(ImeChromeView.Panel.KEYBOARD)
                }
            }
            grid.addView(cell, GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(4,4,4,4) })
        }
        view.showContent("键盘页", ScrollView(this).apply { addView(grid) })
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputController.reset()
        composition.start()
        val type = attribute?.inputType ?: 0
        val variation = type and InputType.TYPE_MASK_VARIATION
        directOnly = (type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT ||
            variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        chrome?.showPanel(ImeChromeView.Panel.KEYBOARD)
        refreshCandidates()
    }
    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        clipboard.addPrimaryClipChangedListener(clipboardListener)
        applySettings()
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        composition.externalSelection(newSelStart, newSelEnd, candidatesEnd)
    }
    override fun onEvaluateFullscreenMode() = false
    private fun updatePanelBack(open: Boolean) {
        if (open == panelBackRegistered) return
        if (open) window.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, panelBackCallback
        ) else window.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(panelBackCallback)
        panelBackRegistered = open
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && chrome?.panel != null && chrome?.panel != ImeChromeView.Panel.KEYBOARD) {
            consumedPanelBack = true
            chrome?.showPanel(ImeChromeView.Panel.KEYBOARD); event.startTracking(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && consumedPanelBack) { consumedPanelBack = false; return true }
        return super.onKeyUp(keyCode, event)
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        chrome?.showPanel(ImeChromeView.Panel.KEYBOARD)
        composition.finish(); inputController.reset()
        super.onFinishInputView(finishingInput)
    }
    override fun onFinishInput() { composition.finish(); inputController.reset(); super.onFinishInput() }
    override fun onDestroy() {
        emojiClosed = true
        emojiRequest++
        emojiExecutor.shutdownNow()
        emojiHandler.removeCallbacksAndMessages(null)
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        updatePanelBack(false)
        engines.close(); prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }
}

/** Preserve the IME host layout parameter type. Also used by the layout renderer tests. */
internal fun View.updateKeyboardHeight(heightPx: Int) {
    minimumHeight = heightPx
    val params = layoutParams ?: FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
    params.height = heightPx
    layoutParams = params
}
