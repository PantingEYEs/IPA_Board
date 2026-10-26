package com.example.ipa_board

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
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
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.FrameLayout
import android.widget.TextView
import com.example.ipa_board.clipboard.*
import java.io.OutputStream

class ClipboardManagerActivity : AppCompatActivity() {

    private lateinit var swSyncOnStart: Switch
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

    private lateinit var history: ClipboardRepository
    private lateinit var historyPanel: ClipboardPanelView
    private lateinit var historyPreferences: SharedPreferences
    private lateinit var actionMenu: FloatingActionMenu
    private var pendingExportIds: Set<String>? = null
    private val historyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ClipboardRepository.KEY_CLIPBOARD_ITEMS) historyPanel.refreshList()
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            try {
                requireNotNull(contentResolver.openOutputStream(result.data!!.data!!)).use(::writeClipboardExport)
                Toast.makeText(this, R.string.clipboard_export_success, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.clipboard_export_failed, Toast.LENGTH_LONG).show()
            }
        }
        pendingExportIds = null
    }

    private val REQUEST_SMS_PERMISSION = 201

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clipboard_manager)

        val root = findViewById<View>(R.id.clipboard_root)
        root.setOnApplyWindowInsetsListener { view, windowInsets ->
            val insets = windowInsets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
        root.requestApplyInsets()

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        swSyncOnStart = findViewById(R.id.sw_clipboard_sync_on_start)
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
        setupHistory(savedInstanceState)

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

    private fun setupHistory(state: Bundle?) {
        historyPreferences = getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        history = ClipboardRepository(this)
        historyPanel = ClipboardPanelView(this, history, ::editClipboardItem).apply {
            id = R.id.clipboard_history_panel
            managementMode = true
            onSelectionChanged = { updateSelectionUi(it) }
        }
        findViewById<FrameLayout>(R.id.clipboard_history_host).addView(historyPanel)
        actionMenu = FloatingActionMenu(findViewById(R.id.fab_main), findViewById(R.id.overlay_mask),
            findViewById(R.id.fab_menu_container))
        findViewById<Button>(R.id.btn_batch_manage).setOnClickListener {
            actionMenu.close()
            historyPanel.isSelectionMode = !historyPanel.isSelectionMode
            revealHistory()
        }
        findViewById<Button>(R.id.clipboard_export_menu).setOnClickListener { actionMenu.close(); launchClipboardExport() }
        findViewById<Button>(R.id.clipboard_export_selected).setOnClickListener { launchClipboardExport() }
        findViewById<Button>(R.id.clipboard_select_all).setOnClickListener {
            if (historyPanel.getSelectedIds().size < history.getItems().size) historyPanel.selectAll()
            else historyPanel.clearSelection()
        }
        historyPanel.isSelectionMode = state?.getBoolean("clipboard_selection_mode") == true
        historyPanel.restoreSelection(state?.getStringArrayList("clipboard_selected_ids").orEmpty())
        pendingExportIds = state?.getStringArrayList("clipboard_export_ids")?.toSet()
        historyPreferences.registerOnSharedPreferenceChangeListener(historyListener)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    actionMenu.isExpanded -> actionMenu.close()
                    historyPanel.isSelectionMode -> { historyPanel.isSelectionMode = false }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    private fun revealHistory() {
        findViewById<View>(R.id.clipboard_history_host).post {
            findViewById<View>(R.id.clipboard_history_host).requestRectangleOnScreen(
                android.graphics.Rect(0, 0, 1, 1), false)
        }
    }

    private fun updateSelectionUi(count: Int) {
        findViewById<View>(R.id.clipboard_batch_bar).visibility = if (historyPanel.isSelectionMode) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.clipboard_selected_count).text = getString(R.string.clipboard_selected_count, count)
        findViewById<Button>(R.id.clipboard_export_selected).isEnabled = count > 0
        findViewById<Button>(R.id.clipboard_select_all).setText(
            if (count > 0 && count == history.getItems().size) R.string.clipboard_clear_selection else R.string.clipboard_select_all)
        findViewById<Button>(R.id.btn_batch_manage).setText(
            if (historyPanel.isSelectionMode) R.string.clipboard_exit_manage else R.string.clipboard_manage)
    }

    private fun editClipboardItem(item: ClipboardItem) {
        val input = EditText(this).apply {
            id = R.id.clipboard_edit_text
            setText(item.text)
            contentDescription = getString(R.string.clipboard_edit_content)
            minLines = 3; maxLines = 8
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.clipboard_edit_title).setView(input)
            .setNegativeButton(R.string.clipboard_edit_cancel, null).setPositiveButton(R.string.clipboard_edit_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    history.editItem(item.id, item.text, input.text.toString())
                    historyPanel.refreshList()
                    dialog.dismiss()
                } catch (_: Exception) { input.error = getString(R.string.clipboard_edit_failed) }
            }
        }
        dialog.show()
    }

    private fun launchClipboardExport() {
        if (historyPanel.getSelectedIds().isEmpty()) {
            historyPanel.isSelectionMode = true
            revealHistory()
            Toast.makeText(this, R.string.clipboard_select_first, Toast.LENGTH_SHORT).show()
            return
        }
        pendingExportIds = historyPanel.getSelectedIds()
        exportLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "clipboard_history.json")
        })
    }

    /** Shared writer for the system document result and isolated in-memory export checks. */
    internal fun writeClipboardExport(output: OutputStream) {
        val ids = pendingExportIds ?: historyPanel.getSelectedIds()
        output.write(history.exportSelection(ids).toByteArray(Charsets.UTF_8))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("clipboard_selection_mode", historyPanel.isSelectionMode)
        outState.putStringArrayList("clipboard_selected_ids", ArrayList(historyPanel.getSelectedIds()))
        pendingExportIds?.let { outState.putStringArrayList("clipboard_export_ids", ArrayList(it)) }
    }

    override fun onResume() {
        super.onResume()
        if (::historyPanel.isInitialized) historyPanel.refreshList()
    }

    override fun onDestroy() {
        if (::historyPreferences.isInitialized) historyPreferences.unregisterOnSharedPreferenceChangeListener(historyListener)
        super.onDestroy()
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

        swSyncOnStart.isChecked = prefs.getBoolean(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, true)
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
            .putBoolean(SettingsConstants.KEY_CLIPBOARD_SYNC_ON_START, swSyncOnStart.isChecked)
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
