package com.example.ipa_board.clipboard

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.widget.Toast
import com.example.ipa_board.SettingsConstants
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.EngineFeature

class SmsOtpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (!EngineSettings.enabled(context, EngineFeature.SMS_OTP)) return

        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(SettingsConstants.KEY_SMS_OTP_AUTO_COPY_ENABLED, false)
        if (!enabled) return

        // Mutual Exclusion: If AutoFill Mode is selected, Broadcast listening & local parsing MUST NOT RUN AT ALL.
        val mode = prefs.getString(SettingsConstants.KEY_SMS_OTP_MODE, "AUTOFILL") ?: "AUTOFILL"
        if (mode == "AUTOFILL") {
            return
        }

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val fullBody = messages.joinToString("") { it.messageBody ?: "" }
        val otpCode = SmsOtpParser.extractOtp(fullBody) ?: return

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("SMS OTP", otpCode)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(context, "Verification code $otpCode copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
