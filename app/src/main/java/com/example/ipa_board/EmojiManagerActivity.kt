package com.example.ipa_board

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.example.ipa_board.emoji.EmojiCatalogRepository
import com.example.ipa_board.emoji.EmojiUpdateManager

class EmojiManagerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emoji)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        setupEmojiUpdateControls()
    }

    private fun setupEmojiUpdateControls() {
        val tvEmojiStatus = findViewById<TextView>(R.id.tv_emoji_status)
        val btnUpdateEmoji = findViewById<Button>(R.id.btn_update_emoji)

        fun updateEmojiStatusText() {
            try {
                val catalog = EmojiCatalogRepository.getInstance(this).load()
                tvEmojiStatus.text = getString(R.string.emoji_current_status, catalog.version, catalog.entries.size)
            } catch (e: Exception) {
                tvEmojiStatus.text = "Unable to load emoji status: ${e.message}"
            }
        }

        updateEmojiStatusText()

        btnUpdateEmoji.setOnClickListener {
            btnUpdateEmoji.isEnabled = false
            Toast.makeText(this, R.string.emoji_updating, Toast.LENGTH_SHORT).show()
            EmojiUpdateManager.updateEmojiCatalog(this) { result ->
                btnUpdateEmoji.isEnabled = true
                result.fold(
                    onSuccess = { catalog ->
                        updateEmojiStatusText()
                        Toast.makeText(this, getString(R.string.emoji_update_success, catalog.version, catalog.entries.size), Toast.LENGTH_LONG).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(this, getString(R.string.emoji_update_failed, error.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }
}
