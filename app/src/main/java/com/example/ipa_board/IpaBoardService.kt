package com.example.ipa_board

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo

class IpaBoardService : InputMethodService() {
    companion object {
        private const val TAG = "IpaBoardService"
        private const val DEBUG = true
    }

    override fun onCreateInputView(): View? {
        if (DEBUG) {
            Log.d(TAG, "onCreateInputView: Creating input view")
        }
        return null
    }

    override fun onStartInput(
        attribute: EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInput(attribute, restarting)
        if (DEBUG) {
            Log.d(TAG, "onStartInput: Input started (restarting=$restarting, inputType=${attribute?.inputType})"
            )
        }
    }

    override fun onStartInputView(
        editorInfo: EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInputView(editorInfo, restarting)
        if (DEBUG) {
            Log.d(TAG, "onStartInputView: Input view started (restarting=$restarting")

        }
    }
}