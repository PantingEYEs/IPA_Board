package com.example.ipa_board

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class ShortcutManagerActivity : Activity() {
    private lateinit var barPreview: MultifunctionBarView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcut)

        val root = findViewById<android.view.View>(R.id.shortcut_root)
        root.setOnApplyWindowInsetsListener { view, windowInsets ->
            val insets = windowInsets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
        root.requestApplyInsets()

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        barPreview = MultifunctionBarView(this).apply {
            editing = true
            status.setText(R.string.multifunction_bar_status)
            onSlotClick = { showBarKeyEditor(it) }
        }
        findViewById<ViewGroup>(R.id.multifunction_bar_preview).addView(barPreview)
        refreshBarPreview()

        com.example.ipa_board.shortcuts.ReplacementRuleEditor(this, findViewById(R.id.replacement_rules))
        findViewById<Button>(R.id.btn_manage_shift_shortcuts).setOnClickListener {
            showShortcutManagerDialog(isShift = true)
        }

        findViewById<Button>(R.id.btn_manage_ctrl_shortcuts).setOnClickListener {
            showShortcutManagerDialog(isShift = false)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBarPreview()
    }

    private fun refreshBarPreview() {
        if (::barPreview.isInitialized) barPreview.setBindings(MultifunctionBarSettings.load(this))
    }

    private fun showBarKeyEditor(slot: MultifunctionBarSlot) {
        val binding = MultifunctionBarSettings.load(this).getValue(slot)
        val actions = KeyAction.entries
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val types = Spinner(this).apply {
            id = R.id.sp_bar_key_type
            contentDescription = getString(R.string.multifunction_bar_function)
            adapter = ArrayAdapter(this@ShortcutManagerActivity, android.R.layout.simple_spinner_dropdown_item, actions.map { it.title })
            setSelection(actions.indexOf(binding.action))
        }
        val text = EditText(this).apply {
            id = R.id.et_bar_key_text
            hint = getString(R.string.multifunction_bar_text)
            filters = arrayOf(InputFilter.LengthFilter(1000))
            setText(binding.text)
        }
        val behavior = Spinner(this).apply {
            id = R.id.sp_bar_text_behavior
            adapter = ArrayAdapter(this@ShortcutManagerActivity, android.R.layout.simple_spinner_dropdown_item, TextBehavior.entries.map { it.title })
            setSelection(TextBehavior.entries.indexOf(binding.textBehavior))
        }
        val help = TextView(this).apply { textSize = 14f }
        fun updateEditor() {
            val action = actions[types.selectedItemPosition]
            text.visibility = if (action == KeyAction.TEXT) View.VISIBLE else View.GONE
            behavior.visibility = text.visibility
            help.text = action.help
        }
        types.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateEditor()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        container.addView(types)
        container.addView(text)
        container.addView(behavior)
        container.addView(help)
        updateEditor()
        // The dialog owns its draft. Cancel/back never writes preferences or executes a binding.
        AlertDialog.Builder(this)
            .setTitle(R.string.multifunction_bar_edit)
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton(R.string.multifunction_bar_cancel, null)
            .setPositiveButton(R.string.multifunction_bar_save) { _, _ ->
                val action = actions[types.selectedItemPosition]
                MultifunctionBarSettings.save(this, slot, MultifunctionBarBinding(action,
                    if (action == KeyAction.TEXT) text.text.toString() else "",
                    if (action == KeyAction.TEXT) TextBehavior.entries[behavior.selectedItemPosition] else TextBehavior.LITERAL))
                refreshBarPreview()
            }.show()
    }

    private fun showShortcutManagerDialog(isShift: Boolean) {
        val prefsKey = if (isShift) SettingsConstants.KEY_SHIFT_SHORTCUTS else SettingsConstants.KEY_CTRL_SHORTCUTS
        val title = if (isShift) "Shift Shortcut Management" else "Ctrl Shortcut Management"
        val prefs = getSharedPreferences(SettingsConstants.PREFS_NAME, MODE_PRIVATE)

        fun loadMap(): MutableMap<String, KeyAction> {
            return SettingsConstants.parseShortcutsJson(prefs.getString(prefsKey, null)).toMutableMap()
        }

        fun saveMap(map: Map<String, KeyAction>) {
            val jsonStr = SettingsConstants.serializeShortcutsJson(map)
            prefs.edit().putString(prefsKey, jsonStr).apply()
        }

        fun refreshDialog(dialogView: LinearLayout) {
            dialogView.removeAllViews()
            val currentMap = loadMap()

            val header = TextView(this).apply {
                text = if (currentMap.isEmpty()) "No custom shortcuts. Tap button below to add." else "Assigned shortcuts:"
                setPadding(0, 0, 0, 16)
                textSize = 14f
            }
            dialogView.addView(header)

            val scroll = ScrollView(this)
            val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            for ((key, action) in currentMap) {
                val itemRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                    gravity = Gravity.CENTER_VERTICAL
                }

                val keyLabelView = TextView(this).apply {
                    text = "${if (isShift) "Shift" else "Ctrl"}+$key ➔ ${action.title} (${action.keyLabel.ifEmpty { action.wireValue }})"
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnDelete = Button(this).apply {
                    text = "Delete"
                    textSize = 14f
                    setOnClickListener {
                        val newMap = loadMap()
                        newMap.remove(key)
                        saveMap(newMap)
                        refreshDialog(dialogView)
                    }
                }

                itemRow.addView(keyLabelView)
                itemRow.addView(btnDelete)
                listLayout.addView(itemRow)
            }

            scroll.addView(listLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400))
            dialogView.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            val btnAdd = Button(this).apply {
                text = "+ Add Shortcut"
                setOnClickListener {
                    showAddShortcutDialog(isShift) {
                        refreshDialog(dialogView)
                    }
                }
            }
            dialogView.addView(btnAdd)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        refreshDialog(container)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showAddShortcutDialog(isShift: Boolean, onAdded: () -> Unit) {
        val prefsKey = if (isShift) SettingsConstants.KEY_SHIFT_SHORTCUTS else SettingsConstants.KEY_CTRL_SHORTCUTS
        val prefs = getSharedPreferences(SettingsConstants.PREFS_NAME, MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val tvLabelKey = TextView(this).apply {
            text = "Trigger Key / Character (e.g. a, c, 1, [, etc.):"
            textSize = 14f
        }
        val etKey = EditText(this).apply {
            hint = "e.g. a"
            maxLines = 1
        }

        val tvLabelAction = TextView(this).apply {
            text = "Bound Action:"
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }

        val availableActions = KeyAction.entries.filter { it != KeyAction.TEXT && it != KeyAction.SHIFT && it != KeyAction.CTRL }
        val actionDisplayNames = availableActions.map { "${it.title} (${it.keyLabel.ifEmpty { it.wireValue }})" }

        val spinnerAction = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, actionDisplayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAction.adapter = adapter

        layout.addView(tvLabelKey)
        layout.addView(etKey)
        layout.addView(tvLabelAction)
        layout.addView(spinnerAction)

        AlertDialog.Builder(this)
            .setTitle("Add ${if (isShift) "Shift" else "Ctrl"} Shortcut")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val inputKey = etKey.text.toString().trim()
                if (inputKey.isEmpty()) {
                    Toast.makeText(this, "Please enter a valid trigger key", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val selectedIndex = spinnerAction.selectedItemPosition
                if (selectedIndex in availableActions.indices) {
                    val selectedAction = availableActions[selectedIndex]
                    val currentMap = SettingsConstants.parseShortcutsJson(prefs.getString(prefsKey, null)).toMutableMap()
                    currentMap[inputKey] = selectedAction
                    prefs.edit().putString(prefsKey, SettingsConstants.serializeShortcutsJson(currentMap)).apply()
                    onAdded()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
