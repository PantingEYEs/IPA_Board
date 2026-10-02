package com.example.ipa_board

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Empty, test-owned editor for real IME integration. Not included in release builds. */
class ImeTestEditorActivity : Activity() {
    lateinit var editor: EditText
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = EditText(this).apply { hint = "IME integration test"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        setContentView(editor)
        editor.requestFocus()
        editor.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }, 300)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) editor.post {
            getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
