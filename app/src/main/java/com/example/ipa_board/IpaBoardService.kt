package com.example.ipa_board

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.example.ipa_board.ime.*
import com.example.ipa_board.emoji.*
import com.example.ipa_board.kaomoji.*
import com.example.ipa_board.clipboard.*
import com.example.ipa_board.calculator.*
import com.example.ipa_board.SettingsConstants.PREFS_NAME
import com.example.ipa_board.SettingsConstants.KEY_LAYOUT_REVISION
import com.example.ipa_board.SettingsConstants.KEY_BG_COLOR_HEX
import com.example.ipa_board.SettingsConstants.KEY_ACTIVE_LAYOUT_FILE
import com.example.ipa_board.SettingsConstants.KEY_KEYBOARD_HEIGHT
import com.example.ipa_board.SettingsConstants.KEY_SYMBOL_COLOR_HEX
import com.example.ipa_board.SettingsConstants.KEY_SHIFT_SHORTCUTS
import com.example.ipa_board.SettingsConstants.KEY_CTRL_SHORTCUTS
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
    private val emojiRepository by lazy { EmojiCatalogRepository.getInstance(this) }
    private val kaomojiRepository by lazy { KaomojiRepository(this) }
    private val emojiExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val emojiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var emojiRequest = 0L
    private var emojiClosed = false
    private var engineStatus = "Loading Dictionary"
    private var isCalcEngineEnabled = true
    private var directOnly = false
    private var consumedPanelBack = false
    private var panelBackRegistered = false
    private val panelBackCallback = android.window.OnBackInvokedCallback { chrome?.showPanel(ImeChromeView.Panel.KEYBOARD) }
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipboardRepository by lazy { ClipboardRepository(this) }

    private data class QuickPasteState(
        val text: String,
        val timestamp: Long,
        var remainingUses: Int
    )
    private var quickPasteState: QuickPasteState? = null
    private val quickPasteHandler = Handler(Looper.getMainLooper())
    private var quickPasteRunnable: Runnable? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        syncClipboardHistory()
        if (chrome?.panel == ImeChromeView.Panel.CLIPBOARD) showClipboard()
    }
    private fun syncClipboardHistory() {
        val clip = try { clipboard.primaryClip } catch (_: SecurityException) { null }
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        val sensitive = clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
        if (clip != null && !text.isNullOrEmpty()) {
            if (clipboardRepository.syncSystemClip(text, clip.description.timestamp, sensitive) != null) {
                onNewClipCopied(text)
            }
        }
    }

    private fun onNewClipCopied(text: String) {
        val enabled = prefs.getBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, true)
        if (!enabled) {
            quickPasteState = null
            updateQuickPasteStatus()
            return
        }
        val usageType = prefs.getString(SettingsConstants.KEY_QUICK_PASTE_USAGE_TYPE, "CUSTOM") ?: "CUSTOM"
        val usageTimes = prefs.getInt(SettingsConstants.KEY_QUICK_PASTE_USAGE_TIMES, 1)
        val allowedUses = if (usageType == "NEVER_EXHAUSTED") Int.MAX_VALUE else usageTimes

        quickPasteState = QuickPasteState(text, System.currentTimeMillis(), allowedUses)

        quickPasteRunnable?.let { quickPasteHandler.removeCallbacks(it) }
        val retentionType = prefs.getString(SettingsConstants.KEY_QUICK_PASTE_RETENTION_TYPE, "CUSTOM") ?: "CUSTOM"
        val retentionSeconds = prefs.getInt(SettingsConstants.KEY_QUICK_PASTE_RETENTION_SECONDS, 60)
        if (retentionType == "CUSTOM" && retentionSeconds > 0) {
            val runnable = Runnable { updateQuickPasteStatus() }
            quickPasteRunnable = runnable
            quickPasteHandler.postDelayed(runnable, retentionSeconds * 1000L)
        }

        updateQuickPasteStatus()
    }

    private fun clearQuickPasteStatus() {
        val view = chrome ?: return
        view.onStatusClick = null
        when (view.panel) {
            ImeChromeView.Panel.KEYBOARD -> view.status.text = if (directOnly) "Direct Input" else engineStatus
            ImeChromeView.Panel.CLIPBOARD -> view.status.text = "Clipboard"
            else -> Unit
        }
    }

    private fun updateQuickPasteStatus() {
        val view = chrome ?: return
        val state = quickPasteState
        val enabled = prefs.getBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, true)

        if (!enabled || state == null) {
            clearQuickPasteStatus()
            return
        }

        if (view.panel != ImeChromeView.Panel.KEYBOARD && view.panel != ImeChromeView.Panel.CLIPBOARD) {
            return
        }

        val now = System.currentTimeMillis()
        val retentionType = prefs.getString(SettingsConstants.KEY_QUICK_PASTE_RETENTION_TYPE, "CUSTOM") ?: "CUSTOM"
        val retentionSeconds = prefs.getInt(SettingsConstants.KEY_QUICK_PASTE_RETENTION_SECONDS, 60)

        var isValid = state.remainingUses > 0
        if (isValid && retentionType == "CUSTOM") {
            if ((now - state.timestamp) > retentionSeconds * 1000L) {
                isValid = false
            }
        } else if (isValid && retentionType == "UNTIL_DISAPPEAR") {
            val exists = clipboardRepository.getItems().any { it.text == state.text }
            if (!exists) {
                isValid = false
            }
        }

        if (!isValid) {
            quickPasteState = null
            clearQuickPasteStatus()
            return
        }

        val snippet = state.text.replace('\n', ' ').trim().let {
            if (it.length > 18) it.take(18) + "…" else it
        }
        view.status.text = snippet
        view.onStatusClick = quickPasteClick@{
            // Revalidate in case this callback outlives a panel transition or expiry.
            updateQuickPasteStatus()
            if (quickPasteState !== state || state.remainingUses <= 0) return@quickPasteClick
            if (composition.literal()) {
                currentInputConnection?.commitText(state.text, 1)
            }
            val usageType = prefs.getString(SettingsConstants.KEY_QUICK_PASTE_USAGE_TYPE, "CUSTOM") ?: "CUSTOM"
            if (usageType != "NEVER_EXHAUSTED") {
                state.remainingUses--
            }
            updateQuickPasteStatus()
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf(KEY_LAYOUT_REVISION, SettingsConstants.KEY_KEYBOARD_FONT_SIZE, KEY_BG_COLOR_HEX, KEY_ACTIVE_LAYOUT_FILE, KEY_KEYBOARD_HEIGHT, KEY_SYMBOL_COLOR_HEX, KEY_SHIFT_SHORTCUTS, KEY_CTRL_SHORTCUTS,
            SettingsConstants.KEY_QUICK_PASTE_ENABLED, SettingsConstants.KEY_QUICK_PASTE_RETENTION_TYPE, SettingsConstants.KEY_QUICK_PASTE_RETENTION_SECONDS,
            SettingsConstants.KEY_QUICK_PASTE_USAGE_TYPE, SettingsConstants.KEY_QUICK_PASTE_USAGE_TIMES)) {
            if (key == KEY_ACTIVE_LAYOUT_FILE || key == KEY_LAYOUT_REVISION) inputController.reset()
            applySettings()
            updateQuickPasteStatus()
            if (key == KEY_LAYOUT_REVISION && chrome?.panel == ImeChromeView.Panel.PAGES) showPages()
        }
    }
    override fun onCreate() {
        super.onCreate()
        LayoutFileManager.initDefaultLayout(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        composition = CompositionController({ currentInputConnection }, { id, raw ->
            engines.query(id, if (directOnly) "" else raw, if (directOnly) "" else composition.beforeCursor)
        }, { refreshCandidates() })
        engines = EngineCoordinator(this) { id, candidates, state ->
            engineStatus = state
            composition.acceptResults(id, candidates)
            refreshCandidates()
        }
    }
    override fun onCreateInputView(): View {
        return ImeChromeView(this).also { view ->
            chrome = view
            view.onCalculatorToggle = {
                isCalcEngineEnabled = !isCalcEngineEnabled
                chrome?.setCalculatorEnabled(isCalcEngineEnabled)
                refreshCandidates()
            }
            view.onCandidate = { candidate, generation -> if (composition.select(candidate, generation)) view.showPanel(ImeChromeView.Panel.KEYBOARD) }
            view.onLiteral = { if (composition.literal()) view.showPanel(ImeChromeView.Panel.KEYBOARD) }
            view.onPanel = { panel ->
                updatePanelBack(panel != ImeChromeView.Panel.KEYBOARD)
                when (panel) {
                    ImeChromeView.Panel.CLIPBOARD -> {
                        showClipboard()
                        updateQuickPasteStatus()
                    }
                    ImeChromeView.Panel.PAGES -> showPages()
                    ImeChromeView.Panel.EMOJI -> showEmoji()
                    ImeChromeView.Panel.KAOMOJI -> showKaomojiPanel()
                    ImeChromeView.Panel.KEYBOARD -> updateQuickPasteStatus()
                    else -> Unit
                }
            }
            applySettings(); refreshCandidates()
            engines.start()
        }
    }
    private fun getMathCandidate(raw: String): Candidate? {
        if (!isCalcEngineEnabled) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val mathOps = setOf('+', '-', '*', '/', '×', '÷')
        val opsCount = trimmed.count { it in mathOps }
        if (opsCount == 0) return null

        if (opsCount == 1 && trimmed.startsWith("-") && trimmed.substring(1).all { it.isDigit() || it == '.' }) {
            return null
        }

        val hasDigit = trimmed.any { it.isDigit() }
        if (!hasDigit) return null

        val evalRes = CalculatorEvaluator.evaluate(trimmed)
        if (evalRes.isEmpty() || evalRes == "Error" || evalRes == "除数不能为0") {
            return null
        }

        val firstNum = trimmed.takeWhile { it.isDigit() || it == '.' || it == '-' }
        if (evalRes == firstNum || evalRes == trimmed) {
            return null
        }

        return Candidate(evalRes, "∑", rank = -1)
    }

    private fun refreshCandidates() {
        val raw = composition.raw
        val baseStatus = if (directOnly) "Direct Input" else engineStatus

        val mathCand = getMathCandidate(raw)
        val displayCandidates = if (mathCand != null) {
            listOf(mathCand) + composition.candidates.filter { it.text != mathCand.text }
        } else {
            composition.candidates
        }

        composition.updateCandidates(displayCandidates)
        chrome?.render(raw, displayCandidates, baseStatus, composition.revision)
        updateQuickPasteStatus()
    }


    private fun applySettings() {
        val view = chrome ?: return
        inputController.shiftShortcuts = SettingsConstants.parseShortcutsJson(prefs.getString(KEY_SHIFT_SHORTCUTS, null))
        inputController.ctrlShortcuts = SettingsConstants.parseShortcutsJson(prefs.getString(KEY_CTRL_SHORTCUTS, null))
        val density = resources.displayMetrics.density
        val availableDp = (resources.displayMetrics.heightPixels / density - 180).toInt().coerceAtLeast(100)
        val height = (prefs.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT).coerceIn(100, availableDp) * density).toInt()
        view.setKeyboardHeight(height)
        fun color(key: String, fallback: String) = try { Color.parseColor(prefs.getString(key, fallback)) } catch (_: IllegalArgumentException) { Color.parseColor(fallback) }
        view.keyboardHost.setBackgroundColor(color(KEY_BG_COLOR_HEX, DEFAULT_BG_COLOR_HEX))
        val layout = LayoutFileManager.activeLayout(this)
        KeyboardRenderer.render(this, view.keyboardHost, layout, height, color(KEY_SYMBOL_COLOR_HEX, DEFAULT_SYMBOL_COLOR_HEX),
            inputController.shiftEnabled, inputController.ctrlEnabled,
            showKeyPreview = true,
            fontSizeSp = prefs.getInt(SettingsConstants.KEY_KEYBOARD_FONT_SIZE, SettingsConstants.DEFAULT_KEYBOARD_FONT_SIZE),
            onKeyQuickSwipeItemClick = { _, _, slot, item -> handleLongPressItem(slot, item) },
            onKeyLongItemClick = { _, _, slot, item -> handleLongPressItem(slot, item) },
            onKeyLongClick = { _, _, slot -> handleLongPress(slot) }) { _, _, slot -> handleKey(slot) }
    }

    private fun handleLongPressItem(slot: KeySlot, item: LongPressItem) {
        if (item.action == KeyAction.TEXT && item.text.isNotEmpty()) {
            if (!directOnly) {
                composition.input(item.text)
                if (inputController.consumeSingleShift()) applySettings()
            } else {
                val ic = currentInputConnection ?: return
                if (ic.commitText(item.text, 1)) {
                    if (inputController.consumeSingleShift()) applySettings()
                }
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
            KeyAction.KAOMOJI -> {
                chrome?.showPanel(if (chrome?.panel == ImeChromeView.Panel.KAOMOJI) ImeChromeView.Panel.KEYBOARD else ImeChromeView.Panel.KAOMOJI)
                return
            }
            KeyAction.CALCULATOR -> {
                isCalcEngineEnabled = !isCalcEngineEnabled
                chrome?.setCalculatorEnabled(isCalcEngineEnabled)
                refreshCandidates()
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

        val slotText = slot.text
        val activeShortcut = when {
            beforeCtrl && slotText.isNotEmpty() -> inputController.ctrlShortcuts[slotText] ?: inputController.ctrlShortcuts[slotText.lowercase(Locale.ROOT)]
            beforeShift && slotText.isNotEmpty() -> inputController.shiftShortcuts[slotText] ?: inputController.shiftShortcuts[slotText.lowercase(Locale.ROOT)]
            else -> null
        }
        if (activeShortcut != null) {
            fun consumeModifiers() {
                inputController.consumeSingleShift()
                if (beforeCtrl) inputController.handle(KeySlot(1f, action = KeyAction.CTRL), null, null)
                applySettings()
            }
            when (activeShortcut) {
                KeyAction.EMOJI -> { consumeModifiers(); chrome?.showPanel(ImeChromeView.Panel.EMOJI); return }
                KeyAction.KAOMOJI -> { consumeModifiers(); chrome?.showPanel(if (chrome?.panel == ImeChromeView.Panel.KAOMOJI) ImeChromeView.Panel.KEYBOARD else ImeChromeView.Panel.KAOMOJI); return }
                KeyAction.CALCULATOR -> {
                    consumeModifiers()
                    isCalcEngineEnabled = !isCalcEngineEnabled
                    chrome?.setCalculatorEnabled(isCalcEngineEnabled)
                    refreshCandidates()
                    return
                }
                KeyAction.CANDIDATES -> { consumeModifiers(); chrome?.showPanel(if (chrome?.panel == ImeChromeView.Panel.CANDIDATES) ImeChromeView.Panel.KEYBOARD else ImeChromeView.Panel.CANDIDATES); return }
                KeyAction.CLIPBOARD -> { consumeModifiers(); chrome?.showPanel(if (chrome?.panel == ImeChromeView.Panel.CLIPBOARD) ImeChromeView.Panel.KEYBOARD else ImeChromeView.Panel.CLIPBOARD); return }
                KeyAction.PAGES -> { consumeModifiers(); chrome?.showPanel(if (chrome?.panel == ImeChromeView.Panel.PAGES) ImeChromeView.Panel.KEYBOARD else ImeChromeView.Panel.PAGES); return }
                KeyAction.PREV_PAGE -> {
                    consumeModifiers()
                    val newPage = LayoutFileManager.switchPage(this, forward = false)
                    val name = LayoutFileManager.loadLayout(this, newPage)?.name ?: newPage
                    Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
                    return
                }
                KeyAction.NEXT_PAGE -> {
                    consumeModifiers()
                    val newPage = LayoutFileManager.switchPage(this, forward = true)
                    val name = LayoutFileManager.loadLayout(this, newPage)?.name ?: newPage
                    Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
                    return
                }
                else -> {}
            }
        }

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
                slot.action == KeyAction.TEXT && text != " " && text.isNotEmpty() -> {
                    composition.input(text)
                    if (inputController.consumeSingleShift()) applySettings()
                    return
                }
            }
        }
        if (!isModifier && !composition.literal()) return
        if (!inputController.handle(slot, ic, currentInputEditorInfo)) Toast.makeText(this, R.string.unsupported_shortcut, Toast.LENGTH_SHORT).show()
        if (!directOnly && !isModifier) composition.refreshContext()
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
                    picker.refreshMostCommonly()
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

    private fun showKaomojiPanel() {
        val view = chrome ?: return
        val panelView = KaomojiPanelView(this, kaomojiRepository) { item ->
            if (composition.literal() && currentInputConnection?.commitText(item.text, 1) == true) {
                // Keep panel open so user can input multiple kaomojis
            }
        }
        val statusView = KaomojiStatusView(this, panelView) {
            showKaomojiFilterPopup(panelView, view.statusContainer)
        }
        view.setStatusCustomView(statusView)
        view.onStatusClick = {
            showKaomojiFilterPopup(panelView, view.statusContainer)
        }
        view.showContent("顔文字", panelView)
    }

    private fun showKaomojiFilterPopup(panelView: KaomojiPanelView, anchorView: View) {
        val context = this
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // Section 1: Color Tags
        val tvColorHeader = TextView(context).apply {
            text = "Color Tags:"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 11f
        }
        root.addView(tvColorHeader)

        val colorScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }

        val colorList = listOf(
            "Red" to "#A85555",
            "Orange" to "#A3622D",
            "Yellow" to "#998436",
            "Green" to "#4E8058",
            "Cyan" to "#3F7E85",
            "Blue" to "#466580",
            "Purple" to "#784E82"
        )

        val colorChips = mutableMapOf<String, TextView>()
        val tagChips = mutableListOf<Pair<String, TextView>>()
        var chipNormalOrder: TextView? = null
        var chipReversedOrder: TextView? = null

        fun updatePopupUI() {
            colorList.forEach { (enName, hex) ->
                val chip = colorChips[enName] ?: return@forEach
                val isSelected = panelView.isColorTagSelected(enName)
                chip.text = if (isSelected) "✓" else ""
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(hex))
                    if (isSelected) {
                        setStroke(dp(2), Color.WHITE)
                    }
                }
                chip.background = bg
                chip.alpha = if (isSelected) 1.0f else 0.45f
            }

            tagChips.forEach { (tag, chip) ->
                val isSelected = panelView.isTagSelected(tag)
                chip.text = if (isSelected && tag != "All") "✓ $tag" else tag
                val bg = GradientDrawable().apply {
                    setColor(if (isSelected) Color.parseColor("#38383A") else Color.parseColor("#222222"))
                    cornerRadius = dp(12).toFloat()
                    if (isSelected) setStroke(dp(1), Color.parseColor("#007AFF"))
                }
                chip.background = bg
                chip.setTextColor(if (isSelected) Color.WHITE else Color.LTGRAY)
            }

            val isRev = panelView.isReversed
            chipNormalOrder?.let { chip ->
                val bg = GradientDrawable().apply {
                    setColor(if (!isRev) Color.parseColor("#38383A") else Color.parseColor("#222222"))
                    cornerRadius = dp(12).toFloat()
                    if (!isRev) setStroke(dp(1), Color.parseColor("#007AFF"))
                }
                chip.background = bg
                chip.setTextColor(if (!isRev) Color.WHITE else Color.LTGRAY)
            }
            chipReversedOrder?.let { chip ->
                val bg = GradientDrawable().apply {
                    setColor(if (isRev) Color.parseColor("#38383A") else Color.parseColor("#222222"))
                    cornerRadius = dp(12).toFloat()
                    if (isRev) setStroke(dp(1), Color.parseColor("#007AFF"))
                }
                chip.background = bg
                chip.setTextColor(if (isRev) Color.WHITE else Color.LTGRAY)
            }
        }

        colorList.forEach { (enName, hex) ->
            val size = dp(26)
            val chip = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(0, 0, dp(8), 0)
                }

                setOnClickListener {
                    panelView.toggleColorTag(enName)
                    updatePopupUI()
                }
            }
            colorChips[enName] = chip
            colorRow.addView(chip)
        }
        colorScroll.addView(colorRow)
        root.addView(colorScroll)

        // Section 2: Tag Categories
        val tvTagHeader = TextView(context).apply {
            text = "Tag Categories:"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 11f
        }
        root.addView(tvTagHeader)

        val tagScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val tagRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }

        val availableTags = panelView.getAvailableTags()

        availableTags.forEach { tag ->
            val chip = TextView(context).apply {
                text = tag
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(6), 0) }

                setOnClickListener {
                    panelView.toggleTag(tag)
                    updatePopupUI()
                }
            }
            tagChips.add(tag to chip)
            tagRow.addView(chip)
        }
        tagScroll.addView(tagRow)
        root.addView(tagScroll)

        // Section 3: Sort Order
        val tvOrderHeader = TextView(context).apply {
            text = "Sort Order:"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 11f
        }
        root.addView(tvOrderHeader)

        val orderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }

        chipNormalOrder = TextView(context).apply {
            text = "Normal"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(6), 0) }

            setOnClickListener {
                if (panelView.isReversed) panelView.toggleReversed()
                updatePopupUI()
            }
        }
        orderRow.addView(chipNormalOrder)

        chipReversedOrder = TextView(context).apply {
            text = "Reversed"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, dp(6), 0) }

            setOnClickListener {
                if (!panelView.isReversed) panelView.toggleReversed()
                updatePopupUI()
            }
        }
        orderRow.addView(chipReversedOrder)
        root.addView(orderRow)

        // Section 4: Reset Button
        val btnReset = Button(context).apply {
            text = "Reset Filters"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(28)
            ).apply { gravity = Gravity.END }
            setOnClickListener {
                panelView.resetFilters()
                updatePopupUI()
            }
        }
        root.addView(btnReset)

        updatePopupUI()

        val popup = PopupWindow(
            root,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        popup.showAsDropDown(anchorView)
    }



    private fun showClipboard() {
        val view = chrome ?: return
        syncClipboardHistory()
        val panelView = ClipboardPanelView(this, clipboardRepository) { item ->
            if (composition.literal() && currentInputConnection?.commitText(item.text, 1) == true) {
                view.showPanel(ImeChromeView.Panel.KEYBOARD)
            }
        }
        view.showContent("Clipboard", panelView)
    }
    private fun showPages() {
        val view = chrome ?: return
        val active = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME)
        val layouts = LayoutFileManager.getLayoutOrder(this).mapNotNull { file -> LayoutFileManager.loadLayout(this, file)?.let { file to it } }
        val grid = GridLayout(this).apply { columnCount = 2 }
        layouts.forEach { (file, layout) ->
            val pageName = file.removeSuffix(".json")
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 8, 8, 8)
                isFocusable = true
                contentDescription = pageName + if (file == active) ", Current Page" else ", Switch Page"
                setBackgroundColor(if (file == active) Color.rgb(55,65,88) else Color.rgb(35,38,46))
                addView(TextView(this@IpaBoardService).apply { text = (if (file == active) "✓ " else "") + pageName; setTextColor(Color.WHITE); textSize = 15f })
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
        view.showContent("Keyboard Pages", ScrollView(this).apply { addView(grid) })
    }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputController.reset()
        val type = attribute?.inputType ?: 0
        val variation = type and InputType.TYPE_MASK_VARIATION
        directOnly = (type and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT ||
            variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        composition.start()
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
        if (composition.raw.isNotEmpty() || oldSelStart != newSelStart || oldSelEnd != newSelEnd)
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
