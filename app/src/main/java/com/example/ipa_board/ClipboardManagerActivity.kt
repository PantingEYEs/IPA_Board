package com.example.ipa_board

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.Toast
import com.example.ipa_board.clipboard.SmsCodeAutoFillManager

class ClipboardManagerActivity : Activity() {

    private lateinit var swQuickPaste: Switch
    private lateinit var swSmsOtp: Switch
    private lateinit var containerSmsOtpModes: View
    private lateinit var rgSmsMode: RadioGroup
    private lateinit var rbSmsModeAutofill: RadioButton
    private lateinit var rbSmsModeBroadcast: RadioButton

    private lateinit var containerOptions: View
    private lateinit var rgRetentionType: RadioGroup
    private lateinit var rbRetentionCustom: RadioButton
    private lateinit var rbRetentionUntilDisappear: RadioButton
    private lateinit var etRetentionSeconds: EditText

    private lateinit var rgUsageType: RadioGroup
    private lateinit var rbUsageCustom: RadioButton
    private lateinit var rbUsageNeverExhausted: RadioButton
    private lateinit var etUsageTimes: EditText

    private lateinit var btnSave: Button

    private val REQUEST_SMS_PERMISSION = 201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clipboard_manager)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        swQuickPaste = findViewById(R.id.sw_quick_paste)
        swSmsOtp = findViewById(R.id.sw_sms_otp)
        containerSmsOtpModes = findViewById(R.id.container_sms_otp_modes)
        rgSmsMode = findViewById(R.id.rg_sms_mode)
        rbSmsModeAutofill = findViewById(R.id.rb_sms_mode_autofill)
        rbSmsModeBroadcast = findViewById(R.id.rb_sms_mode_broadcast)

        containerOptions = findViewById(R.id.container_quick_paste_options)
        rgRetentionType = findViewById(R.id.rg_retention_type)
        rbRetentionCustom = findViewById(R.id.rb_retention_custom)
        rbRetentionUntilDisappear = findViewById(R.id.rb_retention_until_disappear)
        etRetentionSeconds = findViewById(R.id.et_retention_seconds)

        rgUsageType = findViewById(R.id.rg_usage_type)
        rbUsageCustom = findViewById(R.id.rb_usage_custom)
        rbUsageNeverExhausted = findViewById(R.id.rb_usage_never_exhausted)
        etUsageTimes = findViewById(R.id.et_usage_times)

        btnSave = findViewById(R.id.btn_save_clipboard_settings)

        loadSettings()

        swQuickPaste.setOnCheckedChangeListener { _, isChecked ->
            containerOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        swSmsOtp.setOnCheckedChangeListener { _, isChecked ->
            containerSmsOtpModes.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked && rbSmsModeBroadcast.isChecked) {
                if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS_PERMISSION)
                }
            }
        }

        rgSmsMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rb_sms_mode_broadcast) {
                if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS_PERMISSION)
                }
            }
        }

        btnSave.setOnClickListener {
            saveSettings()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                rbSmsModeBroadcast.isChecked = true
                Toast.makeText(this, "SMS permission granted for Broadcast Mode", Toast.LENGTH_SHORT).show()
            } else {
                rbSmsModeAutofill.isChecked = true
                Toast.makeText(this, "SMS permission required for Broadcast Mode. Switched to AutoFill Mode.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(SettingsConstants.PREFS_NAME, MODE_PRIVATE)

        val quickPasteEnabled = prefs.getBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, true)
        swQuickPaste.isChecked = quickPasteEnabled
        containerOptions.visibility = if (quickPasteEnabled) View.VISIBLE else View.GONE

        val smsOtpEnabled = prefs.getBoolean(SettingsConstants.KEY_SMS_OTP_AUTO_COPY_ENABLED, false)
        swSmsOtp.isChecked = smsOtpEnabled
        containerSmsOtpModes.visibility = if (smsOtpEnabled) View.VISIBLE else View.GONE

        val smsMode = prefs.getString(SettingsConstants.KEY_SMS_OTP_MODE, "AUTOFILL") ?: "AUTOFILL"
        if (smsMode == "BROADCAST") {
            val hasSmsPermission = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
            if (hasSmsPermission) {
                rbSmsModeBroadcast.isChecked = true
            } else {
                rbSmsModeAutofill.isChecked = true
            }
        } else {
            rbSmsModeAutofill.isChecked = true
        }

        val retentionType = prefs.getString(SettingsConstants.KEY_QUICK_PASTE_RETENTION_TYPE, "CUSTOM") ?: "CUSTOM"
        if (retentionType == "UNTIL_DISAPPEAR") {
            rbRetentionUntilDisappear.isChecked = true
        } else {
            rbRetentionCustom.isChecked = true
        }

        val retentionSecs = prefs.getInt(SettingsConstants.KEY_QUICK_PASTE_RETENTION_SECONDS, 60)
        etRetentionSeconds.setText(retentionSecs.toString())

        val usageType = prefs.getString(SettingsConstants.KEY_QUICK_PASTE_USAGE_TYPE, "CUSTOM") ?: "CUSTOM"
        if (usageType == "NEVER_EXHAUSTED") {
            rbUsageNeverExhausted.isChecked = true
        } else {
            rbUsageCustom.isChecked = true
        }

        val usageTimes = prefs.getInt(SettingsConstants.KEY_QUICK_PASTE_USAGE_TIMES, 1)
        etUsageTimes.setText(usageTimes.toString())
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences(SettingsConstants.PREFS_NAME, MODE_PRIVATE)
        val quickPasteEnabled = swQuickPaste.isChecked
        val smsOtpEnabled = swSmsOtp.isChecked
        val smsMode = if (rbSmsModeBroadcast.isChecked) "BROADCAST" else "AUTOFILL"

        val retentionType = if (rbRetentionUntilDisappear.isChecked) "UNTIL_DISAPPEAR" else "CUSTOM"
        val retentionSecsStr = etRetentionSeconds.text.toString().trim()
        val retentionSecs = retentionSecsStr.toIntOrNull() ?: 60

        if (quickPasteEnabled && retentionType == "CUSTOM" && (retentionSecs < 0 || retentionSecs > 43200)) {
            Toast.makeText(this, "Retention time must be between 0 and 43200 seconds", Toast.LENGTH_LONG).show()
            return
        }

        val usageType = if (rbUsageNeverExhausted.isChecked) "NEVER_EXHAUSTED" else "CUSTOM"
        val usageTimesStr = etUsageTimes.text.toString().trim()
        val usageTimes = usageTimesStr.toIntOrNull() ?: 1

        if (quickPasteEnabled && usageType == "CUSTOM" && (usageTimes < 0 || usageTimes > 100)) {
            Toast.makeText(this, "Usage count must be between 0 and 100 times", Toast.LENGTH_LONG).show()
            return
        }

        prefs.edit()
            .putBoolean(SettingsConstants.KEY_QUICK_PASTE_ENABLED, quickPasteEnabled)
            .putBoolean(SettingsConstants.KEY_SMS_OTP_AUTO_COPY_ENABLED, smsOtpEnabled)
            .putString(SettingsConstants.KEY_SMS_OTP_MODE, smsMode)
            .putString(SettingsConstants.KEY_QUICK_PASTE_RETENTION_TYPE, retentionType)
            .putInt(SettingsConstants.KEY_QUICK_PASTE_RETENTION_SECONDS, retentionSecs)
            .putString(SettingsConstants.KEY_QUICK_PASTE_USAGE_TYPE, usageType)
            .putInt(SettingsConstants.KEY_QUICK_PASTE_USAGE_TIMES, usageTimes)
            .apply()

        if (smsOtpEnabled && smsMode == "AUTOFILL") {
            SmsCodeAutoFillManager.startAutoFill(this)
        }

        Toast.makeText(this, "Clipboard settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
