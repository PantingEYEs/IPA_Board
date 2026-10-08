package com.example.ipa_board.clipboard

import android.content.Context
import com.example.ipa_board.diagnostics.*
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
                    // DIAGNOSTICS: Report only startup failure; preserve the optional failure callback.
                    AppDiagnostics.configure(context)
                    AppDiagnostics.failure(DiagnosticComponent.SMS, DiagnosticStage.INITIALIZE, e)
                    callback?.invoke(false, e.message)
                }
        } catch (e: Exception) {
            // DIAGNOSTICS: Report no SMS content; preserve the optional failure callback.
            AppDiagnostics.configure(context)
            AppDiagnostics.failure(DiagnosticComponent.SMS, DiagnosticStage.INITIALIZE, e)
            callback?.invoke(false, e.message)
        }
    }
}
