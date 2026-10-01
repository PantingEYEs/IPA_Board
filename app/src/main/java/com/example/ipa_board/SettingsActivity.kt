package com.example.ipa_board

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.example.ipa_board.kaomoji.KaomojiManagerActivity

/**
 * Main Activity screen titled "IPA Board Settings".
 * Serves as the primary launcher screen with 4 entries:
 * 1. Keyboard Page
 * 2. Shortcut
 * 3. Emoji
 * 4. 顔文字
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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

        findViewById<View>(R.id.btn_entry_clipboard).setOnClickListener {
            startActivity(Intent(this, ClipboardManagerActivity::class.java))
        }
    }
}
