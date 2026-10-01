package com.example.ipa_board.kaomoji

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.ipa_board.R

class KaomojiAdapter(
    private val onItemClicked: (KaomojiItem) -> Unit,
    private val onSelectionChanged: (selectedCount: Int) -> Unit
) : RecyclerView.Adapter<KaomojiAdapter.KaomojiViewHolder>() {

    private val items = mutableListOf<KaomojiItem>()
    private val selectedIds = mutableSetOf<String>()
    var isBatchMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                selectedIds.clear()
            }
            notifyDataSetChanged()
            onSelectionChanged(selectedIds.size)
        }

    fun submitList(newItems: List<KaomojiItem>) {
        items.clear()
        items.addAll(newItems)
        val existingIds = items.map { it.id }.toSet()
        selectedIds.retainAll(existingIds)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun getSelectedIds(): Set<String> {
        return selectedIds.toSet()
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(items.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KaomojiViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_kaomoji, parent, false)
        return KaomojiViewHolder(view)
    }

    override fun onBindViewHolder(holder: KaomojiViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class KaomojiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cb_select)
        private val tvKaomojiText: TextView = itemView.findViewById(R.id.tv_kaomoji_text)

        fun bind(item: KaomojiItem) {
            tvKaomojiText.text = item.text

            if (isBatchMode) {
                cbSelect.visibility = View.VISIBLE
                cbSelect.isChecked = selectedIds.contains(item.id)

                val toggleListener = View.OnClickListener {
                    if (selectedIds.contains(item.id)) {
                        selectedIds.remove(item.id)
                        cbSelect.isChecked = false
                    } else {
                        selectedIds.add(item.id)
                        cbSelect.isChecked = true
                    }
                    onSelectionChanged(selectedIds.size)
                }

                itemView.setOnClickListener(toggleListener)
                cbSelect.setOnClickListener(toggleListener)
            } else {
                cbSelect.visibility = View.GONE
                itemView.setOnClickListener {
                    onItemClicked(item)
                }
            }
        }
    }
}
