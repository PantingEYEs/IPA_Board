package com.example.ipa_board

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import kotlin.math.ceil
import com.example.ipa_board.kaomoji.KaomojiManagerActivity

/**
 * Main Activity screen titled "IPA Board Settings".
 * Serves as the primary launcher screen with 6 entries:
 * 1. Keyboard
 * 2. Shortcut
 * 3. Emoji
 * 4. 顔文字
 * 5. Clipboard
 * 6. Engine
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val entrySymbols = mapOf(
            R.id.icon_entry_keyboard_page to KeyAction.PAGES.keyLabel,
            R.id.icon_entry_shortcut to "⌘",
            R.id.icon_entry_emoji to KeyAction.EMOJI.keyLabel,
            R.id.icon_entry_kaomoji to KeyAction.KAOMOJI.keyLabel,
            R.id.icon_entry_clipboard to KeyAction.CLIPBOARD.keyLabel,
            R.id.icon_entry_engine to "⚙"
        )
        entrySymbols.forEach { (id, symbol) ->
            // Request text presentation for symbols that also have an emoji variant.
            findViewById<TextView>(id).text = if (symbol.endsWith("\uFE0E")) symbol else symbol + "\uFE0E"
        }

        val scroll = findViewById<View>(R.id.settings_scroll)
        val topSpace = findViewById<View>(R.id.settings_top_space)
        scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val spacing = ((bottom - top) * 0.12f).toInt()
            if (topSpace.layoutParams.height != spacing) {
                topSpace.layoutParams = topSpace.layoutParams.apply { height = spacing }
            }
        }

        val header = findViewById<View>(R.id.settings_header)
        header.post {
            // Scale the original content height, including the user's font size setting.
            header.layoutParams = header.layoutParams.apply {
                height = ceil(header.height * 1.25).toInt()
            }
        }

        findViewById<View>(R.id.btn_entry_keyboard_page).setOnClickListener {
            startActivity(Intent(this, KeyboardPageActivity::class.java))
        }

        findViewById<View>(R.id.btn_entry_shortcut).setOnClickListener {
            startActivity(Intent(this, ShortcutManagerActivity::class.java))
        }

        findViewById<View>(R.id.btn_entry_emoji).setOnClickListener {
            startActivity(Intent(this, EmojiManagerActivity::class.java))
        }

        findViewById<View>(R.id.btn_entry_kaomoji).setOnClickListener {
            startActivity(Intent(this, KaomojiManagerActivity::class.java))
        }

        findViewById<View>(R.id.btn_entry_engine).setOnClickListener {
            startActivity(Intent(this, EngineManagementActivity::class.java))
        }

        findViewById<View>(R.id.btn_entry_clipboard).setOnClickListener {
            startActivity(Intent(this, ClipboardManagerActivity::class.java))
        }
    }
}
