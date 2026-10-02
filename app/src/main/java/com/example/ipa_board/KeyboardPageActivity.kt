package com.example.ipa_board

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import com.example.ipa_board.SettingsConstants.DEFAULT_BG_COLOR_HEX
import com.example.ipa_board.SettingsConstants.DEFAULT_KEYBOARD_HEIGHT
import com.example.ipa_board.SettingsConstants.DEFAULT_LAYOUT_FILENAME
import com.example.ipa_board.SettingsConstants.DEFAULT_SYMBOL_COLOR_HEX
import com.example.ipa_board.SettingsConstants.KEY_ACTIVE_LAYOUT_FILE
import com.example.ipa_board.SettingsConstants.KEY_BG_COLOR_HEX
import com.example.ipa_board.SettingsConstants.KEY_KEYBOARD_HEIGHT
import com.example.ipa_board.SettingsConstants.KEY_SYMBOL_COLOR_HEX
import com.example.ipa_board.SettingsConstants.PREFS_NAME

class KeyboardPageActivity : Activity() {

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
    private val EXPORT_REQUEST_CODE = 124
    private var pendingExport: String? = null
    private data class ClearedLayout(val filename: String, val original: KeyboardLayout)
    private var clearedLayout: ClearedLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString("pending_export")
        clearedLayout = lastNonConfigurationInstance as? ClearedLayout
        setContentView(R.layout.activity_keyboard_page)

        LayoutFileManager.initDefaultLayout(this)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

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

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

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

        val fontSizeSlider = findViewById<SeekBar>(R.id.sb_font_size)
        val fontSizeValue = findViewById<TextView>(R.id.tv_font_size_value)
        val currentFontSize = prefs.getInt(SettingsConstants.KEY_KEYBOARD_FONT_SIZE,
            SettingsConstants.DEFAULT_KEYBOARD_FONT_SIZE)
            .coerceIn(SettingsConstants.MIN_KEYBOARD_FONT_SIZE, SettingsConstants.MAX_KEYBOARD_FONT_SIZE)
        fontSizeSlider.max = SettingsConstants.MAX_KEYBOARD_FONT_SIZE - SettingsConstants.MIN_KEYBOARD_FONT_SIZE
        fontSizeSlider.progress = currentFontSize - SettingsConstants.MIN_KEYBOARD_FONT_SIZE
        fontSizeValue.text = "${currentFontSize}sp"
        fontSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = progress + SettingsConstants.MIN_KEYBOARD_FONT_SIZE
                fontSizeValue.text = "${size}sp"
                if (fromUser) {
                    prefs.edit().putInt(SettingsConstants.KEY_KEYBOARD_FONT_SIZE, size).apply()
                    refreshPreview()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3. Layout Setup
        updateLayoutSpinner()

        // 4. Action Buttons
        btnImport.setOnClickListener { startImport() }
        btnExport.setOnClickListener { startExport() }
        findViewById<Button>(R.id.btn_rename_layout).setOnClickListener { showRenameLayoutDialog() }
        findViewById<Button>(R.id.btn_reorder_pages).setOnClickListener { showReorderPagesDialog() }
        findViewById<Button>(R.id.btn_new_layout).setOnClickListener { showNewLayoutDialog() }
        findViewById<Button>(R.id.btn_delete_layout).setOnClickListener { deleteCurrentLayout() }
        findViewById<Button>(R.id.btn_clear_layout).setOnClickListener { clearCurrentLayout() }
        findViewById<Button>(R.id.btn_undo_clear).setOnClickListener { undoClear() }
        btnEdit.setOnClickListener { 
            showKeyEditor(0, 0)
        }

        refreshPreview()
    }

    private fun setupColorControls() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        
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
        val files = LayoutFileManager.getLayoutOrder(this)
        val displayNames = files.map { it.removeSuffix(".json") }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spLayouts.onItemSelectedListener = null
        spLayouts.adapter = adapter

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val activeFile = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME
        val index = files.indexOf(activeFile)
        if (index >= 0) spLayouts.setSelection(index)
        spLayouts.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in files.indices) {
                    val selectedFile = files[position]
                    prefs.edit().putString(KEY_ACTIVE_LAYOUT_FILE, selectedFile).apply()
                    refreshPreview()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showReorderPagesDialog() {
        val currentOrder = LayoutFileManager.getLayoutOrder(this).toMutableList()
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        scroll.addView(container)

        fun refreshContainer(dialogView: LinearLayout) {
            dialogView.removeAllViews()
            currentOrder.forEachIndexed { index, filename ->
                val displayName = filename.removeSuffix(".json")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                }
                val title = TextView(this).apply {
                    text = "${index + 1}. $displayName"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val btnUp = Button(this).apply {
                    text = "▲"
                    textSize = 12f
                    isEnabled = index > 0
                    setOnClickListener {
                        val temp = currentOrder[index]
                        currentOrder[index] = currentOrder[index - 1]
                        currentOrder[index - 1] = temp
                        refreshContainer(dialogView)
                    }
                }
                val btnDown = Button(this).apply {
                    text = "▼"
                    textSize = 12f
                    isEnabled = index < currentOrder.size - 1
                    setOnClickListener {
                        val temp = currentOrder[index]
                        currentOrder[index] = currentOrder[index + 1]
                        currentOrder[index + 1] = temp
                        refreshContainer(dialogView)
                    }
                }
                row.addView(title)
                row.addView(btnUp, LinearLayout.LayoutParams((48 * resources.displayMetrics.density).toInt(), (40 * resources.displayMetrics.density).toInt()))
                row.addView(btnDown, LinearLayout.LayoutParams((48 * resources.displayMetrics.density).toInt(), (40 * resources.displayMetrics.density).toInt()))
                dialogView.addView(row)
            }
        }

        refreshContainer(container)

        AlertDialog.Builder(this)
            .setTitle(R.string.reorder_pages_title)
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                LayoutFileManager.saveLayoutOrder(this, currentOrder)
                updateLayoutSpinner()
                refreshPreview()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshPreview() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val bgColorHex = prefs.getString(KEY_BG_COLOR_HEX, DEFAULT_BG_COLOR_HEX) ?: DEFAULT_BG_COLOR_HEX
        val symbolColorHex = prefs.getString(KEY_SYMBOL_COLOR_HEX, DEFAULT_SYMBOL_COLOR_HEX) ?: DEFAULT_SYMBOL_COLOR_HEX
        val heightDp = prefs.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT)
        LayoutFileManager.activeLayout(this)
        val layoutFile = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME
        val hasPage = layoutFile in LayoutFileManager.listLayoutFiles(this)
        previewContainer.visibility = if (hasPage) View.VISIBLE else View.GONE

        findViewById<Button>(R.id.btn_delete_layout).apply {
            isEnabled = hasPage
            setText(R.string.delete_layout)
        }
        val density = resources.displayMetrics.density
        val heightPx = (heightDp * density).toInt()

        previewContainer.layoutParams.height = heightPx
        previewContainer.requestLayout()

        val bgColor = try { Color.parseColor(bgColorHex) } catch (e: Exception) { Color.BLACK }
        val symbolColor = try { Color.parseColor(symbolColorHex) } catch (e: Exception) { Color.WHITE }
        
        previewContainer.setBackgroundColor(bgColor)

        val layout = LayoutFileManager.loadLayout(this, layoutFile) ?: SettingsConstants.DEFAULT_LAYOUT
        val displayName = if (hasPage) layoutFile.removeSuffix(".json") else ""
        findViewById<TextView>(R.id.tv_layout_name).text = getString(R.string.current_page_name, displayName)
        if (clearedLayout?.filename != layoutFile) clearedLayout = null
        findViewById<Button>(R.id.btn_undo_clear).visibility = if (clearedLayout != null) View.VISIBLE else View.GONE
        KeyboardRenderer.render(this, previewContainer, layout, heightPx, symbolColor,
            fontSizeSp = prefs.getInt(SettingsConstants.KEY_KEYBOARD_FONT_SIZE, SettingsConstants.DEFAULT_KEYBOARD_FONT_SIZE),
            showUnassignedPlaceholders = true) { row, column, _ ->
            showKeyEditor(row, column)
        }
    }

    private fun showKeyEditor(row: Int, column: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val filename = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME
        val layout = LayoutFileManager.loadLayout(this, filename) ?: return
        val slot = layout.rows[row].slots[column]
        val editorView = layoutInflater.inflate(R.layout.dialog_key_editor, null)
        val input = editorView.findViewById<EditText>(R.id.et_key_text)
        val longPressTypes = editorView.findViewById<Spinner>(R.id.sp_long_press_type)
        val longPressTextLabel = editorView.findViewById<TextView>(R.id.tv_long_press_text_label)
        val longPressInput = editorView.findViewById<EditText>(R.id.et_long_press_text)
        val currentLpText = if (slot.longPressItems.isNotEmpty()) {
            slot.longPressItems.joinToString(", ") { if (it.action != KeyAction.TEXT) it.action.keyLabel else it.text }
        } else {
            slot.longPressText
        }
        longPressInput.setText(currentLpText)
        val swipeLeftTypes = editorView.findViewById<Spinner>(R.id.sp_swipe_left_type)
        val swipeLeftInput = editorView.findViewById<EditText>(R.id.et_swipe_left_text)
        val swipeRightTypes = editorView.findViewById<Spinner>(R.id.sp_swipe_right_type)
        val swipeRightInput = editorView.findViewById<EditText>(R.id.et_swipe_right_text)

        swipeLeftInput.setText(slot.swipeLeftText)
        swipeRightInput.setText(slot.swipeRightText)

        val types = editorView.findViewById<Spinner>(R.id.sp_key_type)
        val behavior = editorView.findViewById<Spinner>(R.id.sp_text_behavior)
        behavior.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, TextBehavior.entries.map { it.title })
        behavior.setSelection(TextBehavior.entries.indexOf(slot.textBehavior))
        val help = editorView.findViewById<TextView>(R.id.tv_key_help)
        val error = editorView.findViewById<TextView>(R.id.tv_key_error)
        val actions = KeyAction.entries
        input.setText(slot.text)
        input.setSelection(input.text.length)
        types.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actions.map { it.title }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        longPressTypes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actions.map { it.title }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        longPressTypes.setSelection(actions.indexOf(slot.longPressAction))

        swipeLeftTypes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actions.map { it.title }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        swipeLeftTypes.setSelection(actions.indexOf(slot.swipeLeftAction))

        swipeRightTypes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actions.map { it.title }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        swipeRightTypes.setSelection(actions.indexOf(slot.swipeRightAction))

        fun updateEditor() {
            val action = actions[types.selectedItemPosition]
            input.visibility = if (action == KeyAction.TEXT) View.VISIBLE else View.GONE
            behavior.visibility = View.GONE
            help.text = action.help
            error.visibility = View.GONE
        }
        fun updateLongPressEditor() {
            val lpAction = actions[longPressTypes.selectedItemPosition]
            val isText = lpAction == KeyAction.TEXT
            longPressTextLabel.visibility = if (isText) View.VISIBLE else View.GONE
            longPressInput.visibility = if (isText) View.VISIBLE else View.GONE
        }
        fun updateSwipeEditors() {
            val slAction = actions[swipeLeftTypes.selectedItemPosition]
            swipeLeftInput.visibility = if (slAction == KeyAction.TEXT) View.VISIBLE else View.GONE

            val srAction = actions[swipeRightTypes.selectedItemPosition]
            swipeRightInput.visibility = if (srAction == KeyAction.TEXT) View.VISIBLE else View.GONE
        }

        types.setSelection(actions.indexOf(slot.action))
        types.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateEditor()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        longPressTypes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateLongPressEditor()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        swipeLeftTypes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateSwipeEditors()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        swipeRightTypes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateSwipeEditors()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        updateEditor()
        updateLongPressEditor()
        updateSwipeEditors()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Row ${row + 1} · Key ${column + 1}")
            .setMessage(R.string.key_edit_message)
            .setView(editorView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val action = actions[types.selectedItemPosition]
                    val text = if (action == KeyAction.TEXT) input.text.toString() else ""
                    val lpAction = actions[longPressTypes.selectedItemPosition]
                    val lpText = if (lpAction == KeyAction.TEXT) longPressInput.text.toString() else ""
                    val items = if (lpAction == KeyAction.TEXT && lpText.isNotEmpty()) {
                        if (lpText.contains(",")) {
                            lpText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { LongPressItem(text = it) }
                        } else {
                            listOf(LongPressItem(text = lpText))
                        }
                    } else if (lpAction != KeyAction.TEXT) {
                        listOf(LongPressItem(action = lpAction))
                    } else {
                        emptyList()
                    }

                    val slAction = actions[swipeLeftTypes.selectedItemPosition]
                    val slText = if (slAction == KeyAction.TEXT) swipeLeftInput.text.toString() else ""

                    val srAction = actions[swipeRightTypes.selectedItemPosition]
                    val srText = if (srAction == KeyAction.TEXT) swipeRightInput.text.toString() else ""

                    LayoutFileManager.saveLayout(
                        this, filename,
                        layout.withKeyMapping(
                            row, column, text, action, TextBehavior.entries[behavior.selectedItemPosition],
                            longPressText = lpText, longPressAction = lpAction, longPressItems = items,
                            swipeLeftText = slText, swipeLeftAction = slAction,
                            swipeRightText = srText, swipeRightAction = srAction
                        )
                    )
                    clearedLayout = null
                    refreshPreview()
                    dialog.dismiss()
                } catch (e: Exception) {
                    error.text = "Failed to save: ${e.message}"
                    clearedLayout = null
                    refreshPreview()
                    dialog.dismiss()
                } catch (e: Exception) {
                    error.text = "Failed to save: ${e.message}"
                    error.visibility = View.VISIBLE
                }
            }
        }
        dialog.show()
    }

    private fun showNewLayoutDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_new_layout, null)
        val nameInput = content.findViewById<EditText>(R.id.et_layout_name)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.new_layout_title)
            .setMessage(R.string.new_layout_message)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val filename = LayoutFileManager.createBlankLayout(this, nameInput.text.toString())
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_ACTIVE_LAYOUT_FILE, filename).apply()
                    clearedLayout = null
                    updateLayoutSpinner()
                    refreshPreview()
                    dialog.dismiss()
                    Toast.makeText(this, "Created $filename", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    nameInput.error = "Failed to create layout: ${e.message}"
                }
            }
        }
        dialog.show()
    }

    private fun showRenameLayoutDialog() {
        val filename = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME
        val currentDisplayName = filename.removeSuffix(".json")
        val content = layoutInflater.inflate(R.layout.dialog_new_layout, null)
        val nameInput = content.findViewById<EditText>(R.id.et_layout_name)
        nameInput.setText(currentDisplayName)
        nameInput.selectAll()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename_layout)
            .setMessage(R.string.rename_layout_message)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val newFilename = LayoutFileManager.renameLayout(this, filename, nameInput.text.toString())
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_ACTIVE_LAYOUT_FILE, newFilename).apply()
                    clearedLayout = null
                    updateLayoutSpinner()
                    refreshPreview()
                    dialog.dismiss()
                } catch (e: Exception) {
                    nameInput.error = e.message
                }
            }
        }
        dialog.show()
    }

    private fun deleteCurrentLayout() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val filename = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_layout_title)
            .setMessage(getString(R.string.delete_layout_message, filename))
            .setNegativeButton("Cancel", null)
            .setPositiveButton(R.string.delete_layout_confirm) { _, _ ->
                if (LayoutFileManager.deleteLayout(this, filename)) {
                    clearedLayout = null
                    updateLayoutSpinner()
                    refreshPreview()
                    Toast.makeText(this, R.string.delete_layout_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.delete_layout_failed, Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun clearCurrentLayout() {
        val filename = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME
        try {
            val original = requireNotNull(LayoutFileManager.loadLayout(this, filename)) { "Unable to read layout" }
            val blank = original.cleared()
            if (original == blank) {
                Toast.makeText(this, R.string.layout_already_empty, Toast.LENGTH_SHORT).show()
                return
            }
            LayoutFileManager.saveLayout(this, filename, blank)
            clearedLayout = null
            refreshPreview()
            Toast.makeText(this, R.string.layout_cleared, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to clear layout: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun undoClear() {
        val snapshot = clearedLayout ?: return
        val activeFile = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME)
        if (activeFile != snapshot.filename) {
            clearedLayout = null
            refreshPreview()
            return
        }
        try {
            val current = LayoutFileManager.loadLayout(this, snapshot.filename)
            if (current != snapshot.original.cleared()) {
                clearedLayout = null
                refreshPreview()
                Toast.makeText(this, "Layout has changed. Undo is no longer available.", Toast.LENGTH_SHORT).show()
                return
            }
            LayoutFileManager.saveLayout(this, snapshot.filename, snapshot.original)
            clearedLayout = null
            refreshPreview()
            Toast.makeText(this, R.string.layout_restored, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to restore layout: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRetainNonConfigurationInstance(): Any? = clearedLayout

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_export", pendingExport)
        super.onSaveInstanceState(outState)
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, IMPORT_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                try {
                    val uri = requireNotNull(data?.data) { "No file selected" }
                    val json = requireNotNull(pendingExport) { "Please export again" }
                    val stream = requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "Unable to open file" }
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                    Toast.makeText(this, "Configuration exported", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to export: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            pendingExport = null
        }
        if (requestCode == IMPORT_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.selectedImportUris()?.takeIf { it.isNotEmpty() }?.let { importFiles(it) }
        }
    }

    private fun importFiles(uris: List<Uri>) {
        var imported = 0
        var lastFilename: String? = null
        val failures = mutableListOf<String>()
        uris.forEach { uri ->
            val sourceName = sourceFileName(uri)
            try {
                lastFilename = requireNotNull(contentResolver.openInputStream(uri)) { "Unable to read file" }
                    .use { LayoutFileManager.importLayout(this, it, sourceName) }
                imported++
            } catch (e: Exception) {
                failures.add("${sourceName ?: uri.lastPathSegment ?: "File"}: ${e.message}")
            }
        }
        if (imported > 0) {
            clearedLayout = null
            setupColorControls()
            val heightDp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT)
            sbHeight.progress = heightDp - MIN_HEIGHT_DP
            tvHeightValue.text = "${heightDp}dp"
            updateLayoutSpinner()
            refreshPreview()
        }
        val summary = "Imported $imported/${uris.size} keyboard pages" +
            (lastFilename?.let { "; active: $it" } ?: "")
        if (failures.isEmpty()) {
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
        } else {
            AlertDialog.Builder(this).setTitle("Import results")
                .setMessage(summary + "\n\n" + failures.joinToString("\n"))
                .setPositiveButton("OK", null).show()
        }
    }

    private fun sourceFileName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column)?.takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) {
        null
    }

    private fun startExport() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val filename = prefs.getString(KEY_ACTIVE_LAYOUT_FILE, DEFAULT_LAYOUT_FILENAME) ?: DEFAULT_LAYOUT_FILENAME
        val layout = LayoutFileManager.loadLayout(this, filename)
        if (layout != null) {
            pendingExport = JSONObject().apply {
                put("version", 4)
                put("layout", JSONObject(layout.toJson()))
                put("appearance", JSONObject().apply {
                    put("backgroundColor", prefs.getString(KEY_BG_COLOR_HEX, DEFAULT_BG_COLOR_HEX))
                    put("symbolColor", prefs.getString(KEY_SYMBOL_COLOR_HEX, DEFAULT_SYMBOL_COLOR_HEX))
                    put("heightDp", prefs.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT))
                })
            }.toString(2)
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/json"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, filename)
            }, EXPORT_REQUEST_CODE)
        }
    }

}
