package com.example.ipa_board.shortcuts

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.view.ViewGroup
import android.widget.*
import com.example.ipa_board.R
import java.util.UUID

/** Existing Shortcut page owns the editor; nested dialogs keep drafts until Save. */
internal class ReplacementRuleEditor(private val activity: Activity, private val parent: LinearLayout) {
    init { refresh() }
    private fun label(value: String) = TextView(activity).apply {
        text = value; textSize = 16f; setTextColor(activity.getColor(R.color.management_text))
        setPadding(0, 12, 0, 8)
    }
    private fun button(value: String, action: () -> Unit) = Button(activity).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }
    }
    private fun persist(rules: List<ReplacementRule>) {
        if (!ReplacementSettings.save(activity, rules)) Toast.makeText(activity, R.string.replacement_save_failed, Toast.LENGTH_SHORT).show()
        refresh()
    }
    private fun refresh() {
        parent.removeAllViews()
        val rules = try { ReplacementSettings.load(activity) } catch (_: Exception) {
            parent.addView(label(activity.getString(R.string.replacement_read_failed))); return
        }
        if (rules.isEmpty()) parent.addView(label(activity.getString(R.string.replacement_empty)))
        rules.forEachIndexed { index, rule ->
            parent.addView(label("${rule.source} → ${rule.replacement}"))
            val row = LinearLayout(activity)
            fun control(title: Int, enabled: Boolean = true, action: () -> Unit) {
                row.addView(button(activity.getString(title), action).apply {
                    isEnabled = enabled; minWidth = 0; minimumWidth = 0; textSize = 12f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            control(R.string.replacement_edit) { edit(rule) }
            control(R.string.replacement_up, index > 0) {
                val updated = rules.toMutableList(); updated[index] = updated[index - 1]; updated[index - 1] = rule; persist(updated)
            }
            control(R.string.replacement_down, index < rules.lastIndex) {
                val updated = rules.toMutableList(); updated[index] = updated[index + 1]; updated[index + 1] = rule; persist(updated)
            }
            control(R.string.replacement_delete) { persist(rules.filter { it.id != rule.id }) }
            parent.addView(row)
        }
        parent.addView(button(activity.getString(R.string.replacement_add)) { edit(null) }.apply { tag = "replacement-add" })
    }
    private fun edit(rule: ReplacementRule?) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt(); setPadding(padding, padding, padding, padding)
        }
        val source = EditText(activity).apply {
            tag = "replacement-source"; hint = activity.getString(R.string.replacement_source)
            filters = arrayOf(InputFilter.LengthFilter(1000)); setText(rule?.source.orEmpty())
        }
        val target = EditText(activity).apply {
            tag = "replacement-target"; hint = activity.getString(R.string.replacement_target)
            filters = arrayOf(InputFilter.LengthFilter(1000)); setText(rule?.replacement.orEmpty())
        }
        content.addView(label(activity.getString(R.string.replacement_source))); content.addView(source)
        content.addView(label(activity.getString(R.string.replacement_target))); content.addView(target)
        val dialog = AlertDialog.Builder(activity).setTitle(if (rule == null) R.string.replacement_add else R.string.replacement_edit)
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(R.string.multifunction_bar_cancel, null)
            .setPositiveButton(R.string.multifunction_bar_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (source.text.isEmpty() || target.text.isEmpty()) {
                    (if (source.text.isEmpty()) source else target).error = activity.getString(R.string.replacement_required)
                } else {
                    val rules = try { ReplacementSettings.load(activity).toMutableList() } catch (_: Exception) {
                        Toast.makeText(activity, R.string.replacement_read_failed, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val updated = ReplacementRule(rule?.id ?: UUID.randomUUID().toString(), source.text.toString(), target.text.toString())
                    if (rule == null) rules.add(updated) else {
                        val index = rules.indexOfFirst { it.id == rule.id }
                        if (index < 0) { dialog.dismiss(); refresh(); return@setOnClickListener }
                        rules[index] = updated
                    }
                    persist(rules); dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
