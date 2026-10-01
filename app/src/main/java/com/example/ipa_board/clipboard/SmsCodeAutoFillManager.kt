package com.example.ipa_board.clipboard

import android.content.Context
import com.google.android.gms.auth.api.phone.SmsRetriever

object SmsCodeAutoFillManager {

    fun startAutoFill(context: Context, callback: ((Boolean, String?) -> Unit)? = null) {
        try {
            val client = SmsRetriever.getClient(context)
            client.startSmsRetriever()
                .addOnSuccessListener {
                    callback?.invoke(true, null)
                }
                .addOnFailureListener { e ->
                    callback?.invoke(false, e.message)
                }
        } catch (e: Exception) {
            callback?.invoke(false, e.message)
        }
    }
}
