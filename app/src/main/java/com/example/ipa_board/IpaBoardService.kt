package com.example.ipa_board

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.example.ipa_board.SettingsConstants.DEFAULT_BG_COLOR_HEX
import com.example.ipa_board.SettingsConstants.DEFAULT_KEYBOARD_HEIGHT
import com.example.ipa_board.SettingsConstants.DEFAULT_LAYOUT_FILENAME
import com.example.ipa_board.SettingsConstants.DEFAULT_SYMBOL_COLOR_HEX
import com.example.ipa_board.SettingsConstants.KEY_ACTIVE_LAYOUT_FILE
import com.example.ipa_board.SettingsConstants.KEY_BG_COLOR_HEX
import com.example.ipa_board.SettingsConstants.KEY_KEYBOARD_HEIGHT
import com.example.ipa_board.SettingsConstants.KEY_SYMBOL_COLOR_HEX
import com.example.ipa_board.SettingsConstants.PREFS_NAME

class IpaBoardService : InputMethodService() {

    private var keyboardView: ViewGroup? = null
    private lateinit var prefs: SharedPreferences

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsConstants.KEY_LAYOUT_REVISION || key == KEY_BG_COLOR_HEX || key == KEY_ACTIVE_LAYOUT_FILE || key == KEY_KEYBOARD_HEIGHT || key == KEY_SYMBOL_COLOR_HEX) {
            applySettings()
        }
    }

    override fun onCreate() {
        super.onCreate()
        LayoutFileManager.initDefaultLayout(this)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View? {
        val root = layoutInflater.inflate(R.layout.keyboard_view, null)
        keyboardView = root as? ViewGroup
        applySettings()
        return root
    }

    private fun applySettings() {
        val container = keyboardView ?: return
        
        // 1. Height
        val heightDp = prefs.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT)
        val density = resources.displayMetrics.density
        val heightPx = (heightDp * density).toInt()

        container.updateKeyboardHeight(heightPx)
        
        // 2. Background Color
        val colorHex = prefs.getString(KEY_BG_COLOR_HEX, DEFAULT_BG_COLOR_HEX) ?: DEFAULT_BG_COLOR_HEX
        try {
            container.setBackgroundColor(Color.parseColor(colorHex))
        } catch (e: Exception) {
            container.setBackgroundColor(Color.parseColor(DEFAULT_BG_COLOR_HEX))
        }

        // 3. Symbol Color
        val symbolColorHex = prefs.getString(KEY_SYMBOL_COLOR_HEX, DEFAULT_SYMBOL_COLOR_HEX) ?: DEFAULT_SYMBOL_COLOR_HEX
        val symbolColor = try { Color.parseColor(symbolColorHex) } catch (e: Exception) { Color.WHITE }

        // 4. Layout File
        val layoutFile = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME
        val layout = LayoutFileManager.loadLayout(this, layoutFile) ?: SettingsConstants.DEFAULT_LAYOUT
        KeyboardRenderer.render(this, container, layout, heightPx, symbolColor) { _, _, slot ->
            if (slot.text.isNotEmpty()) currentInputConnection?.commitText(slot.text, 1)
        }
        
        container.requestLayout()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
    }

    override fun onStartInputView(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        applySettings()
    }
}

/** Keep the IME host's layout parameter type when refreshing an attached input view. */
internal fun View.updateKeyboardHeight(heightPx: Int) {
    minimumHeight = heightPx
    val params = layoutParams ?: FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        heightPx
    )
    params.height = heightPx
    layoutParams = params
}
