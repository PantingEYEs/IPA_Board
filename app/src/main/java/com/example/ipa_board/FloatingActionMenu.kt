package com.example.ipa_board

import android.view.View
import com.google.android.material.floatingactionbutton.FloatingActionButton

/** Shared expansion, mask and animation behavior for management-page action menus. */
internal class FloatingActionMenu(private val button: FloatingActionButton,
    private val mask: View, private val menu: View) {
    var isExpanded = false
        private set
    init {
        button.setOnClickListener { toggle() }
        mask.setOnClickListener { close() }
    }
    fun toggle() {
        isExpanded = !isExpanded
        mask.visibility = if (isExpanded) View.VISIBLE else View.GONE
        menu.visibility = if (isExpanded) View.VISIBLE else View.GONE
        button.animate().rotation(if (isExpanded) 45f else 0f).setDuration(200).start()
    }
    fun close() { if (isExpanded) toggle() }
}
