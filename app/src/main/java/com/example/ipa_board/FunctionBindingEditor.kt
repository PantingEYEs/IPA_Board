package com.example.ipa_board

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner

/** Shared function/text controls for user Popup items and generated Classic queue items. */
// Binding and accessibility labels are supplied by the owning dialog.
@android.annotation.SuppressLint("ViewConstructor")
internal class FunctionBindingEditor(context: Context, initial: LongPressItem,
    functionDescription: String = context.getString(R.string.popup_editor_item_function),
    textDescription: String = context.getString(R.string.popup_editor_item_text)) : LinearLayout(context) {
    private val function = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, KeyAction.entries.map { it.title })
        setSelection(KeyAction.entries.indexOf(initial.action))
        contentDescription = functionDescription
    }
    private val textInput = EditText(context).apply {
        setText(initial.text)
        setHint(R.string.popup_editor_text)
        contentDescription = textDescription
    }

    init {
        orientation = VERTICAL
        addView(function, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(textInput)
        fun refresh() { textInput.visibility = if (KeyAction.entries[function.selectedItemPosition] == KeyAction.TEXT) VISIBLE else GONE }
        function.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refresh()
    }

    fun bindingOrNull(): LongPressItem? {
        val action = KeyAction.entries[function.selectedItemPosition]
        val text = if (action == KeyAction.TEXT) textInput.text.toString() else ""
        if (text.length > 1000) { textInput.error = context.getString(R.string.popup_editor_text_limit); return null }
        return LongPressItem(text, action)
    }

    fun setDescriptions(functionDescription: String, textDescription: String) {
        function.contentDescription = functionDescription
        textInput.contentDescription = textDescription
    }
}
