package com.example.ipa_board

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.example.ipa_board.SettingsConstants.DEFAULT_BG_COLOR_HEX
import com.example.ipa_board.SettingsConstants.DEFAULT_KEYBOARD_HEIGHT
import com.example.ipa_board.SettingsConstants.DEFAULT_LAYOUT_FILENAME
import com.example.ipa_board.SettingsConstants.DEFAULT_SYMBOL_COLOR_HEX
import com.example.ipa_board.SettingsConstants.KEY_ACTIVE_LAYOUT_FILE
import com.example.ipa_board.SettingsConstants.KEY_BG_COLOR_HEX
import com.example.ipa_board.SettingsConstants.KEY_KEYBOARD_HEIGHT
import com.example.ipa_board.SettingsConstants.KEY_SYMBOL_COLOR_HEX
import com.example.ipa_board.SettingsConstants.PREFS_NAME

class SettingsActivity : Activity() {

    private lateinit var previewContainer: ViewGroup
    private lateinit var tvHeightValue: TextView
    private lateinit var sbHeight: SeekBar
    private lateinit var spLayouts: Spinner

    private lateinit var rgColorTarget: RadioGroup
    private lateinit var sbHue: SeekBar
    private lateinit var sbSaturation: SeekBar
    private lateinit var sbLightness: SeekBar
    private lateinit var tvHueLabel: TextView
    private lateinit var tvSaturationLabel: TextView
    private lateinit var tvLightnessLabel: TextView

    private val MIN_HEIGHT_DP = 150
    private val IMPORT_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        LayoutFileManager.initDefaultLayout(this)

        previewContainer = findViewById(R.id.keyboard_container)
        spLayouts = findViewById(R.id.sp_layouts)
        tvHeightValue = findViewById(R.id.tv_height_value)
        sbHeight = findViewById(R.id.sb_height)

        rgColorTarget = findViewById(R.id.rg_color_target)
        sbHue = findViewById(R.id.sb_hue)
        sbSaturation = findViewById(R.id.sb_saturation)
        sbLightness = findViewById(R.id.sb_lightness)
        tvHueLabel = findViewById(R.id.tv_hue_label)
        tvSaturationLabel = findViewById(R.id.tv_saturation_label)
        tvLightnessLabel = findViewById(R.id.tv_lightness_label)

        val btnImport = findViewById<Button>(R.id.btn_import)
        val btnExport = findViewById<Button>(R.id.btn_export)
        val btnEdit = findViewById<Button>(R.id.btn_edit)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Color Setup (HSL)
        setupColorControls()

        // 2. Height Setup
        val currentHeightDp = prefs.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT)
        sbHeight.progress = currentHeightDp - MIN_HEIGHT_DP
        tvHeightValue.text = "${currentHeightDp}dp"
        sbHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val newHeightDp = progress + MIN_HEIGHT_DP
                tvHeightValue.text = "${newHeightDp}dp"
                if (fromUser) {
                    prefs.edit().putInt(KEY_KEYBOARD_HEIGHT, newHeightDp).apply()
                    refreshPreview()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3. Layout Setup
        updateLayoutSpinner()
        spLayouts.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val filename = parent?.getItemAtPosition(position) as String
                prefs.edit().putString(KEY_ACTIVE_LAYOUT_FILE, filename).apply()
                refreshPreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 4. Action Buttons
        btnImport.setOnClickListener { startImport() }
        btnExport.setOnClickListener { startExport() }
        btnEdit.setOnClickListener { 
            Toast.makeText(this, "Edit feature coming soon...", Toast.LENGTH_SHORT).show()
        }

        refreshPreview()
    }

    private fun setupColorControls() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        fun updateSlidersFromTarget() {
            val targetKey = if (rgColorTarget.checkedRadioButtonId == R.id.rb_target_bg) KEY_BG_COLOR_HEX else KEY_SYMBOL_COLOR_HEX
            val defaultHex = if (targetKey == KEY_BG_COLOR_HEX) DEFAULT_BG_COLOR_HEX else DEFAULT_SYMBOL_COLOR_HEX
            val hex = prefs.getString(targetKey, defaultHex) ?: defaultHex
            
            val color = try { Color.parseColor(hex) } catch (e: Exception) { Color.BLACK }
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            
            sbHue.progress = hsl[0].toInt()
            sbSaturation.progress = (hsl[1] * 100).toInt()
            sbLightness.progress = (hsl[2] * 100).toInt()
            
            updateLabels(hsl[0], hsl[1], hsl[2])
        }

        rgColorTarget.setOnCheckedChangeListener { _, _ -> updateSlidersFromTarget() }

        val hslListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val h = sbHue.progress.toFloat()
                    val s = sbSaturation.progress.toFloat() / 100f
                    val l = sbLightness.progress.toFloat() / 100f
                    
                    val color = ColorUtils.HSLToColor(floatArrayOf(h, s, l))
                    val hex = String.format("#%06X", 0xFFFFFF and color)
                    
                    val targetKey = if (rgColorTarget.checkedRadioButtonId == R.id.rb_target_bg) KEY_BG_COLOR_HEX else KEY_SYMBOL_COLOR_HEX
                    prefs.edit().putString(targetKey, hex).apply()
                    
                    updateLabels(h, s, l)
                    refreshPreview()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        sbHue.setOnSeekBarChangeListener(hslListener)
        sbSaturation.setOnSeekBarChangeListener(hslListener)
        sbLightness.setOnSeekBarChangeListener(hslListener)
        
        updateSlidersFromTarget()
    }

    private fun updateLabels(h: Float, s: Float, l: Float) {
        tvHueLabel.text = "Hue: ${h.toInt()}°"
        tvSaturationLabel.text = "Saturation: ${(s * 100).toInt()}%"
        tvLightnessLabel.text = "Lightness: ${(l * 100).toInt()}%"
    }

    private fun updateLayoutSpinner() {
        val files = LayoutFileManager.listLayoutFiles(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, files)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spLayouts.adapter = adapter

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeFile = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME)
        val index = files.indexOf(activeFile)
        if (index >= 0) spLayouts.setSelection(index)
    }

    private fun refreshPreview() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bgColorHex = prefs.getString(KEY_BG_COLOR_HEX, DEFAULT_BG_COLOR_HEX) ?: DEFAULT_BG_COLOR_HEX
        val symbolColorHex = prefs.getString(KEY_SYMBOL_COLOR_HEX, DEFAULT_SYMBOL_COLOR_HEX) ?: DEFAULT_SYMBOL_COLOR_HEX
        val heightDp = prefs.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT)
        val layoutFile = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME

        val density = resources.displayMetrics.density
        val heightPx = (heightDp * density).toInt()

        previewContainer.layoutParams.height = heightPx
        previewContainer.requestLayout()

        val bgColor = try { Color.parseColor(bgColorHex) } catch (e: Exception) { Color.BLACK }
        val symbolColor = try { Color.parseColor(symbolColorHex) } catch (e: Exception) { Color.WHITE }
        
        previewContainer.setBackgroundColor(bgColor)

        val layout = LayoutFileManager.loadLayout(this, layoutFile) ?: SettingsConstants.DEFAULT_LAYOUT
        KeyboardRenderer.render(this, previewContainer, layout, heightPx, symbolColor)
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/json"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, IMPORT_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMPORT_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                importFile(uri)
            }
        }
    }

    private fun importFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val json = inputStream.bufferedReader().use { it.readText() }
                val layout = KeyboardLayout.fromJson(json)
                val filename = "imported_${System.currentTimeMillis()}.json"
                LayoutFileManager.saveLayout(this, filename, layout)
                updateLayoutSpinner()
                Toast.makeText(this, "Layout imported!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to import: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startExport() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val filename = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME
        val layout = LayoutFileManager.loadLayout(this, filename)
        if (layout != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, layout.toJson())
                putExtra(Intent.EXTRA_SUBJECT, "IPA Board Layout: $filename")
            }
            startActivity(Intent.createChooser(shareIntent, "Export Layout"))
        }
    }

    private fun isValidHex(color: String): Boolean {
        val hexPattern = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$".toRegex()
        return hexPattern.matches(color)
    }
}
