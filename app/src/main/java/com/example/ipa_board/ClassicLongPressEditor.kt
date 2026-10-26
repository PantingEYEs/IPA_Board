package com.example.ipa_board

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView

/** The nested dialog edits a private draft; only its Save updates the containing key draft. */
internal object ClassicLongPressEditor {
    private class Row(val view: LinearLayout, val binding: FunctionBindingEditor,
        val default: RadioButton, val delete: Button)

    fun show(context: Context, initial: List<LongPressItem>, defaultIndex: Int,
        onSave: (List<LongPressItem>, Int) -> Unit) {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        body.addView(TextView(context).apply { setText(R.string.classic_queue_help); setTextColor(Color.LTGRAY) })
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(list) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, dp(320)))
        val rows = mutableListOf<Row>()
        var selected: Row? = null
        fun refresh() {
            rows.forEachIndexed { index, row ->
                row.default.isChecked = row === selected
                row.default.text = context.getString(R.string.classic_queue_default_item, index + 1)
                row.default.contentDescription = context.getString(R.string.classic_queue_default_item, index + 1)
                row.delete.contentDescription = context.getString(R.string.classic_queue_delete_item, index + 1)
                row.binding.setDescriptions(context.getString(R.string.classic_queue_function, index + 1),
                    context.getString(R.string.classic_queue_text, index + 1))
            }
        }
        fun add(binding: LongPressItem) {
            val position = rows.size + 1
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val picker = FunctionBindingEditor(context, binding,
                context.getString(R.string.classic_queue_function, position), context.getString(R.string.classic_queue_text, position))
            val default = RadioButton(context)
            val delete = Button(context).apply { setText(R.string.classic_queue_delete) }
            val row = Row(rowView, picker, default, delete)
            rowView.addView(LinearLayout(context).apply {
                addView(default, LinearLayout.LayoutParams(0, -2, 1f)); addView(delete)
            })
            rowView.addView(picker)
            rows += row
            list.addView(rowView)
            if (selected == null) selected = row
            default.setOnClickListener { selected = row; refresh() }
            delete.setOnClickListener {
                rows.remove(row); list.removeView(rowView)
                if (selected === row) selected = rows.firstOrNull()
                refresh()
            }
            refresh()
        }
        initial.forEach(::add)
        selected = rows.getOrNull(defaultIndex) ?: rows.firstOrNull()
        refresh()
        body.addView(Button(context).apply {
            id = R.id.classic_queue_add
            setText(R.string.classic_queue_add)
            setOnClickListener { add(LongPressItem()); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
        })
        val dialog = AlertDialog.Builder(context).setTitle(R.string.classic_queue_title).setView(body)
            .setNegativeButton(R.string.popup_editor_cancel, null).setPositiveButton(R.string.popup_editor_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = rows.map { it.binding.bindingOrNull() ?: return@setOnClickListener }
                onSave(result, selected?.let(rows::indexOf)?.coerceAtLeast(0) ?: 0)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
